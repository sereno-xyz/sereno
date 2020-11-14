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
   java.security.cert.CertificateException
   java.security.cert.X509Certificate
   java.util.Date
   javax.net.ssl.HttpsURLConnection
   javax.net.ssl.SSLContext
   javax.net.ssl.TrustManager
   javax.net.ssl.X509TrustManager
   java.security.cert.CertificateException))

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
    (handle-exception (.getCause e) monitor)

    (instance? java.security.cert.CertificateException e)
    {:status "down"
     :retry true
     :reason (ex-message e)}

    :else
    (do
      (log/errorf e "Unexpected exception on monitor '%s'\nparams: %s"
                  (:name monitor) (pr-str (:params monitor)))
      {:status "down"
       :retry true
       :reason (ex-message e)})))

(defn- retrieve-certificate-expiration
  [uri]
  (let [url   (URL. ^String uri)
        conn  (.openConnection url)]

    (when-not (instance? HttpsURLConnection conn)
      (ex/raise :type :invalid-arguments
                :code :not-https-uri-provided))

    (.setConnectTimeout ^HttpsURLConnection conn 10000)
    (.connect ^HttpsURLConnection conn)

    (try
      (let [certs (.getServerCertificates ^HttpsURLConnection conn)]
        (doseq [^X509Certificate cert (seq certs)]
          (let [name   (.. cert getSubjectDN getName)
                expire (.getNotAfter cert)]
            (.toInstant expire))))
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

        (< remaining (inst-ms (dt/duration {:days 7})))
        (assoc result
               :status "warn"
               :reason "near-expiration")

        :else
        (assoc result :status "up"))
        {:status "up"})
    (catch Exception e
      (handle-exception e monitor))))