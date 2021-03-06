;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns user
  (:require
   [app.config :as cfg]
   [app.init :as init]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [app.common.exceptions :as ex]
   [clj-kondo.core :as kondo]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.repl :refer :all]
   [clojure.spec.alpha :as s]
   [clojure.test :as test]
   [clojure.tools.namespace.repl :as repl]
   [clojure.walk :refer [macroexpand-all]]
   [criterium.core :refer [quick-bench bench with-progress-reporting]]
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
   javax.net.ssl.X509TrustManager))

(repl/disable-reload! (find-ns 'integrant.core))

(defonce system nil)

;; --- Benchmarking Tools

(defmacro run-quick-bench
  [& exprs]
  `(with-progress-reporting (quick-bench (do ~@exprs) :verbose)))

(defmacro run-quick-bench'
  [& exprs]
  `(quick-bench (do ~@exprs)))

(defmacro run-bench
  [& exprs]
  `(with-progress-reporting (bench (do ~@exprs) :verbose)))

(defmacro run-bench'
  [& exprs]
  `(bench (do ~@exprs)))


;; --- Development Stuff

(defn- start
  []
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             (-> init/system-config
                                 (ig/prep)
                                 (ig/init))))
  :started)

(defn- stop
  []
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             nil))
  :stoped)

(defn restart
  []
  (stop)
  (repl/refresh :after 'user/start))

(defn restart-all
  []
  (stop)
  (repl/refresh-all :after 'user/start))
