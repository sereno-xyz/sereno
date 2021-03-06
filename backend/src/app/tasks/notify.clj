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
   [app.telegram :as tgm]
   [app.db :as db]
   [app.tokens :as tkn]
   [app.emails :as emails]
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [clojure.string]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defmulti notify! (fn [{:keys [contact monitor]}] [(:type contact) (:type monitor)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::code ::us/keyword)
(s/def ::hint ::us/string)
(s/def ::ex-class ::us/string)
(s/def ::ex-data map?)
(s/def ::ex-stack ::us/string)
(s/def ::ex-message ::us/string)

(s/def ::cause
  (s/keys :req-un [::code ::hint]
          :opt-un [::ex-class
                   ::ex-data
                   ::ex-stack
                   ::ex-message]))

(s/def ::id ::us/uuid)
(s/def ::type ::us/string)
(s/def ::status ::us/string)
(s/def ::name ::us/string)
(s/def ::params map?)
(s/def ::http-client fn?)
(s/def ::public-uri string?)
(s/def ::tokens ::tkn/service)
(s/def ::telegram (s/nilable ::tgm/service))

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::tokens ::public-uri ::http-client ::telegram]))

(s/def ::contact (s/keys :req-un [::id ::type ::params]))
(s/def ::monitor (s/keys :req-un [::id ::status ::name]))
(s/def ::result  (s/keys :req-un [::status] :opt-in [::cause]))

(s/def ::notify-props
  (s/keys :req-un [::monitor ::result ::contact]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [{:keys [props] :as task}]
    (us/assert ::notify-props props)
    (notify! (merge cfg props))
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Email Notification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare retrieve-profile-type)
(declare increate-email-counter)
(declare send-email-notification)
(declare annotate-and-check-email-send-limits!)

(s/def :internal.contacts.email/id ::us/uuid)
(s/def :internal.contacts.email/subscription-id ::us/uuid)
(s/def :internal.contacts.email/type #{"email"})
(s/def :internal.contacts.email/params
  (s/keys :req-un [::us/email]))

(s/def ::email-contact
  (s/keys :req-un [:internal.contacts.email/id
                   :internal.contacts.email/type
                   :internal.contacts.email/subscription-id
                   :internal.contacts.email/params]))

(defn- generate-tokens
  [{:keys [contact tokens] :as cfg}]
  {:unsub  ((:create tokens) {:iss :unsub-monitor
                              :exp (dt/in-future {:minutes 30})
                              :id (:subscription-id contact)})
   :delete ((:create tokens) {:iss :delete-contact
                              :exp (dt/in-future {:hours 48})
                              :contact-id (:id contact)})
   :cdata  ((:create tokens) {:iss :contact
                              :exp (dt/in-future {:days 7})
                              :profile-id (:owner-id contact)
                              :contact-id (:id contact)})})

(defmethod notify! ["email" "http"]
  [{:keys [pool monitor result contact] :as cfg}]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg :conn conn)
          tkn (generate-tokens cfg)]
      (annotate-and-check-email-send-limits! cfg (:owner-id monitor))
      (emails/send! conn emails/http-monitor-notification
                  {:old-status (:status monitor)
                   :new-status (:status result)
                   :to (get-in contact [:params :email])
                   :cause (:cause result)
                   :monitor-name (:name monitor)
                   :public-uri (:public-uri cfg)
                   :unsubscribe-token (:unsub tkn)
                   :delete-token (:delete tkn)
                   :custom-data (:cdata tkn)}))))

(defmethod notify! ["email" "ssl"]
  [{:keys [pool monitor result contact] :as cfg}]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg :conn conn)
          tkn (generate-tokens cfg)]
      (annotate-and-check-email-send-limits! cfg (:owner-id monitor))
      (emails/send! conn emails/ssl-monitor-notification
                  {:status (:status result)
                   :expired-at (str (:expired-at monitor))
                   :cause (:cause result)
                   :to (get-in contact [:params :email])
                   :monitor-name (:name monitor)
                   :monitor-params (:params monitor)
                   :public-uri (:public-uri cfg)
                   :unsubscribe-token (:unsub tkn)
                   :delete-token (:delete tkn)
                   :custom-data (:cdata tkn)}))))


