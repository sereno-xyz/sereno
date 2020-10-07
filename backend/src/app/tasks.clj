;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.util.time :as dt]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   java.time.Duration
   java.time.Instant
   java.util.Date))


;; --- Submit API

(s/def ::name ::us/string)
(s/def ::delay
  (s/or :int ::us/integer
        :duration dt/duration?))
(s/def ::queue ::us/string)
(s/def ::props map?)

(s/def ::task-options
  (s/keys :req-un [::name]
          :opt-un [::delay ::props ::queue]))

(def ^:private sql:insert-new-task
  "insert into task (id, name, props, queue, priority, max_retries, scheduled_at)
   values (?, ?, ?, ?, ?, ?, clock_timestamp() + ?)
   returning id")

(defn submit!
  [conn {:keys [name delay props queue key priority max-retries]
         :or {delay 0 props {} queue "default" priority 100 max-retries 1}
         :as options}]
  (us/verify ::task-options options)
  (let [duration  (dt/duration delay)
        interval  (db/interval duration)
        props     (db/tjson props)
        id        (uuid/next)]
    ;; (log/debugf "submit task %s to be executed in %s" name duration)
    (db/exec-one! conn [sql:insert-new-task id name props queue priority max-retries interval])
    id))
