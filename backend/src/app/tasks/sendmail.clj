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
   [app.util.emails :as emails]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig])
  (:import
   java.io.ByteArrayOutputStream))

(defn- send-console!
  [cfg email]
  (let [baos (ByteArrayOutputStream.)
        mesg (emails/smtp-message cfg email)]
    (.writeTo mesg baos)
    (let [out (with-out-str
                (println "email console dump:")
                (println "******** start email" (:id email) "**********")
                (println (.toString baos))
                (println "******** end email "(:id email) "**********"))]
      (log/info out))))

;; TODO: add specs
;; (defmethod ig/pre-init-spec ::handler
;;   [_]
;;   (s/keys :req-un [::smtp]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as tdata}]
    (if (:enabled cfg)
      (emails/send! cfg props)
      (send-console! cfg props))))
