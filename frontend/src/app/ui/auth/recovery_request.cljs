;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.auth.recovery-request
  (:require
   [app.common.spec :as us]
   [app.events :as ev]
   [app.store :as st]
   [app.ui.auth.notification]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.router :as r]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn- on-success
  [form]
  (let [msg "Recovery email sent."]
    (reset! form {})
    (st/emit! (ev/show-message {:content msg
                                :type :success
                                :timeout 2000}))))

(defn- on-submit
  [form event]
  (st/emit!
   (ev/request-profile-recovery
    (with-meta (:clean-data @form)
      {:on-success (partial on-success form)}))))

;; --- Recovery Request Page

(s/def ::recovery-request-form
  (s/keys :req-un [::us/email]))

(mf/defc recovery-request-page
  [{:keys [locale] :as props}]
  (let [form (fm/use-form :spec ::recovery-request-form :initial {})]
    [:section.login-layout
     [:div.recovery-request-form.form-container
      [:div.form-title
       [:h1 "Password recovery"]]

      [:& fm/form {:on-submit on-submit
                   :form  form}
       [:div.form-row
        [:& fm/input {:name :email
                      :label "Email"
                      :type "text"}]]

       [:div.form-row.submit-row
        [:& fm/submit-button
         {:label "Submit"}]]]

      [:div.form-links
       [:a {:on-click #(st/emit! (r/nav :auth-login))}
        "Go back to login"]]]]))
