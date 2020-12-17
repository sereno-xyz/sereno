;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.rpc.export
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.monitors :refer [decode-monitor-row
                             decode-status-row
                             change-monitor-status!]]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [app.util.services :as sv]
   [clojure.java.io :as io]
   [clojure.data.csv :as csv]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [ring.core.protocols :as rp])
  (:import
   java.util.zip.GZIPInputStream
   java.util.zip.GZIPOutputStream))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exports & Imports
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- generate-export!
  [{:keys [pool]} {:keys [monitors days profile-id out]
                   :or {days 90}
                   :as params}]
  (letfn [(retrieve-monitors [conn]
            (->> (db/exec! conn ["select * from monitor where owner_id=?" profile-id])
                 (map decode-monitor-row)))
          (retrieve-monitor-status [conn]
            (let [sql "select ms.* from monitor_status as ms
                         join monitor as m on (m.id = ms.monitor_id)
                        where m.owner_id = ?"]
              (->> (db/exec! conn [sql profile-id])
                   (map decode-status-row))))

          (retrieve-monitor-entries [conn {:keys [since limit]
                                           :or {since (dt/now)
                                                limit 1000}}]
            (let [sql "select me.* from monitor_entry as me
                         join monitor as m on (m.id = me.monitor_id)
                        where me.created_at >= now() - ?::interval
                          and me.created_at < ?
                          and m.owner_id = ?
                        order by me.created_at desc
                        limit ?"
                  until (-> (dt/duration {:days days})
                            (db/interval))]
              (db/exec! conn [sql until since profile-id limit])))]

    (db/with-atomic [conn pool]
      (with-open [zout (GZIPOutputStream. out)]
        (let [writer (t/writer zout {:type :json})]
          (t/write! writer {:type :export :format 1 :created-at (dt/now)})
          (doseq [monitor (retrieve-monitors conn)]
            (t/write! writer {:type :monitor :payload monitor}))
          (doseq [mstatus (retrieve-monitor-status conn)]
            (t/write! writer {:type :monitor-status :payload mstatus}))
          (loop [since (dt/now)]
            (let [entries (retrieve-monitor-entries conn {:since since})]
              (when (seq entries)
                (t/write! writer {:type :monitor-entries-chunk :payload entries})
                (recur (:created-at (last entries)))))))))))

