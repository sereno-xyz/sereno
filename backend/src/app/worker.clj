;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.worker
  "Async tasks abstraction (impl)."
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.util.time :as dt]
   [app.util.async :as aa]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [promesa.exec :as px]
   [integrant.core :as ig])
  (:import
   java.util.concurrent.ExecutorService
   java.time.Duration
   java.time.Instant
   java.util.Date))

(def ^:private
  sql:mark-as-retry
  "update task
      set scheduled_at = clock_timestamp() + ?::interval,
          modified_at = clock_timestamp(),
          error = ?,
          status = 'retry',
          retry_num = retry_num + ?
    where id = ?")

(def default-delay
  (dt/duration {:seconds 10}))

(defn- mark-as-retry
  [conn {:keys [task error inc-by delay]
         :or {inc-by 1 delay default-delay}}]
  (let [explain (ex-message error)
        delay   (db/interval delay)
        sqlv    [sql:mark-as-retry delay explain inc-by (:id task)]]
    (db/exec-one! conn sqlv)
    nil))

(defn- mark-as-failed
  [conn {:keys [task error]}]
  (let [explain (ex-message error)]
    (db/update! conn :task
                {:error explain
                 :modified-at (dt/now)
                 :status "failed"}
                {:id (:id task)})
    nil))

(defn- mark-as-completed
  [conn {:keys [task] :as opts}]
  (let [now (dt/now)]
    (db/update! conn :task
                {:completed-at now
                 :modified-at now
                 :status "completed"}
                {:id (:id task)})
    nil))

(defn- decode-task-row
  [{:keys [props] :as row}]
  (when row
    (cond-> row
      (db/pgobject? props) (assoc :props (db/decode-transit-pgobject props)))))

(defn- handle-task
  [tasks {:keys [name] :as item}]
  (let [task-fn (get tasks name)]
    (if task-fn
      (task-fn item)
      (log/warn "no task handler found for" (pr-str name)))
    {:status :completed :task item}))

(defn- handle-exception
  [e item]
  (let [error (ex-data e)]
    (if (and (< (:retry-num item)
                (:max-retries item))
             (= ::retry (:type error)))
      (cond-> {:status :retry :task item :error e}
        (dt/duration? (:delay error))
        (assoc :delay (:delay error))

        (= ::noop (:strategy error))
        (assoc :inc-by 0))

      (do
        (log/errorf e "Unhandled exception on task '%s' (retry: %s) (props: '%s')"
                    (:name item) (:retry-num item) (pr-str (:props item)))
        (if (>= (:retry-num item) (:max-retries item))
          {:status :failed :task item :error e}
          {:status :retry :task item :error e})))))

(defn- run-task
  [{:keys [tasks conn]} item]
  (try
    (log/debugf "Started task '%s/%s/%s'." (:name item) (:id item) (:retry-num item))
    (handle-task tasks item)
    (catch Exception e
      (handle-exception e item))
    (finally
      (log/debugf "Finished task '%s/%s/%s'." (:name item) (:id item) (:retry-num item)))))

(def ^:private
  sql:select-next-tasks
  "select * from task as t
    where t.scheduled_at <= now()
      and t.queue = ?
      and (t.status = 'new' or t.status = 'retry')
    order by t.priority desc, t.scheduled_at
    limit ?
      for update skip locked")

(defn- event-loop-fn*
  [{:keys [tasks pool executor batch-size] :or {batch-size 1} :as opts}]
  (db/with-atomic [conn pool]
    (let [queue (:queue opts "default")
          items (->> (db/exec! conn [sql:select-next-tasks queue batch-size])
                     (map decode-task-row)
                     (seq))
          opts  (assoc opts :conn conn)]

      (if (nil? items)
        ::empty
        (let [proc-xf (comp (map #(partial run-task opts %))
                            (map #(px/submit! executor %)))]
          (->> (into [] proc-xf items)
               (map deref)
               (run! (fn [res]
                       (case (:status res)
                         :retry (mark-as-retry conn res)
                         :failed (mark-as-failed conn res)
                         :completed (mark-as-completed conn res)))))
          ::handled)))))

(defn- event-loop-fn
  [{:keys [executor] :as opts}]
  (aa/thread-call executor #(event-loop-fn* opts)))

;; --- Executor

(s/def ::name ::us/string)
(s/def ::min-threads ::us/integer)
(s/def ::max-threads ::us/integer)
(s/def ::idle-timeout ::us/integer)

(defmethod ig/pre-init-spec ::executor [_]
  (s/keys :opt-un [::min-threads ::max-threads ::idle-timeout ::name]))

(defmethod ig/init-key ::executor
  [_ {:keys [min-threads max-threads idle-timeout name]
      :or {min-threads 0
           max-threads 64
           idle-timeout 60000
           name "worker"}
      :as opts}]
  (doto (org.eclipse.jetty.util.thread.QueuedThreadPool. (int max-threads)
                                                         (int min-threads)
                                                         (int idle-timeout))
    (.setStopTimeout 500)
    (.setName name)))

(defmethod ig/halt-key! ::executor
  [_ instance]
  (.stop ^org.eclipse.jetty.util.thread.QueuedThreadPool instance))


;; --- Worker

(s/def ::name ::us/string)
(s/def ::queue ::us/string)
(s/def ::parallelism ::us/integer)
(s/def ::batch-size ::us/integer)
(s/def ::tasks (s/map-of string? ::us/fn))
(s/def ::poll-interval ::dt/duration)

(defmethod ig/pre-init-spec ::worker [_]
  (s/keys :req-un [::aa/executor
                   ::db/pool
                   ::batch-size
                   ::name
                   ::poll-interval
                   ::queue
                   ::tasks]))

(defmethod ig/init-key ::worker
  [_ {:keys [pool poll-interval name queue] :as opts}]
  (log/infof "Starting worker '%s' on queue '%s'." name queue)
  (let [cch     (a/chan 1)
        poll-ms (inst-ms poll-interval)]
    (a/go-loop []
      (let [[val port] (a/alts! [cch (event-loop-fn opts)] :priority true)]
        (cond
          ;; Terminate the loop if close channel is closed or
          ;; event-loop-fn returns nil.
          (or (= port cch) (nil? val))
          (log/infof "Stop condition found. Shutdown worker: '%s'" name)

          (db/pool-closed? pool)
          (do
            (log/info "Worker eventloop is aborted because pool is closed.")
            (a/close! cch))

          (and (instance? java.sql.SQLException val)
               (contains? #{"08003" "08006" "08001" "08004"} (.getSQLState ^java.sql.SQLException val)))
          (do
            (log/error "Connection error, trying resume in some instants.")
            (a/<! (a/timeout poll-interval))
            (recur))

          (and (instance? java.sql.SQLException val)
               (= "40001" (.getSQLState ^java.sql.SQLException val)))
          (do
            (log/debug "Serialization failure (retrying in some instants).")
            (a/<! (a/timeout poll-ms))
            (recur))

          (instance? Exception val)
          (do
            (log/errorf val "Unexpected error ocurried on polling the database (will resume in some instants).")
            (a/<! (a/timeout poll-ms))
            (recur))

          (= ::handled val)
          (recur)

          (= ::empty val)
          (do
            (a/<! (a/timeout poll-ms))
            (recur)))))

    (reify
      java.lang.AutoCloseable
      (close [_]
        (a/close! cch)))))

(defmethod ig/halt-key! ::worker
  [_ instance]
  (.close ^java.lang.AutoCloseable instance))
