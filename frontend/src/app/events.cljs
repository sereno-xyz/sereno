;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.events
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [app.config :as cfg]
   [app.common.spec :as us]
   [app.common.exceptions :as ex]
   [app.common.data :as d]
   [app.events.messages :as em]
   [app.repo :as rp]
   [app.store :as st :refer [initial-state]]
   [app.util.storage :refer [storage]]
   [app.util.router :as r]
   [app.util.i18n :as i18n]
   [app.util.transit :as t]
   [app.util.time :as dt]
   [app.util.websockets :as ws]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]))

(def re-throw #(rx/throw %))
(declare logged-in)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Profile Related Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)

(s/def ::login-params
  (s/keys :req-un [::email ::password]))

(defmethod ptk/resolve :login
  [type {:keys [email password] :as data}]
  (us/verify ::login-params data)
  (ptk/reify ::login
    ptk/UpdateEvent
    (update [_ state]
      (merge state (dissoc initial-state :route :router)))

    ptk/WatchEvent
    (watch [this state s]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta data)
            params {:email email
                    :password password}]
        (->> (rx/timer 100)
             (rx/mapcat #(rp/req! :login params))
             (rx/tap on-success)
             (rx/map logged-in)
             (rx/catch on-error))))))

(defn logged-in
  [data]
  (ptk/reify ::logged-in
    ptk/WatchEvent
    (watch [this state stream]
      (rx/of (ptk/event :retrieve-profile)
             (r/nav :monitors)))))


(s/def ::fullname ::us/not-empty-string)
(s/def ::created-at ::us/inst)
(s/def ::lang ::us/not-empty-string)

(s/def ::profile
  (s/keys :req-un [::id]
          :opt-un [::created-at
                   ::fullname
                   ::email
                   ::lang]))

(defn profile-retrieved
  [{:keys [fullname] :as data}]
  (us/verify ::profile data)
  (ptk/reify ::profile-retrieved
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :profile
             (cond-> data
               (nil? (:lang data))
               (assoc :lang cfg/default-language))))

    ptk/EffectEvent
    (effect [_ state stream]
      (let [profile (:profile state)]
        (swap! storage assoc :profile profile)
        (i18n/set-current-locale! (:lang profile))))))

(def clear-user-data
  (ptk/reify ::clear-user-data
    ptk/UpdateEvent
    (update [_ state]
      (select-keys state [:route :router :session-id :history]))

    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/req! :logout)
           (rx/ignore)))

    ptk/EffectEvent
    (effect [_ state s]
      (reset! storage {})
      (i18n/set-default-locale!))))

(def logout
  (ptk/reify :logout
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of clear-user-data
             (r/nav :auth-login)))))

(s/def ::register
  (s/keys :req-un [::fullname
                   ::password
                   ::email]))

(defn register
  "Create a register event instance."
  [data]
  (s/assert ::register data)
  (ptk/reify ::register
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta data)]
        (->> (rp/req! :register-profile data)
             (rx/tap on-success)
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty))))))))


;; --- Profile Recovery Request (Password)

(s/def ::request-profile-recovery
  (s/keys :req-un [::email]))

(defn request-profile-recovery
  [data]
  (us/verify ::request-profile-recovery data)
  (ptk/reify ::request-profile-recovery
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta data)]

        (->> (rp/req! :request-profile-recovery data)
             (rx/tap on-success)
             (rx/catch (fn [err]
                         (on-error err))))))))

;; --- Profile Recovery (Password)

(s/def ::token string?)
(s/def ::recover-profile
  (s/keys :req-un [::password ::token]))

(defn recover-profile
  [{:keys [token password] :as data}]
  (us/verify ::recover-profile data)
  (ptk/reify ::recover-profile
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta data)]
        (->> (rp/req! :recover-profile data)
             (rx/tap on-success)
             (rx/catch (fn [err]
                         (on-error)
                         (rx/empty))))))))

