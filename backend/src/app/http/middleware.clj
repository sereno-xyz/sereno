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
   [app.config :as cfg]
   [app.util.transit :as t]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.resource :refer [wrap-resource]]))

(defn- wrap-parse-request-body
  [handler]
  (letfn [(parse-transit [body]
            (try
              (let [reader (t/reader body)]
                (t/read! reader))
              (catch Exception e
                (ex/raise :type :parse
                          :message "Unable to parse transit from request body."
                          :cause e))))
          (parse-json [body]
            (try
              (let [reader (io/reader body)]
                (json/read reader))
              (catch Exception e
                (ex/raise :type :parse
                          :message "Unable to parse json from request body."
                          :cause e))))]

    (fn [{:keys [headers body request-method] :as request}]
      (handler
       (cond-> request
         (and (= "application/transit+json" (get headers "content-type"))
              (not= request-method :get))
         (assoc :body-params (parse-transit body))

         (and (= "application/json" (get headers "content-type"))
              (not= request-method :get))
         (assoc :body-params (parse-json body)))))))

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
      (catch Throwable e
        (on-error e request)))))

(def errors
  {:name ::errors
   :compile (constantly wrap-errors)})

;; (def metrics
;;   {:name ::metrics
;;    :wrap (fn [handler]
;;            (mtx/wrap-counter handler {:id "http__requests_counter"
;;                                       :help "Absolute http requests counter."}))})

(def cookies
  {:name ::cookies
   :compile (constantly wrap-cookies)})

(def params
  {:name ::params
   :compile (constantly wrap-params)})

(def multipart-params
  {:name ::multipart-params
   :compile (constantly wrap-multipart-params)})

(def keyword-params
  {:name ::keyword-params
   :compile (constantly wrap-keyword-params)})
