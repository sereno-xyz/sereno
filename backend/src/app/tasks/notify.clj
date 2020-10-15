;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.notify
  (:require
   [app.common.spec :as us]
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.db :as db]
   [app.tokens :as tkn]
   [app.emails :as emails]
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defmulti notify! (fn [cfg contact monitor result] (:type contact)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::id ::us/uuid)
(s/def ::type ::us/string)
(s/def ::reason ::us/string)
(s/def ::status ::us/string)
(s/def ::name ::us/string)
(s/def ::params map?)

(s/def ::contact (s/keys :req-un [::id ::type ::params]))
(s/def ::monitor (s/keys :req-un [::id ::status ::name]))
(s/def ::result  (s/keys :req-un [::status]
                         :opt-in [::reason]))

(s/def ::notify-props
  (s/keys :req-un [::monitor ::result ::contact]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [{:keys [props] :as task}]
    (us/assert ::notify-props props)
    (let [{:keys [contact monitor result]} props]
      (notify! cfg contact monitor result))))

(s/def ::http-client fn?)
(s/def ::public-uri string?)
(s/def ::tokens ::tkn/service)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::tokens ::public-uri ::http-client]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Email Notification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare retrieve-profile-type)
(declare increate-email-counter)
(declare send-email-notification)
(declare annotate-and-check-email-send-limits!)

(s/def :internal.contacts.email/id uuid?)
(s/def :internal.contacts.email/type #{"email"})
(s/def :internal.contacts.email/params
  (s/keys :req-un [::us/email]))

(s/def ::email-contact
  (s/keys :req-un [:internal.contacts.email/id
                   :internal.contacts.email/type
                   :internal.contacts.email/params]))

(defmethod notify! "email"
  [{:keys [pool tokens] :as cfg} contact monitor result]
  (db/with-atomic [conn pool]
    (let [cfg   (assoc cfg :conn conn)]
      (annotate-and-check-email-send-limits! cfg (:owner-id monitor))
      (send-email-notification cfg contact monitor result))))

(defn- send-email-notification
  [{:keys [pool tokens] :as cfg} contact monitor result]
  (us/assert ::email-contact contact)
  (let [utoken ((:create tokens) {:iss :unsub-monitor
                                  :exp (dt/in-future {:minutes 30})
                                  :monitor-id (:id monitor)
                                  :contact-id (:id contact)})
        dtoken ((:create tokens) {:iss :delete-contact
                                  :exp (dt/in-future {:hours 48})
                                  :contact-id (:id contact)})
        cdata  ((:create tokens) {:iss :contact
                                  :exp (dt/in-future {:days 7})
                                  :profile-id (:owner-id contact)
                                  :contact-id (:id contact)})]
    (emails/send! pool emails/monitor-notification
                  {:old-status (:status monitor)
                   :new-status (:status result)
                   :monitor-name (:name monitor)
                   :public-uri (:public-uri cfg)
                   :unsubscribe-token utoken
                   :delete-token dtoken
                   :to (get-in contact [:params :email])
                   :custom-data cdata})))

(defn annotate-and-check-email-send-limits!
  [cfg profile-id]
  (let [pquotes (retrieve-profile-type cfg profile-id)
        nsent   (increate-email-counter cfg profile-id)
        allowed (:max-email-notifications pquotes ##Inf)]
    (<= nsent allowed)))

(def sql:increase-email-counter
  "insert into profile_counters (profile_id, email_notifications)
   values (?, 1)
   on conflict (profile_id, created_at)
      do update set email_notifications = profile_counters.email_notifications + 1
   returning email_notifications")

(defn- increate-email-counter
  [{:keys [conn]} profile-id]
  (let [result (db/exec-one! conn [sql:increase-email-counter profile-id])]
    (:email-notifications result 1)))

(def sql:profile-type
  "select pt.* from profile_type as pt
     join profile as p on (p.type = pt.id)
    where p.id = ?")

(defn retrieve-profile-type
  [{:keys [conn]} profile-id]
  (db/exec-one! conn [sql:profile-type profile-id]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mattermost Notification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :internal.contacts.mattermost/params
  (s/keys :req-un [::us/uri]))

(s/def ::mattermost-contact
  (s/keys :req-un [:internal.contacts.mattermost/params]))

(defmethod notify! "mattermost"
  [cfg contact monitor result]
  (us/assert ::mattermost-contact contact)
  (let [send! (:http-client cfg)
        uri   (get-in contact [:params :uri])
        text  (str/format "@channel **%s** status change from **%s** to **%s**"
                          (:name monitor)
                          (str/upper (:status monitor))
                          (str/upper (:status result)))
        rsp   (send! {:uri uri
                      :method :post
                      :headers {"content-type" "application/json"}
                      :body (json/write-str {:text text})})]
    (when (not= (:status rsp) 200)
      (ex/raise :type :internal
                :code :mattermost-webhook-not-reachable
                :response rsp))
    nil))

