;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.notifications
  (:require
   [app.events :as ev]
   [app.store :as st]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]

   [cljs.spec.alpha :as s]
   [app.common.spec :as us]
   [beicon.core :as rx]
   [potok.core :as ptk]))



(def +message-animation-timeout+ 600)

(s/def ::content ::us/not-empty-string)
(s/def ::type (s/or :kw keyword? :str string?))
(s/def ::timeout ::us/integer)

(s/def ::show
  (s/keys :req-un [::content ::type]
          :opt-un [::timeout]))

(defn hide
  []
  (ptk/reify ::hide
    ptk/UpdateEvent
    (update [_ state]
      (update state :message assoc :status :hide))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter (ptk/type? ::show) stream)]
        (->> (rx/of #(dissoc % :message))
             (rx/delay +message-animation-timeout+)
             (rx/take-until stoper))))))

(defn show
  [data]
  (us/assert ::show data)
  (ptk/reify ::show
    ptk/UpdateEvent
    (update [_ state]
      (let [message (cond-> (assoc data :status :visible)
                      (nil? (:timeout data))
                      (assoc :timeout 3000))]
        (assoc state :message message)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [message (get state :message)
            stoper  (rx/filter (ptk/type? ::show-message) stream)]
          (->> (rx/of (hide))
               (rx/delay (:timeout message))
               (rx/take-until stoper))))))



;; (mf/defc notification-modal
;;   [{:keys [message] :as props}]
;;   (let [close #(modal/hide!)]
;;     [:div.modal-overlay.primary
;;      [:div.modal-content-simple
;;       [:span message]
;;       [:a.close {:on-click close} "close"]]]))

(defn- type->icon
  [type]
  (case type
    :warning i/exclamation
    :error i/bomb
    :success i/check
    :info i/info))

(mf/defc notification-item
  [{:keys [type status on-close quick? content] :as props}]
  (let [klass (dom/classnames
               :success (= type :success)
               :error   (= type :error)
               :info    (= type :info)
               :warning (= type :warning)
               :hide    (= status :hide)
               :quick   quick?)]
    [:section.banner {:class klass}
     [:div.content
      [:div.icon (type->icon type)]
      [:span content]]
     [:div.close {:on-click on-close} i/times]]))

(mf/defc notifications
  []
  (let [message  (mf/deref st/message-ref)
        on-close #(st/emit! ev/hide-message)]
    (when message
      [:& notification-item {:type (:type message)
                             :quick? (boolean (:timeout message))
                             :status (:status message)
                             :content (:content message)
                             :on-close on-close}])))



