;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns app.emails
  "Main api for send emails."
  (:require
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [app.tasks :as tasks]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.worker :as wrk]
   [app.db :as db]
   [app.util.emails :as emails]))

;; TODO
(defn get-or-create-factory
  [cache id])

(defmethod ig/pre-init-spec ::emails [_]
  (s/keys :req-un [::db/pool ::wrk/worker]))

(defmethod ig/init-key ::emails
  [_ {:keys [pool] :as cfg}]
  (let [cache   (atom {})
        submit! (:worker cfg)]
    (with-meta
      (fn sendmail
        ([id context] (sendmail pool id context))
        ([conn id context]
         (let [factory (get-or-create-factory cache id)
               email   (factory context)]
           (submit! conn {:name "sendmail"
                          :delay 0
                          :max-retries 1
                          :priority 200
                          :props email}))))
      {:cache cache})))



(defn send!
  [& args])

(def register
  (emails/template-factory ::register))

(def password-recovery
  (emails/template-factory ::password-recovery))

(def http-monitor-notification
  (emails/template-factory ::http-monitor-notification))

(def ssl-monitor-notification
  (emails/template-factory ::ssl-monitor-notification))

(def healthcheck-monitor-notification
  (emails/template-factory ::healthcheck-monitor-notification))

(def change-email
  (emails/template-factory ::change-email))

(def verify-contact
  (emails/template-factory ::verify-contact))
