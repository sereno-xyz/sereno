;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.session
  (:require
   [integrant.core :as ig]
   [app.db :as db]
   [buddy.core.codecs :as bc]
   [buddy.core.nonce :as bn]))

(defn next-session-id
  ([] (next-session-id 96))
  ([n]
   (-> (bn/random-nonce n)
       (bc/bytes->b64u)
       (bc/bytes->str))))

(defn create!
  [conn {:keys [profile-id user-agent]}]
  (let [id (next-session-id)]
    (db/insert! conn :http-session {:id id
                                    :profile-id profile-id
                                    :user-agent user-agent})
    id))

(defn delete!
  [conn request]
  (when-let [token (get-in request [:cookies "auth-token" :value])]
    (db/delete! conn :http-session {:id token}))
  nil)

(defn retrieve
  [conn token]
  (when token
    (-> (db/exec-one! conn ["select profile_id from http_session where id = ?" token])
        (:profile-id))))

(defn retrieve-from-request
  [conn request]
  (->> (get-in request [:cookies "auth-token" :value])
       (retrieve conn)))

(defn cookies
  [opts]
  {"auth-token" (merge opts {:path "/" :http-only true})})

(defn middleware
  [handler {:keys [pool] :as cfg}]
  (fn [request]
    (if-let [profile-id (retrieve-from-request pool request)]
      (handler (assoc request :profile-id profile-id))
      (handler request))))

