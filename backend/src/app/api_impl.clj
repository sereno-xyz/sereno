;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.api-impl
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.emails :as emails]
   [app.http :as http]
   [app.telegram :as telegram]
   [app.tasks.monitor :refer [run-monitor!]]
   [app.tasks.notify :refer [notify!]]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [buddy.hashers :as bh]
   [app.util.services :as sv]
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [ring.core.protocols :as rp])
  (:import
   org.postgresql.jdbc.PgArray
   org.postgresql.jdbc.PgConnection
   java.util.zip.GZIPInputStream
   java.util.zip.GZIPOutputStream
   java.io.InputStream
   java.io.OutputStream))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Profile & Auth
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Mutation: Login

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)

(s/def ::login
  (s/keys :req-un [::email ::password]))

(defn- check-password!
  [profile password]
  (when (= (:password profile) "!")
    (ex/raise :type :validation
              :code :account-without-password))
  (let [result (bh/verify password (:password profile))]
    (when-not (:valid result)
      (ex/raise :type :validation
                :code :wrong-credentials))))

(sv/defmethod ::login {:auth false}
  [{:keys [pool]} {:keys [email password] :as params}]
  (letfn [(validate-profile [profile]
            (when-not profile
              (ex/raise :type :validation
                        :code :wrong-credentials))

            (when-not (:is-active profile)
              (ex/raise :type :validation
                        :code :profile-not-activated))

            (check-password! profile password)
            profile)]

    (db/with-atomic [conn pool]
      (let [email  (str/lower email)]
        (-> (db/get-by-params conn :profile
                              {:email email}
                              {:for-update true})
            (validate-profile)
            (dissoc :password))))))


;; --- Mutation: Register profile

(declare check-profile-existence!)
(declare create-profile)
(declare create-profile-relations)

(s/def ::register-profile
  (s/keys :req-un [::email ::password ::fullname]))

(sv/defmethod ::register-profile {:auth false}
  [{:keys [pool tokens] :as cfg} params]
  (db/with-atomic [conn pool]
    (check-profile-existence! conn params)
    (let [profile (->> (create-profile conn params)
                       (create-profile-relations conn))

          claims  {:profile-id (:id profile)
                   :email (:email profile)
                   :iss :verify-profile
                   :exp (dt/plus (dt/now) (dt/duration {:days 7}))}

          token   ((:create tokens) claims)]

      (emails/send! conn emails/register
                    {:to (:email profile)
                     :name (:fullname profile)
                     :public-uri (:public-uri cfg)
                     :token token})

      (dissoc profile :password))))

(def ^:private sql:profile-existence
  "select exists (select * from profile
                   where email = ?) as val")

(defn check-profile-existence!
  [conn {:keys [email] :as params}]
  (let [email  (str/lower email)
        result (db/exec-one! conn [sql:profile-existence email])]
    (when (:val result)
      (ex/raise :type :validation
                :code :email-already-exists))
    params))

(defn create-profile
  "Create the profile entry on the database with limited input
  filling all the other fields with defaults."
  [conn {:keys [fullname email password] :as params}]
  (let [id       (uuid/next)
        password (bh/derive password {:alg :bcrypt+sha512})]
    (db/insert! conn :profile
                {:id id
                 :type (:default-profile-type cfg/config "default")
                 :fullname fullname
                 :email (str/lower email)
                 :password password})))

(defn create-profile-relations
  [conn profile]
  (let [params {:email (:email profile)}]
    (db/insert! conn :contact
                {:owner-id (:id profile)
                 :name (str "Primary contact (" (:email profile) ")")
                 :type "email"
                 :validated-at (dt/now)
                 :params (db/tjson params)})
    profile))


;; --- Mutation: Login Or Register

(s/def ::fullname ::us/not-empty-string)
(s/def ::name ::us/not-empty-string)
(s/def ::profile-id ::us/uuid)
(s/def ::external-id ::us/not-empty-string)

(s/def ::login-or-register
  (s/keys :req-un [::email ::fullname ::external-id]))

(sv/defmethod ::login-or-register {:auth false}
  [{:keys [pool]} {:keys [email fullname external-id] :as params}]
  (letfn [(create-profile [conn {:keys [fullname email]}]
            (db/insert! conn :profile
                        {:id (uuid/next)
                         :fullname fullname
                         :email (str/lower email)
                         :external-id external-id
                         :is-active true
                         :password "!"}))

          (register-profile [conn params]
            (->> (create-profile conn params)
                 (create-profile-relations conn)))]

    (db/with-atomic [conn pool]
      (let [profile (db/get-by-params conn :profile {:email (str/lower email)})
            profile (or profile (register-profile conn params))]
        (dissoc profile :password)))))


