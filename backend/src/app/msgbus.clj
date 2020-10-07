;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.msgbus
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.util.async :as aa]
   [app.util.blob :as blob]
   [app.util.transit :as t]
   [clojure.core.async :as a]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   java.sql.Wrapper
   java.sql.SQLException
   org.postgresql.PGConnection
   org.postgresql.PGNotification))

(s/def ::pool db/pool?)

(defn notification->map
  [^PGNotification item]
  (let [{:keys [id operation owner-id] :as payload} (json/read-str (.getParameter item) :key-fn keyword)]
    (cond-> (assoc payload :channel (.getName item))
      (and (string? id)
           (re-matches us/uuid-rx id))
      (assoc :id (uuid/uuid id))

      (and (string? owner-id)
           (re-matches us/uuid-rx owner-id))
      (assoc :owner-id (uuid/uuid owner-id))

      (string? operation)
      (assoc :operation (keyword (str/lower operation))))))


(defn poll
  [conn]
  (db/exec-one! conn ["SELECT 1"])
  (let [pgconn (.unwrap ^Wrapper conn PGConnection)]
    (seq (.getNotifications ^PGConnection pgconn))))

(defn event-loop
  [{:keys [conn out] :as opts}]
  (loop [items nil]
    (if (nil? items)
      (let [items (poll conn)]
        (if (empty? items)
          (do
            (Thread/sleep 1000)
            (recur nil))
          (recur (seq items))))

      (let [item (notification->map (first items))]
        (when (a/>!! out item)
          (recur (next items)))))))

(defn start-event-loop*
  [{:keys [pool] :as opts}]
  (when-let [conn (db/open pool)]
    (db/exec-one! conn ["LISTEN db_changes;"])
    (event-loop (assoc opts :conn conn))))

(defn start-event-loop
  [{:keys [executor] :as opts}]
  (aa/thread-call executor #(start-event-loop* opts)))

(defmethod ig/pre-init-spec ::instance [_]
  (s/keys :req-un [::db/pool ::aa/executor]))

(defmethod ig/init-key ::instance
  [_ {:keys [pool] :as opts}]
  (log/info "Starting msgbus instance.")
  (let [out  (a/chan 16)
        chs  (atom #{})
        chd  (a/chan)
        opts (assoc opts :out out)]
    (a/go-loop [ech (start-event-loop opts)]
      (let [[val port] (a/alts! [chd ech out] :priority true)]
        (cond
          (and (= port out) (not (nil? val)))
          (do
            (doseq [c @chs]
              (when-not (a/>! c val)
                (swap! chs disj c)))
            (recur ech))

          (and (= port ech) (instance? Exception val))
          (do
            (if (and (instance? SQLException val)
                     (contains? #{"08003" "08006" "08001" "08004"} (.getSQLState ^SQLException val)))
              (log/error "Connection error, trying reconnection in some instants.")
              (log/errorf val "Unexpected error ocurred with database connection (trying teconnect in some instants)"))
            (recur (start-event-loop opts)))

          (and (not (nil? val))
               (db/pool-closed? pool))
          (do
            (log/error "Msgbus eventloop aborted because pool is closed.")
            (a/close! chd)
            (a/close! out))

          :else
          (do
            (log/info (str/format "Stop condition found. Shutdown msgbus."))
            (swap! chs (fn [chans]
                         (doseq [c chans]
                           (a/close! c))
                         #{}))))))

    (reify
      java.lang.AutoCloseable
      (close [_]
        (a/close! chd)
        (a/close! out))

      clojure.lang.IFn
      (invoke [_ ch]
        (swap! chs conj ch)
        ch))))


(defmethod ig/halt-key! ::instance
  [_ instance]
  (.close ^java.lang.AutoCloseable instance))
