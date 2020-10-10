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
   [app.api-auth :as auth]
   [app.api-impl :as impl]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.http :as http]
   [app.http.errors :as errors]
   [app.http.middleware :as middleware]
   [app.session :as session]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [reitit.ring :as rr]
   [ring.util.response :refer [resource-response]]
   [ring.adapter.jetty9 :as jetty]))

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
  [impl req]
  (let [type    (keyword (get-in req [:path-params :cmd]))
        data    (merge (:params req)
                       (:body-params req)
                       (:uploads req))

        data    (if (:profile-id req)
                  (assoc data :profile-id (:profile-id req))
                  (dissoc data :profile-id))
        handler (get impl type default-handler)
        resp    (handler (with-meta data {:req req}))]
    (if (http/response? resp)
      resp
      {:status 200
       :body resp})))

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
       ["/echo" {:get #(echo-handler cfg %)
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
  [sname f cfg]
  (assert (var? f) "`f` should be a var")
  (let [mreg  (:metrics-registry cfg)
        mobj  (mtx/create
               {:name (-> (str "api_" sname "_response_millis")
                          (str/replace "-" "_"))
                :registry mreg
                :type :summary
                :help (str/format "Service '%s' response time in milliseconds." sname)})
        mdata (meta f)
        f     (mtx/wrap-summary (deref f) mobj)
        spec  (or (:spec mdata)
                  (s/spec any?))]
    (log/debug (str/format "Registering '%s' command to rpc service."
                           (str (:name mdata))))
    (fn [params]
      (when (and (:auth mdata true)
                 (not (uuid? (:profile-id params))))
        (ex/raise :type :not-authenticated))
      (->> (us/conform spec params)
           (f cfg)))))

(defmethod ig/pre-init-spec :app.api/impl [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key :app.api/impl
  [_ cfg]
  (reduce-kv (fn [res sname vfn]
               (cond-> res
                 (:spec (meta vfn))
                 (assoc (keyword sname)
                        (wrap-impl sname vfn cfg))))
             {}
             (ns-publics (find-ns 'app.api-impl))))
