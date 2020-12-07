;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.api.profile
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.emails :as emails]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [buddy.hashers :as bh]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Profile & Auth
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Mutation: Login

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)

(s/def ::login
  (s/keys :req-un [::email ::password]))

(defn- check-password!
  [profile password]
  (when (= (:password profile) "!")
    (ex/raise :type :validation
              :code :account-without-password))
  (let [result (bh/verify password (:password profile))]
    (when-not (:valid result)
      (ex/raise :type :validation
                :code :wrong-credentials))))

(sv/defmethod ::login {:auth false}
  [{:keys [pool]} {:keys [email password] :as params}]
  (letfn [(validate-profile [profile]
            (when-not profile
              (ex/raise :type :validation
                        :code :wrong-credentials))

            (when-not (:is-active profile)
              (ex/raise :type :validation
                        :code :profile-not-activated))

            (check-password! profile password)
            profile)]

    (db/with-atomic [conn pool]
      (let [email  (str/lower email)]
        (-> (db/get-by-params conn :profile
                              {:email email}
                              {:for-update true})
            (validate-profile)
            (dissoc :password))))))


;; --- Mutation: Register profile

(declare check-profile-existence!)
(declare create-profile)
(declare create-profile-relations)

(s/def ::register-profile
  (s/keys :req-un [::email ::password ::fullname]))

(sv/defmethod ::register-profile {:auth false}
  [{:keys [pool tokens] :as cfg} params]
  (db/with-atomic [conn pool]
    (check-profile-existence! conn params)
    (let [profile (->> (create-profile conn params)
                       (create-profile-relations conn))

          claims  {:profile-id (:id profile)
                   :email (:email profile)
                   :iss :verify-profile
                   :exp (dt/plus (dt/now) (dt/duration {:days 7}))}

          token   ((:create tokens) claims)]

      (emails/send! conn emails/register
                    {:to (:email profile)
                     :name (:fullname profile)
                     :public-uri (:public-uri cfg)
                     :token token})

      (dissoc profile :password))))

(def ^:private sql:profile-existence
  "select exists (select * from profile
                   where email = ?) as val")

(defn check-profile-existence!
  [conn {:keys [email] :as params}]
  (let [email  (str/lower email)
        result (db/exec-one! conn [sql:profile-existence email])]
    (when (:val result)
      (ex/raise :type :validation
                :code :email-already-exists))
    params))

(defn- derive-password
  [password]
  (bh/derive password
             {:alg :argon2id
              :memory 16384
              :iterations 20
              :parallelism 2}))

(defn create-profile
  "Create the profile entry on the database with limited input
  filling all the other fields with defaults."
  [conn {:keys [fullname email password] :as params}]
  (let [id       (uuid/next)
        password (derive-password password)]
    (db/insert! conn :profile
                {:id id
                 :type (:default-profile-type cfg/config "default")
                 :fullname fullname
                 :email (str/lower email)
                 :password password})))

(defn create-profile-relations
  [conn profile]
  (db/insert! conn :contact
              {:owner-id (:id profile)
               :created-at (:created-at profile)
               :name "Primary Contact"
               :type "owner"
               :validated-at (:created-at profile)
               :params (db/tjson {})})
  profile)


;; --- Mutation: Login Or Register

(s/def ::fullname ::us/not-empty-string)
(s/def ::name ::us/not-empty-string)
(s/def ::profile-id ::us/uuid)
(s/def ::external-id ::us/not-empty-string)

(s/def ::login-or-register
  (s/keys :req-un [::email ::fullname ::external-id]))

(sv/defmethod ::login-or-register {:auth false}
  [{:keys [pool]} {:keys [email fullname external-id] :as params}]
  (letfn [(create-profile [conn {:keys [fullname email]}]
            (db/insert! conn :profile
                        {:id (uuid/next)
                         :fullname fullname
                         :email (str/lower email)
                         :external-id external-id
                         :is-active true
                         :password "!"}))

          (register-profile [conn params]
            (->> (create-profile conn params)
                 (create-profile-relations conn)))]

    (db/with-atomic [conn pool]
      (let [profile (db/get-by-params conn :profile {:email (str/lower email)})
            profile (or profile (register-profile conn params))]
        (dissoc profile :password)))))


;; --- Mutation: Update Profile

(s/def ::update-profile
  (s/keys :req-un [::fullname ::profile-id]))

(sv/defmethod ::update-profile
  [{:keys [pool]} {:keys [fullname profile-id]}]
  (db/update! pool :profile
              {:fullname fullname}
              {:id profile-id})
  nil)


;; --- Mutation: Update Profile Password

(s/def ::password ::us/not-empty-string)
(s/def ::old-password ::us/not-empty-string)

(s/def ::update-profile-password
  (s/keys :req-un [::profile-id ::password]
          :opt-un [::old-password]))