(defmethod notify! ["email" "healthcheck"]
  [{:keys [pool monitor result contact] :as cfg}]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg :conn conn)
          tkn (generate-tokens cfg)]
      (annotate-and-check-email-send-limits! cfg (:owner-id monitor))
      (emails/send! conn emails/healthcheck-monitor-notification
                  {:status (:status result)
                   :expired-at (str (:expired-at monitor))
                   :cause (:cause result)
                   :to (get-in contact [:params :email])
                   :monitor-name (:name monitor)
                   :monitor-params (:params monitor)
                   :public-uri (:public-uri cfg)
                   :unsubscribe-token (:unsub tkn)
                   :delete-token (:delete tkn)
                   :custom-data (:cdata tkn)}))))

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

(defn send-to-mattermost!
  [{:keys [contact] :as cfg} content]
  (us/assert ::mattermost-contact contact)
  (let [send! (:http-client cfg)
        uri   (get-in contact [:params :uri])
        rsp   (send! {:uri uri
                      :method :post
                      :headers {"content-type" "application/json"}
                      :body (json/write-str {:text content})})]
    (when (not= (:status rsp) 200)
      (ex/raise :type :internal
                :code :mattermost-webhook-not-reachable
                :response rsp))))

(defmethod notify! ["mattermost" "http"]
  [{:keys [monitor result] :as cfg}]
  (let [cause (:cause result)
        content
        (if (= "up" (:status result))
          (str/fmt "@channel\nMonitor **%s** has gone **UP**" (:name monitor))
          (str
           (str/fmt "@channel\nMonitor **%s** has gone **DOWN**\n" (:name monitor))
           (str/fmt "**Cause code:** %s\n" (name (:code cause)))
           (str/fmt "**Cause hint:** %s\n" (:hint cause))))]

    (send-to-mattermost! cfg content)))

(defmethod notify! ["mattermost" "ssl"]
  [{:keys [monitor result] :as cfg}]
  (let [cause (:cause result)
        content
        (cond
          (= (:status result) "warn")
          (str/format "@channel\nMonitor **%s** is near to expiration." (:name monitor))

          (= (:status result) "down")
          (str (str/fmt "@channel\nMonitor **%s** is now expired or has invalid ssl certificate.\n" (:name monitor))
               (str/fmt "**Cause code:** %s\n" (name (:code cause)))
               (str/fmt "**Cause hint:** %s\n" (:hint cause)))

          :else
          (str/format "@channel **%s** is live." (:name monitor)))]

    (send-to-mattermost! cfg content)))

