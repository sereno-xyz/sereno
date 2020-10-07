;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.profile-password
  "A profile password modal impl."
  (:require
   [app.common.spec :as us]
   [app.events :as ev]
   [app.events.messages :as em]
   [app.store :as st]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(defn- password-equality
  [data]
  (let [password-1 (:password data)
        password-2 (:password-2 data)]

    (cond-> {}
      (and password-1 password-2 (not= password-1 password-2))
      (assoc :password-2 {:message "Password does not match."})

      (and password-1 (> 8 (count password-1)))
      (assoc :password {:message "Password too short"}))))

(s/def ::password ::us/not-empty-string)
(s/def ::password-2 ::us/not-empty-string)
(s/def ::old-password ::us/not-empty-string)

(s/def ::password-form
  (s/keys :req-un [::password
                   ::password-2]
          :opt-un [::old-password]))

(mf/defc password-change-modal
  {::mf/register modal/components
   ::mf/register-as :password-change}
  [{:keys [profile] :as props}]
  (let [on-close (st/emitf (modal/hide))
        form      (fm/use-form :spec ::password-form
                               :validators [password-equality]
                               :initial {})
        on-success
        (mf/use-callback
         (mf/deps form)
         (fn [form]
           (let [params {:type :success
                         :timeout 2000
                         :content "Password changed successfully."}]
             (st/emit! (modal/hide)
                       (ptk/event :retrieve-profile)
                       (em/show params)))))

        on-error
        (mf/use-callback
         (mf/deps form)
         (fn [err]
           (case (:code err)
             :old-password-not-match
             (swap! form assoc-in [:errors :old-password]
                    {:message "Incorrect password"})

             (let [params {:type :error
                           :timeout 4000
                           :content "Error on changing password"}]
               (st/emit! (ev/show-message params))))))

        on-submit
        (mf/use-callback
         (mf/deps form)
         (st/emitf (ev/update-profile-password
                    (with-meta (:clean-data @form)
                      {:on-success on-success
                       :on-error on-error}))))]

    [:div.modal-overlay
     [:div.modal.change-password-modal.form-container
      [:& fm/form {:on-submit on-submit
                   :form form}
       [:div.modal-header
        [:div.modal-header-title
         [:h2 "Change password"]]
        [:div.modal-close-button
         {:on-click on-close} i/times]]

       [:div.modal-content
        (when-not (:has-password profile)
         [:div.inline-notifications
          [:div.notification-item.opaque
           [:span "Your account does not have password. This is because
                   you are logged with external auth provider."]]])

        (when (:has-password profile)
          [:div.form-row
           [:& fm/input
            {:name :old-password
             :type "password"
             :disabled (boolean (:external-id profile))
             :label "Current password"}]])

        [:div.form-row
         [:& fm/input
          {:name :password
           :type "password"
           :disabled (boolean (:external-id profile))
           :label "New password"}]]

        [:div.form-row
         [:& fm/input
          {:name :password-2
           :type "password"
           :disabled (boolean (:external-id profile))
           :label "Repeat new password"}]]]

      [:div.modal-footer
       [:div.action-buttons
        [:& fm/submit-button
         {:label "Save"
          :disabled (boolean (:external-id profile))}]]]]]]))

