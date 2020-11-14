;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.api.monitors
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.api.profile :refer [get-profile]]
   [app.tasks.monitor :refer [run-monitor!]]
   [app.util.time :as dt]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s])
  (:import
   org.postgresql.jdbc.PgArray))

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
