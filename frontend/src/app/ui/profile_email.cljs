;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.profile-email
  (:require
   [app.common.spec :as us]
   [app.events :as ev]
   [app.store :as st]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(s/def ::email-1 ::us/email)
(s/def ::email-2 ::us/email)

(defn- email-equality
  [data]
  (let [email-1 (:email-1 data)
        email-2 (:email-2 data)]
    (cond-> {}
      (and email-1 email-2 (not= email-1 email-2))
      (assoc :email-2 {:message (tr "errors.email-invalid-confirmation")}))))

(s/def ::email-change-form
  (s/keys :req-un [::email-1 ::email-2]))

(defn- on-error
  [form error]
  (if (= (:code error) :email-already-exists)
    (swap! form (fn [data]
                  (let [error {:message (tr "errors.email-already-exists")}]
                    (assoc-in data [:errors :email-1] error))))
    (rx/throw error)))

(defn- on-success
  [form]
  (st/emit! (ev/show-message
             {:content "Verification email sent."
              :type :success})))

(defn- on-submit
  [form event]
  (let [email (get-in @form [:clean-data :email-1])
        data  (with-meta {:email email}
                {:on-error (partial on-error form)
                 :on-success (partial on-success form)})]
    (st/emit! (ptk/event :request-email-change data))
    (modal/hide!)))

(mf/defc email-change-modal
  {::mf/register modal/components
   ::mf/register-as :email-change}
  [props]
  (let [locale   (mf/deref i18n/locale)
        profile  (mf/deref st/profile-ref)
        on-close (st/emitf (modal/hide))
        form     (fm/use-form :spec ::email-change-form
                              :validators [email-equality]
                              :initial {})]
    [:div.modal-overlay
     [:div.modal.email-change-modal.form-container
      [:& fm/form {:form form
                   :on-submit on-submit}
       [:div.modal-header
        [:div.modal-header-title
         [:h2 "Request email change"]]
        [:div.modal-close-button
         {:on-click on-close} i/times]]

       [:div.modal-content
        [:div.form-row
         [:& fm/input {:type "text"
                       :name :email-1
                       :label "New email:"
                       :trim true}]]

        [:div.form-row
         [:& fm/input {:type "text"
                       :name :email-2
                       :label "Repeat new email:"
                       :trim true}]]]


       [:div.modal-footer
        [:div.action-buttons
         [:& fm/submit-button
          {:label "Request email change"}]]]]]]))