(defmethod ptk/resolve :cancel-email-change
  [_ params]
  (ptk/reify :cancel-email-change
    ptk/WatchEvent
    (watch [_ status stream]
      (->> (rp/req! :cancel-email-change-process {})
           (rx/map #(ptk/event :retrieve-profile))))))

;; --- Profile Fetched


(defmethod ptk/resolve :retrieve-profile
  [_ params]
  (ptk/reify :retrieve-profile
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/qry! :retrieve-profile)
           (rx/map profile-retrieved)))))

;; --- Update Profile

(s/def ::update-profile
  (s/keys :req-un [::fullname]))

(defmethod ptk/resolve :update-profile
  [_ params]
  (us/verify ::update-profile params)
  (ptk/reify ::update-profile
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :update-profile params)
             (rx/tap on-success)
             (rx/map #(ptk/event :retrieve-profile))
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty))))))))


;; --- Request Email Change

(defmethod ptk/resolve :request-email-change
  [_ {:keys [email] :as data}]
  (us/assert ::us/email email)
  (ptk/reify ::request-email-change
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta data)]
        (->> (rp/req! :request-email-change data)
             (rx/tap on-success)
             (rx/map #(ptk/event :retrieve-profile))
             (rx/catch (fn [err]
                         (on-error err))))))))

;; --- Update Profile Password

(s/def ::old-password ::us/not-empty-string)

(s/def ::update-profile-password
  (s/keys :req-un [::password]
          :opt-un [::old-password]))

(defn update-profile-password
  [params]
  (us/verify ::update-profile-password params)
  (ptk/reify ::update-profile-password
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :update-profile-password params)
             (rx/tap on-success)
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Contacts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Fetch Contacts

(defmethod ptk/resolve :fetch-contacts
  [_ _]
  (letfn [(fetched [contacts state]
            (assoc state :contacts (d/index-by :id contacts)))]
    (ptk/reify ::fetch-contacts
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/qry! :retrieve-contacts)
             (rx/map #(partial fetched %)))))))

(s/def ::create-email-contact
  (s/keys :req-un [::name ::us/email]))

(defn create-email-contact
  [params]
  (us/assert ::create-email-contact params)
  (ptk/reify ::create-email-contact
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :create-email-contact params)
             (rx/tap on-success)
             (rx/map #(ptk/event :fetch-contacts))
             (rx/catch on-error))))))

(s/def ::create-mattermost-contact
  (s/keys :req-un [::name ::us/uri]))

(defn create-mattermost-contact
  [params]
  (s/assert ::create-mattermost-contact params)
  (ptk/reify ::create-mattermost-contact
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :create-mattermost-contact params)
             (rx/tap on-success)
             (rx/map #(ptk/event :fetch-contacts))
             (rx/catch on-error))))))

(s/def ::create-discord-contact
  (s/keys :req-un [::name ::us/uri]))

(defn create-discord-contact
  [params]
  (s/assert ::create-discord-contact params)
  (ptk/reify ::create-discord-contact
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :create-discord-contact params)
             (rx/tap on-success)
             (rx/map #(ptk/event :fetch-contacts))
             (rx/catch on-error))))))

(s/def ::create-telegram-contact
  (s/keys :req-un [::name]))

(defn create-telegram-contact
  [params]
  (s/assert ::create-telegram-contact params)
  (ptk/reify ::create-telegram-contact
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :create-telegram-contact params)
             (rx/tap on-success)
             (rx/map #(ptk/event :fetch-contacts))
             (rx/catch on-error))))))

(s/def ::is-paused ::us/boolean)
(s/def ::update-contact
  (s/keys :req-un [::id ::name ::is-paused]))

(defn update-contact
  [{:keys [id] :as params}]
  (s/assert ::update-contact params)
  (ptk/reify ::update-contact
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:contacts id]
                 (fn [contact]
                   (merge contact params))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :update-contact params)
             (rx/tap on-success)
             (rx/catch on-error))))
    ))

