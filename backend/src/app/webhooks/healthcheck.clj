;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.webhooks.healthcheck
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [app.tasks.monitor :as tsk]
   [promesa.exec :as px]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(declare process-healthcheck!)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ {:keys [executor] :as cfg}]
  (fn [request]
    (let [mid (get-in request [:path-params :id])
          mid (us/conform ::us/uuid mid)]
      (px/run! executor (partial process-healthcheck! (assoc cfg :monitor-id mid)))
      {:status 200
       :body "OK"})))

;; TODO: update scheduled_at
;; TODO: add migration for: add context to monitor_entru

(defn process-healthcheck!
  [{:keys [pool monitor-id] :as cfg}]
  (db/with-atomic [conn pool]
    (let [monitor (tsk/retrieve-monitor conn monitor-id)
          schd-at (-> (dt/now)
                      (dt/plus (dt/duration (get monitor :cadence)))
                      (dt/plus (dt/duration (get-in monitor [:params :grace-time]))))]
      (cond
        (= "started" (:status monitor))
        (do
          (tsk/update-monitor-status! conn monitor-id {:status "up"})
          (tsk/insert-monitor-status-change! conn monitor-id {:status "up"})
          (tsk/insert-monitor-entry! conn monitor-id {:latency 0 :status "up"})
          (db/insert! conn :monitor-schedule
                      {:monitor-id monitor-id
                       :scheduled-at schd-at}))

        (= "up" (:status monitor))
        (do
          (tsk/update-monitor-status! conn monitor-id {:status "up"})
          (tsk/insert-monitor-entry! conn monitor-id {:latency 0 :status "up"})
          (db/update! conn :monitor-schedule
                      {:scheduled-at schd-at}
                      {:monitor-id monitor-id}))


        (= "down" (:status monitor))
        (do
          (tsk/update-monitor-status! conn monitor-id {:status "up"})
          (tsk/insert-monitor-status-change! conn monitor-id {:status "up"})
          (tsk/insert-monitor-entry! conn monitor-id {:latency 0 :status "up"})
          (tsk/notify-contacts! conn monitor {:status "up"})
          (db/insert! conn :monitor-schedule
                      {:monitor-id monitor-id
                       :scheduled-at schd-at}))))))

