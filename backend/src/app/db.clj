;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.db
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.transit :as t]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time :as jdbc-dt]
   [next.jdbc.optional :as jdbc-opt]
   [next.jdbc.sql :as jdbc-sql]
   [next.jdbc.sql.builder :as jdbc-bld])
  (:import
   com.zaxxer.hikari.HikariConfig
   com.zaxxer.hikari.HikariDataSource
   com.zaxxer.hikari.metrics.prometheus.PrometheusMetricsTrackerFactory
   java.io.InputStream
   java.io.OutputStream
   java.sql.Connection
   java.sql.Savepoint
   java.time.Duration
   org.postgresql.PGConnection
   org.postgresql.jdbc.PgArray
   org.postgresql.largeobject.LargeObject
   org.postgresql.largeobject.LargeObjectManager
   org.postgresql.util.PGInterval
   org.postgresql.util.PGobject))

(declare open)
(declare create-pool)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::uri ::us/not-empty-string)
(s/def ::name ::us/not-empty-string)
(s/def ::min-pool-size ::us/integer)
(s/def ::max-pool-size ::us/integer)
(s/def ::migrations fn?)

(defmethod ig/pre-init-spec ::pool [_]
  (s/keys :req-un [::uri ::name ::min-pool-size ::max-pool-size ::migrations]))

(defmethod ig/init-key ::pool
  [_ {:keys [migrations] :as cfg}]
  (let [pool (create-pool cfg)]
    (when migrations
      (with-open [conn (open pool)]
        (migrations conn)))
    pool))

(defmethod ig/halt-key! ::pool
  [_ pool]
  (.close ^com.zaxxer.hikari.HikariDataSource pool))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API & Impl
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def initsql
  (str "SET statement_timeout = 10000;\n"
       "SET idle_in_transaction_session_timeout = 30000;"))

(defn- create-datasource-config
  [opts]
  (let [dburi    (:uri opts)
        username (:username opts)
        password (:password opts)
        config   (HikariConfig.)
        mtf      (PrometheusMetricsTrackerFactory. (:metrics-registry opts))]
    (doto config
      (.setJdbcUrl (str "jdbc:" dburi))
      (.setPoolName (:name opts "default"))
      (.setAutoCommit true)
      (.setReadOnly false)
      (.setConnectionTimeout 8000)  ;; 8seg
      (.setValidationTimeout 8000)  ;; 8seg
      (.setIdleTimeout 120000)      ;; 2min
      (.setMaxLifetime 1800000)     ;; 30min
      (.setMinimumIdle (:min-pool-size opts 0))
      (.setMaximumPoolSize (:max-pool-size opts 30))
      (.setMetricsTrackerFactory mtf)
      (.setConnectionInitSql initsql)
      (.setInitializationFailTimeout -1))
    (when username (.setUsername config username))
    (when password (.setPassword config password))
    config))

(defn pool?
  [v]
  (instance? javax.sql.DataSource v))

(defn unwrap
  [conn klass]
  (.unwrap ^Connection conn klass))

(defn lobj-manager
  [conn]
  (let [conn (unwrap conn org.postgresql.PGConnection)]
    (.getLargeObjectAPI ^PGConnection conn)))

(defn lobj-create
  [manager]
  (.createLO ^LargeObjectManager manager LargeObjectManager/READWRITE))

(defn lobj-open
  ([manager oid]
   (lobj-open manager oid {}))
  ([manager oid {:keys [mode] :or {mode :rw}}]
   (let [mode (case mode
                (:r :read) LargeObjectManager/READ
                (:w :write) LargeObjectManager/WRITE
                (:rw :read+write) LargeObjectManager/READWRITE)]
     (.open ^LargeObjectManager manager (long oid) mode))))

(defn lobj-unlink
  [manager oid]
  (.unlink ^LargeObjectManager manager (long oid)))

(extend-type LargeObject
  io/IOFactory
  (make-reader [lobj opts]
    (let [^InputStream is (.getInputStream ^LargeObject lobj)]
      (io/make-reader is opts)))
  (make-writer [lobj opts]
    (let [^OutputStream os (.getOutputStream ^LargeObject lobj)]
      (io/make-writer os opts)))
  (make-input-stream [lobj opts]
    (let [^InputStream is (.getInputStream ^LargeObject lobj)]
      (io/make-input-stream is opts)))
  (make-output-stream [lobj opts]
    (let [^OutputStream os (.getOutputStream ^LargeObject lobj)]
      (io/make-output-stream os opts))))

(s/def ::pool pool?)

(defn pool-closed?
  [pool]
  (.isClosed ^com.zaxxer.hikari.HikariDataSource pool))

(defn- create-pool
  [opts]
  (let [dsc (create-datasource-config opts)]
    (jdbc-dt/read-as-instant)
    (HikariDataSource. dsc)))

