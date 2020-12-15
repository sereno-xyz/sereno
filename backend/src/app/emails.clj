;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.emails
  "Main api for send emails."
  (:require
   [clojure.spec.alpha :as s]
   [app.config :as cfg]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.tasks :as tasks]
   [app.util.emails :as emails]))

;; --- Public API

(defn send!
  "Schedule the email for sending."
  [conn email-factory context]
  (us/verify fn? email-factory)
  (us/verify map? context)
  (let [email (email-factory context)]
    (tasks/submit! conn {:name "sendmail"
                         :delay 0
                         :max-retries 1
                         :priority 200
                         :props email})))

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
