;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.monitor
  (:require
   [app.common.spec :as us]
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.emails :as emails]
   [app.util.time :as dt]
   [app.tasks.monitor-http :as mhttp]
   [cuerdas.core :as str]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn run-monitor
  [cfg monitor]
  (case (:type monitor)
    :http (mhttp/run cfg monitor)
    (ex/raise :type :internal
              :code :not-implemented)))

(s/def ::monitor
  (s/keys :req-un [::type ::params ::name]
          :opt-un [::id]))

(defn run-monitor!
  [cfg monitor]
  (us/assert ::monitor monitor)
  (let [start  (. System (nanoTime))
        result (run-monitor cfg monitor)
        end    (. System (nanoTime))]
    (assoc result :latency (/ (double (- end start)) 1000000.0))))

(defn- decode-row
  [{:keys [params tags] :as row}]
  (cond-> row
    (db/pgobject? params)
    (assoc :params (db/decode-transit-pgobject params))

    (db/pgarray? tags)
    (assoc :tags (set (db/pgarray->array tags)))))

(defn- retrieve-monitor
  [conn id]
  (-> (db/get-by-id conn :monitor id)
      (decode-row)))

(defn- update-monitor-status!
  [conn id result]
  (db/exec! conn ["update monitor set monitored_at=now(), status=? where id=?" (:status result) id]))

(defn- insert-monitor-entry!
  [conn id result]
  (db/exec! conn ["insert into monitor_entry (monitor_id, latency, status, created_at, reason)
                   values (?, ?, ?, now(), ?)" id (:latency result) (:status result) (:reason result)]))

(defn- insert-monitor-status-change!
  [conn id result]
  (db/exec! conn ["update monitor_status set finished_at=now()
                    where id=(select id from monitor_status
                               where monitor_id=?
                                 and finished_at is null
                               order by created_at desc
                               limit 1)" id])
  (db/exec! conn ["insert into monitor_status (monitor_id, status, created_at, reason)
                   values (?, ?, now(), ?)" id (:status result) (:reason result)]))

(def sql:monitor-contacts
  "select c.*, mcr.id as subscription_id
     from monitor_contact_rel as mcr
    inner join contact as c on (c.id = mcr.contact_id)
    where mcr.monitor_id = ?
      and c.is_paused is false
      and c.is_disabled is false")

(defn- notify-contacts!
  [conn monitor result]
  (let [contacts (->> (db/exec! conn [sql:monitor-contacts (:id monitor)])
                      (map decode-row))]
    ;; TODO: enable batch task submit
    (doseq [contact contacts]
      (tasks/submit! conn {:name "notify"
                           :props {:monitor monitor :result result :contact contact}
                           :priority 200}))))

(defn- status-changed?
  [monitor result]
  (not= (:status monitor) (:status result)))

(s/def ::http-client fn?)

(defmethod ig/pre-init-spec ::handler
  [_]
  (s/keys :req-un [::db/pool ::http-client]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [tdata]
    (let [{:keys [id]} (:props tdata)]
      (db/with-atomic [conn pool]
        (when-let [monitor (retrieve-monitor conn id)]
          (when (not= "paused" (:status monitor))
            (let [result (run-monitor! cfg monitor)]

              ;; Execute the possible update-fn
              (ex/ignoring
               (when-let [update-fn (:update-fn result)]
                 (update-fn conn)))

              (if (and (:retry result) (< (:retry-num tdata) (:max-retries tdata)))
                (ex/raise :type :app.worker/retry
                          :hint "monitor does not passes all checks")
                (do
                  (when (status-changed? monitor result)
                    (insert-monitor-status-change! conn id result)
                    (when (not= "started" (:status monitor))
                      (notify-contacts! conn monitor result)))

                  (update-monitor-status! conn id result)
                  (insert-monitor-entry! conn id result))))))))))
