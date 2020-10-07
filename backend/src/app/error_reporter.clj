;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.error-reporter
  "A mattermost integration for error reporting."
  (:require
   [promesa.exec :as px]
   [cuerdas.core :as str]
   [clojure.data.json :as json]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [app.config :as cfg]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [clojure.tools.logging :as log]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.util.async :as aa]
   [app.util.emails :as emails]
   [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Email Error Reporting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare send-notification!)
(defonce queue-fn identity)

(s/def ::http-client fn?)
(s/def ::uri (s/nilable ::us/uri))

(defmethod ig/pre-init-spec ::instance [_]
  (s/keys :req-un [::aa/executor ::uri ::http-client]))

(defmethod ig/init-key ::instance
  [_ {:keys [executor uri] :as cfg}]
  (let [out (a/chan (a/sliding-buffer 64))]
    (log/info "Intializing error reporter.")
    (if uri
      (do
        (alter-var-root #'queue-fn (constantly (fn [x] (a/>!! out x))))
        (a/go-loop []
          (let [val (a/<! out)]
            (if (nil? val)
              (log/info "Closing error reporting loop.")
              (do
                (px/run! executor #(send-notification! cfg val))
                (recur))))))
      (log/info "No webhook uri is provided (error reporting becomes noop)."))
    out))

(defmethod ig/halt-key! ::instance
  [_ out]
  (alter-var-root #'queue-fn (constantly identity))
  (a/close! out))

;; (defn- exception->string
;;   [^Throwable err]
;;   (with-out-str
;;     (.printStackTrace err (java.io.PrintWriter. *out*))))

(defn send-notification!
  [cfg report]
  (try
    (let [send! (:http-client cfg)
          uri   (:uri cfg)

          text  (str "@channel Unhandled exception."
                     "\n```\n"
                     report
                     "\n```")

          rsp   (send! {:uri uri
                        :method :post
                        :headers {"content-type" "application/json"}
                        :body (json/write-str {:text text})})]

      (when (not= (:status rsp) 200)
        (log/warnf "Error reporting webhook replying with unexpected status: %s\n%s"
                   (:status rsp)
                   (pr-str rsp))))
    (catch Exception e
      (log/warnf e "Unexpected exception on error reporter."))))