(defn delete-contact
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-contact
    ptk/UpdateEvent
    (update [_ state]
      (update state :contacts dissoc id))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :delete-contact {:id id})
             (rx/tap on-success)
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Notifications & Banners
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def show-message em/show)
(def hide-message (em/hide))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Monitors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ptk/resolve :fetch-monitors
  [_ params]
  (ptk/reify :fetch-monitors
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/qry! :retrieve-monitors)
           (rx/map (fn [result]
                     #(assoc % :monitors (d/index-by :id result))))))))

(defmethod ptk/resolve :fetch-monitor
  [_ {:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::fetch-monitor
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/qry! :retrieve-monitor {:id id})
           (rx/map (fn [monitor]
                     #(assoc-in % [:monitors id] monitor)))))))

(s/def ::name string?)
(s/def ::cadence ::us/integer)
(s/def ::contacts (s/coll-of ::us/uuid :min-count 1))
(s/def ::tags (s/coll-of ::us/string :kind set?))

(s/def ::uri string?)
(s/def ::headers (s/nilable (s/map-of string? string?)))
(s/def ::should-include ::us/string)
(s/def ::method ::us/keyword)

(s/def ::create-http-monitor
  (s/keys :req-un [::name ::cadence ::contacts ::method ::uri]
          :opt-un [::tags ::should-include ::headers]))

(defmethod ptk/resolve :create-http-monitor
  [_ params]
  (us/assert ::create-http-monitor params)
  (ptk/reify ::create-http-monitor
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error re-throw}} (meta params)]
        (->> (rp/req! :create-http-monitor params)
             (rx/tap on-success)
             (rx/map #(ptk/event :fetch-monitors))
             (rx/catch on-error))))))

(s/def ::alert-before (s/and ::us/integer pos?))

(s/def ::create-ssl-monitor
  (s/keys :req-un [::name ::contacts ::uri ::alert-before]
          :opt-un [::tags]))

(defmethod ptk/resolve :create-ssl-monitor
  [_ params]
  (us/assert ::create-ssl-monitor params)
  (ptk/reify ::create-ssl-monitor
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error re-throw}} (meta params)]
        (->> (rp/req! :create-ssl-monitor params)
             (rx/tap on-success)
             (rx/map #(ptk/event :fetch-monitors))
             (rx/catch on-error))))))


(s/def ::grace-time ::us/integer)
(s/def ::create-healthcheck-monitor
  (s/keys :req-un [::name ::cadence ::contacts ::grace-time]
          :opt-un [::tags]))

(defmethod ptk/resolve :create-healthcheck-monitor
  [_ params]
  (us/assert ::create-healthcheck-monitor params)
  (ptk/reify ::create-healthcheck-monitor
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error re-throw}} (meta params)]
        (->> (rp/req! :create-healthcheck-monitor params)
             (rx/tap on-success)
             (rx/map #(ptk/event :fetch-monitors))
             (rx/catch on-error))))))

(s/def ::update-http-monitor
  (s/keys :req-un [::id ::name ::cadence ::contacts ::method ::uri]
          :opt-un [::tags ::should-include ::headers]))

(defmethod ptk/resolve :update-http-monitor
  [_ {:keys [id] :as params}]
  (us/assert ::update-http-monitor params)
  (ptk/reify ::update-http-monitor
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :update-http-monitor params)
             (rx/tap on-success)
             (rx/map #(ptk/event :fetch-monitors))
             (rx/catch (fn [error]
                         (or (on-error error)
                             (rx/empty)))))))))


(s/def ::update-ssl-monitor
  (s/keys :req-un [::id ::name ::contacts ::uri ::alert-before]
          :opt-un [::tags]))

(defmethod ptk/resolve :update-ssl-monitor
  [_ {:keys [id] :as params}]
  (us/assert ::update-ssl-monitor params)
  (ptk/reify ::update-ssl-monitor
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :update-ssl-monitor params)
             (rx/tap on-success)
             (rx/map #(ptk/event :fetch-monitors))
             (rx/catch (fn [error]
                         (or (on-error error)
                             (rx/empty)))))))))


(s/def ::update-healthcheck-monitor
  (s/keys :req-un [::id ::name ::cadence ::contacts ::grace-time]
          :opt-un [::tags]))

(defmethod ptk/resolve :update-healthcheck-monitor
  [_ {:keys [id] :as params}]
  (us/assert ::update-healthcheck-monitor params)
  (ptk/reify ::update-healthcheck-monitor
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :update-healthcheck-monitor params)
             (rx/tap on-success)
             (rx/map #(ptk/event :fetch-monitors))
             (rx/catch on-error))))))


(defn delete-monitor
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-monitor
    ptk/UpdateEvent
    (update [_ state]
      (update state :monitors dissoc id))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/req! :delete-monitor {:id id})
           (rx/map #(ptk/event :fetch-monitors))))))

(defn pause-monitor
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::pause-monitor
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:monitors id] assoc :status "paused"))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :pause-monitor {:id id})
             (rx/tap on-success)
             (rx/map (constantly ::monitor-paused))
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty))))))))

