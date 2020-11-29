;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.repo
  (:require
   [beicon.core :as rx]
   [app.config :as cfg]
   [app.util.http-api :as http]))

(defn- handle-response
  [response]
  (cond
    (http/success? response)
    (rx/of (:body response))

    (= (:status response) 400)
    (rx/throw (:body response))

    (= (:status response) 401)
    (rx/throw {:type :authentication
               :code :not-authenticated})

    (= (:status response) 403)
    (rx/throw {:type :authorization
               :code :not-authorized})

    (= (:status response) 404)
    (rx/throw {:type :not-found :code :object-not-found})

    :else
    (rx/throw {:type :internal-error
               :status (:status response)
               :body (:body response)})))


(defmulti request (fn [id params opts] id))

(defmethod request :login
  [id params opts]
  (let [uri (str cfg/public-uri "/api/login")]
    (->> (http/send! {:method :post :uri uri :body params})
         (rx/mapcat handle-response))))

(defmethod request :logout
  [id params opts]
  (let [uri (str cfg/public-uri "/api/logout")]
    (->> (http/send! {:method :post :uri uri :body params})
         (rx/mapcat handle-response))))

(defmethod request :gauth
  [id params opts]
  (let [uri (str cfg/public-uri "/api/oauth/google")]
    (->> (http/send! {:method :post :uri uri :body params})
         (rx/mapcat handle-response))))

(defmethod request :request-import
  [id params]
  (let [uri  (str cfg/public-uri "/api/rpc/" (name id))
        form (js/FormData.)]
    (run! (fn [[key val]]
            (.append form (name key) val))
          (seq params))
    (->> (http/send! {:method :post :uri uri :body form})
         (rx/mapcat handle-response))))

(defmethod request :default
  [id params opts]
  (let [uri (str cfg/public-uri "/api/rpc/" (name id))]
    (if (:query opts)
      (->> (http/send! {:method :get :uri uri :query params})
           (rx/mapcat handle-response))
      (->> (http/send! {:method :post :uri uri :body params})
           (rx/mapcat handle-response)))))

(defn req!
  ([id] (request id {} {}))
  ([id params] (request id params {}))
  ([id params opts] (request id params opts)))

(defn qry!
  ([id] (request id {} {:query true}))
  ([id params] (request id params {:query true}))
  ([id params opts] (request id params (assoc opts :query true))))

