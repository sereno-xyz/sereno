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
   [app.util.async :as aa]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
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
   org.eclipse.jetty.http.HttpMethod
   org.eclipse.jetty.server.handler.ErrorHandler
   org.eclipse.jetty.util.SocketAddressResolver$Sync
   org.eclipse.jetty.util.ssl.SslContextFactory$Client))

(defmethod ig/init-key ::server
  [_ {:keys [router ws port] :as opts}]
  (log/info "Starting http server.")
  (let [options {:port (or port 4460)
                 :h2c? true
                 :join? false
                 :allow-null-path-info true
                 :websockets ws}

        server  (jetty/run-jetty router options)
        handler (doto (ErrorHandler.)
                  (.setShowStacks true)
                  (.setServer server))]

    (.setErrorHandler server handler)
    server))

(defmethod ig/halt-key! ::server
  [_ server]
  (log/info "Stoping http server." server)
  (.stop server))


(defmethod ig/pre-init-spec ::client [_]
  (s/keys :req-un [::aa/executor]))

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
;; --- Http Main Handler (Router)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare router)

(s/def ::auth map?)
(s/def ::rpc map?)

(defmethod ig/pre-init-spec ::router [_]
  (s/keys :req-un [::rpc ::auth]))

(defmethod ig/init-key ::router
  [_ cfg]
  (rr/ring-handler
   (router cfg)
   (rr/routes
    (rr/create-resource-handler {:path "/"})
    (rr/create-default-handler))
   {:middleware [[middleware/format-response-body]
                 [middleware/errors errors/handle]
                 [middleware/parse-request-body]
                 [middleware/params]
                 [middleware/multipart-params]
                 [middleware/keyword-params]
                 [middleware/cookies]
                 [session/middleware cfg]]}))

(defn- index-handler
  [request]
  (resource-response "index.html" {:root "public"}))


(defn- router
  [{:keys [auth rpc webhooks metrics] :as cfg}]
  (rr/router
   [["/metrics" {:get (:handler metrics)}]
    ["/webhook"
     ["/awssns" {:post (:awssns webhooks)}]
     ["/telegram" {:post (:telegram webhooks)}]]

    ["/hc/:id" {:get (:healthcheck webhooks)}]

    ["/auth"
     ["/login" {:post (:login auth)}]
     ["/logout" {:post (:logout auth)}]
     ["/google" {:post (:gauth auth)}]
     ["/google/callback" {:get (:gauth-callback auth)}]]

    ["/rpc/:cmd" {:get (:handler rpc)
                  :post (:handler rpc)}]

    ["/" {:get index-handler}]]))


