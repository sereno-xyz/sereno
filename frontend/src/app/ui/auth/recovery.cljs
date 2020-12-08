;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.auth.recovery
  (:require
   [app.common.spec :as us]
   [app.events :as ev]
   [app.store :as st]
   [app.ui.auth.notification]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.router :as r]
   [app.util.timers :as ts]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(s/def ::password-1 ::us/not-empty-string)
(s/def ::password-2 ::us/not-empty-string)
(s/def ::token ::us/not-empty-string)

(s/def ::recovery-form
  (s/keys :req-un [::password-1
                   ::password-2]))

(defn- password-equality
  [data]
  (let [password-1 (:password-1 data)
        password-2 (:password-2 data)]
    (cond-> {}
      (and password-1 password-2
           (not= password-1 password-2))
      (assoc :password-2 {:message "errors.password-invalid-confirmation"})

      (and password-1 (> 8 (count password-1)))
      (assoc :password-1 {:message "errors.password-too-short"}))))

(defn- on-success
  [form rsp]
  (let [msg "Password changed successfully!"]
    (reset! form {})
    (st/emit! (r/nav :auth-login)
              (ev/show-message {:content msg
                                :timeout 2000
                                :type :success}))))

(defn- on-error
  [form error]
  (let [msg "Invalid token!"]
    (ev/show-message {:content msg :type :error})))

(defn- on-submit
  [form event]
  (st/emit!
   (ev/recover-profile
    (with-meta {:token (get-in @form [:clean-data :token])
                :password (get-in @form [:clean-data :password-2])}
      {:on-error (partial on-error form)
       :on-success (partial on-success form)}))))

;; --- Recovery Request Page

(mf/defc recovery-page
  [{:keys [locale params] :as props}]
  (let [form (fm/use-form :spec ::recovery-form
                          :validators [password-equality]
                          :initial params)]
    [:section.login-layout
     [:div.recovery-request-form.form-container
      [:div.form-title
       [:h1 "Profile recovery"]]

      [:& fm/form {:on-submit on-submit
                   :form form}
       [:div.form-row
        [:& fm/input {:type "password"
                      :name :password-1
                      :label "New password"}]]

       [:div.form-row
        [:& fm/input {:type "password"
                      :name :password-2
                      :label "Repeat password"}]]

       [:div.form-row.submit-row
        [:& fm/submit-button
         {:label "Submit"}]]]

      [:div.form-links
       [:a {:on-click (st/emitf (r/nav :auth-login))}
        "Go back to login"]]]]))