(s/def :internal.rpc.request-export/days
  (s/& ::us/integer #(>= 90 %)))

(s/def ::request-export
  (s/keys :opt-un [::profile-id
                   :internal.rpc.request-export/days]))

(defn- generate-export-body
  [cfg params]
  (reify rp/StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (try
        (generate-export! cfg (assoc params :out out))
        (catch org.eclipse.jetty.io.EofException _)
        (catch Exception e
          (log/errorf e "Exception on export"))))))

(sv/defmethod ::request-export
  [cfg params]
  (with-meta {}
    {:transform-response
     (fn [_ _]
       {:status 200
        :headers {"content-type" "text/plain"
                  "content-disposition" "attachment; filename=\"export.data\""}
        :body (generate-export-body cfg params)})}))


;; --- Request Import

(defn process-import!
  [conn {:keys [profile-id file]}]
  (letfn [(process-monitor [state monitor]
            (let [id (uuid/next)]
              (db/insert! conn :monitor
                          (assoc monitor
                                 :id id
                                 :params (db/tjson (:params monitor))
                                 :owner-id profile-id
                                 :tags (into-array String (:tags monitor))
                                 :status "imported"))
              (update state :monitors assoc (:id monitor) id)))

          (process-monitor-status [state mstatus]
            (let [monitor-id (get-in state [:monitors (:monitor-id mstatus)])]
              (db/insert! conn :monitor-status
                          (assoc mstatus
                                 :cause (db/tjson (:cause mstatus))
                                 :monitor-id monitor-id
                                 :id (uuid/next)))
              state))

          (process-monitor-entries-chunk [state items]
            (let [fields [:monitor-id :created-at :reason :latency :status]
                  index  (:monitors state)]
              (db/insert-multi! conn :monitor-entry
                                fields
                                (->> items
                                     (map (fn [row]
                                            (let [monitor-id (get index (:monitor-id row))]
                                              (assoc row :monitor-id monitor-id))))
                                     (map (apply juxt fields))))
              state))

          (handle-monitor-status [id]
            (db/insert! conn :monitor-schedule {:monitor-id id})
            (change-monitor-status! conn id "imported")
            (change-monitor-status! conn id "paused"))

          (read-chunk! [reader]
            (try
              (t/read! reader)
              (catch java.lang.RuntimeException e
                (let [cause (ex-cause e)]
                  (when-not (instance? java.io.EOFException cause)
                    (throw cause))))))

          (handle-import [file]
            (with-open [in  (io/input-stream (:tempfile file))
                        zin (GZIPInputStream. in)]
              (let [reader (t/reader zin)]
                (loop [state {}]
                  (let [item (read-chunk! reader)]
                    (if (nil? item)
                      state
                      (recur (case (:type item)
                               :monitor (process-monitor state (:payload item))
                               :monitor-status (process-monitor-status state (:payload item))
                               :monitor-entries-chunk (process-monitor-entries-chunk state (:payload item))
                               state))))))))]

    (let [state (handle-import file)]
      (run! handle-monitor-status (vals (:monitors state))))))

(s/def :internal.http.upload/filename ::us/string)
(s/def :internal.http.upload/size ::us/integer)
(s/def :internal.http.upload/content-type ::us/string)
(s/def :internal.http.upload/tempfile #(instance? java.io.File %))

(s/def :internal.http/upload
  (s/keys :req-un [:internal.http.upload/filename
                   :internal.http.upload/size
                   :internal.http.upload/tempfile
                   :internal.http.upload/content-type]))

(s/def ::file :internal.http/upload)
(s/def ::request-import
  (s/keys :req-un [::file ::profile-id]))

(sv/defmethod ::request-import
  [{:keys [pool]} {:keys [profile-id file] :as params}]
  (db/with-atomic [conn pool]
    (process-import! conn params)
    nil))


;; --- Request Export Status History as CSV

(declare generate-status-history-csv-export)
(declare sql:monitor-status-history)

(s/def ::format ::us/keyword)
(s/def ::id ::us/uuid)

(s/def ::export-monitor-status-history
  (s/keys :req-un [::format ::id]))

(sv/defmethod ::export-monitor-status-history
  [{:keys [pool]} {:keys [id format] :as params}]
  (letfn [(generate-csv [items out]
            (csv/write-csv out [["created_at", "finished_at", "status"]])
            (doseq [row items]
              (csv/write-csv out [[(dt/instant->isoformat (:created-at row))
                                   (dt/instant->isoformat (:finished-at row))
                                   (:status row)]])))

          (generate-json [items out]
            (doseq [row items]
              (-> {:id (str (:id row))
                   :created_at  (dt/instant->isoformat (:created-at row))
                   :finished-at (dt/instant->isoformat (:finished-at row))
                   :status (:status row)
                   :cause (:cause row)}
                  (json/write out))
              (binding [*out* out]
                (print "\n"))))

          (generate-transit [items out]
            (let [writer (t/writer out)]
              (doseq [row items]
                (t/write! writer row))))

          (write-to-stream [out]
            (let [items (db/exec! pool [sql:monitor-status-history id])
                  items (map decode-status-row items)]
              (try
                (if (or (= format :csv)
                        (= format :json))
                  (with-open [out (io/writer out)]
                    (case format
                      :csv (generate-csv items out)
                      :json (generate-json items out)))
                  (generate-transit items out))
                (catch org.eclipse.jetty.io.EofException _)
                (catch Exception e
                  (log/errorf e "Exception on export")))))]

  (with-meta {}
    {:transform-response
     (fn [_ _]
       {:status 200
        :headers {"content-type" "text/plain"
                  "content-disposition" "attachment; filename=\"export.json\""
                  }
        :body (reify rp/StreamableResponseBody
                (write-body-to-stream [_ _ out]
                  (write-to-stream out)))})})))

(def ^:private
  sql:monitor-status-history
  "select e.id, e.created_at, e.finished_at, e.status, e.cause
     from monitor_status as e
    where e.monitor_id = ?
    order by e.created_at desc")
