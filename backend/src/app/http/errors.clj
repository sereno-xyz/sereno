;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.http.errors
  "A errors handling for the http server."
  (:require
   [app.common.exceptions :as ex]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [expound.alpha :as expound]
   [io.aviso.exception :as e]))

(defmulti handle-exception
  (fn [err & rest]
    (:type (ex-data err))))

(defmethod handle-exception :validation
  [error request]
  (let [header (get-in request [:headers "accept"])
        edata  (ex-data error)]
    (cond
      (= :spec-validation (:code edata))
      (if (str/starts-with? header "text/html")
        {:status 400
         :headers {"content-type" "text/html"}
         :body (str "<pre style='font-size:14px'>"
                    (with-out-str
                      (expound/printer (:data edata)))
                    "</pre>\n")}
        ;; Dissoc the error data because it is not serializable.
        {:status 400
         :body (dissoc edata)})

      :else
      {:status 400
       :body edata})))

(defn get-context-string
  [request edata]
  (str "=| uri:          " (pr-str (:uri request)) "\n"
       "=| method:       " (pr-str (:request-method request)) "\n"
       "=| path-params:  " (pr-str (:path-params request)) "\n"
       "=| query-params: " (pr-str (:query-params request)) "\n"

       (when-let [bparams (:body-params request)]
         (str "=| body-params:  " (pr-str bparams) "\n"))

       (when (map? edata)
         (str "=| ex-data:      " (pr-str edata) "\n"))

       "\n"))

(defmethod handle-exception :assertion
  [error request]
  (let [edata (ex-data error)]
    (log/errorf error
                (str "Assertion error\n"
                     (get-context-string request edata)
                     (with-out-str (expound/printer (:data edata)))))
    {:status 500
     :body edata}))

(defmethod handle-exception :not-authenticated
  [err req]
  {:status 401
   :body ""})

(defmethod handle-exception :not-found
  [error request]
  {:status 404
   :body (ex-data error)})

(defmethod handle-exception :service-error
  [err req]
  (handle-exception (.getCause ^Throwable err) req))

(defmethod handle-exception :default
  [error request]
  (let [edata (ex-data error)]
    (log/errorf error
                (str "Internal Error\n"
                     (get-context-string request edata)))

    {:status 500
     :body (dissoc edata :data)}))

(defn handle
  [error req]
  (if (or (instance? java.util.concurrent.CompletionException error)
          (instance? java.util.concurrent.ExecutionException error))
    (handle-exception (.getCause ^Throwable error) req)
    (handle-exception error req)))
