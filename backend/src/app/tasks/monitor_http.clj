;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.monitor-http
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

(defn- handle-exception
  [^Exception e monitor]
  (cond
    (instance? java.util.concurrent.ExecutionException e)
    (handle-exception (.getCause e) monitor)

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


(defn run
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
        (handle-exception e monitor)))))
