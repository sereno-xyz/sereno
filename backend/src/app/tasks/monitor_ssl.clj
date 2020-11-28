;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.monitor-ssl
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
   [integrant.core :as ig])
  (:import
   java.net.URL
   java.security.cert.Certificate
   java.security.cert.X509Certificate
   javax.net.ssl.HttpsURLConnection
   javax.net.ssl.SSLContext))

(s/def ::type #{"ssl"})
(s/def :internal.monitors.ssl/uri
  (s/and ::us/uri #(str/starts-with? % "https:")))
(s/def :internal.monitors.http/params
  (s/keys :req-un [:internal.monitors.ssl/uri]))

(s/def ::monitor
  (s/keys :req-un [::type :internal.monitors.ssl/params]))

(defn- handle-exception
  [^Exception e monitor]
  (cond
    (instance? java.util.concurrent.ExecutionException e)
    (handle-exception (ex-cause e) monitor)

    (or (instance? javax.net.ssl.SSLHandshakeException e)
        (instance? sun.security.validator.ValidatorException e))
    (let [cause (ex-cause e)]
      (if (nil? cause)
        {:status "down"
         :retry true
         :cause (assoc (ex/->map e)
                       :code :ssl
                       :hint "Error on validation of ssl certificate.")}
        (handle-exception cause monitor)))

    (or (instance? java.security.cert.CertPathValidatorException e)
        (instance? java.security.cert.CertificateException e)
        (instance? sun.security.provider.certpath.SunCertPathBuilderException e)
        (instance? javax.net.ssl.SSLException e))
    {:status "down"
     :retry false
     :cause (assoc (ex/->map e)
                   :code :ssl
                   :hint "Error on validation of ssl certificate.")}

    (instance? clojure.lang.ExceptionInfo e)
    (let [data (ex-data e)]
      {:status "down"
       :retry false
       :cause (assoc (ex/->map e)
                     :code :ssl
                     :hint (ex-message e))})

    (or (instance? java.util.concurrent.TimeoutException e)
        (instance? java.net.SocketTimeoutException e))
    {:status "down"
     :retry true
     :cause (assoc (ex/->map e)
                   :code :timeout
                   :hint "Timeout reached on connecting to the host.")}

    (instance? java.net.UnknownHostException e)
    {:status "down"
     :retry true
     :cause (assoc (ex/->map e)
                   :code :dns-lookup
                   :hint "Unable to resolve the domain name to a valid ip.")}

    :else
    (do
      (log/errorf e "Unexpected exception on monitor '%s'\nparams: %s"
                  (:name monitor) (pr-str (:params monitor)))
      {:status "down"
       :retry true
       :cause (assoc (ex/->map e)
                     :code :unknown
                     :hint "Not controlled error (see details on the exception).")})))

(defn- retrieve-certificate-expiration
  [uri]
  (let [url   (URL. ^String uri)
        conn  (.openConnection url)]

    (when-not (instance? HttpsURLConnection conn)
      (ex/raise :type :invalid-arguments
                :code :invalid-url
                :message "Invalid URL, https-like url is required"))

    (.setConnectTimeout ^HttpsURLConnection conn 10000)
    (.connect ^HttpsURLConnection conn)

    (try
      (let [certs  (.getServerCertificates ^HttpsURLConnection conn)
            cert   (first certs)
            name   (.. ^X509Certificate cert getSubjectDN getName)
            expire (.getNotAfter ^X509Certificate cert)]
        (.toInstant expire))
      (finally
        (.disconnect ^HttpsURLConnection conn)))))

(defn- update-monitor
  [conn id expire]
  (db/update! conn :monitor
              {:expired-at expire}
              {:id id}))

(defn run
  [cfg {:keys [id params] :as monitor}]
  (us/assert ::monitor monitor)
  (try
    (let [expire    (retrieve-certificate-expiration (:uri params))
          remaining (dt/duration-between (dt/now) expire)
          remaining (inst-ms remaining)

          result    (cond-> {}
                      (not= expire (:expired-at monitor))
                      (assoc :update-fn #(update-monitor % id expire)))]

      (cond
        (neg? remaining)
        (assoc result
               :status "down"
               :reason "certificate-expired")

        (< remaining (inst-ms (dt/duration {:days (:alert-before params)})))
        (assoc result
               :status "warn"
               :reason "near-expiration")

        :else
        (assoc result :status "up")))
    (catch Exception e
      (handle-exception e monitor))))
