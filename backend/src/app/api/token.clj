;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.api.token
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.emails :as emails]
   [app.http :as http]
   [app.telegram :as telegram]
   [app.util.time :as dt]
   [app.api.profile :refer [check-profile-existence!]]
   [app.util.transit :as t]
   [buddy.hashers :as bh]
   [app.util.services :as sv]
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [ring.core.protocols :as rp]))

;; --- Mutation: Verify Profile Token

(declare process-token)

(s/def ::verify-token
  (s/keys :req-un [::token]))

(sv/defmethod ::verify-token {:auth false}
  [{:keys [pool tokens] :as cfg} {:keys [token] :as params}]
  (let [claims ((:verify tokens) token)]
    (db/with-atomic [conn pool]
      (process-token (assoc cfg :conn conn) claims))))


(defmulti process-token (fn [cfg token] (:iss token)))

(defmethod process-token :change-email
  [{:keys [conn] :as cfg} {:keys [profile-id] :as claims}]
  (let [profile (db/get-by-id conn :profile profile-id {:for-update true})]
    (check-profile-existence! conn {:email (:email claims)})
    (db/update! conn :profile
                {:email (:email claims)}
                {:id (:id profile)})
    claims))

(defmethod process-token :verify-profile
  [{:keys [conn] :as cfg} {:keys [profile-id] :as claims}]
  (let [profile (db/get-by-id conn :profile profile-id {:for-update true})]
    (when (:is-active profile)
      (ex/raise :type :validation
                :code :profile-already-active))

    (when (not= (:email profile)
                (:email claims))
      (ex/raise :type :validation
                :code :invalid-token))

    (db/update! conn :profile
                {:is-active true}
                {:id profile-id})

    claims))

(defmethod process-token :verify-contact
  [{:keys [conn] :as cfg} {:keys [contact-id] :as claims}]
  (db/update! conn :contact
              {:validated-at (dt/now)}
              {:id contact-id})

  claims)

(defmethod process-token :unsub-monitor
  [{:keys [conn] :as cfg} {:keys [id] :as claims}]
  (db/delete! conn :monitor-contact-rel {:id id})
  claims)

(defmethod process-token :delete-contact
  [{:keys [conn] :as cfg} {:keys [contact-id] :as claims}]
  (db/delete! conn :contact {:id contact-id})
  claims)

(defmethod process-token :gauth
  [cfg token]
  token)

(defmethod process-token :default
  [cfg token]
  (ex/raise :type :validation
            :code :invalid-token
            :context token))


