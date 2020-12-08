;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.api
  (:require
   [app.api.auth :as auth]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.http :as http]
   [app.http.errors :as errors]
   [app.http.middleware :as middleware]
   [app.metrics :as mtx]
   [app.session :as session]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [reitit.ring :as rr]
   [ring.adapter.jetty9 :as jetty]
   [ring.util.response :refer [resource-response]]))

(defn- echo-handler
  [opts req]
  {:status 200
   :body {:params (:params req)
          :cookies (:cookies req)
          :headers (:headers req)}})

(defn- default-handler
  [req]
  (ex/raise :type :not-found))

(defn index-handler
  [request]
  (resource-response "index.html" {:root "public"}))

(defn rpc-handler
  [impl request]
  (let [type    (keyword (get-in request [:path-params :cmd]))
        data    (merge (:params request)
                       (:body-params request)
                       (:uploads request))

        data    (if (:profile-id request)
                  (assoc data :profile-id (:profile-id request))
                  (dissoc data :profile-id))

        result  ((get impl type default-handler) data)
        mdata   (meta result)]

    (cond->> {:status 200 :body result}
      (fn? (:transform-response mdata)) ((:transform-response mdata) request))))

(defn- router
  [{:keys [auth impl webhooks] :as cfg}]
  (let [login-handler  (partial auth/login-handler cfg)
        logout-handler (partial auth/logout-handler cfg)
        gauth-handler  (partial auth/gauth-handler cfg)
        gauth-callback-handler (partial auth/gauth-callback-handler cfg)]

    (rr/router
     [["/metrics" {:handler (partial mtx/handler cfg)
                   :method :get}]
      ["/webhook"
       ["/awssns" {:handler (:awssns webhooks)
                   :method :post}]
       ["/telegram" {:handler (:telegram webhooks)
                     :method :post}]]

      ["/api"
       ["/login" {:handler login-handler
                  :method :post}]
       ["/logout" {:handler logout-handler
                   :method :post}]

       ["/oauth"
        ["/google" {:handler gauth-handler
                    :method :post}]
        ["/google/callback" {:handler gauth-callback-handler
                             :method :get}]]


       ["/rpc/:cmd" {:handler #(rpc-handler impl %)}]
       #_["/echo" {:get #(echo-handler cfg %)
                   :post #(echo-handler cfg %)}]]
      ["/" {:get index-handler}]])))


(s/def ::impl map?)
(s/def ::http-client fn?)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::impl ::db/pool ::http-client]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (rr/ring-handler
   (router cfg)

   (rr/routes
    (rr/create-resource-handler {:path "/"})
    (rr/create-default-handler))
   #_(constantly {:status 404, :body ""})
   {:middleware [[middleware/format-response-body]
                 [middleware/errors errors/handle]
                 [middleware/parse-request-body]
                 [middleware/params]
                 [middleware/multipart-params]
                 [middleware/keyword-params]
                 [middleware/cookies]
                 [session/middleware cfg]]}))

(defn- wrap-impl
  [f mdata cfg]
  (let [mreg  (:metrics-registry cfg)
        mobj  (mtx/create
               {:name (-> (str "api_" (::sv/name mdata) "_response_millis")
                          (str/replace "-" "_"))
                :registry mreg
                :type :summary
                :help (str/format "Service '%s' response time in milliseconds." (::sv/name mdata))})
        f     (mtx/wrap-summary f mobj)
        spec  (or (::sv/spec mdata) (s/spec any?))]

    (log/debugf "Registering '%s' command to rpc service." (::sv/name mdata))
    (fn [params]
      (when (and (:auth mdata true) (not (uuid? (:profile-id params))))
        (ex/raise :type :not-authenticated))
      (f cfg (us/conform spec params)))))

(defmethod ig/pre-init-spec :app.api/impl [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key :app.api/impl
  [_ cfg]
  (->> (sv/scan-ns 'app.api.profile
                   'app.api.monitors
                   'app.api.contacts
                   'app.api.token
                   'app.api.export)
       (map (fn [vfn]
              (let [mdata (meta vfn)]
                [(keyword (::sv/name mdata))
                 (wrap-impl (deref vfn) mdata cfg)])))
       (into {})))
