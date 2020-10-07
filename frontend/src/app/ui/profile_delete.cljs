;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.profile-delete
  "A profile deletion modal impl."
  (:require
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.events :as ev]
   [app.events.messages :as em]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(mf/defc delete-profile-modal
  {::mf/register modal/components
   ::mf/register-as :delete-profile-confirmation}
  [props]
  (let [on-close (st/emitf (modal/hide))
        password (mf/use-state "")
        error    (mf/use-state nil)
        submit
        (fn [event]
          (->> (rp/req! :delete-profile {:password @password})
               (rx/subs (fn [resp]
                          (st/emit! ev/logout)
                          (ts/schedule 100 (fn []
                                             (modal/hide!)
                                             (st/emit! (em/show {:type :success
                                                                 :content "Profile deleted."
                                                                 :timeout 5000})))))
                        (fn [err]
                          (if (= (:type err) :validation)
                            (reset! error (:code err))
                            (st/emit! (em/show {:contact "Unexpected error."
                                                :type :error
                                                :timeout 1000})))))))]

    [:div.modal-overlay
     [:div.modal.confirm-dialog.form-container
      [:div.modal-header
       [:div.modal-header-title
        [:h2 "Delete your account"]]
       [:div.modal-close-button
        {:on-click on-close} i/times]]
      [:div.modal-content
       [:p [:strong "Are you sure you want to delete your account?"]]
       [:p
        "Deleting your account will delete all your associated data
        such as your monitors, contacts and all historical data. You
        cannot undo this operation."]

       [:div.form-row
        [:input {:type "password"
                 :value @password
                 :placeholder "Type your password here to confirm"
                 :on-change (fn [e]
                              (reset! error nil)
                              (->> (dom/get-target e)
                                   (dom/get-value)
                                   (reset! password)))}]
        (when (= :wrong-credentials @error)
          [:span.error "Wrong password"])]]

      [:div.modal-footer
       [:div.action-buttons
        [:a.accept-button
         {:on-click (when-not (empty? @password) submit)
          :class (dom/classnames :disabled (empty? @password))}
         "Permanently delete"]
        [:a.cancel-button
         {:on-click #(modal/hide!)} "Cancel"]]]]]))

