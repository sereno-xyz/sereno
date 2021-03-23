;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.http
  (:require
   [app.http.auth :as auth]
   [app.http.errors :as errors]
   [app.http.middleware :as middleware]
   [app.http.session :as session]
   [app.metrics :as mtx]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [app.util.log4j :refer [update-thread-context!]]
   [integrant.core :as ig]
   [reitit.ring :as rr]
   [ring.adapter.jetty9 :as jetty]
   [ring.core.protocols :as rp]
   [ring.util.response :refer [resource-response]])
  (:import
   java.util.concurrent.TimeUnit
   org.eclipse.jetty.client.HttpClient
   org.eclipse.jetty.client.api.ContentResponse
   org.eclipse.jetty.client.api.Request
   org.eclipse.jetty.client.util.StringContentProvider
   org.eclipse.jetty.server.Server
   org.eclipse.jetty.http.HttpMethod
   org.eclipse.jetty.server.handler.ErrorHandler
   org.eclipse.jetty.server.handler.StatisticsHandler
   org.eclipse.jetty.util.SocketAddressResolver$Sync
   org.eclipse.jetty.util.ssl.SslContextFactory$Client))


(defmethod ig/init-key ::server
  [_ {:keys [handler ws port name metrics] :as opts}]
  (log/infof "starting '%s' server on port %s." name port)
  (let [pre-start (fn [^Server server]
                    (let [handler (doto (ErrorHandler.)
                                    (.setShowStacks true)
                                    (.setServer server))]
                      (.setErrorHandler server ^ErrorHandler handler)
                      (when metrics
                        (let [stats (new StatisticsHandler)]
                          (.setHandler ^StatisticsHandler stats (.getHandler server))
                          (.setHandler server stats)
                          (mtx/instrument-jetty! (:registry metrics) stats)))))

        options   (merge
                   {:port port
                    :h2c? true
                    :join? false
                    :allow-null-path-info true
                    :configurator pre-start}
                   (when (seq ws)
                     {:websockets ws}))

        server    (jetty/run-jetty handler options)]
    (assoc opts :server server)))

(defmethod ig/halt-key! ::server
  [_ {:keys [server name port] :as opts}]
  (log/infof "stoping '%s' server on port %s." name port)
  (jetty/stop-server server))

(defmethod ig/pre-init-spec ::client [_]
  (s/keys :req-un [::wrk/executor]))

(defmethod ig/init-key ::client
  [_ {:keys [executor] :as opts}]
  (let [sslctx (SslContextFactory$Client.)
        client (HttpClient. sslctx)]
    (.setFollowRedirects client true)
    (.setExecutor client executor)
    (.setName client "default")
    (.setTCPNoDelay client true)
    (.setSocketAddressResolver client (SocketAddressResolver$Sync.))
    (.setStopTimeout client 500)
    (.start client)
    (with-meta
      (fn send!
        ([request] (send! request {}))
        ([{:keys [uri headers timeout method body]
           :or {timeout 30000}
           :as request} opts]
         (let [^Request request (.newRequest client ^String uri)]
           (.timeout request timeout TimeUnit/MILLISECONDS)
           (.method request (case method
                              :get HttpMethod/GET
                              :post HttpMethod/POST
                              :head HttpMethod/HEAD))
           (doseq [[k v] headers]
             (.header request ^String k ^String v))

           (when body
             (.content request (StringContentProvider. body)))

           (let [response (.send request)]
             {:status (.getStatus response)
              :body (.getContentAsString response)}))))
      {::instance client})))


(defmethod ig/halt-key! ::client
  [_ instance]
  (let [mdata (meta instance)]
    (.stop ^HttpClient (::instance mdata))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Http Main Handler (Router)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare router)

(s/def ::session map?)
(s/def ::oauth map?)
(s/def ::rpc map?)

(defmethod ig/pre-init-spec ::router [_]
  (s/keys :req-un [::rpc ::oauth ::session]))

(defmethod ig/init-key ::router
  [_ cfg]
  (let [handler (rr/ring-handler
                 (router cfg)
                 (rr/routes
                  (rr/create-resource-handler {:path "/"})
                  (rr/create-default-handler))
                 {:middleware [middleware/server-timing]})]
    (fn [request]
      (try
        (handler request)
        (catch Throwable e
          (try
            (let [cdata (errors/get-error-context request e)]
              (update-thread-context! cdata)
              (log/errorf e "unhandled exception: %s (id: %s)" (ex-message e) (str (:id cdata)))
              {:status 500
               :body "internal server error"})
            (catch Throwable e
              (log/errorf e "unhandled exception: %s" (ex-message e))
              {:status 500
               :body "internal server error"})))))))

(defn- index-handler
  [request]
  (resource-response "index.html" {:root "public"}))

(defn- router
  [{:keys [auth rpc webhooks metrics session oauth] :as cfg}]
  (rr/router
   [["" {:middleware [[middleware/etag]
                      [middleware/format-response-body]
                      [middleware/params]
                      [middleware/multipart-params]
                      [middleware/keyword-params]
                      [middleware/parse-request-body]
                      [middleware/errors errors/handle]
                      [middleware/cookies]]}

     ["/metrics" {:get (:handler metrics)}]

     ["/webhook"
      ["/awssns" {:post (:awssns webhooks)}]
      ["/telegram" {:post (:telegram webhooks)}]]

     ["/hc/:monitor-id"
      {:get  (:healthcheck webhooks)
       :head (:healthcheck webhooks)
       :post (:healthcheck webhooks)}]

     ["/hc/:monitor-id/:label"
      {:get  (:healthcheck webhooks)
       :post (:healthcheck webhooks)}]

     ["/oauth"
      ["/google" {:post (get-in oauth [:google :handler])}]
      ["/google/callback" {:get (get-in oauth [:google :callback-handler])}]

      ["/gitlab" {:post (get-in oauth [:gitlab :handler])}]
      ["/gitlab/callback" {:get (get-in oauth [:gitlab :callback-handler])}]

      ["/github" {:post (get-in oauth [:github :handler])}]
      ["/github/callback" {:get (get-in oauth [:github :callback-handler])}]]

     ["/rpc/:cmd" {:middleware [(:middleware session)]}
      {:get (:handler rpc)
       :post (:handler rpc)}]

     ["/" {:get index-handler}]]]))
