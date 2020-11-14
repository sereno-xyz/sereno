;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.api.auth
  (:require
   [app.common.exceptions :as ex]
   [app.session :as session]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.tools.logging :as log]
   [lambdaisland.uri :as uri]))

(def gauth-uri "https://accounts.google.com/o/oauth2/v2/auth")
(def gauth-scope
  (str "email profile "
       "https://www.googleapis.com/auth/userinfo.email "
       "https://www.googleapis.com/auth/userinfo.profile "
       "openid"))

(defn- build-redirect-url
  [cfg]
  (let [public (uri/uri (:public-uri cfg))]
    (str (assoc public :path "/api/oauth/google/callback"))))

(defn- get-access-token
  [cfg code]
  (let [params {:code code
                :client_id (get-in cfg [:google :client-id])
                :client_secret (get-in cfg [:google :client-secret])
                :redirect_uri (build-redirect-url cfg)
                :grant_type "authorization_code"}
        req    {:method :post
                :headers {"content-type" "application/x-www-form-urlencoded"}
                :uri "https://oauth2.googleapis.com/token"
                :body (uri/map->query-string params)}
        send!  (:http-client cfg)
        res    (send! req)]

    (when (not= 200 (:status res))
      (ex/raise :type :internal
                :code :invalid-response-from-google
                :context {:status (:status res)
                          :body (:body res)}))

    (try
      (let [data (json/read-str (:body res))]
        (get data "access_token"))
      (catch Exception e
        (log/error "unexpected error on parsing response body from google access tooken request" e)
        nil))))

(defn- get-user-info
  [cfg token]
  (let [req   {:uri "https://openidconnect.googleapis.com/v1/userinfo"
               :headers {"Authorization" (str "Bearer " token)}
               :method :get}
        send! (:http-client cfg)
        res   (send! req)]

    (when (not= 200 (:status res))
      (ex/raise :type :internal
                :code :invalid-response-from-google
                :context {:status (:status res)
                          :body (:body res)}))
    (try
      (let [data (json/read-str (:body res))]
        ;; (clojure.pprint/pprint data)
        {:id    (str "google:" (get data "sub"))
         :email (get data "email")
         :fullname (get data "name")})
      (catch Exception e
        (log/error "unexpected error on parsing response body from google access tooken request" e)
        nil))))

(defn gauth-handler
  [{:keys [pool tokens] :as cfg} _]
  (let [claims {:iss :gauth
                :exp (dt/plus (dt/now) #app/duration "5m")}
        token  ((:create tokens) claims)
        params {:scope gauth-scope
                :access_type "offline"
                :include_granted_scopes true
                :state token
                :response_type "code"
                :redirect_uri (build-redirect-url cfg)
                :client_id (get-in cfg [:google :client-id])}
        query  (uri/map->query-string params)
        uri    (-> (uri/uri gauth-uri)
                   (assoc :query query))]
    {:status 200
     :body {:redirect-uri (str uri)}}))


(defn gauth-callback-handler
  [{:keys [pool impl tokens] :as cfg} request]
  (let [token  (get-in request [:params :state])
        _      ((:verify tokens) token {:iss :gauth})
        info   (some->> (get-in request [:params :code])
                        (get-access-token cfg)
                        (get-user-info cfg))]

    (when-not info
      (ex/raise :type :authentication
                :code :unable-to-authenticate-with-google))

    (let [profile ((:login-or-register impl) {:email (:email info)
                                              :fullname (:fullname info)
                                              :external-id (:id info)})


          uagent  (get-in request [:headers "user-agent"])
          claims  {:iss :gauth
                   :exp (dt/plus (dt/now) #app/duration "5m")
                   :profile-id (:id profile)}
          token   ((:create tokens) claims)
          uri     (-> (uri/uri (:public-uri cfg))
                      (assoc :path "/#/auth/verify-token")
                      (assoc :query (uri/map->query-string {:token token})))
          sid     (session/create! pool {:profile-id (:id profile)
                                         :user-agent uagent})]
      {:status 302
       :headers {"location" (str uri)}
       :cookies (session/cookies {:value sid})
       :body ""})))


(defn login-handler
  [{:keys [pool impl] :as cfg} request]
  (let [data    (:body-params request)
        uagent  (get-in request [:headers "user-agent"])
        profile ((:login impl) data)
        sid     (session/create! pool {:profile-id (:id profile)
                                       :user-agent uagent})]
    {:status 200
     :cookies (session/cookies {:value sid})
     :body profile}))

(defn logout-handler
  [{:keys [pool impl] :as cfg} request]
  (session/delete! pool request)
  {:status 200
   :cookies (session/cookies {:value "" :max-age -1})
   :body ""})

