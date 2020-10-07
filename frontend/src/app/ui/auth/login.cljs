;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.auth.login
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.events :as ev]
   [app.events.messages :as em]
   [app.store :as st]
   [app.repo :as rp]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.router :as r]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(defn- login-with-google
  []
  (->> (rp/req! :gauth {})
       (rx/subs (fn [{:keys [redirect-uri] :as rsp}]
                  (.replace js/location redirect-uri)))))

(defn- on-error
  [form error]
  (cond
    (and (= (:type error) :validation)
         (= (:code error) :wrong-credentials))
    (swap! form assoc-in [:errors :password]
           {:message "Email o password incorrect."})

    (and (= (:type error) :validation)
         (= (:code error) :email-not-validated))
    (swap! form assoc-in [:errors :email]
           {:message "Email not validated."})

    (and (= (:type error) :validation)
         (= (:code error) :account-without-password))
    (rx/of (em/show {:content "Account without password."
                     :type :error
                     :timeout 2000}))

    :else
    (throw error)))

(defn- on-submit
  [form event]
  (let [params (with-meta (:clean-data @form)
                 {:on-error (partial on-error form)})]
    (st/emit! (ptk/event :login params))))

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)

(s/def ::login-form
  (s/keys :req-un [::email ::password]))

(mf/defc login-page
  [props]
  (let [form (fm/use-form :spec ::login-form
                          :initial {})]
    [:section.login-layout
     [:div.login-form.form-container

      [:div.form-title
       [:span.logo i/logo]
       [:h1 "Sign In"]]

      [:& fm/form {:on-submit on-submit
                   :form form}

       [:div.form-row
        [:& fm/input
         {:name :email
          :type "text"
          :tab-index "1"
          :label "Email"}]]

       [:div.form-row
        [:& fm/input
         {:type "password"
          :name :password
          :tab-index "2"
           :label "Password"}]]

       [:div.form-row.submit-row
        [:& fm/submit-button
         {:label "Login"}]]]

      [:div.row-form
       [:a.login-with-google {:on-click login-with-google} i/google
        [:span "Login with Google"]]]

      [:div.form-links
       [:a {:on-click #(st/emit! (r/nav :auth-register))}
        "Don't have an account?"]
       [:a {:on-click #(st/emit! (r/nav :auth-recovery-request))}
        "Don't remember a password?"]]]]))