;; --- Mutation: Update Profile

(s/def ::update-profile
  (s/keys :req-un [::fullname ::profile-id]))

(sv/defmethod ::update-profile
  [{:keys [pool]} {:keys [fullname profile-id]}]
  (db/update! pool :profile
              {:fullname fullname}
              {:id profile-id})
  nil)


;; --- Mutation: Update Profile Password

(s/def ::password ::us/not-empty-string)
(s/def ::old-password ::us/not-empty-string)

(s/def ::update-profile-password
  (s/keys :req-un [::profile-id ::password]
          :opt-un [::old-password]))

(sv/defmethod ::update-profile-password
  [{:keys [pool]} {:keys [password profile-id old-password] :as params}]
  (db/with-atomic [conn pool]
    (let [profile (db/get-by-id conn :profile profile-id {:for-update true})]
      (when (not= "!" (:password profile))
        (let [result  (bh/verify old-password (:password profile))]
          (when-not (:valid result)
            (ex/raise :type :validation
                      :code :old-password-not-match))))
      (db/update! conn :profile
                  {:password (bh/derive password {:alg :bcrypt+sha512})}
                  {:id profile-id})
      nil)))



;; --- Mutation: Delete Profile

(s/def ::delete-profile
  (s/keys :req-un [::profile-id ::password]))

(sv/defmethod ::delete-profile
  [{:keys [pool]} {:keys [profile-id password] :as params}]
  (db/with-atomic [conn pool]
    (let [profile (db/get-by-id conn :profile profile-id {:for-update true})]
      (check-password! profile password)
      (db/delete! conn :profile {:id profile-id})
      (db/delete! conn :http-session {:profile-id profile-id})
      nil)))


;; --- Mutation: Profile Recovery Request

(s/def ::request-profile-recovery
  (s/keys :req-un [::email]))

