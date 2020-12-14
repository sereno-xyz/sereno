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
      (when-let [{:keys [id status age] :as monitor} (tsk/retrieve-monitor conn (:id props))]
        (when (= "up" status)
          (let [current-age  (dt/duration {:seconds age})
                max-age      (dt/plus (dt/duration {:seconds (get monitor :cadence)})
                                      (dt/duration {:seconds (get-in monitor [:props :grace-time])}))]

            (log/infof "checking monitor %s with age %s where max-age is %s" (:name monitor) current-age max-age)
            (when (> (inst-ms current-age)
                     (inst-ms max-age))
              (tsk/update-monitor-status! conn id {:status "down"})
              (db/delete! conn :monitor-schedule {:monitor-id id}))))))))