(defn resume-monitor
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::resume-monitor
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:monitors id] assoc :status "started"))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-error re-throw
                  on-success identity}} (meta params)]
        (->> (rp/req! :resume-monitor {:id id})
             (rx/tap on-success)
             (rx/map (constantly ::monitor-started))
             (rx/catch on-error))))))

(defmethod ptk/resolve :fetch-monitor-detail
  [_ {:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (letfn [(on-fetched [data state]
            (update-in state [:monitor-detail id]
                       (fn [detail]
                         (d/merge detail data))))]
    (ptk/reify :fetch-monitor-detail
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/qry! :retrieve-monitor-detail {:id id})
             (rx/map #(partial on-fetched %)))))))

(defmethod ptk/resolve :fetch-monitor-status-history
  [_ {:keys [id since limit]
      :or {since (dt/now)
           limit 20}
      :as params}]
  (us/assert ::us/uuid id)
  (letfn [(load-more? [result]
            (cond
              (zero? (count result)) false
              (= 0 (mod (count result) limit)) true
              :else false))

          (on-fetched [items state]
            (let [more?   (load-more? items)
                  last-dt (:created-at (last items))]
              (update-in state [:monitor-status-history id]
                         (fn [data]
                           (as-> data $
                             (if (:brief params)
                               (assoc $ :items (d/index-by :id items))
                               (update $ :items merge (d/index-by :id items)))
                             (assoc $ :load-more-since last-dt :load-more more?))))))]

    (ptk/reify ::fetch-monitor-status-history
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/qry! :retrieve-monitor-status-history
                      {:id id
                       :since since
                       :limit limit})
             (rx/map #(partial on-fetched %)))))))

(defmethod ptk/resolve :load-more-status-history
  [_ {:keys [id]}]
  (ptk/reify ::load-more-status-history
    ptk/WatchEvent
    (watch [_ state stream]
      (let [since (get-in state [:monitor-status-history id :load-more-since])]
        (rx/of (ptk/event :fetch-monitor-status-history {:id id :since since}))))))


(defmethod ptk/resolve :fetch-monitor-logs
  [_ {:keys [id since limit]
      :or {since (dt/now)
           limit 20}
      :as params}]
  (us/assert ::us/uuid id)
  (letfn [(load-more? [result]
            (cond
              (zero? (count result)) false
              (= 0 (mod (count result) limit)) true
              :else false))

          (on-fetched [items state]
            (let [more?   (load-more? items)
                  last-dt (:created-at (last items))
                  id-fn   #(hash-set (:monitor-id %) (inst-ms (:created-at %)))]
              (update-in state [:monitor-logs id]
                         (fn [data]
                           (as-> data $
                             (if (:brief params)
                               (assoc $ :items (d/index-by id-fn items))
                               (update $ :items merge (d/index-by id-fn items)))
                             (assoc $ :load-more-since last-dt :load-more more?))))))]

    (ptk/reify ::fetch-monitor-logs
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/qry! :retrieve-monitor-logs
                      {:id id
                       :since since
                       :limit limit})
             (rx/map #(partial on-fetched %)))))))

(defmethod ptk/resolve :load-more-logs
  [_ {:keys [id]}]
  (ptk/reify ::load-more-logs
    ptk/WatchEvent
    (watch [_ state stream]
      (let [since (get-in state [:monitor-logs id :load-more-since])]
        (rx/of (ptk/event :fetch-monitor-logs {:id id :since since}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialization Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- filter-update-messages-from-websocket
  [stream id]
  (->> stream
       (rx/filter (ptk/type? ::websocket-message))
       (rx/map deref)
       (rx/filter #(= (:metadata/channel %) "db_changes"))
       (rx/filter #(= (:database/table %) "monitor"))
       (rx/filter #(= (:database/operation %) :update))
       (rx/filter #(= id (:id %)))))

(defmethod ptk/resolve :init-monitor-page
  [_ {:keys [id] :as params}]
  (ptk/reify :init-monitor-page
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter (ptk/type? :stop-monitor-page) stream)]
        (rx/concat
         (rx/of (ptk/event :fetch-monitor params)
                (ptk/event :fetch-contacts))
         (->> (filter-update-messages-from-websocket stream id)
              (rx/map #(ptk/event :fetch-monitor params))
              (rx/take-until stoper)))))))

(defmethod ptk/resolve :init-monitor-detail-section
  [_ {:keys [id] :as params}]
  (ptk/reify :init-monitor-detail-section
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter (ptk/type? :stop-monitor-detail-section) stream)]
        (rx/concat
         (rx/of (ptk/event :fetch-monitor-detail {:id id}))
         (->> (filter-update-messages-from-websocket stream id)
              (rx/map #(ptk/event :fetch-monitor-detail params))
              (rx/take-until stoper)))))))

(defmethod ptk/resolve :init-monitor-status-history
  [_ {:keys [id] :as params}]
  (ptk/reify :init-monitor-status-history
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter (ptk/type? :stop-monitor-status-history) stream)
            params (select-keys params [:id :brief :limit])]
        (rx/merge
         (rx/of (ptk/event :fetch-monitor-status-history params))
         (->> stream
              (rx/filter #(or (= ::monitor-started %)
                              (= ::monitor-paused %)))
              (rx/map #(ptk/event :fetch-monitor-status-history params))
              (rx/take-until stoper))
         (->> stream
              (rx/filter (ptk/type? ::websocket-message))
              (rx/map deref)
              (rx/filter #(= (:metadata/channel %) "db_changes"))
              (rx/filter #(= (:database/table %) "monitor"))
              (rx/filter #(= (:database/operation %) :update))
              (rx/filter #(= id (:id %)))
              (rx/map #(ptk/event :fetch-monitor-status-history params))
              (rx/take-until stoper)))))))

(defmethod ptk/resolve :init-monitor-logs
  [_ {:keys [id] :as params}]
  (ptk/reify :init-monitor-logs
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter (ptk/type? :stop-monitor-logs) stream)
            params (select-keys params [:id :brief :limit])]
        (rx/merge
         (rx/of (ptk/event :fetch-monitor-logs params))
         (->> (filter-update-messages-from-websocket stream id)
              (rx/map #(ptk/event :fetch-monitor-logs params))
              (rx/take-until stoper)))))))

(defmethod ptk/resolve :stop-monitor-logs
  [_ {:keys [id] :as params}]
  (ptk/reify :stop-monitor-logs
    ptk/UpdateEvent
    (update [_ state]
      (update state :monitor-logs dissoc id))))


(defmethod ptk/resolve :initialize-monitor-list
  [_ params]
  (ptk/reify ::initialize-monitor-list
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter (ptk/type? :finalize-monitor-list) stream)]
        (rx/merge
         (rx/of (ptk/event :fetch-monitors)
                (ptk/event :fetch-contacts))

         (->> stream
              (rx/filter (ptk/type? ::websocket-message))
              (rx/map deref)
              (rx/filter #(= (:metadata/channel %) "db_changes"))
              (rx/filter #(= (:database/table %) "monitor"))
              (rx/map (fn [{:keys [id] :as message}]
                        (case (:database/operation message)
                          (:update :insert)
                          (ptk/event :fetch-monitor {:id id})

                          :delete
                          #(update % :monitors dissoc id))))
              (rx/take-until stoper)))))))


(defmethod ptk/resolve :initialize-contacts
  [_ params]
  (ptk/reify :initialize-contacts
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter (ptk/type? :finalize-contacts) stream)]
        (rx/merge
         (rx/of (ptk/event :fetch-contacts))
         (->> stream
              (rx/filter (ptk/type? ::websocket-message))
              (rx/map deref)
              (rx/filter #(= (:metadata/channel %) "db_changes"))
              (rx/filter #(= (:database/table %) "contact"))
              (rx/map (fn [{:keys [id] :as message}]
                        (case (:database/operation message)
                          (:update :insert)
                          (ptk/event :fetch-contacts)

                          :delete
                          #(update % :contacts dissoc id))))
              (rx/take-until stoper)))))))


(defmethod ptk/resolve :initialize-websocket
  [_ params]
  (ptk/reify ::initialize-websocket
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stp (rx/filter #(= % ::finalize-websocket) stream)
            ws  (-> (ws/uri "/ws/notifications")
                    (ws/websocket))]
        (->> ws
             (rx/map t/decode)
             (rx/filter #(= :message (:type %)))
             (rx/map :payload)
             (rx/map #(ptk/data-event ::websocket-message %))
             (rx/take-until stp))))))

