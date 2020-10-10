;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.webhooks.awssns
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(declare parse-json)
(declare parse-params)
(declare retrieve-contacts)
(declare retrieve-profile)
(declare process-message)

(def MAX-BOUNCES 3)


(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool shared-key] :as cfg}]
  (fn [request]
    (let [skey   (get-in request [:query-params "shared-key"])
          params (parse-json (slurp (:body request)))
          mtype  (get params "Type")]

      (when (not= skey shared-key)
        (log/warnf "Unauthorized request from webhook ('%s')." skey)
        (ex/raise :type :not-authorized
                  :code :invalid-shared-key))

      (cond
        (= mtype "SubscriptionConfirmation")
        (let [surl   (get params "SubscribeURL")
              stopic (get params "TopicArn")
              send!  (:http-client cfg)]
          (log/infof "Subscription received (topic=%s, url=%s)" stopic surl)
          (send! {:uri surl :method :get :timeout 5000}))

        (= mtype "Notification")
        (do
          (log/infof "Received: %s" (pr-str params))
          (run! (partial process-message cfg)
                (parse-params params)))

        :else
        (log/infof "Unexpected data received: %s" (pr-str params)))

      {:status 200 :body ""})))

(defn process-message
  [{:keys [pool] :as cfg} {:keys [type email mdata] :as message}]
  (log/debugf "Procesing message: %s" (pr-str message))
  (db/with-atomic [conn pool]
    (let [contacts    (retrieve-contacts conn email)
          profile     (retrieve-profile conn email)
          now         (dt/now)]

      (log/debugf "Found contacts: %s" (pr-str contacts))
      (log/debugf "Found profile %s" (pr-str profile))

      (doseq [contact contacts]
        (if (or (>= (:bounces contact) (dec MAX-BOUNCES))
                (= type :complaint))
          (db/update! conn :contact
                      {:is-disabled true
                       :is-paused true
                       :disable-reason (db/tjson mdata)
                       :bounces MAX-BOUNCES
                       :bounced-at now}
                      {:id (:id contact)})
          (db/update! conn :contact
                      {:is-paused true
                       :pause-reason (db/tjson mdata)
                       :bounces (inc (:bounces contact))
                       :bounced-at now}
                  {:id (:id contact)}))
        (when (not= (:id profile) (:owner-id contact))
          (db/insert! conn :profile-incident
                      {:profile-id (:owner-id contact)
                       :created-at now
                       :type (name type)
                       :mdata (db/tjson mdata)})))
      (when profile
        (db/insert! conn :profile-incident
                    {:profile-id (:id profile)
                     :created-at now
                     :type (name type)
                     :mdata (db/tjson mdata)})))))

(defn retrieve-contacts
  [conn email]
  (let [sql "select * from contact
              where (params->>'~:email') = ?
                and type = 'email'
                for update"]
    (db/exec! conn [sql email])))

(defn retrieve-profile
  [conn email]
  (db/get-by-params conn :profile {:email email}))

(defn- parse-params
  [params]
  (when-let [message (parse-json (get params "Message"))]
    (condp = (get message "notificationType")
      "Bounce"
      (let [reason     (get message "bounce")
            recipients (->> (get reason "bouncedRecipients")
                            (map (fn [item]
                                   {:email (get item "emailAddress")
                                    :status (get item "status")
                                    :action (get item "action")
                                    :dcode  (get item "diagnosticCode")})))
            mdata      {:type :bounce
                        :kind (get reason "bounceType")
                        :feedback-id (get reason "feedbackId")
                        :timestamp (get reason "timestamp")}]

        (for [recipient recipients]
          {:type :bounce
           :email (:email recipient)
           :mdata (assoc mdata :recipient recipient)}))

      "Complaint"
      (let [reason (get message "complaint")
            emails (->> (get reason "complainedRecipients")
                        (map #(get % "emailAddress")))

            mdata  {:type :complaint
                    :user-agent (get reason "userAgent")
                    :feedback-type (get reason "complaintFeedbackType")
                    :recieved-at (get reason "arrivalDate")
                    :feedback-id (get reason "feedbackId")}]
        (for [email emails]
          {:type :complaint
           :email email
           :mdata (assoc mdata :email email)})))))

(defn- parse-json
  [v]
  (ex/ignoring
   (json/read-str v)))
