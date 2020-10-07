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
   [app.store :as st]
   [app.repo :as rp]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.confirm :refer [confirm-dialog]]
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

(defmulti prepare-submit-data (fn [form] (get-in form [:data :type])))

(defmethod prepare-submit-data :default [_] (ex/raise :type :not-implemented))
(defmethod prepare-submit-data "email"
  [form]
  {:type "email"
   :id (get-in form [:clean-data :id])
   :is-paused (get-in form [:clean-data :is-paused])
   :name (get-in form [:clean-data :name])
   :params {:email (get-in form [:clean-data :email])}})

(defmethod prepare-submit-data "mattermost"
  [form]
  {:type "mattermost"
   :id (get-in form [:clean-data :id])
   :is-paused (get-in form [:clean-data :is-paused])
   :name (get-in form [:clean-data :name])
   :params {:uri (get-in form [:clean-data :uri])}})

(s/def ::email-contact-form
  (s/keys :req-un [::us/email ::name]))

(mf/defc email-contact-modal
  {::mf/register modal/components
   ::mf/register-as :email-contact}
  [{:keys [item] :as props}]
  (let [on-close (st/emitf (modal/hide))

        on-submit
        (mf/use-callback
         (fn [form]
           (let [params (prepare-submit-data @form)
                 params (with-meta params
                          {:on-success on-close})]
             (if item
               (st/emit! (ev/update-contact params))
               (st/emit! (ev/create-contact params))))))

        initial
        (mf/use-memo
         (mf/deps item)
         (fn []
           (if item
             {:id (:id item)
              :type (:type item)
              :name (:name item)
              :is-paused (:is-paused item)
              :email (get-in item [:params :email])}
             {:type "email"
              :is-paused false})))

        form (fm/use-form :spec ::email-contact-form
                          :initial initial)]

    [:div.modal-overlay
     [:div.modal.contact-modal.form-container
      [:& fm/form {:on-submit on-submit :form form}
       [:div.modal-header
        [:div.modal-header-title
         (if item
           [:h2 "Update email contact:"]
           [:h2 "Create email contact:"])]
        [:div.modal-close-button
         {:on-click on-close} i/times]]

       [:div.modal-content
        [:div.form-row
         [:& fm/input
          {:name :name
           :type "text"
           :label "Label"}]]

         [:div.form-row
          [:& fm/input
           {:name :email
            :type "text"
            :label "Email address"}]]]

       [:div.modal-footer
        [:div.action-buttons
         [:& fm/submit-button {:label "Submit"}]]]]]]))

(s/def ::mattermost-contact-form
  (s/keys :req-un [::us/uri ::name]))

(mf/defc mattermost-contact-modal
  {::mf/register modal/components
   ::mf/register-as :mattermost-contact}
  [{:keys [item] :as props}]
  (let [on-close (st/emitf (modal/hide))
        on-submit
        (mf/use-callback
         (fn [form]
           (let [params (prepare-submit-data @form)
                 params (with-meta params
                          {:on-success on-close})]
             (if item
               (st/emit! (ev/update-contact params))
               (st/emit! (ev/create-contact params))))))

        initial
        (mf/use-memo
         (mf/deps item)
         (fn []
           (if item
             {:id (:id item)
              :type (:type item)
              :name (:name item)
              :is-paused (:is-paused item)
              :uri (get-in item [:params :uri])}
             {:type "mattermost"
              :is-paused false})))

        form (fm/use-form :spec ::mattermost-contact-form
                          :initial initial)]

    [:div.modal-overlay
     [:div.modal.contact-modal.form-container
      [:& fm/form {:on-submit on-submit :form form}
       [:div.modal-header
        [:div.modal-header-title
         (if item
           [:h2 "Update Mattermost Webhook"]
           [:h2 "Create Mattermost Webhook"])]
        [:div.modal-close-button
         {:on-click on-close} i/times]]

       [:div.modal-content
        [:div.form-row
         [:& fm/input
          {:name :name
           :type "text"
           :label "Label"}]]

        [:div.form-row
         [:& fm/input
          {:name :uri
           :type "text"
           :label "Webhook URI"}]]]

       [:div.modal-footer
        [:div.action-buttons
         [:& fm/submit-button {:label "Submit"}]]]]]]))


(mf/defc contact-item
  [{:keys [item] :as props}]
  (let [toggle-paused
        (mf/use-callback
         (mf/deps item)
         (fn [event]
           (when-not (:is-disabled item)
             (st/emit! (-> (update item :is-paused not)
                           (ev/update-contact))))))

        edit
        (mf/use-callback
         (mf/deps item)
         (fn [event]
           (when-not (:is-disabled item)
             (case (:type item)
               "email" (modal/show! {::modal/type :email-contact :item item})
               "mattermost" (modal/show! {::modal/type :mattermost-contact :item item})))))

        delete
        (mf/use-callback
         (mf/deps item)
         (fn [event]
           (when-not (:is-disabled item)
             (let [message   (str/format "Do you want to delete '%s'?" (:name item))
                   on-accept (st/emitf (ev/delete-contact item))]
               (st/emit! (modal/show {:type :confirm
                                      :title "Deleting contact"
                                      :message message
                                      :accept-label "Delete contact"
                                      :on-accept on-accept}))))))]

    [:div.row {:class (dom/classnames
                       :paused   (:is-paused item)
                       :disabled (:is-disabled item))}
     [:div.type i/envelope]
     [:div.title {:title (:email item)} (:name item)]
     [:div.options
      (if (:is-paused item)
        [:a {:title "Enable" :on-click toggle-paused} i/play]
        [:a {:title "Pause / Disable" :on-click toggle-paused} i/pause])
      [:a {:title "Edit" :on-click edit} i/edit]
      [:a {:title "Delete" :on-click delete} i/trash-alt]]]))

(mf/defc contacts-section
  {::mf/wrap [mf/memo]}
  []
  (mf/use-effect #(st/emit! (ptk/event :fetch-contacts)))
  (let [contacts (mf/deref st/contacts-ref)]
    [:section.contacts-table
     [:div.table
      [:div.header.row
       [:div.type "Type"]
       [:div.title "Contact"]
       [:div.options]]
      [:div.rows
       (for [item (->> (vals contacts)
                       (sort-by :created-at))]
         [:& contact-item {:key (:id item)
                           :item item}])]]]))

(mf/defc options
  []
  (let [add-email-contact (mf/use-callback (st/emitf (modal/show {:type :email-contact})))
        add-mattermost-contact (mf/use-callback (st/emitf (modal/show {:type :mattermost-contact})))
        show-dropdown? (mf/use-state false)
        show-dropdown  (mf/use-callback #(reset! show-dropdown? true))
        hide-dropdown  (mf/use-callback #(reset! show-dropdown? false))]
    [:section.topside-options
     [:a.add-monitor {:on-click show-dropdown} i/plus
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
         [:div.text "Mattermost"]]]]]]))

(mf/defc contacts-page
  [props]
  (let [profile (mf/deref st/profile-ref)]
    [:section.contacts
     [:div.single-column-1200
      [:& options]
      [:& contacts-section]]]))
