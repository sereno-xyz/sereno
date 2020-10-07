;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.auth.register
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.events :as ev]
   [app.events.messages :as em]
   [app.store :as st]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.router :as r]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn- on-error
  [form error]
  (if (and (= (:type error) :validation)
           (= (:code error) :email-already-exists))
    (swap! form assoc-in [:errors :email]
           {:message "Email already exists"})
    (rx/throw error)))


(defn- on-success
  [form]
  (let [msg (str/format "We have sent you an email to '%s' for verification."
                        (:email (:clean-data @form)))]
    (st/emit! (em/show {:content msg
                        :type :success
                        :timeout 5000}))
    (reset! form {})))

(defn- on-submit
  [form event]
  (let [params (:clean-data @form)
        mdata  {:on-error (partial on-error form)
                :on-success (partial on-success form)}]
    (st/emit! (ev/register (with-meta params mdata)))))


(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)
(s/def ::fullname ::us/not-empty-string)

(s/def ::register-form
  (s/keys :req-un [::fullname ::email ::password]))

(mf/defc register-page
  []
  (let [form (fm/use-form :spec ::register-form :initial {})]
    [:section.login-layout
     [:div.login-form.form-container

      [:div.form-title
       [:span.logo i/logo]
       [:h1 "Register"]]

      [:& fm/form {:on-submit on-submit
                   :form form}
       [:div.form-row
        [:& fm/input
         {:name :fullname
          :type "text"
          :tab-index "1"
          :label "Fullname"}]]

       [:div.form-row
        [:& fm/input
         {:name :email
          :type "text"
          :tab-index "2"
          :label "Email"}]]

       [:div.form-row
        [:& fm/input
         {:type "password"
          :name :password
          :tab-index "3"
          :label "Password"}]]

       [:div.form-row.submit-row
        [:& fm/submit-button
         {:label "Register"}]]]

      [:div.form-links
       [:a {:on-click #(st/emit! (r/nav :auth-login))}
        "Already have an account?"]]]]))
