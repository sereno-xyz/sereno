;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.monitor
  (:require
   [app.common.spec :as us]
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.emails :as emails]
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defmulti run-monitor (fn [cfg monitor] (:type monitor)))

(s/def ::type #{"http"})
(s/def :internal.monitors.http/uri ::us/uri)
(s/def :internal.monitors.http/method ::us/keyword)
(s/def :internal.monitors.http/should-include ::us/string)
(s/def :internal.monitors.http/headers
  (s/map-of ::us/string ::us/string))

(s/def :internal.monitors.http/params
  (s/keys :req-un [:internal.monitors.http/uri
                   :internal.monitors.http/method]
          :opt-un [:internal.monitors.http/headers
                   :internal.monitors.http/should-include]))

(s/def ::http-monitor
  (s/keys :req-un [::type :internal.monitors.http/params]))

(defn handle-http-exception
  [^Exception e monitor]
  (cond
    (instance? java.util.concurrent.ExecutionException e)
    (handle-http-exception (.getCause e) monitor)

    (instance? java.net.ConnectException e)
    {:status "down"
     :retry true
     :reason "connection-refused"}

    (instance? java.util.concurrent.TimeoutException e)
    {:status "down"
     :retry true
     :reason "timeout"}

    (instance? java.net.NoRouteToHostException e)
    {:status "down"
     :retry true
     :reason "no-route-to-host"}

    (instance? java.nio.channels.ClosedChannelException e)
    {:status "down"
     :retry true
     :reason "closed-connection"}

    (instance? java.io.IOException e)
    {:status "down"
     :retry true
     :reason "io"}

    (instance? java.io.EOFException e)
    {:status "down"
     :retry true
     :reason "eof"}

    (instance? java.net.SocketTimeoutException e)
    {:status "down"
     :retry true
     :reason "connect-timeout"}

    (or (instance? javax.net.ssl.SSLHandshakeException e)
        (instance? java.security.cert.CertificateException e))
    {:status "down"
     :retry true
     :reason "ssl-validation"}

    (instance? java.net.UnknownHostException e)
    {:status "down"
     :retry true
     :reason "dns-lookup"}

    :else
    (do
      (log/errorf e "Unexpected exception on monitor '%s'\nparams: %s"
                  (:name monitor) (pr-str (:params monitor)))
      {:status "down"
       :retry true
       :reason (str "unknown " (pr-str (ex-message e)))})))


(defmethod run-monitor "http"
  [cfg {:keys [params] :as monitor}]
  (us/assert ::http-monitor monitor)
  (let [send!   (:http-client cfg)
        request {:uri (:uri params)
                 :method (:method params)
                 :headers (:headers params {})
                 :timeout 5000}

        incl-t  (as-> params $
                  (:should-include $)
                  (str/trim $)
                  (when-not (str/empty? $) $))]
    (try
      (let [result (send! request)]
        (if (< 199 (:status result) 206)
          (cond
            (and incl-t (not (string? (:body result))))
            {:status "down"
             :reason "binary-body"}

            (and incl-t (not (str/includes? (:body result) incl-t)))
            {:status "down"
             :reason "include-check"}

            :else
            {:status "up"})
          {:status "down"
           :reason (str "status-code " (pr-str (:status result)))}))

      (catch Exception e
        (handle-http-exception e monitor)))))

(s/def ::monitor
  (s/keys :req-un [::type ::params ::name]
          :opt-un [::id]))

(defn run-monitor!
  [cfg monitor]
  (us/assert ::monitor monitor)
  (let [start  (. System (nanoTime))
        result (run-monitor cfg monitor)
        end    (. System (nanoTime))]
    (assoc result :latency (/ (double (- end start)) 1000000.0))))

(defn- decode-row
  [{:keys [params tags] :as row}]
  (cond-> row
    (db/pgobject? params)
    (assoc :params (db/decode-transit-pgobject params))

    (db/pgarray? tags)
    (assoc :tags (set (db/pgarray->array tags)))))

(defn- retrieve-monitor
  [conn id]
  (-> (db/get-by-id conn :monitor id)
      (decode-row)))

(defn- update-monitor-status!
  [conn id result]
  (db/exec! conn ["update monitor set monitored_at=now(), status=? where id=?" (:status result) id]))

(defn- insert-monitor-entry!
  [conn id result]
  (db/exec! conn ["insert into monitor_entry (monitor_id, latency, status, created_at, reason)
                   values (?, ?, ?, now(), ?)" id (:latency result) (:status result) (:reason result)]))

(defn- insert-monitor-status-change!
  [conn id result]
  (db/exec! conn ["update monitor_status set finished_at=now()
                    where id=(select id from monitor_status
                               where monitor_id=?
                                 and finished_at is null
                               order by created_at desc
                               limit 1)" id])
  (db/exec! conn ["insert into monitor_status (monitor_id, status, created_at, reason)
                   values (?, ?, now(), ?)" id (:status result) (:reason result)]))

(def sql:monitor-contacts
  "select c.*, mcr.id as subscription_id
     from monitor_contact_rel as mcr
    inner join contact as c on (c.id = mcr.contact_id)
    where mcr.monitor_id = ?
      and c.is_paused is false
      and c.is_disabled is false")

(defn- notify-contacts!
  [conn monitor result]
  (let [contacts (->> (db/exec! conn [sql:monitor-contacts (:id monitor)])
                      (map decode-row))]
    ;; TODO: enable batch task submit
    (doseq [contact contacts]
      (tasks/submit! conn {:name "notify"
                           :props {:monitor monitor :result result :contact contact}
                           :priority 200}))))

(defn- status-changed?
  [monitor result]
  (not= (:status monitor) (:status result)))

(s/def ::http-client fn?)

(defmethod ig/pre-init-spec ::handler
  [_]
  (s/keys :req-un [::db/pool ::http-client]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [tdata]
    (let [{:keys [id]} (:props tdata)]
      (db/with-atomic [conn pool]
        (when-let [monitor (retrieve-monitor conn id)]
          (when (not= "paused" (:status monitor))
            (let [result (run-monitor! cfg monitor)]
              (if (and (:retry result)
                       (< (:retry-num tdata) (:max-retries tdata)))
                (ex/raise :type :app.worker/retry
                          :hint "monitor does not passes all checks")
                (do
                  (when (status-changed? monitor result)
                    (insert-monitor-status-change! conn id result)
                    (when (not= "started" (:status monitor))
                      (notify-contacts! conn monitor result)))

                  (update-monitor-status! conn id result)
                  (insert-monitor-entry! conn id result))))))))))

