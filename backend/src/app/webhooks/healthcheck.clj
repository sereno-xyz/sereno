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
   [app.metrics :as mtx]
   [clojure.data.json :as json]
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [app.tasks.monitor :as tsk]
   [promesa.exec :as px]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(declare insert-monitor-schedule!)
(declare update-monitor-schedule!)
(declare process-healthcheck)
(declare wrap-ex-handling)
(declare wrap-metrics)
(declare collect-metadata)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ {:keys [executor metrics] :as cfg}]
  (let [f (-> process-healthcheck
              (wrap-ex-handling)
              (wrap-metrics cfg))]
    (fn [request]
      (clojure.pprint/pprint request)
      (let [mid (get-in request [:path-params :id])
            mid (us/conform ::us/uuid mid)
            mdt (collect-metadata request)
            cfg (assoc cfg :monitor-id mid :mdata mdt)]
        (px/run! executor (partial f cfg))
        {:status 200
         :body "OK"}))))

(defn- wrap-ex-handling
  [f]
  (fn [cfg]
    (try
      (f cfg)
      (catch Exception e
        (log/errorf e "Unhandled error")))))

(defn- wrap-metrics
  [f {:keys [metrics] :as cfg}]
  (let [mreg (:registry metrics)
        mobj (mtx/create
              {:name "webhook_healthcheck_process_millis"
               :registry mreg
               :type :summary
               :help "Healthcheck webhook process timming."})]
    (mtx/wrap-summary f mobj)))

(defn- process-healthcheck
  [{:keys [pool monitor-id mdata] :as cfg}]
  (db/with-atomic [conn pool]
    (let [monitor (tsk/retrieve-monitor conn monitor-id)
          delta   (dt/plus (dt/duration {:seconds (get monitor :cadence)})
                           (dt/duration {:seconds (get-in monitor [:params :grace-time])}))]
      (cond
        (= "started" (:status monitor))
        (do
          (tsk/update-monitor-status! conn monitor-id {:status "up"})
          (tsk/insert-monitor-status-change! conn monitor-id {:status "up"})
          (tsk/insert-monitor-entry! conn monitor-id {:latency 0 :status "up" :metadata mdata})
          (insert-monitor-schedule! conn monitor-id delta))

        (= "up" (:status monitor))
        (do
          (tsk/update-monitor-status! conn monitor-id {:status "up"})
          (tsk/insert-monitor-entry! conn monitor-id {:latency 0 :status "up" :metadata mdata})
          (if (some? (:scheduled-at monitor))
            (update-monitor-schedule! conn monitor-id delta)
            (insert-monitor-schedule! conn monitor-id delta)))

        (= "down" (:status monitor))
        (do
          (tsk/update-monitor-status! conn monitor-id {:status "up"})
          (tsk/insert-monitor-status-change! conn monitor-id {:status "up"})
          (tsk/insert-monitor-entry! conn monitor-id {:latency 0 :status "up" :metadata mdata})
          (tsk/notify-contacts! conn monitor {:status "up"})
          (insert-monitor-schedule! conn monitor-id delta))))))

;; No helpers from db/ namespace are used for this function because we
;; want to be cosnsitent with the transaction time retuned by the
;; `now()` function.

(defn- insert-monitor-schedule!
  [conn monitor-id delta]
  (let [interval (db/interval delta)]
    (db/exec-one! conn ["insert into monitor_schedule (monitor_id, modified_at, scheduled_at)
                         values (?, now(), now() + ?::interval)"
                        monitor-id
                        interval])))

(defn- update-monitor-schedule!
  [conn monitor-id delta]
  (let [interval (db/interval delta)]
    (db/exec-one! conn ["update monitor_schedule
                            set modified_at=now(),
                                scheduled_at=now() + ?::interval
                          where monitor_id = ?"
                        interval
                        monitor-id])))

(defn- collect-metadata
  [{:keys [headers] :as request}]
  (let [host   (or (get headers "x-forwarded-for")
                   (get headers "host"))
        uagent (get headers "user-agent")]
    {:host host
     :user-agent uagent}))
