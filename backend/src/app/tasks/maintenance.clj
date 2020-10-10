;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.maintenance
  (:require
   [app.common.spec :as us]
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.db :as db]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

;; TODO: clear not activated profiles

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task: Vacuum Tables
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/pre-init-spec ::vacuum-tables [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::vacuum-tables
  [_ {:keys [pool] :as cfg}]
  (fn [tdata]
    (db/exec-one! pool ["VACUUM FREEZE monitor_entry"])
    (db/exec-one! pool ["VACUUM FREEZE profile_incident"])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task: Tasks Cleaner
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare clear-tasks)

(s/def ::tasks-max-age ::dt/duration)

(defmethod ig/pre-init-spec ::clean-old-tasks [_]
  (s/keys :req-un [::db/pool ::tasks-max-age]))

(defmethod ig/init-key ::clean-old-tasks
  [_ {:keys [pool] :as cfg}]
  (fn [tdata]
    (db/with-atomic [conn pool]
      (let [cfg (assoc cfg :conn conn)]
        (clear-tasks cfg)))))

(defn- clear-tasks
  [{:keys [conn tasks-max-age] :as cfg}]
  (let [sql    "delete from task_completed where scheduled_at < now() - ?::interval"
        result (db/exec-one! conn [sql (db/interval tasks-max-age)])]
    (log/infof "Removed %s rows from completed tasks table." (:next.jdbc/update-count result))))