(defmethod notify! ["mattermost" "healthcheck"]
  [{:keys [monitor result] :as cfg}]
  (let [cause (:cause result)
        content
        (if (= "up" (:status result))
          (str/fmt "@channel\nMonitor **%s** has gone **UP**" (:name monitor))
          (str
           (str/fmt "@channel\nMonitor **%s** has gone **DOWN**\n" (:name monitor))
           (when cause
             (str/fmt "**Cause code:** %s\n" (name (:code cause)))
             (str/fmt "**Cause hint:** %s\n" (:hint cause)))))]

    (send-to-mattermost! cfg content)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Discord Notification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :internal.contacts.discord/params
  (s/keys :req-un [::us/uri]))

(s/def ::discord-contact
  (s/keys :req-un [:internal.contacts.discord/params]))

(defn send-to-discord!
  [{:keys [contact] :as cfg} content]
  (us/assert ::discord-contact contact)
  (let [send! (:http-client cfg)
        uri   (get-in contact [:params :uri])
        rsp   (send! {:uri uri
                      :method :post
                      :headers {"content-type" "application/json"}
                      :body (json/write-str content)})]
    (when (not= (:status rsp) 204)
      (ex/raise :type :internal
                :message (str/format "Unexpected status code (%s) received from discord." (:status rsp))
                :code :discord-webhook-not-reachable
                :response rsp))))

(defmethod notify! ["discord" "http"]
  [{:keys [monitor result] :as cfg}]
  (let [cause (:cause result)
        description
        (if (= "up" (:status result))
          (str/fmt "**%s** monitor has gone **UP**" (:name monitor))
          (str
           (str/fmt "**%s** monitor has gone **DOWN**\n" (:name monitor))
           (str/fmt "**Cause code:** %s\n" (name (:code cause)))
           (str/fmt "**Cause hint:** %s\n" (:hint cause))))

        embedd  {:title "Monitor status change notification"
                 :description description}

        content {:username "sereno.xyz"
                 :content "@everyone"
                 :avatar_url "https://sereno.xyz/images/logo.png"
                 :embeds [embedd]}]
    (send-to-discord! cfg content)))

(defmethod notify! ["discord" "ssl"]
  [{:keys [monitor result] :as cfg}]
  (let [cause (:cause result)

        description
        (cond
          (= (:status result) "warn")
          (str/format "**%s** is near to expiration." (:name monitor))

          (= (:status result) "down")
          (str
           (str/fmt "**%s** is now expired or has invalid ssl certificate.\n" (:name monitor))
           (str/fmt "**Cause code:** %s\n" (name (:code cause)))
           (str/fmt "**Cause hint:** %s\n" (:hint cause)))

          :else
          (str/format "**%s** is live." (:name monitor)))

        embedd  {:title "Monitor status change notification"
                 :description description}
        content {:username "sereno.xyz"
                 :content "@everyone"
                 :avatar_url "https://sereno.xyz/images/logo.png"
                 :embeds [embedd]}]
    (send-to-discord! cfg content)))

(defmethod notify! ["discord" "healthcheck"]
  [{:keys [monitor result] :as cfg}]
  (let [cause (:cause result)
        description
        (if (= "up" (:status result))
          (str/fmt "**%s** monitor has gone **UP**" (:name monitor))
          (str
           (str/fmt "**%s** monitor has gone **DOWN**\n" (:name monitor))
           (when cause
             (str/fmt "**Cause code:** %s\n" (name (:code cause)))
             (str/fmt "**Cause hint:** %s\n" (:hint cause)))))

        embedd  {:title "Monitor status change notification"
                 :description description}

        content {:username "sereno.xyz"
                 :content "@everyone"
                 :avatar_url "https://sereno.xyz/images/logo.png"
                 :embeds [embedd]}]
    (send-to-discord! cfg content)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Telegram Notification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare escape-html)

(s/def :internal.contacts.telegram/subscription-id ::us/uuid)
(s/def :internal.contacts.telegram/chat-id ::us/integer)
(s/def :internal.contacts.telegram/id ::us/uuid)

(s/def :internal.contacts.telegram/params
  (s/keys :req-un [:internal.contacts.telegram/chat-id]))

(s/def ::telegram-contact
  (s/keys :req-un [:internal.contacts.telegram/params
                   :internal.contacts.telegram/subscription-id
                   :internal.contacts.telegram/id]))

(defn send-to-teleram!
  [{:keys [contact telegram] :as cfg} content]
  (us/assert ::telegram-contact contact)
  (when telegram
    (let [chat-id (get-in contact [:params :chat-id])]
      ((:send-message telegram) {:chat-id chat-id :content content}))))

(defmethod notify! ["telegram" "http"]
  [{:keys [monitor result] :as cfg}]
  (let [cause (:cause result)
        content
        (if (= "up" (:status result))
          (str/fmt "<b>%s</b> monitor has gone <u><b>UP</b></u>" (escape-html (:name monitor)))
          (str
           (str/fmt "<b>%s</b> monitor has gone <u><b>DOWN</b></u>\n" (escape-html (:name monitor)))
           (str/fmt "<b>Cause code:</b> <code>%s</code>\n" (name (:code cause)))
           (str/fmt "<b>Cause hint:</b> <code>%s</code>\n" (:hint cause))))]
    (send-to-teleram! cfg content)))

(defmethod notify! ["telegram" "ssl"]
  [{:keys [monitor result] :as cfg}]
  (let [cause (:cause result)
        content
        (cond
          (= (:status result) "warn")
          (str/format "<b>%s</b> is near to expiration." (escape-html (:name monitor)))

          (= (:status result) "down")
          (str
           (str/fmt "<b>%s</b> monitor is now expired or has invalid ssl certificate.\n" (escape-html (:name monitor)))
           (str/fmt "<b>Cause code:</b> <code>%s</code>\n" (name (:code cause)))
           (str/fmt "<b>Cause hint:</b> <code>%s</code>\n" (:hint cause)))

          :else
          (str/format "<b>%s</b> is live." (:name monitor)))]

    (send-to-teleram! cfg content)))

(defmethod notify! ["telegram" "healthcheck"]
  [{:keys [monitor result] :as cfg}]
  (let [cause (:cause result)
        content
        (if (= "up" (:status result))
          (str/fmt "<b>%s</b> monitor has gone <u><b>UP</b></u>" (escape-html (:name monitor)))
          (str
           (str/fmt "<b>%s</b> monitor has gone <u><b>DOWN</b></u>\n" (escape-html (:name monitor)))
           (when cause
             (str/fmt "<b>Cause code:</b> <code>%s</code>\n" (name (:code cause)))
             (str/fmt "<b>Cause hint:</b> <code>%s</code>\n" (:hint cause)))))]
    (send-to-teleram! cfg content)))



(defn escape-html
  [s]
  (clojure.string/escape s {\< "&lt;", \> "&gt;", \& "&amp;"}))
