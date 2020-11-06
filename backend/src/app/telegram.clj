;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.telegram
  "Telegram service."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.util.time :as dt]
   [buddy.core.codecs :as bc]
   [clojure.data.json :as json]
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import java.util.UUID
           java.nio.ByteBuffer))

(declare b64us->uuid)
(declare uuid->b64us)

(def base-uri "https://api.telegram.org/bot")
(def fmt-uri #(str base-uri %1 "/" %2))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Service
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- send-message
  [{:keys [id token] :as cfg} {:keys [chat-id content] :as message}]
  (when (and id token)
    (let [payload {:chat_id chat-id
                   :text content
                   :parse_mode "MarkdownV2"}
          send!   (:http-client cfg)
          resp    (send! {:uri (fmt-uri (:token cfg) "sendMessage")
                          :headers {"content-type" "application/json"}
                          :body (json/write-str payload)
                          :method :post})
          body    (ex/ignoring (json/read-str (:body resp)))]
      (when-not (and (= 200 (:status resp))
                     (true? (get body "ok")))
        (ex/raise :type :internal
                  :code :telegram-error
                  :hint (get body "description")
                  :response resp))
      true)))

(defn- parse-update
  [cfg params]
  (let [base {:id (get params "update_id")}]
    (cond
      (contains? params "message")
      (let [message (get params "message")
            chat    (get message "chat")
            user    (get message "from")
            base    (assoc base
                           :chat-id (get chat "id")
                           :chat-type (get chat "type")
                           :chat-title (or (get chat "title")
                                           (str/trim (str (get chat "first_name") " "
                                                          (get chat "last_name")))))]
        (cond
          (and (string? (get message "text"))
               (str/starts-with? (get message "text") "/"))
          (let [content (get message "text")
                [cmd param] (str/split content #"\s+" 2)
                [cmd other] (str/split cmd #"\@" 2)]
            (assoc base
                   :type :command
                   :op (subs cmd 1)
                   :param (str/trim param)))

          (contains? message "left_chat_member")
          (let [member (get message "left_chat_member")]
            (assoc base
                   :type :member-leave
                   :member-id (get member "id")
                   :member-username (get member "username")
                   :member-is-bot (get member "is_bot")))

          (string? (get message "new_chat_title"))
          (assoc base
                 :type :title-change
                 :chat-title (get message "new_chat_title"))

          :else
          (assoc base :type :generic-message)))

      (contains? params "edited_message")
      (parse-update cfg (assoc params "message" (get params "edited_message")))

      :else
      {:type :unknown
       :params params})))

(s/def :app.telegram.service/token ::us/string)
(s/def :app.telegram.service/id ::us/integer)
(s/def :app.telegram.service/send-message fn?)
(s/def :app.telegram.service/parse-update fn?)

(s/def :app.telegram/service
  (s/keys :req-un [:app.telegram.service/send-message
                   :app.telegram.service/parse-update
                   :app.telegram.service/token
                   :app.telegram.service/id]))

(s/def ::id (s/nilable ::us/integer))
(s/def ::token (s/nilable ::us/string))
(s/def ::http-client fn?)

(defmethod ig/pre-init-spec ::service [_]
  (s/keys :req-un [::db/pool ::token ::id ::http-client]))

(defmethod ig/init-key ::service
  [_ cfg]
  (when (and (:token cfg) (:id cfg))
    (assoc cfg
           :send-message (partial send-message cfg)
           :parse-update (partial parse-update cfg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebHook
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare process-message)
(declare process-command)
(declare process-callback)
(declare handle-request)
(declare reply-msg)

(s/def :app.telegram.webhook/telegram (s/nilable ::service))

(defmethod ig/pre-init-spec ::webhook [_]
  (s/keys :req-un [:app.telegram.webhook/telegram ::db/pool]))

(defmethod ig/init-key ::webhook
  [_ {:keys [telegram shared-key] :as cfg}]
  (fn [request]
    (let [skey (get-in request [:query-params "shared-key"])]
      (when (not= skey shared-key)
        (log/warnf "Unauthorized request from webhook ('%s')." skey)
        (ex/raise :type :not-authorized
                  :code :invalid-shared-key)))

    (when (nil? telegram)
      (ex/raise :type :internal
                :code "telegram service not enabled"))

    (let [params (:body-params request)]
      (log/debugf "Incoming telegram webhook request: %s" (pr-str params))
      (if-not (contains? params "update_id")
        {:status 204 :body ""}
        (let [response (handle-request cfg params)]
          (log/debugf "Generated response: %s" (pr-str response))
          (if (map? response)
            {:status 200
             :headers {"content-type" "application/json"}
             :body (json/write-str response)}
            {:status 204
             :body ""}))))))

(defn- handle-request
  [{:keys [telegram] :as cfg} params]
  (let [{:keys [type] :as message} ((:parse-update telegram) params)]
    (log/debugf "Parsed message: %s" (pr-str message))
    (process-message cfg message)))

(defmulti process-message (fn [cfg message] (:type message)))
(defmulti process-command (fn [cfg command] (:op command)))

(defmethod process-message :default
  [{:keys [pool] :as cfg} {:keys [chat-id] :as message}]
  (log/debugf "No impl for message: %s" (pr-str message))
  nil)

(defmethod process-command :default
  [{:keys [pool] :as cfg} {:keys [chat-id] :as command}]
  (log/debugf "No impl for command: %s" (pr-str command))
  (reply-msg chat-id (str/format "No implementation found for command: %s" (:op command))))

(defmethod process-message :command
  [cfg message]
  (process-command cfg message))

(defmethod process-message :title-change
  [cfg {:keys [chat-id chat-title]}]
  (log/debugf "chat %s changed title to %s" chat-id chat-title))


(defmethod process-message :member-leave
  [{:keys [telegram pool] :as cfg} {:keys [chat-id member-id] :as message}]
  (when (= member-id (:id telegram))
    (let [sql  "update contact set validated_at=null, is_disabled=true, is_paused=true
                 where (params->>'~:chat-id')::bigint = ?
                   and type = 'telegram'"]
      (db/exec-one! pool [sql chat-id])
      nil)))


(defmethod process-command "start"
  [{:keys [pool] :as cfg} {:keys [chat-id param] :as command}]
  (db/with-atomic [conn pool]
    (let [cid (ex/ignoring (b64us->uuid param))]
      (if (nil? cid)
        (reply-msg chat-id (str/format "Invalid id: '%s'" param))
        (let [contact (db/get-by-id conn :contact cid {:for-update true})]
          (cond
            (and (some? contact)
                 (nil? (:validated-at contact)))
            (let [params {:chat-title (:chat-title command)
                          :chat-id    (:chat-id command)
                          :chat-type  (:chat-type command)}]
              (db/update! conn :contact
                          {:is-disabled false
                           :is-paused false
                           :validated-at (dt/now)
                           :params (db/tjson params)}
                          {:id (:id contact)})
              (reply-msg chat-id (str/format "Contact '%s' ('%s') enabled."
                                             (:name contact) param)))

            (some? contact)
            (reply-msg chat-id (str/format "Contact '%s' is already activated" (:name contact)))

            :else
            (reply-msg chat-id (str/format "Contact '%s' does not exists." param))))))))

(declare retrieve-monitor-contacts)

(defmethod process-command "list"
  [{:keys [pool] :as cfg} {:keys [param chat-id] :as command}]
  (let [monitors (->> (retrieve-monitor-contacts pool chat-id)
                      (map #(str/format "%s / %s" (uuid->b64us (:id %)) (:monitor-name %)))
                      (interleave "\n"))
        text     (if (seq monitors)
                   (str "This is a list of monitors:\n" (apply str monitors))
                   (str "This chat does not belogns to any monitor."))]
    (reply-msg chat-id text)))

(defmethod process-command "unsubscribe"
  [{:keys [pool] :as cfg} {:keys [param chat-id] :as command}]
  (if (empty? param)
    (process-command cfg (assoc command :op "list"))
    (let [sid (ex/ignoring (b64us->uuid param))]
      (if (nil? sid)
        (reply-msg chat-id (str/format "Invalid id: '%s'" param))
        (db/with-atomic [conn pool]
          (let [items (retrieve-monitor-contacts conn chat-id)
                item  (d/seek #(= sid (:id %)) items)]
            (if item
              (do
                (db/delete! conn :monitor-contact-rel {:id (:id item)})
                (reply-msg chat-id (str/format "Unsubscribed from %s ('%s')."
                                               (:monitor-name item) param)))
              (reply-msg chat-id (str/format "No monitor subscription found with id: '%s'" param)))))))))

;; --- Helpers

(defn reply-msg
  [chat-id message]
  {"method" "sendMessage"
   "chat_id" chat-id
   "text" message})

(def sql:monitor-contacts
  "select s.id,
          m.name as monitor_name,
          c.id as contact_id,
          (c.params->>'~:chat-id')::bigint as chat_id
     from monitor_contact_rel as s
     join monitor as m on (m.id = s.monitor_id)
     join contact as c on (c.id = s.contact_id)
    where (c.params->>'~:chat-id')::bigint = ?
      and (c.type = 'telegram')")

(defn- retrieve-monitor-contacts
  [conn chat-id]
  (db/exec! conn [sql:monitor-contacts chat-id]))

(defn uuid->b64us
  [^UUID v]
  (let [buffer (ByteBuffer/allocate 16)]
    (.putLong buffer (.getMostSignificantBits v))
    (.putLong buffer (.getLeastSignificantBits v))
    (-> (bc/bytes->b64u (.array buffer))
        (bc/bytes->str))))

(defn b64us->uuid
  [^String v]
  (let [v (-> (bc/str->bytes v)
              (bc/b64u->bytes))
        b (ByteBuffer/wrap v)]
    (UUID. (.getLong b) (.getLong b))))
