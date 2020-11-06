;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.scheduler
  "Monitor execution scheduling service."
  (:require
   [app.common.spec :as us]
   [app.util.async :as aa]
   [app.db :as db]
   [app.tasks :as tasks]
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

(defn get-next-inst
  [item]
  (let [cron   (dt/cron (:cron-expr item))
        offset (dt/get-cron-offset cron (:created-at item))]
    (dt/get-next-inst cron offset)))

(defn schedule-item
  [{:keys [conn]} item]
  (let [ninst (get-next-inst item)]
    (tasks/submit! conn {:name "monitor"
                         :props {:id (:id item)}
                         :max-retries 2
                         :delay 0})
    (db/update! conn :monitor-schedule
                {:modified-at (dt/now)
                 :scheduled-at ninst}
                {:monitor-id (:id item)})))

(def sql:retrieve-scheduled-monitors
  "select m.id, m.created_at, m.status, m.cron_expr
     from monitor_schedule as ms
     join monitor as m on (m.id = ms.monitor_id)
    where ms.scheduled_at <= now()
      and m.status in ('started', 'up', 'down')
    order by ms.scheduled_at, ms.modified_at
    limit ?
      for update of ms skip locked")

(defn- event-loop-fn*
  [{:keys [pool batch-size] :as opts}]
  (db/with-atomic [conn pool]
    (let [items (db/exec! conn [sql:retrieve-scheduled-monitors batch-size])
          opts  (assoc opts :conn conn)]
      (run! (partial schedule-item opts) items)
      (count items))))

(defn- event-loop-fn
  [{:keys [executor] :as opts}]
  (aa/thread-call executor #(event-loop-fn* opts)))

(defn- schedule-chan
  [{:keys [id cron task] :as item}]
  (a/go
    (let [now-t (dt/now)
          ninst (dt/next-exec-time cron now-t)
          d     (dt/duration-between now-t ninst)]
      (a/<! (a/timeout (+ (inst-ms d) 500)))
      item)))

(defn- schedule-acquire-lock
  [conn id]
  (let [result (db/exec-one! conn ["select pg_try_advisory_xact_lock(?) as locked" id])]
    (:locked result)))

(defn- schedule-run
  [{:keys [pool executor]} {:keys [id task]}]
  (aa/thread-call executor (fn []
                             (db/with-atomic [conn pool]
                               (when (schedule-acquire-lock conn id)
                                 (tasks/submit! conn {:name task :max-retries 0}))))))


(s/def ::poll-interval ::dt/duration)
(s/def ::batch-size ::us/integer)
(s/def ::id ::us/integer)
(s/def ::cron ::dt/cron-expr)
(s/def ::task ::us/string)

(s/def ::schedule-item
  (s/keys :req-un [::id ::cron ::task]))

(s/def ::schedule
  (s/coll-of ::schedule-item :kind vector? :min-count 1))

(defmethod ig/pre-init-spec ::scheduler
  [_]
  (s/keys :req-un [::db/pool ::aa/executor ::poll-interval ::batch-size]
          :opt-un [::schedule]))

(defmethod ig/init-key ::scheduler
  [_ {:keys [pool poll-interval schedule] :as opts}]
  (log/info "Starting monitor scheduler.")
  (let [chd     (a/chan)
        poll-ms (inst-ms poll-interval)]

    (a/go-loop [sch (into #{} (map schedule-chan) schedule)]
      (let [[item port] (a/alts! (into [chd] sch))]
        (if (= port chd)
          (log/info "Stop condition found. Shutdown scheduler")
          (do
            (a/<! (schedule-run opts item))
            (recur (-> sch
                       (disj port)
                       (conj (schedule-chan item))))))))

    (a/go-loop []
      (let [[val port] (a/alts! [chd (event-loop-fn opts)] :priority true)]
        ;; (log/debug "scheduler event-loop:" (pr-str val))
        (cond
          ;; Terminate the loop if close channel is closed or
          ;; event-loop-fn returns nil.
          (or (= port chd) (nil? val))
          (log/info "Stop condition found. Shutdown scheduler")

          (db/pool-closed? pool)
          (do
            (log/error "Scheduler eventloop aborted because pool is closed.")
            (a/close! chd))

          (and (instance? java.sql.SQLException val)
               (contains? #{"08003" "08006" "08001" "08004"} (.getSQLState ^java.sql.SQLException val)))
          (do
            (log/error "Connection error, trying reconnection in some instants.")
            (a/<! (a/timeout poll-ms))
            (recur))

          (instance? Exception val)
          (do
            (log/errorf val "Unexpected error ocurried with database connection (reconnect in some instants).")
            (a/<! (a/timeout poll-ms))
            (recur))

          (pos? val)
          (recur)

          (zero? val)
          (do
            (a/<! (a/timeout poll-ms))
            (recur))

          :else
          (do
            (log/infof "Unexpected condition occurred: %s" (pr-str val))
            (recur)))))

    (reify
      java.lang.AutoCloseable
      (close [_]
        (a/close! chd)))))

(defmethod ig/halt-key! ::scheduler
  [_ instance]
  (.close ^java.lang.AutoCloseable instance))