(sv/defmethod ::update-profile-password
  [{:keys [pool]} {:keys [password profile-id old-password] :as params}]
  (db/with-atomic [conn pool]
    (let [profile (db/get-by-id conn :profile profile-id {:for-update true})]
      (when (not= "!" (:password profile))
        (let [result  (bh/verify old-password (:password profile))]
          (when-not (:valid result)
            (ex/raise :type :validation
                      :code :old-password-not-match))))
      (db/update! conn :profile
                  {:password (derive-password password)}
                  {:id profile-id})
      nil)))



;; --- Mutation: Delete Profile

(s/def ::delete-profile
  (s/keys :req-un [::profile-id ::password]))

(sv/defmethod ::delete-profile
  [{:keys [pool]} {:keys [profile-id password] :as params}]
  (db/with-atomic [conn pool]
    (let [profile (db/get-by-id conn :profile profile-id {:for-update true})]
      (check-password! profile password)
      (db/delete! conn :profile {:id profile-id})
      (db/delete! conn :http-session {:profile-id profile-id})
      nil)))


;; --- Mutation: Profile Recovery Request

(s/def ::request-profile-recovery
  (s/keys :req-un [::email]))

(sv/defmethod ::request-profile-recovery {:auth false}
  [{:keys [pool tokens] :as cfg} {:keys [email] :as params}]
  (letfn [(create-recovery-token [{:keys [id] :as profile}]
            (let [claims {:profile-id id
                          :iss :password-recovery
                          :exp (dt/plus (dt/now) #app/duration "10m")}
                  token  ((:create tokens) claims)]
              (assoc profile :token token)))

          (send-email-notification [conn profile]
            (emails/send! conn emails/password-recovery
                          {:to (:email profile)
                           :public-uri (:public-uri cfg)
                           :token (:token profile)
                           :name (:fullname profile)}))

          (retrieve-profile-by-email [conn email]
            (let [email (str/lower email)]
              (db/get-by-params conn :profile {:email email})))]

    (db/with-atomic [conn pool]
      (some->> email
               (retrieve-profile-by-email conn)
               (create-recovery-token)
               (send-email-notification conn))
      nil)))


;; --- Mutation: Recover Profile

(s/def ::token ::us/not-empty-string)
(s/def ::recover-profile
  (s/keys :req-un [::token ::password]))

(sv/defmethod ::recover-profile {:auth false}
  [{:keys [pool tokens]} {:keys [token password]}]
  (letfn [(validate-token [token]
            (let [params {:iss :password-recovery}
                  claims ((:verify tokens) token params)]
              (:profile-id claims)))

          (update-password [conn profile-id]
            (let [pwd (derive-password password)]
              (db/update! conn :profile {:password pwd} {:id profile-id})))]

    (db/with-atomic [conn pool]
      (->> (validate-token token)
           (update-password conn))
      nil)))


;; --- Mutation: Request Email Change

(declare check-profile-existence!)
(declare create-profile)
(declare create-profile-relations)

(s/def ::request-email-change
  (s/keys :req-un [::email]))

(sv/defmethod ::request-email-change
  [{:keys [pool tokens] :as cfg} {:keys [profile-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [email   (str/lower email)
          profile (db/get-by-id conn :profile profile-id {:for-update true})
          claims  {:iss :change-email
                   :exp (dt/plus (dt/now) #app/duration "15m")
                   :profile-id profile-id
                   :email email}
          token   ((:create tokens) claims)]

      (when (not= email (:email profile))
        (check-profile-existence! conn params))

      (emails/send! conn emails/change-email
                    {:to (:email profile)
                     :name (:fullname profile)
                     :public-uri (:public-uri cfg)
                     :pending-email (:email claims)
                     :token token})
      nil)))

;; --- Query: retrieve profile

(def sql:retrieve-profile
  "select p.*,
          pt.min_cadence as limits_min_cadence,
          pt.max_contacts as limits_max_contacts,
          pt.max_monitors as limits_max_monitors,
          pt.max_email_notifications as limits_max_email_notifications,
          pc.created_at as counters_period,
          pc.email_notifications as counters_email_notifications,
          (select count(*) from monitor where owner_id = p.id) as counters_monitors,
          (select count(*) from contact where owner_id = p.id) as counters_contacts

     from profile as p
    inner join profile_type as pt on (pt.id = p.type)
     left join profile_counters as pc on (pc.profile_id = p.id and
                                          pc.created_at = date_trunc('month', now()))
    where p.id = ?")

(defn get-profile
  [conn id]
  (let [result (db/exec-one! conn [sql:retrieve-profile id])]
    (when-not result
      (ex/raise :type :not-found))
    result))

(s/def ::retrieve-profile
  (s/keys :opt-un [::profile-id]))

(sv/defmethod ::retrieve-profile
  [{:keys [pool]} {:keys [profile-id] :as params}]
  (if profile-id
    (let [data (get-profile pool profile-id)]
      (dissoc data :password))
    {:id uuid/zero
     :fullname "Anonymous"}))
