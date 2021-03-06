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
   [app.tasks.monitor-ssl  :as mssl]
   [app.tasks.monitor-http :as mhttp]
   [cuerdas.core :as str]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn run-monitor
  [cfg monitor]
  (case (:type monitor)
    "http" (mhttp/run cfg monitor)
    "ssl"  (mssl/run cfg monitor)
    (ex/raise :type :internal
              :code :not-implemented
              :hint (str/fmt "moitor type %s not implemented" (:type monitor)))))


(s/def ::type ::us/string)
(s/def ::parasm map?)
(s/def ::name ::us/string)
(s/def ::id ::us/uuid)

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


;; NOTE: The left join is necessary because we want lock for update
;; the both tables (or one in case no row exists on the joined table).

(def sql:retrieve-monitor
  "select m.*,
          extract(epoch from age(now(), m.monitored_at))::bigint as age,
          ms.scheduled_at
     from monitor as m
     left join monitor_schedule as ms on (ms.monitor_id = m.id)
    where m.id = ?
      for update of m")

(defn retrieve-monitor
  [conn id]
  (-> (db/exec-one! conn [sql:retrieve-monitor id])
      (decode-row)))

(defn update-monitor-status!
  [conn id result]
  (db/exec! conn ["update monitor set monitored_at=now(), status=? where id=?" (:status result) id]))

(defn insert-monitor-entry!
  [conn id result]
  (db/exec! conn ["insert into monitor_entry (monitor_id, latency, status, created_at, cause, metadata)
                   values (?, ?, ?, now(), ?, ?)"
                  id
                  (:latency result)
                  (:status result)
                  (when-let [cause (:cause result)]
                    (db/tjson cause))
                  (when-let [metadata (:metadata result)]
                    (db/tjson metadata))]))

(defn insert-monitor-status-change!
  [conn id result]
  (db/exec! conn ["update monitor_status set finished_at=now()
                    where id=(select id from monitor_status
                               where monitor_id=?
                                 and finished_at is null
                               order by created_at desc
                               limit 1)" id])
  (db/exec! conn ["insert into monitor_status (monitor_id, status, created_at, cause)
                   values (?, ?, now(), ?)"
                  id
                  (:status result)
                  (db/tjson (:cause result))]))

(def sql:monitor-contacts
  "select c.*,
          mcr.id as subscription_id,
          pf.email as owner_email
     from monitor_contact_rel as mcr
     join contact as c on (c.id = mcr.contact_id)
     join profile as pf on (c.owner_id = pf.id)
    where mcr.monitor_id = ?
      and c.is_paused is false
      and c.is_disabled is false")

(defn notify-contacts!
  [conn monitor result]
  (let [contacts (->> (db/exec! conn [sql:monitor-contacts (:id monitor)])
                      (map decode-row)
                      (map (fn [contact]
                             (cond-> contact
                               (= "owner" (:type contact))
                               (assoc :params {:email (:owner-email contact)}
                                      :type "email")))))]

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
              (when-let [update-fn (:update-fn result)]
                (update-fn conn))

              (if (and (:retry result) (< (:retry-num tdata) (:max-retries tdata)))
                (ex/raise :type :app.worker/retry
                          :delay (dt/duration {:minutes 1})
                          :hint "monitor does not passes all checks")

                (let [result (dissoc result :update-fn)]
                  (when (status-changed? monitor result)
                    (insert-monitor-status-change! conn id result)
                    (when (not= "started" (:status monitor))
                      (notify-contacts! conn monitor result)))

                  (update-monitor-status! conn id result)
                  (when (not= "healthcheck" (:type monitor))
                    (insert-monitor-entry! conn id result)))))))))))