(defmacro with-atomic
  [& args]
  `(jdbc/with-transaction ~@args))

(defn- kebab-case [s] (str/replace s #"_" "-"))
(defn- snake-case [s] (str/replace s #"-" "_"))
(defn- as-kebab-maps
  [rs opts]
  (jdbc-opt/as-unqualified-modified-maps rs (assoc opts :label-fn kebab-case)))

(defn row->map
  "A helper that adapts database style row to clojure style map,
  converting all snake_case attrs into kebab-case, and it only works
  on a first level of the map."
  [m]
  (let [xf #(if (string? %) (keyword (str/replace % #"_" "-")) %)]
    (persistent!
     (reduce-kv (fn [m k v] (assoc! m (xf k) v))
                (transient {}) m))))

(defn open
  ^java.sql.Connection
  [pool]
  (jdbc/get-connection pool))

(defn exec!
  ([ds sv]
   (exec! ds sv {}))
  ([ds sv opts]
   (jdbc/execute! ds sv (assoc opts :builder-fn as-kebab-maps))))

(defn exec-one!
  ([ds sv] (exec-one! ds sv {}))
  ([ds sv opts]
   (jdbc/execute-one! ds sv (assoc opts :builder-fn as-kebab-maps))))

(def ^:private default-options
  {:table-fn snake-case
   :column-fn snake-case
   :builder-fn as-kebab-maps})

(defn insert!
  [ds table params]
  (jdbc-sql/insert! ds table params default-options))

(defn insert-multi!
  [ds table fields params]
  (jdbc-sql/insert-multi! ds table fields params default-options))

(defn update!
  [ds table params where]
  (let [opts (assoc default-options :return-keys true)]
    (jdbc-sql/update! ds table params where opts)))

(defn delete!
  [ds table params]
  (let [opts (assoc default-options :return-keys true)]
    (jdbc-sql/delete! ds table params opts)))

(defn get-by-params
  ([ds table params]
   (get-by-params ds table params nil))
  ([ds table params opts]
   (let [opts (cond-> (merge default-options opts)
                (:for-update opts)
                (assoc :suffix "for update"))]
     (exec-one! ds (jdbc-bld/for-query table params opts) opts))))

(defn get-by-id
  ([ds table id]
   (get-by-params ds table {:id id} nil))
  ([ds table id opts]
   (get-by-params ds table {:id id} opts)))

(defn query
  ([ds table params]
   (query ds table params nil))
  ([ds table params opts]
   (let [opts (cond-> (merge default-options opts)
                (:for-update opts)
                (assoc :suffix "for update"))]
     (exec! ds (jdbc-bld/for-query table params opts) opts))))

(defn savepoint
  ([^Connection conn]
   (.setSavepoint conn))
  ([^Connection conn label]
   (.setSavepoint conn (name label))))

(defn rollback!
  ([^Connection conn]
   (.rollback conn))
  ([^Connection conn ^Savepoint sp]
   (.rollback conn sp)))

(defn pgobject?
  [v]
  (instance? PGobject v))

(defn pgarray?
  [v]
  (instance? PgArray v))

(defn pgarray->array
  [v]
  (.getArray ^PgArray v))

(defn pginterval?
  [v]
  (instance? PGInterval v))

(defn pginterval
  [data]
  (org.postgresql.util.PGInterval. ^String data))

(defn interval
  [data]
  (cond
    (integer? data)
    (->> (/ data 1000.0)
         (format "%s seconds")
         (pginterval))

    (string? data)
    (pginterval data)

    (dt/duration? data)
    (->> (/ (.toMillis ^Duration data) 1000.0)
         (format "%s seconds")
         (pginterval))

    :else
    (ex/raise :type :not-implemented)))

(defn json
  "Encode as plain json."
  [data]
  (doto (org.postgresql.util.PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str data))))

(defn tjson
  "Encode as transit json."
  [data]
  (doto (org.postgresql.util.PGobject.)
    (.setType "jsonb")
    (.setValue (t/encode-verbose-str data))))

(defn decode-json-pgobject
  [^PGobject o]
  (let [typ (.getType o)
        val (.getValue o)]
    (if (or (= typ "json")
            (= typ "jsonb"))
      (json/read-str val :key-fn keyword)
      val)))

(defn decode-transit-pgobject
  [^PGobject o]
  (let [typ (.getType o)
        val (.getValue o)]
    (if (or (= typ "json")
            (= typ "jsonb"))
      (t/decode-str val)
      val)))

(defn decode-pgobject
  [^PGobject obj]
  (let [typ (.getType obj)
        val (.getValue obj)]
    (if (or (= typ "json")
            (= typ "jsonb"))
      (json/read-str val :key-fn keyword)
      val)))
