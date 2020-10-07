;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.mailjet-webhook
  (:require
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.emails :as emails]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn process-event
  [pool data]
  (let [type      (:event data)
        recipient (:email data)
        track-id  (:CustomID data)
        reason    (:error_related_to data)]
    (log/debugf "Processing MailJet Event (recipient=%s type=%s reason=%s)" recipient type reason)
    (cond
      (and (us/email-string? recipient)
           (or (= "spam" type)
               (= "bounce" type)))
      (db/update! pool :contact
                  {:is-disabled true
                   :disable-reason type}
                  {:id track-id
                   :type "email"})

      (and (us/email-string? recipient)
           (= "blocked" type))
      (db/update! pool :contact
                  {:is-disabled true
                   :disable-reason (str type " " reason)}
                  {:id track-id
                   :type "email"}))))

(defmethod ig/pre-init-spec ::handler
  [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [{:keys [props] :as tdata}]
    (let [events (:events props)]
      (run! (partial process-event pool) events))))
