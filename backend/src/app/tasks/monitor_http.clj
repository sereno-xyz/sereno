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
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.emails :as emails]
   [app.tasks :as tasks]
   [app.util.time :as dt]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
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
    (handle-exception (ex-cause e) monitor)

    (instance? java.net.ConnectException e)
    {:status "down"
     :retry true
     :cause (assoc (ex/->map e)
                   :code :connection
                   :hint "Unable to connect to the host.")}

    (or (instance? java.nio.channels.ClosedChannelException e)
        (instance? java.io.EOFException e)
        (instance? java.io.IOException e))
    {:status "down"
     :retry true
     :cause (assoc (ex/->map e)
                   :code :connection
                   :hint "Unable to complete connection to the host.")}

    (instance? java.net.NoRouteToHostException e)
    {:status "down"
     :retry true
     :cause (assoc (ex/->map e)
                   :code :no-route
                   :hint "Unable to find a correct route to host.")}

    (instance? org.eclipse.jetty.client.HttpResponseException e)
    {:status "down"
     :retry true
     :cause (assoc (ex/->map e)
                   :code :http
                   :hint "Unable to complete http request.")}

    (or (instance? java.util.concurrent.TimeoutException e)
        (instance? java.net.SocketTimeoutException e))
    {:status "down"
     :retry true
     :cause (assoc (ex/->map e)
                   :code :timeout
                   :hint "Timeout reached on connecting to the host.")}

    (or (instance? javax.net.ssl.SSLHandshakeException e)
        (instance? java.security.cert.CertificateException e))
    {:status "down"
     :retry true
     :cause (assoc (ex/->map e)
                   :code :ssl
                   :hint "Unexpected error related to SSL certificate validation")}

    (instance? java.net.UnknownHostException e)
    {:status "down"
     :retry true
     :cause (assoc (ex/->map e)
                   :code :dns-lookup
                   :hint "Unable to resolve the domain name to a valid ip.")}

    :else
    (do
      (log/errorf e
                  (str "Unhandled exception.\n"
                       "=> monitor: " (:name monitor) "\n"
                       "=> type:    " (:type monitor) "\n"
                       "=> params: \n"
                       (with-out-str
                         (pprint (:params monitor)))))
      {:status "down"
       :retry true
       :cause (assoc (ex/->map e)
                     :code :unknown
                     :hint "Not controlled error (see details on the exception).")})))

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
             :retry false
             :cause {:code :binary-body
                     :hint "Can't parse body, binary data found."}}

            (and incl-t (not (str/includes? (:body result) incl-t)))
            {:status "down"
             :retry false
             :cause {:code :include-check
                     :hint "Include check failed."}}

            :else
            {:status "up"})

          {:status "down"
           :cause {:code :http
                   :status (:status result)
                   :hint (str/format "Status code: %s" (:status result))}}))

      (catch Exception e
        (handle-exception e monitor)))))
