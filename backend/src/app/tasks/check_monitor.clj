;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.check-monitor
  "Auxiliar task implementation for checking the healthcheck monitors."
  (:require
   [app.common.spec :as us]
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.db :as db]
   [app.util.time :as dt]
   [app.tasks.monitor :as tsk]
   [cuerdas.core :as str]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(declare retrieve-monitor)

(defmethod ig/pre-init-spec ::handler
  [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [{:keys [props] :as task}]
    (db/with-atomic [conn pool]
      (when-let [{:keys [id status age scheduled-at] :as monitor} (tsk/retrieve-monitor conn (:id props))]
        (if (and (= "up" status) (nil? scheduled-at))
          ;; This means that the task is clearly not received any
          ;; healthcheck ping, so we proceed to setting it to down
          ;; state.
          (let [result {:status "down"
                        :cause {:code :missing-keepalive
                                :hint "the ping request is not received on time"}}]
            (tsk/update-monitor-status! conn id result)
            (tsk/insert-monitor-status-change! conn id result)
            (tsk/notify-contacts! conn monitor result))


          ;; In all other cases, this can be a false positive and
          ;; keepalive ping is received in the last moment, between
          ;; task scheduling and task execution; or the monitor is
          ;; already in down state for some other reason.
          (let [maxage (dt/plus (dt/duration {:seconds (get monitor :cadence)})
                                (dt/duration {:seconds (get-in monitor [:params :grace-time])}))
                age    (dt/duration {:seconds age})]
            (log/warnf "monitor %s does not match the criteria for marking it as down (status=%s, age=%s, max-age=%s, scheduled-at=%s)"
                       id status
                       (dt/duration->str age)
                       (dt/duration->str maxage)
                       scheduled-at)))))))
