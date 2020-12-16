;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.http.middleware
  (:require
   [app.common.exceptions :as ex]
   [app.common.data :as d]
   [app.config :as cfg]
   [app.util.transit :as t]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [ring.util.codec :as codec]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.resource :refer [wrap-resource]]))

(defn- wrap-parse-request-body
  [handler]
  (letfn [(parse-transit [body]
            (let [reader (t/reader body)]
              (t/read! reader)))
          (parse-json [body]
            (let [reader (io/reader body)]
              (json/read reader)))

          (parse [type body]
            (try
              (case type
                :json (parse-json body)
                :transit (parse-transit body))
              (catch Exception e
                (let [type (if (:debug cfg/config) :json-verbose :json)
                      data {:type :parse
                            :hint "Unable to parse request body"
                            :message (ex-message e)}]
                  {:status 400
                   :body (t/encode-str data {:type type})}))))]
    (fn [{:keys [headers body request-method] :as request}]
      (let [ctype (get headers "content-type")]
        (handler
         (case ctype
           "application/transit+json"
           (assoc request :body-params (parse :transit body))

           "application/json"
           (assoc request :body-params (parse :json body))

           request))))))

(def parse-request-body
  {:name ::parse-request-body
   :compile (constantly wrap-parse-request-body)})

(defn- impl-format-response-body
  [response type]
  (let [body (:body response)]
    (cond
      (coll? body)
      (-> response
          (assoc :body (t/encode body {:type type}))
          (update :headers assoc
                  "content-type"
                  "application/transit+json"))

      (nil? body)
      (assoc response :status 204 :body "")

      :else
      response)))

(defn- wrap-format-response-body
  [handler]
  (let [type (if (:debug cfg/config) :json-verbose :json)]
    (fn [request]
      (let [response (handler request)]
        (cond-> response
          (map? response) (impl-format-response-body type))))))

(def format-response-body
  {:name ::format-response-body
   :compile (constantly wrap-format-response-body)})

(defn- wrap-errors
  [handler on-error]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (on-error e request)))))

(def errors
  {:name ::errors
   :compile (constantly wrap-errors)})

(def cookies
  {:name ::cookies
   :compile (constantly wrap-cookies)})

(defn- wrap-query-params
  [handler]
  (fn [{:keys [query-string] :as request}]
    (let [params (some-> query-string
                         (codec/form-decode "UTF-8")
                         (d/keywordize))
          params (if (map? params) params {})]
      (handler (assoc request :query-params params)))))

(def query-params
  {:name ::params
   :compile (constantly wrap-query-params)})

(def multipart-params
  {:name ::multipart-params
   :compile (constantly wrap-multipart-params)})
