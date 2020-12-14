;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.rpc.monitors
  (:require
   [app.rpc.profile :refer [get-profile]]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.tasks.monitor :refer [run-monitor!]]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s])
  (:import
   org.postgresql.jdbc.PgArray))

;; --- Mutation: Create http monitor

(declare validate-monitor-limits!)
(declare validate-cadence-limits!)
(declare parse-and-validate-cadence!)
(declare prepare-http-monitor-params)

(s/def ::id ::us/uuid)
(s/def ::method ::us/keyword)
(s/def ::uri ::us/uri)
(s/def ::should-include ::us/string)
(s/def ::headers (s/map-of ::us/string ::us/string))
(s/def ::cadence ::us/integer)
(s/def ::contacts (s/coll-of ::us/uuid :min-count 1))
(s/def ::tags (s/coll-of ::us/string :kind set?))

(s/def ::create-http-monitor
  (s/keys :req-un [::name ::cadence ::profile-id ::contacts ::method ::uri]
          :opt-un [::tags ::should-include ::headers]))

(sv/defmethod ::create-http-monitor
  [{:keys [pool]} {:keys [cadence profile-id name contacts tags] :as data}]
  (db/with-atomic [conn pool]
    (let [id      (uuid/next)
          now-t   (dt/now)
          cron    (parse-and-validate-cadence! cadence)
          profile (get-profile conn profile-id)
          params  (prepare-http-monitor-params data)]

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
                   :type "http"
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


(defn- prepare-http-monitor-params
  [{:keys [uri method headers should-include]}]
  (cond-> {:uri uri
           :method (or method :get)}
    (map? headers)
    (assoc :headers headers)

    (string? should-include)
    (assoc :should-include should-include)))

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


;; --- Mutation: Create SSL Monitor

(s/def ::create-ssl-monitor
  (s/keys :req-un [::profile-id ::name ::contacts ::uri ::alert-before]
          :opt-un [::tags]))

(sv/defmethod ::create-ssl-monitor
  [{:keys [pool]} {:keys [profile-id name contacts tags uri alert-before]}]
  (db/with-atomic [conn pool]
    (let [id      (uuid/next)
          now-t   (dt/now)
          cadence 21600 ;; 6h
          profile (get-profile conn profile-id)
          params  {:uri uri
                   :alert-before alert-before}]

      (validate-monitor-limits! conn profile)

      (db/insert! conn :monitor
                  {:id id
                   :owner-id profile-id
                   :name name
                   :cadence cadence
                   :cron-expr (get cadence-map cadence)
                   :created-at now-t
                   :status "started"
                   :type "ssl"
                   :params (db/tjson params)
                   :tags (into-array String tags)})

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


;; --- Mutation: Create health check monitor

(s/def ::grace-time ::us/integer)

(s/def ::create-healthcheck-monitor
  (s/keys :req-un [::name ::profile-id ::contacts ::cadence ::grace-time]
          :opt-un [::tags]))

(sv/defmethod ::create-healthcheck-monitor
  [{:keys [pool]} {:keys [name contacts tags profile-id cadence grace-time]}]
  (db/with-atomic [conn pool]
    (let [id      (uuid/next)
          profile (get-profile conn profile-id)
          now-t   (dt/now)
          params  {:schedule :simple
                   :grace-time grace-time}]

      (validate-monitor-limits! conn profile)
      (validate-cadence-limits! profile cadence)

      (db/insert! conn :monitor
                  {:id id
                   :owner-id profile-id
                   :name name
                   :cadence cadence
                   :cron-expr "" ; Explicitly empty
                   :created-at now-t
                   :status "started"
                   :type "healthcheck"
                   :params (db/tjson params)
                   :tags (into-array String tags)})

      (db/insert! conn :monitor-status
                  {:monitor-id id
                   :status "created"
                   :created-at now-t
                   :finished-at now-t})

      (db/insert! conn :monitor-status
                  {:monitor-id id
                   :status "started"
                   :created-at now-t})

      (doseq [cid contacts]
        (db/insert! conn :monitor-contact-rel
                    {:monitor-id id
                     :contact-id cid}))
      nil)))



;; --- Mutation: Update Http Monitor

(s/def ::update-http-monitor
  (s/keys :req-un [::id ::name ::cadence ::profile-id ::contacts ::uri ::method]
          :opt-un [::tags ::should-include ::headers]))

(sv/defmethod ::update-http-monitor
  [{:keys [pool]} {:keys [id name cadence profile-id contacts tags] :as data}]
  (db/with-atomic [conn pool]
    (let [params  (prepare-http-monitor-params data)
          monitor (db/get-by-params conn :monitor
                                    {:id id :owner-id profile-id}
                                    {:for-update true})
          profile (get-profile conn profile-id)
          cron    (parse-and-validate-cadence! cadence)
          offset  (dt/get-cron-offset cron (:created-at monitor))]

      (validate-cadence-limits! profile cadence)

      (when-not monitor
        (ex/raise :type :not-found
                  :code :object-does-not-found))

      (when (not= "http" (:type monitor))
        (ex/raise :type :validation
                  :code :wront-monitor-type))

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

      (db/delete! conn :monitor-contact-rel {:monitor-id id})
      (doseq [cid contacts]
        (db/insert! conn :monitor-contact-rel
                    {:monitor-id id
                     :contact-id cid}))

      nil)))


;; --- Mutation: Update Http Monitor

(s/def ::update-ssl-monitor
  (s/keys :req-un [::id ::name ::profile-id ::contacts ::uri ::alert-before]
          :opt-un [::tags]))

(sv/defmethod ::update-ssl-monitor
  [{:keys [pool]} {:keys [id name profile-id contacts tags uri alert-before] :as data}]
  (db/with-atomic [conn pool]
    (let [params  {:uri uri :alert-before alert-before}
          monitor (db/get-by-params conn :monitor
                                    {:id id :owner-id profile-id}
                                    {:for-update true})]

      (when-not monitor
        (ex/raise :type :not-found
                  :code :object-does-not-found))

      (when (not= "ssl" (:type monitor))
        (ex/raise :type :validation
                  :code :wront-monitor-type
                  :hint "expected ssl monitor"))

      (db/update! conn :monitor-schedule
                  {:scheduled-at (dt/now)}
                  {:monitor-id id})

      (db/update! conn :monitor
                  {:name name
                   :params (db/tjson params)
                   :tags (into-array String tags)}
                  {:id id
                   :owner-id profile-id})

      (db/delete! conn :monitor-contact-rel {:monitor-id id})
      (doseq [cid contacts]
        (db/insert! conn :monitor-contact-rel
                    {:monitor-id id
                     :contact-id cid}))

      nil)))


;; --- Mutation: Update Health Check monitor

(s/def ::update-healthcheck-monitor
  (s/keys :req-un [::id ::name ::profile-id ::contacts ::cadence ::grace-time]
          :opt-un [::tags]))

(sv/defmethod ::update-healthcheck-monitor
  [{:keys [pool]} {:keys [id name profile-id cadence grace-time contacts tags]}]
  (db/with-atomic [conn pool]
    (let [params  {:schedule :simple
                   :grace-time grace-time}
          profile (get-profile conn profile-id)
          monitor (db/get-by-params conn :monitor
                                    {:id id :owner-id profile-id}
                                    {:for-update true})]

      (validate-cadence-limits! profile cadence)

      (when-not monitor
        (ex/raise :type :not-found
                  :code :object-does-not-found))

      (when (not= "healthcheck" (:type monitor))
        (ex/raise :type :validation
                  :code :wront-monitor-type))

      (db/update! conn :monitor
                  {:name name
                   :cadence cadence
                   :params (db/tjson params)
                   :tags (into-array String tags)}
                  {:id id
                   :owner-id profile-id})

      (db/delete! conn :monitor-contact-rel {:monitor-id id})

      (doseq [cid contacts]
        (db/insert! conn :monitor-contact-rel
                    {:monitor-id id
                     :contact-id cid}))

      nil)))


;; --- Test Monitor

(s/def ::test-http-monitor any?)

(sv/defmethod ::test-http-monitor
  [cfg data]
  (let [params (prepare-http-monitor-params data)]
    (run-monitor! cfg {:type "http"
                       :params params
                       :name ""})))


;; --- Mutation: Pause monitor

(defn change-monitor-status!
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

(declare sql:monitor-chart-buckets)
(declare sql:monitor-latencies)
(declare sql:monitor-uptime)

(s/def ::retrieve-monitor-summary
  (s/keys :req-un [::id]))

(sv/defmethod ::retrieve-monitor-summary
  [{:keys [pool]} {:keys [id profile-id]}]
  (db/with-atomic [conn pool]
    (let [monitor (db/exec-one! conn [sql:retrieve-monitor profile-id id])]
      (when-not monitor
        (ex/raise :type :not-found
                  :hint "monitor does not exists"))
      (let [buckets   (db/exec! conn [sql:monitor-chart-buckets id])
            latencies (db/exec-one! conn [sql:monitor-latencies id ])
            uptime    (db/exec-one! conn [sql:monitor-uptime id ])]
        (d/merge latencies uptime {:buckets buckets})))))

(def sql:monitor-latencies
  "select percentile_cont(0.90) within group (order by latency) as latency_p90,
          avg(latency)::float8 as latency_avg
     from monitor_entry as me
    where me.monitor_id = ?
      and me.created_at > now() - '90 days'::interval")

(def sql:monitor-uptime
  "with entries as (
      select e.*,
             (coalesce(e.finished_at, now()) -
              case when e.created_at < (now()-'90 days'::interval)
                   then now()-'90 days'::interval
                   else e.created_at end) as duration
        from monitor_status as e
       where e.monitor_id = ?
         and e.status in ('up', 'down', 'warn')
         and tstzrange(e.created_at, e.finished_at) && tstzrange(now() - '90 days'::interval, now())
   )
   select (select extract(epoch from sum(duration)) from entries)::float8 as total_seconds,
          (select extract(epoch from sum(duration)) from entries where status = 'down')::float8 as down_seconds,
          (select extract(epoch from sum(duration)) from entries where status = 'up' or status = 'warn')::float8 as up_seconds")

;; TODO: seems like the where condition is not very efficient
(def sql:monitor-chart-buckets
   "select time_bucket('1 day'::interval, created_at) as ts,
           round(avg(latency)::numeric, 2)::float8 as avg
     from monitor_entry
    where monitor_id = ?
      and (now()-created_at) < '90 days'::interval group by 1 order by 1")


;; --- Query: Retrieve Monitor Status

(declare decode-status-row)

(def sql:monitor-status-history
  "select e.*
     from monitor_status as e
    where e.monitor_id = ?
      and e.created_at < ?
    order by e.created_at desc
    limit ?")

(s/def ::limit ::us/integer)
(s/def ::since ::dt/instant)

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
    (->> (db/exec! pool [sql:monitor-status-history id since limit])
         (mapv decode-status-row))))

(defn decode-status-row
  [{:keys [cause] :as row}]
  (cond-> row
    (db/pgobject? cause)
    (assoc :cause (db/decode-transit-pgobject cause))))


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

