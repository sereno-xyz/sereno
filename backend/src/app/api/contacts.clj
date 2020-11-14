;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.api.contacts
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.emails :as emails]
   [app.api.profile :refer [get-profile]]
   [app.telegram :as telegram]
   [app.util.time :as dt]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]))

(defn- validate-contacts-limits!
  [conn profile]
  (let [result (db/exec-one! conn ["select count(*) as total
                                      from contact
                                     where owner_id=?" (:id profile)])]
    (when (>= (:total result 0)
              (or (:limits-max-contacts profile) ##Inf))
      (ex/raise :type :validation
                :code :contact-limits-reached))))


;; --- Mutation: Create Email Contact

(s/def ::create-email-contact
  (s/keys :req-un [::profile-id ::name ::email]))

(sv/defmethod ::create-email-contact
  [{:keys [pool tokens] :as cfg} {:keys [profile-id name email] :as props}]
  (db/with-atomic [conn pool]
    (let [id      (uuid/next)
          profile (get-profile conn profile-id)]

      ;; Validate limits
      (validate-contacts-limits! conn profile)

      ;; Do the main logic
      (let [claims  {:iss :verify-contact
                     :exp (dt/plus (dt/now) #app/duration "24h")
                     :contact-id id}
            token   ((:create tokens) claims)
            params  {:email email}]

        (emails/send! conn emails/verify-contact
                      {:to (:email params)
                       :public-uri (:public-uri cfg)
                       :invited-by (:fullname profile)
                       :invited-by-email (:email profile)
                       :token token})

        (try
          (db/insert! conn :contact
                      {:id id
                       :owner-id profile-id
                       :name name
                       :type "email"
                       :params (db/tjson params)})
          (catch org.postgresql.util.PSQLException e
            (if (= "23505" (.getSQLState e))
              (ex/raise :type :validation
                        :code :contact-already-exists
                        :cause e)
              (throw e))))
        nil))))


;; --- Mutation: Create Mattermost Contact

(s/def ::create-mattermost-contact
  (s/keys :req-un [::profile-id ::name ::us/uri]))

(sv/defmethod ::create-mattermost-contact
  [{:keys [pool] :as cfg} {:keys [profile-id name uri] :as props}]
  (db/with-atomic [conn pool]
    (let [id      (uuid/next)
          profile (get-profile conn profile-id)]

      ;; Validate limits
      (validate-contacts-limits! conn profile)

      ;; Do the main logic
      (let [params {:uri uri}]
        (db/insert! conn :contact
                    {:id id
                     :owner-id profile-id
                     :name name
                     :type "mattermost"
                     :validated-at (dt/now)
                     :params (db/tjson params)})
        nil))))


;; --- Mutation: Create Mattermost Contact

(declare decode-contact-row)

(s/def ::create-telegram-contact
  (s/keys :req-un [::profile-id ::name]))

(sv/defmethod ::create-telegram-contact
  [{:keys [pool] :as cfg} {:keys [profile-id name] :as props}]
  (db/with-atomic [conn pool]
    (let [id      (uuid/next)
          profile (get-profile conn profile-id)]

      ;; Validate limits
      (validate-contacts-limits! conn profile)

      ;; Do the main logic
      (let [contact (db/insert! conn :contact
                                {:id id
                                 :owner-id profile-id
                                 :name name
                                 :type "telegram"
                                 :params (db/tjson {})})]
        (decode-contact-row contact)))))


;; --- Mutation: Update contact

(s/def ::update-contact
  (s/keys :req-un [::id ::name ::is-paused ::profile-id]))

(sv/defmethod ::update-contact
  [{:keys [pool]} {:keys [id profile-id name is-paused] :as props}]
  (db/with-atomic [conn pool]
    (let [item (db/get-by-params conn :contact
                                 {:id id :owner-id profile-id}
                                 {:for-update true})]
      (when-not item
        (ex/raise :type :not-found
                  :code :object-not-found))

      (when (:is-blocked item)
        (ex/raise :type :validation
                  :code :contact-disabled
                  :reason (:reason item)))

      (db/update! conn :contact
                  {:name name
                   :is-paused is-paused}
                  {:id id :owner-id profile-id})
      nil)))


;; --- Mutation: Delete contact

(s/def ::delete-contact
  (s/keys :req-un [::id ::profile-id]))

(sv/defmethod ::delete-contact
  [{:keys [pool]} {:keys [id profile-id]}]
  (db/with-atomic [conn pool]
    (let [item (db/get-by-params conn :contact
                                 {:id id :owner-id profile-id}
                                 {:for-update true})]
      (when-not item
        (ex/raise :type :not-found
                  :code :object-not-found))
      (db/delete! conn :contact
                  {:id id :owner-id profile-id})
      nil)))


;; --- Query: Retrieve contacts

(defn decode-contact-row
  [{:keys [params pause-reason disable-reason id] :as row}]
  (cond-> (dissoc row :ref)
    (uuid? id)
    (assoc :short-id (telegram/uuid->b64us id))

    (db/pgobject? params)
    (assoc :params (db/decode-transit-pgobject params))

    (db/pgobject? pause-reason)
    (assoc :pause-reason (db/decode-transit-pgobject pause-reason))

    (db/pgobject? disable-reason)
    (assoc :disable-reason (db/decode-transit-pgobject disable-reason))))

(s/def ::retrieve-contacts
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::retrieve-contacts
  [{:keys [pool]} {:keys [profile-id]}]
  (->> (db/query pool :contact {:owner-id profile-id})
       (map decode-contact-row)))
