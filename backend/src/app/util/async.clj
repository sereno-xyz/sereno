;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.async
  (:require
   [clojure.spec.alpha :as s]
   [clojure.core.async :as a]
   [cuerdas.core :as str])
  (:import
   java.util.concurrent.Executor
   java.util.concurrent.ThreadFactory
   java.util.concurrent.ForkJoinPool
   java.util.concurrent.ForkJoinPool$ForkJoinWorkerThreadFactory
   java.util.concurrent.ExecutorService
   java.util.concurrent.atomic.AtomicLong))

(s/def ::executor #(instance? Executor %))


;; TODO: move to promesa.exec ns
;;
;; (defn counted-thread-factory
;;   [name daemon]
;;   (let [along (AtomicLong. 0)]
;;     (reify ThreadFactory
;;       (newThread [this runnable]
;;         (doto (Thread. ^Runnable runnable)
;;           (.setDaemon ^Boolean daemon)
;;           (.setName (str/format name (.getAndIncrement along))))))))

;; org.postgresql.util.PSQLException: This connection has been closed.
;;         at org.postgresql.jdbc.PgConnection.checkClosed(PgConnection.java:865) ~[postgresql-42.2.14.jar:42.2.14]
;;         at org.postgresql.jdbc.PgConnection.prepareStatement(PgConnection.java:1771) ~[postgresql-42.2.14.jar:42.2.14]
;;         at org.postgresql.jdbc.PgConnection.prepareStatement(PgConnection.java:418) ~[postgresql-42.2.14.jar:42.2.14]
;;         at com.zaxxer.hikari.pool.ProxyConnection.prepareStatement(ProxyConnection.java:337) ~[HikariCP-3.4.5.jar:?]
;;         at com.zaxxer.hikari.pool.HikariProxyConnection.prepareStatement(HikariProxyConnection.java) ~[HikariCP-3.4.5.jar:?]
;;         at next.jdbc.prepare$create.invokeStatic(prepare.clj:136) ~[?:?]
;;         at next.jdbc.prepare$create.invoke(prepare.clj:86) ~[?:?]
;;         at next.jdbc.result_set$eval24905$fn__24910.invoke(result_set.clj:766) ~[?:?]
;;         at next.jdbc.protocols$eval23873$fn__23874$G__23862__23883.invoke(protocols.clj:33) ~[?:?]
;;         at next.jdbc$execute_one_BANG_.invokeStatic(jdbc.clj:249) ~[?:?]
;;         at next.jdbc$execute_one_BANG_.invoke(jdbc.clj:233) ~[?:?]
;;         at app.db$exec_one_BANG_.invokeStatic(db.clj:130) ~[?:?]
;;         at app.db$exec_one_BANG_.invoke(db.clj:127) ~[?:?]
;;         at app.db$exec_one_BANG_.invokeStatic(db.clj:128) ~[?:?]
;;         at app.db$exec_one_BANG_.invoke(db.clj:127) ~[?:?]
;;         at app.msgbus$poll.invokeStatic(msgbus.clj:48) ~[?:?]
;;         at app.msgbus$poll.invoke(msgbus.clj:46) ~[?:?]
;;         at app.msgbus$event_loop.invokeStatic(msgbus.clj:56) ~[?:?]
;;         at app.msgbus$event_loop.invoke(msgbus.clj:52) ~[?:?]
;;         at app.msgbus$start_event_loop_STAR_.invokeStatic(msgbus.clj:71) ~[?:?]
;;         at app.msgbus$start_event_loop_STAR_.invoke(msgbus.clj:67) ~[?:?]
;;         at app.msgbus$start_event_loop$fn__36712.invoke(msgbus.clj:75) ~[?:?]
;;         at clojure.core.async$thread_call$fn__32777.invoke(async.clj:484) ~[?:?]
;;         at clojure.lang.AFn.run(AFn.java:22) ~[clojure-1.10.1.jar:?]
;;         at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1130) ~[?:?]
;;         at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:630) ~[?:?]
;;         at java.lang.Thread.run(Thread.java:832) [?:?]


(defonce processors
  (delay (.availableProcessors (Runtime/getRuntime))))

(defn forkjoin-thread-factory
  [f]
  (reify ForkJoinPool$ForkJoinWorkerThreadFactory
    (newThread [this pool]
      (let [wth (.newThread ForkJoinPool/defaultForkJoinWorkerThreadFactory pool)]
        (f wth)))))

(defn forkjoin-named-thread-factory
  [name]
  (reify ForkJoinPool$ForkJoinWorkerThreadFactory
    (newThread [this pool]
      (let [wth (.newThread ForkJoinPool/defaultForkJoinWorkerThreadFactory pool)]
        (.setName wth (str name ":" (.getPoolIndex wth)))
        wth))))

(defn forkjoin-pool
  [{:keys [factory async? parallelism]
    :or {async? true}
    :as opts}]
  (let [parallelism (or parallelism @processors)
        factory (cond
                  (fn? factory) (forkjoin-thread-factory factory)
                  (instance? ForkJoinPool$ForkJoinWorkerThreadFactory factory) factory
                  (nil? factory) ForkJoinPool/defaultForkJoinWorkerThreadFactory
                  :else (throw (ex-info "Unexpected thread factory" {:factory factory})))]
    (ForkJoinPool. (or parallelism @processors) factory nil async?)))


(defmacro go-try
  [& body]
  `(a/go
     (try
       ~@body
       (catch Exception e# e#))))

(defmacro <?
  [ch]
  `(let [r# (a/<! ~ch)]
     (if (instance? Exception r#)
       (throw r#)
       r#)))

(defn thread-call
  [^Executor executor f]
  (let [c (a/chan 1)]
    (try
      (.execute executor
                (fn []
                  (try
                    (let [ret (try (f) (catch Exception e e))]
                      (when-not (nil? ret)
                        (a/>!! c ret)))
                    (finally
                      (a/close! c)))))
      c
      (catch java.util.concurrent.RejectedExecutionException e
        (a/close! c)
        c))))


(defmacro with-thread
  [executor & body]
  (if (= executor ::default)
    `(a/thread-call (^:once fn* [] (try ~@body (catch Exception e# e#))))
    `(thread-call ~executor (^:once fn* [] ~@body))))
