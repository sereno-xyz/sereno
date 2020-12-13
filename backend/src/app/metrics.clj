;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.metrics
  (:require
   [clojure.tools.logging :as log]
   [app.common.exceptions :as ex]
   [app.worker]
   [next.jdbc :as jdbc]
   [app.util.time :as dt]
   [integrant.core :as ig]
   [cuerdas.core :as str])
  (:import
   io.prometheus.client.CollectorRegistry
   io.prometheus.client.Counter
   io.prometheus.client.Gauge
   io.prometheus.client.Summary
   io.prometheus.client.exporter.common.TextFormat
   io.prometheus.client.hotspot.DefaultExports
   java.io.StringWriter))

(declare instrument!)
(declare create-registry)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- instrument-jdbc!
  [registry]
  (instrument!
   :registry registry
   :vars [#'next.jdbc/execute-one!
          #'next.jdbc/execute!]
   :type :counter
   :name "database_query_counter"
   :help "An absolute counter of database queries."))

(defn- instrument-workers!
  [registry]
  (instrument!
   :registry registry
   :type :summary
   :name "worker_task_checkout_millis"
   :help "Latency measured between scheduld_at and execution time."
   :var  #'app.worker/handle-task
   :wrap (fn [rootf mobj]
           (let [mdata (meta rootf)
                 origf (::original mdata rootf)]
             (with-meta
               (fn [tasks item]
                 (let [now (inst-ms (dt/now))
                       sat (inst-ms (:scheduled-at item))]
                   (mobj :observe (- now sat))
                   (origf tasks item)))
               {::original origf})))))


(defn handler
  [registry request]
  (let [samples  (.metricFamilySamples ^CollectorRegistry registry)
        writer   (StringWriter.)]
    (TextFormat/write004 writer samples)
    {:headers {"content-type" TextFormat/CONTENT_TYPE_004}
     :body (.toString writer)}))

(defmethod ig/init-key ::metrics
  [_ opts]
  (log/infof "Initializing prometheus registry and instrumentation.")
  (let [registry (create-registry)]
    (instrument-workers! registry)
    (instrument-jdbc! registry)
    {:handler (partial handler registry)
     :registry registry}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-registry
  []
  (let [registry (CollectorRegistry.)]
    ;; (DefaultExports/register registry)
    registry))

(defmacro with-measure
  [& {:keys [expr cb]}]
  `(let [start# (System/nanoTime)
         tdown# ~cb]
     (try
       ~expr
       (finally
         (tdown# (/ (- (System/nanoTime) start#) 1000000))))))

(defn make-counter
  [{:keys [name help registry] :as props}]
  (let [instance (doto (Counter/build)
                   (.name name)
                   (.help help))
        instance (.register instance registry)]
    (reify
      clojure.lang.IDeref
      (deref [_] instance)

      clojure.lang.IFn
      (invoke [_ cmd]
        (.inc ^Counter instance)))))

(defn make-gauge
  [{:keys [name help registry] :as props}]
  (let [instance (doto (Gauge/build)
                   (.name name)
                   (.help help))
        instance (.register instance registry)]

    (reify
      clojure.lang.IDeref
      (deref [_] instance)

      clojure.lang.IFn
      (invoke [_ cmd]
        (case cmd
          :inc (.inc ^Gauge instance)
          :dec (.dec ^Gauge instance))))))

(defn make-summary
  [{:keys [name help registry] :as props}]
  (let [instance (doto (Summary/build)
                   (.name name)
                   (.help help)
                   (.quantile 0.5 0.05)
                   (.quantile 0.9 0.01)
                   (.quantile 0.99 0.001))
        instance (.register instance registry)]
    (reify
      clojure.lang.IDeref
      (deref [_] instance)

      clojure.lang.IFn
      (invoke [_ cmd val]
        (.observe ^Summary instance val)))))

(defn create
  [{:keys [type name] :as props}]
  (case type
    :counter (make-counter props)
    :gauge (make-gauge props)
    :summary (make-summary props)))

(defn wrap-counter
  [rootf mobj]
  (let [mdata (meta rootf)
        origf (::original mdata rootf)]
    (with-meta
      (fn
        ([a]
         (mobj :inc)
         (origf a))
        ([a b]
         (mobj :inc)
         (origf a b))
        ([a b & more]
         (mobj :inc)
         (apply origf a b more)))
      (assoc mdata ::original origf))))

(defn wrap-summary
  [rootf mobj]
  (let [mdata (meta rootf)
        origf (::original mdata rootf)]
    (with-meta
      (fn
        ([a]
         (with-measure
           :expr (origf a)
           :cb #(mobj :observe %)))
        ([a b]
         (with-measure
           :expr (origf a b)
           :cb #(mobj :observe %)))
        ([a b & more]
         (with-measure
           :expr (apply origf a b more)
           :cb #(mobj :observe %))))
      (assoc mdata ::original origf))))

(defn instrument!
  [& {:keys [var vars wrap] :as props}]
  (let [obj  (create props)
        vars (if var [var] vars)]
    (cond
      (instance? Counter @obj)
      (doseq [var vars]
        (alter-var-root var (or wrap wrap-counter) obj))

      (instance? Summary @obj)
      (doseq [var vars]
        (alter-var-root var (or wrap wrap-summary) obj))

      :else
      (ex/raise :type :not-implemented))))
