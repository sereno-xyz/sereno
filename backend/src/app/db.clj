;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns app.db
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.transit :as t]
   [app.util.time :as dt]
   [app.util.migrations :as mg]
   [app.db.sql :as sql]
   [app.metrics :as mtx]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time :as jdbc-dt]
   [next.jdbc.optional :as jdbc-opt]
   [next.jdbc.sql :as jdbc-sql]
   [next.jdbc.sql.builder :as jdbc-bld])
  (:import
   java.lang.AutoCloseable
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
(declare instrument-jdbc!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::uri ::us/not-empty-string)
(s/def ::name ::us/not-empty-string)
(s/def ::min-pool-size ::us/integer)
(s/def ::max-pool-size ::us/integer)
(s/def ::migrations map?)

(defmethod ig/pre-init-spec ::pool [_]
  (s/keys :req-un [::uri ::name ::min-pool-size ::max-pool-size ::migrations ::mtx/metrics]))

(defmethod ig/init-key ::pool
  [_ {:keys [migrations metrics] :as cfg}]
  (log/infof "initialize connection pool '%s' with uri '%s'" (:name cfg) (:uri cfg))
  (instrument-jdbc! (:registry metrics))
  (let [pool (create-pool cfg)]
    (when (seq migrations)
      (with-open [conn ^AutoCloseable (open pool)]
        (mg/setup! conn)
        (doseq [[mname steps] migrations]
          (mg/migrate! conn {:name (name mname) :steps steps}))))
    pool))

(defmethod ig/halt-key! ::pool
  [_ pool]
  (.close ^com.zaxxer.hikari.HikariDataSource pool))

(defn- instrument-jdbc!
  [registry]
  (mtx/instrument-vars!
   [#'next.jdbc/execute-one!
    #'next.jdbc/execute!]
   {:registry registry
    :type :counter
    :name "database_query_total"
    :help "An absolute counter of database queries."}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API & Impl
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def initsql
  (str "SET statement_timeout = 120000;\n"
       "SET idle_in_transaction_session_timeout = 120000;"))

(defn- create-datasource-config
  [{:keys [metrics] :as cfg}]
  (let [dburi    (:uri cfg)
        username (:username cfg)
        password (:password cfg)
        config   (HikariConfig.)
        mtf      (PrometheusMetricsTrackerFactory. (:registry metrics))]
    (doto config
      (.setJdbcUrl (str "jdbc:" dburi))
      (.setPoolName (:name cfg "default"))
      (.setAutoCommit true)
      (.setReadOnly false)
      (.setConnectionTimeout 8000)  ;; 8seg
      (.setValidationTimeout 8000)  ;; 8seg
      (.setIdleTimeout 120000)      ;; 2min
      (.setMaxLifetime 1800000)     ;; 30min
      (.setMinimumIdle (:min-pool-size cfg 0))
      (.setMaximumPoolSize (:max-pool-size cfg 30))
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

(s/def ::pool pool?)

(defn pool-closed?
  [pool]
  (.isClosed ^com.zaxxer.hikari.HikariDataSource pool))

(defn- create-pool
  [cfg]
  (let [dsc (create-datasource-config cfg)]
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
   (jdbc/execute! ds sv (assoc opts :builder-fn sql/as-kebab-maps))))

(defn exec-one!
  ([ds sv] (exec-one! ds sv {}))
  ([ds sv opts]
   (jdbc/execute-one! ds sv (assoc opts :builder-fn sql/as-kebab-maps))))

(defn insert!
  ([ds table params] (insert! ds table params nil))
  ([ds table params opts]
   (exec-one! ds
              (sql/insert table params opts)
              (assoc opts :return-keys true))))

(defn update!
  ([ds table params where] (update! ds table params where nil))
  ([ds table params where opts]
   (exec-one! ds
              (sql/update table params where opts)
              (assoc opts :return-keys true))))

(defn delete!
  ([ds table params] (delete! ds table params nil))
  ([ds table params opts]
   (exec-one! ds
              (sql/delete table params opts)
              (assoc opts :return-keys true))))

(defn get-by-params
  ([ds table params]
   (get-by-params ds table params nil))
  ([ds table params opts]
   (exec-one! ds (sql/select table params opts))))

(defn get-by-id
  ([ds table id]
   (get-by-params ds table {:id id} nil))
  ([ds table id opts]
   (get-by-params ds table {:id id} opts)))

(defn query
  ([ds table params]
   (query ds table params nil))
  ([ds table params opts]
   (exec! ds (sql/select table params opts))))

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

(defn create-array
  [conn type objects]
  (let [^PGConnection conn (unwrap conn org.postgresql.PGConnection)]
    (if (coll? objects)
      (.createArrayOf conn ^String type (into-array Object objects))
      (.createArrayOf conn ^String type objects))))

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
