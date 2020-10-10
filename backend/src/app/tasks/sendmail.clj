;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.sendmail
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.emails :as emails]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defmulti sendmail (fn [config email] (:backend config)))

(defmethod sendmail "console"
  [{:keys [smtp] :as cfg} email]
  (let [baos (java.io.ByteArrayOutputStream.)
        mesg (emails/smtp-message smtp email)]
    (.writeTo mesg baos)
    (let [out (with-out-str
                (println "email console dump:")
                (println "******** start email" (:id email) "**********")
                (println (.toString baos))
                (println "******** end email "(:id email) "**********"))]
      (log/info out))))

(defmethod sendmail "smtp"
  [{:keys [smtp] :as cfg} email]
  (emails/send! smtp email))

(s/def ::http-client fn?)
(s/def ::backend ::us/not-empty-string)

;; TODO: use proper specs
(s/def ::smtp (s/map-of ::us/keyword any?))

(defmethod ig/pre-init-spec ::handler
  [_]
  (s/keys :req-un [::backend ::http-client ::smtp]))

(defmethod ig/init-key ::handler
  [_ {:keys [smtp] :as cfg}]
  (fn [{:keys [props] :as tdata}]
    ;; (let [props (cond-> props
    ;; (nil? (:from props))
    ;; (assoc :from (:default-from smtp)))]
    (sendmail cfg props)))