(sv/defmethod ::request-profile-recovery {:auth false}
  [{:keys [pool tokens] :as cfg} {:keys [email] :as params}]
  (letfn [(create-recovery-token [conn {:keys [id] :as profile}]
            (let [claims {:profile-id id
                          :iss :password-recovery
                          :exp (dt/plus (dt/now) #app/duration "10m")}
                  token  ((:create tokens) claims)]
              (assoc profile :token token)))

          (send-email-notification [conn profile]
            (emails/send! conn emails/password-recovery
                          {:to (:email profile)
                           :public-uri (:public-uri cfg)
                           :token (:token profile)
                           :name (:fullname profile)}))

          (retrieve-profile-by-email [conn email]
            (let [email (str/lower email)]
              (db/get-by-params conn :profile {:email email})))]

    (db/with-atomic [conn pool]
      (some->> email
               (retrieve-profile-by-email conn)
               (create-recovery-token conn)
               (send-email-notification conn))
      nil)))


;; --- Mutation: Recover Profile

(s/def ::token ::us/not-empty-string)
(s/def ::recover-profile
  (s/keys :req-un [::token ::password]))

(sv/defmethod ::recover-profile {:auth false}
  [{:keys [pool tokens]} {:keys [token password]}]
  (letfn [(validate-token [conn token]
            (let [params {:iss :password-recovery}
                  claims ((:verify tokens) token params)]
              (:profile-id claims)))

          (update-password [conn profile-id]
            (let [pwd (bh/derive password)]
              (db/update! conn :profile {:password pwd} {:id profile-id})))]

    (db/with-atomic [conn pool]
      (->> (validate-token conn token)
           (update-password conn))
      nil)))


;; --- Mutation: Request Email Change

(declare check-profile-existence!)
(declare create-profile)
(declare create-profile-relations)

(s/def ::request-email-change
  (s/keys :req-un [::email]))

(sv/defmethod ::request-email-change
  [{:keys [pool tokens] :as cfg} {:keys [profile-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [email   (str/lower email)
          profile (db/get-by-id conn :profile profile-id {:for-update true})
          claims  {:iss :change-email
                   :exp (dt/plus (dt/now) #app/duration "15m")
                   :profile-id profile-id
                   :email email}
          token   ((:create tokens) claims)]

      (when (not= email (:email profile))
        (check-profile-existence! conn params))

      (emails/send! conn emails/change-email
                    {:to (:email profile)
                     :name (:fullname profile)
                     :public-uri (:public-uri cfg)
                     :pending-email (:email claims)
                     :token token})
      nil)))


;; --- Mutation: Verify Profile Token

(declare process-token)

(s/def ::verify-token
  (s/keys :req-un [::token]))

(sv/defmethod ::verify-token {:auth false}
  [{:keys [pool tokens] :as cfg} {:keys [token] :as params}]
  (let [claims ((:verify tokens) token)]
    (db/with-atomic [conn pool]
      (process-token (assoc cfg :conn conn) claims))))


(defmulti process-token (fn [cfg token] (:iss token)))

(defmethod process-token :change-email
  [{:keys [conn] :as cfg} {:keys [profile-id] :as claims}]
  (let [profile (db/get-by-id conn :profile profile-id {:for-update true})]
    (check-profile-existence! conn {:email (:email claims)})
    (db/update! conn :profile
                {:email (:email claims)}
                {:id (:id profile)})
    claims))

(defmethod process-token :verify-profile
  [{:keys [conn] :as cfg} {:keys [profile-id] :as claims}]
  (let [profile (db/get-by-id conn :profile profile-id {:for-update true})]
    (when (:is-active profile)
      (ex/raise :type :validation
                :code :profile-already-active))

    (when (not= (:email profile)
                (:email claims))
      (ex/raise :type :validation
                :code :invalid-token))

    (db/update! conn :profile
                {:is-active true}
                {:id profile-id})

    claims))

(defmethod process-token :verify-contact
  [{:keys [conn] :as cfg} {:keys [contact-id] :as claims}]
  (db/update! conn :contact
              {:validated-at (dt/now)}
              {:id contact-id})

  claims)

(defmethod process-token :unsub-monitor
  [{:keys [conn] :as cfg} {:keys [id] :as claims}]
  (db/delete! conn :monitor-contact-rel {:id id})
  claims)

(defmethod process-token :delete-contact
  [{:keys [conn] :as cfg} {:keys [contact-id] :as claims}]
  (db/delete! conn :contact {:id contact-id})
  claims)

(defmethod process-token :gauth
  [cfg token]
  token)

(defmethod process-token :default
  [cfg token]
  (ex/raise :type :validation
            :code :invalid-token
            :context token))


;; --- Query: retrieve profile

(def sql:retrieve-profile
  "select p.*,
          pt.min_cadence as limits_min_cadence,
          pt.max_contacts as limits_max_contacts,
          pt.max_monitors as limits_max_monitors,
          pt.max_email_notifications as limits_max_email_notifications,
          pc.created_at as counters_period,
          pc.email_notifications as counters_email_notifications,
          (select count(*) from monitor where owner_id = p.id) as counters_monitors,
          (select count(*) from contact where owner_id = p.id) as counters_contacts

     from profile as p
    inner join profile_type as pt on (pt.id = p.type)
     left join profile_counters as pc on (pc.profile_id = p.id and
                                          pc.created_at = date_trunc('month', now()))
    where p.id = ?")

(defn- get-profile
  [conn id]
  (let [result (db/exec-one! conn [sql:retrieve-profile id])]
    (when-not result
      (ex/raise :type :not-found))
    result))

(s/def ::retrieve-profile
  (s/keys :opt-un [::profile-id]))

(sv/defmethod ::retrieve-profile
  [{:keys [pool]} {:keys [profile-id] :as params}]
  (if profile-id
    (let [data (get-profile pool profile-id)]
      (dissoc data :password))
    {:id uuid/zero
     :fullname "Anonymous"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Monitors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Mutation: Create monitor

(def cadence-map
  {20    "*/20 * * * * ?"
   60    "0 */1 * * * ?"
   120   "0 */2 * * * ?"
   300   "0 */5 * * * ?"
   1800  "0 */30 * * * ?"
   3600  "0 0 */1 * * ?"
   7200  "0 0 */2 * * ?"
   21600 "0 0 */6 * * ?"
   43200 "0 0 */12 * * ?"
   86400 "0 0 0 */1 * ?"})

(defn- parse-and-validate-cadence!
  [cadence]
  (let [expr (get cadence-map cadence)]
    (when-not expr
      (ex/raise :type :validation
                :code :invalid-cadence-value))
    (dt/cron expr)))

(defn- validate-monitor-limits!
  [conn profile]
  (let [result (db/exec-one! conn ["select count(*) as total
                                      from monitor
                                     where owner_id=?" (:id profile)])]
    (when (>= (:total result 0)
              (or (:limits-max-monitors profile) ##Inf))
      (ex/raise :type :validation
                :code :monitor-limits-reached))))

(defn- validate-cadence-limits!
  [profile cadence]
  (let [min-cadence  (or (:limits-min-cadence profile) 60)]
    (when (> min-cadence cadence)
      (ex/raise :type :validation
                :code :cadence-limits-reached))))

(s/def ::method ::us/keyword)
(s/def ::uri ::us/uri)
(s/def ::should-include ::us/string)
(s/def ::headers (s/map-of ::us/string ::us/string))

(s/def ::http-monitor-params
  (s/keys :req-un [::method ::uri]
          :opt-un [::should-include ::headers]))

(defn- prepare-http-monitor-params
  [params]
  (let [{:keys [uri method headers should-include]} (us/conform ::http-monitor-params params)]
    (cond-> {:uri uri
             :method (or method :get)}
      (map? headers)
      (assoc :headers headers)

      (string? should-include)
      (assoc :should-include should-include))))

(s/def ::type ::us/string)
(s/def ::cadence ::us/integer)
(s/def ::contacts (s/coll-of ::us/uuid :min-count 1))
(s/def ::params (s/map-of ::us/keyword any?))
(s/def ::tags (s/coll-of ::us/string :kind set?))

(s/def ::create-http-monitor
  (s/keys :req-un [::type ::name ::cadence ::profile-id ::contacts ::params]
          :opt-un [::tags]))

(sv/defmethod ::create-http-monitor
  [{:keys [pool]} {:keys [cadence type params profile-id name contacts tags] :as data}]
  (db/with-atomic [conn pool]
    (let [id      (uuid/next)
          now-t   (dt/now)
          cron    (parse-and-validate-cadence! cadence)
          profile (get-profile conn profile-id)
          params  (prepare-http-monitor-params params)]

      (validate-monitor-limits! conn profile)
      (validate-cadence-limits! profile cadence)

      (db/insert! conn :monitor
                  {:id id
                   :owner-id profile-id
                   :name name
                   :cadence cadence
                   :cron-expr (str cron)
                   :created-at now-t
                   :status "started"
                   :type type
                   :params (db/tjson params)
                   :tags (into-array String tags)
                   })

      (db/insert! conn :monitor-status
                  {:monitor-id id
                   :status "created"
                   :created-at now-t
                   :finished-at now-t})

      (db/insert! conn :monitor-status
                  {:monitor-id id
                   :status "started"
                   :created-at now-t})

      (db/insert! conn :monitor-schedule
                  {:monitor-id id})

      (doseq [cid contacts]
        (db/insert! conn :monitor-contact-rel
                    {:monitor-id id
                     :contact-id cid}))
      nil)))


;; --- Mutation: Update Http Monitor

(s/def ::id ::us/uuid)
(s/def ::update-monitor
  (s/keys :req-un [::id ::name ::cadence ::profile-id ::contacts ::params]
          :opt-un [::tags]))

(sv/defmethod ::update-monitor
  [{:keys [pool]} {:keys [id name type cadence profile-id contacts params tags]}]
  (db/with-atomic [conn pool]
    (let [params  (case type
                    "http" (prepare-http-monitor-params params)
                    (ex/raise :type :not-implemented))

          monitor (db/get-by-params conn :monitor
                                    {:id id :owner-id profile-id}
                                    {:for-update true})

          cron    (parse-and-validate-cadence! cadence)
          offset  (dt/get-cron-offset cron (:created-at monitor))]

      (validate-cadence-limits! profile-id cadence)
      (when-not monitor
        (ex/raise :type :not-found
                  :code :object-does-not-found))

      (db/update! conn :monitor-schedule
                  {:scheduled-at (dt/get-next-inst cron offset)
                   :modified-at (dt/now)}
                  {:monitor-id (:id monitor)})

      (db/update! conn :monitor
                  {:name name
                   :cadence cadence
                   :cron-expr (str cron)
                   :params (db/tjson params)
                   :tags (into-array String tags)}
                  {:id id
                   :owner-id profile-id})

      (db/delete! conn :monitor-contact-rel
                  {:monitor-id id})

      (doseq [cid contacts]
        (db/insert! conn :monitor-contact-rel
                    {:monitor-id id
                     :contact-id cid}))

      nil)))


;; --- Test Monitor

(s/def ::test-monitor
  (s/keys :req-un [::type ::cadence ::params]))

(sv/defmethod ::test-monitor
  [cfg params]
  (run-monitor! cfg params))


;; --- Mutation: Pause monitor

(defn- change-monitor-status!
  [conn id status]
  (db/update! conn :monitor
              {:status status}
              {:id id})
  (db/exec! conn ["update monitor_status set finished_at=clock_timestamp()
                    where id=(select id from monitor_status
                               where monitor_id=?
                                 and finished_at is null
                               order by created_at desc
                               limit 1)" id])
  (db/exec! conn ["insert into monitor_status (monitor_id, status, created_at)
                   values (?, ?, clock_timestamp())" id status]))


(s/def ::pause-monitor
  (s/keys :req-un [::id ::profile-id]))

(sv/defmethod ::pause-monitor
  [{:keys [pool]} {:keys [id profile-id]}]
  (db/with-atomic [conn pool]
    (let [monitor (db/get-by-params conn :monitor
                                    {:id id :owner-id profile-id}
                                    {:for-update true})]
      (when-not monitor
        (ex/raise :type :not-found
                  :code :object-not-found))

      (when (not= "paused" (:status monitor))
        (change-monitor-status! conn id "paused"))
      nil)))


;; --- Mutation: Resume monitor

(s/def ::resume-monitor
  (s/keys :req-un [::id ::profile-id]))

(sv/defmethod ::resume-monitor
  [{:keys [pool]} {:keys [id profile-id]}]
  (db/with-atomic [conn pool]
    (let [monitor (db/get-by-params conn :monitor
                                    {:id id :owner-id profile-id}
                                    {:for-update true})]
      (when-not monitor
        (ex/raise :type :not-found
                  :code :object-not-found))

      (when (= "paused" (:status monitor))
        (change-monitor-status! conn id "started"))
      nil)))

;; --- Mutation: Delete monitor

(s/def ::delete-monitor
  (s/keys :req-un [::id ::profile-id]))

(sv/defmethod ::delete-monitor
  [{:keys [pool]} {:keys [id profile-id]}]
  (db/delete! pool :monitor {:id id ::owner-id profile-id})
  nil)


;; --- Query: Retrieve Monitors

;; TODO: move pgarray logic to db ns.

(defn decode-monitor-row
  [{:keys [params tags contacts] :as row}]
  (cond-> row
    (db/pgobject? params)
    (assoc :params (db/decode-transit-pgobject params))

    (and (instance? PgArray tags)
         (= "text" (.getBaseTypeName ^PgArray tags)))
    (assoc :tags (set (.getArray ^PgArray tags)))

    (and (instance? PgArray contacts)
         (= "uuid" (.getBaseTypeName ^PgArray contacts)))
    (assoc :contacts (set (.getArray ^PgArray contacts)))))

(def sql:retrieve-monitors
  "select m.*,
          (select array_agg(contact_id) from monitor_contact_rel
            where monitor_id=m.id) as contacts
     from monitor as m
    where m.owner_id = ?
    order by m.created_at")

(s/def ::retrieve-monitors
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::retrieve-monitors
  [{:keys [pool]} {:keys [profile-id]}]
  (let [result (db/exec! pool [sql:retrieve-monitors profile-id])]
    (mapv decode-monitor-row result)))

;; --- Query: Retrieve Monitor

(def sql:retrieve-monitor
  (str "with monitors as ( " sql:retrieve-monitors " ) "
       "select * from monitors where id = ?"))

(s/def ::retrieve-monitor
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::retrieve-monitor
  [{:keys [pool]} {:keys [profile-id id] :as params}]
  (let [row (db/exec-one! pool [sql:retrieve-monitor profile-id id])]
    (when-not row
      (ex/raise :type :not-found))
    (decode-monitor-row row)))

;; --- Query: Retrieve Monitor Latency Summary

(declare sql:monitor-summary-buckets)
(declare sql:monitor-summary)
(declare sql:monitor-uptime)

(def bucket-size
  "Translates the period to the bucket size."
  {"24hours"  (db/interval "30 minutes")
   "7days"    (db/interval "3 hour")
   "30days"   (db/interval "12 hours")})

(s/def ::period #(contains? bucket-size %))
(s/def ::retrieve-monitor-latency-summary
  (s/keys :req-un [::id ::period]))

(sv/defmethod ::retrieve-monitor-latency-summary
  [{:keys [pool]} {:keys [id profile-id period]}]
  (db/with-atomic [conn pool]
    (let [monitor   (db/exec-one! conn [sql:retrieve-monitor profile-id id])
          partition (get bucket-size period)]
      (when-not monitor
        (ex/raise :type :not-found
                  :hint "monitor does not exists"))

      (let [buckets (db/exec! conn [sql:monitor-summary-buckets partition id period])
            data    (db/exec-one! conn [sql:monitor-summary id period])
            uptime  (db/exec-one! conn [sql:monitor-uptime period period id period])]
        {:buckets buckets
         :data (merge data uptime)}))))


(def sql:monitor-summary
  "select percentile_cont(0.90) within group (order by latency) as latency_p90,
          avg(latency)::float8 as latency_avg
     from monitor_entry as me
    where me.monitor_id = ?
      and me.created_at > now() - ?::interval")

(def sql:monitor-uptime
  "with entries as (
      select e.*,
             (coalesce(e.finished_at, now()) -
              case when e.created_at < (now()-?::interval)
                   then now()-?::interval
                   else e.created_at end) as duration
        from monitor_status as e
       where e.monitor_id = ?
         and tstzrange(e.created_at, e.finished_at) && tstzrange(now() - ?::interval, now())
   )
   select (select extract(epoch from sum(duration)) from entries)::float8 as total_seconds,
          (select extract(epoch from sum(duration)) from entries where status = 'down')::float8 as down_seconds,
          (select extract(epoch from sum(duration)) from entries where status = 'up')::float8 as up_seconds")

(def sql:monitor-summary-buckets
   "select time_bucket(?::interval, created_at) as ts,
           round(avg(latency)::numeric, 2)::float8 as avg
     from monitor_entry
    where monitor_id = ?
      and (now()-created_at) < ?::interval group by 1 order by 1")


;; --- Query: Retrieve Monitor Status

(def sql:monitor-status-history
  "select e.*
     from monitor_status as e
    where e.monitor_id = ?
      and e.created_at < ?
    order by e.created_at desc
    limit ?")

(s/def ::limit ::us/integer)
(s/def ::since ::us/inst)
(s/def ::retrieve-monitor-status-history
  (s/keys :req-un [::id ::profile-id ::limit]
          :opt-un [::since]))

(sv/defmethod ::retrieve-monitor-status-history
  [{:keys [pool]} {:keys [id profile-id since limit]}]
  (let [since   (or since (dt/now))
        limit   (min limit 50)
        monitor (db/exec-one! pool [sql:retrieve-monitor profile-id id])]
    (when-not monitor
      (ex/raise :type :not-found
                :hint "monitor does not exists"))
    (db/exec! pool [sql:monitor-status-history id since limit])))


;; --- Query: Retrieve Monitor Log

(def sql:retrieve-monitor-entries
  "select * from monitor_entry
    where monitor_id = ?
      and created_at < ?
    order by created_at desc
    limit ?")

(s/def ::retrieve-monitor-log
  (s/keys :req-un [::profile-id ::id ::since ::limit]))

(sv/defmethod ::retrieve-monitor-log
  [{:keys [pool]} {:keys [id profile-id since limit] :as params}]
  (let [limit   (min limit 50)
        monitor (db/exec-one! pool [sql:retrieve-monitor profile-id id])
        entries (db/exec! pool [sql:retrieve-monitor-entries id since limit])]
    (when-not monitor
      (ex/raise :type :not-found
                :hint "monitor does not exists"))
    entries))


;; --- Query: Retrieve all tags

(def sql:retrieve-all-tags
  "select distinct t.tag as tag
     from (select unnest(tags) as tag
             from monitor
            where owner_id = ?) as t")

(s/def ::retrieve-all-tags
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::retrieve-all-tags
  [{:keys [pool]} {:keys [profile-id]}]
  (->> (db/exec! pool [sql:retrieve-all-tags profile-id])
       (into #{} (map :tag))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Contacts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- validate-contacts-limits!
  [conn profile]
  (let [result (db/exec-one! conn ["select count(*) as total
                                      from contact
                                     where owner_id=?" (:id profile)])]
    (when (>= (:total result 0)
              (or (:limits-max-contacts profile) ##Inf))
      (ex/raise :type :validation
                :code :contact-limits-reached))))


;; --- Mutation: Create Email Contact

(s/def ::create-email-contact
  (s/keys :req-un [::profile-id ::name ::email]))

(sv/defmethod ::create-email-contact
  [{:keys [pool tokens] :as cfg} {:keys [profile-id name email] :as props}]
  (db/with-atomic [conn pool]
    (let [id      (uuid/next)
          profile (get-profile conn profile-id)]

      ;; Validate limits
      (validate-contacts-limits! conn profile)

      ;; Do the main logic
      (let [claims  {:iss :verify-contact
                     :exp (dt/plus (dt/now) #app/duration "24h")
                     :contact-id id}
            token   ((:create tokens) claims)
            params  {:email email}]

        (emails/send! conn emails/verify-contact
                      {:to (:email params)
                       :public-uri (:public-uri cfg)
                       :invited-by (:fullname profile)
                       :invited-by-email (:email profile)
                       :token token})

        (try
          (db/insert! conn :contact
                      {:id id
                       :owner-id profile-id
                       :name name
                       :type "email"
                       :params (db/tjson params)})
          (catch org.postgresql.util.PSQLException e
            (if (= "23505" (.getSQLState e))
              (ex/raise :type :validation
                        :code :contact-already-exists
                        :cause e)
              (throw e))))
        nil))))


;; --- Mutation: Create Mattermost Contact

(s/def ::create-mattermost-contact
  (s/keys :req-un [::profile-id ::name ::us/uri]))

(sv/defmethod ::create-mattermost-contact
  [{:keys [pool] :as cfg} {:keys [profile-id name uri] :as props}]
  (db/with-atomic [conn pool]
    (let [id      (uuid/next)
          profile (get-profile conn profile-id)]

      ;; Validate limits
      (validate-contacts-limits! conn profile)

      ;; Do the main logic
      (let [params {:uri uri}]
        (db/insert! conn :contact
                    {:id id
                     :owner-id profile-id
                     :name name
                     :type "mattermost"
                     :validated-at (dt/now)
                     :params (db/tjson params)})
        nil))))


;; --- Mutation: Create Mattermost Contact

(declare decode-contact-row)

(s/def ::create-telegram-contact
  (s/keys :req-un [::profile-id ::name]))

(sv/defmethod ::create-telegram-contact
  [{:keys [pool] :as cfg} {:keys [profile-id name] :as props}]
  (db/with-atomic [conn pool]
    (let [id      (uuid/next)
          profile (get-profile conn profile-id)]

      ;; Validate limits
      (validate-contacts-limits! conn profile)

      ;; Do the main logic
      (let [contact (db/insert! conn :contact
                                {:id id
                                 :owner-id profile-id
                                 :name name
                                 :type "telegram"
                                 :params (db/tjson {})})]
        (decode-contact-row contact)))))


;; --- Mutation: Update contact

(s/def ::update-contact
  (s/keys :req-un [::id ::name ::is-paused ::profile-id]))

(sv/defmethod ::update-contact
  [{:keys [pool]} {:keys [id profile-id name is-paused] :as props}]
  (db/with-atomic [conn pool]
    (let [item (db/get-by-params conn :contact
                                 {:id id :owner-id profile-id}
                                 {:for-update true})]
      (when-not item
        (ex/raise :type :not-found
                  :code :object-not-found))

      (when (:is-blocked item)
        (ex/raise :type :validation
                  :code :contact-disabled
                  :reason (:reason item)))

      (db/update! conn :contact
                  {:name name
                   :is-paused is-paused}
                  {:id id :owner-id profile-id})
      nil)))


;; --- Mutation: Delete contact

(s/def ::delete-contact
  (s/keys :req-un [::id ::profile-id]))

(sv/defmethod ::delete-contact
  [{:keys [pool]} {:keys [id profile-id]}]
  (db/with-atomic [conn pool]
    (let [item (db/get-by-params conn :contact
                                 {:id id :owner-id profile-id}
                                 {:for-update true})]
      (when-not item
        (ex/raise :type :not-found
                  :code :object-not-found))
      (db/delete! conn :contact
                  {:id id :owner-id profile-id})
      nil)))


;; --- Query: Retrieve contacts

(defn decode-contact-row
  [{:keys [params pause-reason disable-reason id] :as row}]
  (cond-> (dissoc row :ref)
    (uuid? id)
    (assoc :short-id (telegram/uuid->b64us id))

    (db/pgobject? params)
    (assoc :params (db/decode-transit-pgobject params))

    (db/pgobject? pause-reason)
    (assoc :pause-reason (db/decode-transit-pgobject pause-reason))

    (db/pgobject? disable-reason)
    (assoc :disable-reason (db/decode-transit-pgobject disable-reason))))

(s/def ::retrieve-contacts
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::retrieve-contacts
  [{:keys [pool]} {:keys [profile-id]}]
  (->> (db/query pool :contact {:owner-id profile-id})
       (map decode-contact-row)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exports & Imports
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- generate-export!
  [{:keys [pool]} {:keys [monitors days profile-id out]
                   :or {days 90}
                   :as params}]
  (letfn [(retrieve-monitors [conn]
            (->> (db/exec! conn ["select * from monitor where owner_id=?" profile-id])
                 (map decode-monitor-row)))
          (retrieve-monitor-status [conn]
            (let [sql "select ms.* from monitor_status as ms
                         join monitor as m on (m.id = ms.monitor_id)
                        where m.owner_id = ?"]
              (db/exec! conn [sql profile-id])))

          (retrieve-monitor-entries [conn {:keys [since limit]
                                           :or {since (dt/now)
                                                limit 1000}}]
            (let [sql "select me.* from monitor_entry as me
                         join monitor as m on (m.id = me.monitor_id)
                        where me.created_at >= now() - ?::interval
                          and me.created_at < ?
                          and m.owner_id = ?
                        order by me.created_at desc
                        limit ?"
                  until (-> (dt/duration {:days days})
                            (db/interval))]
              (db/exec! conn [sql until since profile-id limit])))]

    (db/with-atomic [conn pool]
      (with-open [zout (GZIPOutputStream. out)]
        (let [writer (t/writer zout {:type :json})]
          (t/write! writer {:type :export :format 1 :created-at (dt/now)})
          (doseq [monitor (retrieve-monitors conn)]
            (t/write! writer {:type :monitor :payload monitor}))
          (doseq [mstatus (retrieve-monitor-status conn)]
            (t/write! writer {:type :monitor-status :payload mstatus}))
          (loop [since (dt/now)]
            (let [entries (retrieve-monitor-entries conn {:since since})]
              (when (seq entries)
                (t/write! writer {:type :monitor-entries-chunk :payload entries})
                (recur (:created-at (last entries)))))))))))

(s/def :internal.api.request-export/days
  (s/& ::us/integer #(>= 90 %)))

(s/def ::request-export
  (s/keys :opt-un [::profile-id
                   :internal.api.request-export/days]))

(defn- generate-export-body
  [cfg params]
  (reify rp/StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (try
        (generate-export! cfg (assoc params :out out))
        (catch org.eclipse.jetty.io.EofException e)
        (catch Exception e
          (log/errorf e "Exception on export"))))))

(sv/defmethod ::request-export
  [cfg params]
  (with-meta {}
    {:transform-response
     (fn [request response]
       {:status 200
        :headers {"content-type" "text/plain"
                  "content-disposition" "attachment; filename=\"export.data\""}
        :body (generate-export-body cfg params)})}))

;; --- Request Import

(defn process-import!
  [conn {:keys [profile-id file]}]
  (letfn [(process-monitor [state monitor]
            (let [id (uuid/next)]
              (db/insert! conn :monitor
                          (assoc monitor
                                 :id id
                                 :params (db/tjson (:params monitor))
                                 :owner-id profile-id
                                 :tags (into-array String (:tags monitor))
                                 :status "imported"))
              (update state :monitors assoc (:id monitor) id)))

          (process-monitor-status [state mstatus]
            (let [monitor-id (get-in state [:monitors (:monitor-id mstatus)])]
              (db/insert! conn :monitor-status
                          (assoc mstatus
                                 :monitor-id monitor-id
                                 :id (uuid/next)))
              state))

          (process-monitor-entries-chunk [state items]
            (let [fields [:monitor-id :created-at :reason :latency :status]
                  index  (:monitors state)]
              (db/insert-multi! conn :monitor-entry
                                fields
                                (->> items
                                     (map (fn [row]
                                            (let [monitor-id (get index (:monitor-id row))]
                                              (assoc row :monitor-id monitor-id))))
                                     (map (apply juxt fields))))
              state))

          (handle-monitor-status [id]
            (db/insert! conn :monitor-schedule {:monitor-id id})
            (change-monitor-status! conn id "imported")
            (change-monitor-status! conn id "paused"))

          (read-chunk! [reader]
            (try
              (t/read! reader)
              (catch java.lang.RuntimeException e
                (let [cause (ex-cause e)]
                  (when-not (instance? java.io.EOFException cause)
                    (throw cause))))))

          (handle-import [file]
            (with-open [in  (io/input-stream (:tempfile file))
                        zin (GZIPInputStream. in)]
              (let [reader (t/reader zin)]
                (loop [state {}]
                  (let [item (read-chunk! reader)]
                    (if (nil? item)
                      state
                      (recur (case (:type item)
                               :monitor (process-monitor state (:payload item))
                               :monitor-status (process-monitor-status state (:payload item))
                               :monitor-entries-chunk (process-monitor-entries-chunk state (:payload item))
                               state))))))))]

    (let [state (handle-import file)]
      (run! handle-monitor-status (vals (:monitors state))))))

(s/def :internal.http.upload/filename ::us/string)
(s/def :internal.http.upload/size ::us/integer)
(s/def :internal.http.upload/content-type ::us/string)
(s/def :internal.http.upload/tempfile #(instance? java.io.File %))

(s/def :internal.http/upload
  (s/keys :req-un [:internal.http.upload/filename
                   :internal.http.upload/size
                   :internal.http.upload/tempfile
                   :internal.http.upload/content-type]))

(s/def ::file :internal.http/upload)
(s/def ::request-import
  (s/keys :req-un [::file ::profile-id]))

(sv/defmethod ::request-import
  [{:keys [pool]} {:keys [profile-id file] :as params}]
  (db/with-atomic [conn pool]
    (process-import! conn params)
    nil))
