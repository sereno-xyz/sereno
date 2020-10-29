;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.contacts
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.events.messages :as em]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.confirm :refer [confirm-dialog]]
   [app.ui.contacts.email]
   [app.ui.contacts.mattermost]
   [app.ui.contacts.telegram]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.router :as r]
   [app.util.time :as tm]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(mf/defc contact-item
  [{:keys [contact] :as props}]
  (let [toggle-paused
        (mf/use-callback
         (mf/deps contact)
         (fn [event]
           (when-not (:is-disabled contact)
             (st/emit! (-> (update contact :is-paused not)
                           (ev/update-contact))))))

        edit
        (mf/use-callback
         (mf/deps contact)
         (fn [event]
           (case (:type contact)
             "email" (modal/show! {:type :email-contact :id (:id contact)})
             "mattermost" (modal/show! {:type :mattermost-contact :id (:id contact)})
             "telegram" (modal/show! {:type :telegram-contact :id (:id contact)}))))

        delete
        (mf/use-callback
         (mf/deps contact)
         (fn [event]
           (when-not (:is-disabled contact)
             (let [message   (str/format "Do you want to delete '%s'?" (:name contact))
                   on-accept (st/emitf (ev/delete-contact contact))]
               (st/emit! (modal/show {:type :confirm
                                      :title "Deleting contact"
                                      :message message
                                      :accept-label "Delete contact"
                                      :on-accept on-accept}))))))]

    [:div.row {:class (dom/classnames
                       :disabled (not (:validated-at contact))
                       :paused   (:is-paused contact)
                       :disabled (:is-disabled contact))}
     [:div.type (case (:type contact)
                  "email" i/envelope
                  "mattermost" i/mattermost
                  "telegram" i/telegram
                  nil)]

     [:div.title {:title (:email contact)} (:name contact)]
     [:div.options
      (if (:validated-at contact)
        (if (:is-paused contact)
          [:a {:title "Enable" :on-click toggle-paused} i/play]
          [:a {:title "Pause / Disable" :on-click toggle-paused} i/pause])
        [:span.warning {:title "Contact pending validation"} i/exclamation])

      (cond
        (some? (:disable-reason contact))
        (let [reason (:pause-reason contact)
              type   (:type reason)]
          [:span.warning
           {:title (str/format "Disable reason: %s | Incidence num: %s"
                               (name type) (:bounces contact))}
           i/exclamation])

        (some? (:pause-reason contact))
        (let [reason (:pause-reason contact)
              type   (:type reason)]
          [:span.warning
           {:title (str/format "Pause reason: %s" (name type))}
           i/exclamation]))

      [:a {:title "Edit" :on-click edit} i/edit]
      [:a {:title "Delete" :on-click delete} i/trash-alt]]]))

(mf/defc contacts-section
  {::mf/wrap [mf/memo]}
  []
  (mf/use-effect
   (fn []
     (st/emit! (ptk/event :initialize-contacts))
     (st/emitf (ptk/event :finalize-contacts))))
  (let [contacts (mf/deref st/contacts-ref)]
    [:section.contacts-table-container
     [:div.contacts-table
      [:div.header.row
       [:div.type "Type"]
       [:div.title "Contact"]
       [:div.options]]
      [:div.rows
       (for [contact (->> (vals contacts)
                          (sort-by :created-at))]
         [:& contact-item {:key (:id contact)
                           :contact contact}])]]]))

(mf/defc options
  []
  (let [add-email-contact (mf/use-callback (st/emitf (modal/show {:type :email-contact})))
        add-mattermost-contact (mf/use-callback (st/emitf (modal/show {:type :mattermost-contact})))
        add-telegram-contact (mf/use-callback (st/emitf (modal/show {:type :telegram-contact})))
        show-dropdown? (mf/use-state false)
        show-dropdown  (mf/use-callback #(reset! show-dropdown? true))
        hide-dropdown  (mf/use-callback #(reset! show-dropdown? false))]
    [:section.options-bar
     [:a.add-button {:on-click show-dropdown} i/plus
      [:& dropdown {:show @show-dropdown?
                    :on-close hide-dropdown}
       [:ul.dropdown
        [:li {:on-click add-email-contact
              :title "Add new email contact"}
         [:div.icon i/envelope]
         [:div.text "Email"]]
        [:li {:on-click add-mattermost-contact
              :title "Add new mattermost contact"}
         [:div.icon i/mattermost]
         [:div.text "Mattermost"]]
        [:li {:on-click add-telegram-contact
              :title "Add new telegram contact"}
         [:div.icon i/telegram]
         [:div.text "Telegram"]]]]]]))

(mf/defc contacts-page
  [props]
  (let [profile (mf/deref st/profile-ref)]
    [:section.contacts
     [:div.single-column-1200
      [:& options]
      [:& contacts-section]]]))
