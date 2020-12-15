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
   [app.common.data :as d]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.util.time :as dt]
   [app.metrics :as mtx]
   [clojure.data.json :as json]
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.java.io :as io]
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
(declare parse-request)
(declare build-result)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ {:keys [executor metrics] :as cfg}]
  (let [f (-> process-healthcheck
              (wrap-ex-handling)
              (wrap-metrics cfg))]
    (fn [request]
      (let [prm (parse-request request)
            cfg (assoc cfg :params prm)]
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
  [{:keys [pool params] :as cfg}]
  (db/with-atomic [conn pool]
    (let [monitor-id (:monitor-id params)
          monitor    (tsk/retrieve-monitor conn monitor-id)
          delta      (dt/plus (dt/duration {:seconds (get monitor :cadence)})
                              (dt/duration {:seconds (get-in monitor [:params :grace-time])}))]

      (cond
        (= "started" (:status monitor))
        (let [result (build-result params)]
          (tsk/update-monitor-status! conn monitor-id result)
          (tsk/insert-monitor-status-change! conn monitor-id result)
          (tsk/insert-monitor-entry! conn monitor-id result)
          (insert-monitor-schedule! conn monitor-id delta))

        (= "up" (:status monitor))
        (let [result (build-result params)]
          (tsk/update-monitor-status! conn monitor-id result)
          (tsk/insert-monitor-entry! conn monitor-id result)

          (cond
            (not= (:status result) (:status monitor))
            (do
              (tsk/insert-monitor-status-change! conn monitor-id result)
              (db/delete! conn :monitor-schedule {:monitor-id monitor-id})
              (tsk/notify-contacts! conn monitor result))

            (some? (:scheduled-at monitor))
            (update-monitor-schedule! conn monitor-id delta)

            :else
            (insert-monitor-schedule! conn monitor-id delta)))

        (= "down" (:status monitor))
        (let [result (build-result params)]
          (tsk/update-monitor-status! conn monitor-id result)
          (tsk/insert-monitor-entry! conn monitor-id result)

          (when (not= (:status result) (:status monitor))
            (tsk/insert-monitor-status-change! conn monitor-id result)
            (insert-monitor-schedule! conn monitor-id delta)
            (tsk/notify-contacts! conn monitor result)))))))

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


(s/def ::monitor-id ::us/uuid)
(s/def ::label ::us/string)
(s/def ::exit ::us/integer)

(s/def ::path-params
  (s/keys :req-un [::monitor-id]
          :opt-un [::label]))

(s/def ::query-params
  (s/keys :opt-un [::exit]))

(defn- parse-request
  [{:keys [headers body path-params query-params] :as request}]
  (let [pparams (us/conform ::path-params path-params)
        qparams (us/conform ::query-params query-params)
        host    (or (get headers "x-forwarded-for")
                    (get headers "host"))
        uagent  (get headers "user-agent")]
    (d/merge pparams qparams
             {:host host
              :content (slurp body)
              :user-agent uagent})))

(defn- build-result
  [{:keys [exit content user-agent label host] :as params}]
  (let [metadata (d/remove-nil-vals
                  {:content content
                   :host host
                   :user-agent user-agent
                   :label label
                   :exit exit})]

    (cond
      (and (some? exit) (not (zero? exit)))
      {:status "down"
       :cause {:code :exit-code
               :exit exit
               :hint "exit code different of 0 (zero)"}
       :metadata metadata}

      (or (= label "fail") (= label "error"))
      {:status "down"
       :cause {:code :fail-label
               :hint "reported explicit fail state"
               :label label}
       :metadata metadata}

      :else
      {:status "up"
       :cause nil
       :metadata metadata})))
