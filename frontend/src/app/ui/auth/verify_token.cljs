;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.auth.verify-token
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.events :as ev]
   [app.store :as st]
   [app.repo :as rp]
   [app.ui.icons :as i]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.router :as r]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defmulti handle-token :iss)

(defmethod handle-token :verify-profile
  [token]
  (let [msg "Profile verified succesfully!"]
    (st/emit! (r/nav :auth-login))
    (ts/schedule 100 #(st/emit! (ev/show-message {:type :success
                                                  :content msg
                                                  :timeout 3000})))))
(defmethod handle-token :change-email
  [token]
  (let [msg "Email changed successfully!"]
    (ts/schedule 100 #(st/emit! (ev/show-message {:type :success
                                                  :content msg
                                                  :timeout 3000})))
    (st/emit! (r/nav :profile-settings)
              (ptk/event :retrieve-profile))))


(defmethod handle-token :unsub-contact
  [token]
  (let [msg "Unsubscribed successfully."]
    (ts/schedule 100 #(st/emit! (ev/show-message {:type :success
                                                  :content msg
                                                  :timeout 3000})))
    (st/emit! (r/nav :auth-login))))

(defmethod handle-token :gauth
  [token]
  (st/emit! (ptk/event :retrieve-profile)
            (r/nav :monitor-list)))


(defmethod handle-token :default
  [token]
  (let [msg "Done!"]
    (ts/schedule 100 #(st/emit! (ev/show-message {:type :success
                                                  :content msg
                                                  :timeout 3000})))
    (st/emit! (r/nav :auth-login))))

(mf/defc verify-token-page
  [{:keys [route] :as props}]
  (let [token   (get-in route [:query-params :token])
        error   (mf/use-state nil)
        success (mf/use-state nil)]
    (mf/use-effect
     (fn []
       (->> (rx/of token)
            (rx/delay 1000)
            (rx/mapcat #(rp/req! :verify-profile-token {:token %}))
            (rx/subs
             (fn [tdata] (handle-token tdata))
             (fn [e]
               (cond
                 (= (:code e) :email-already-exists)
                 (let [msg (tr "errors.email-already-exists")]
                   (reset! error msg))

                 (= (:code e) :invalid-token)
                 (let [msg "Invalid or already used token."]
                   (reset! error msg))

                 (= (:type e) :not-found)
                 (let [msg "Invalid token"]
                   (reset! error msg))

                 :else
                 (let [msg "Unexpected error."]
                   (reset! error msg))))))))

    (cond
      (not (nil? @success))
      [:div.modal-wrapper
       [:div.modal
        [:div.modal-content
         [:div.notification
          [:div.loading @success]]]]]

      (not (nil? @error))
      [:div.modal-wrapper
       [:div.modal
        [:div.modal-content
         [:div.notification
          [:div.error-message @error]]]]]

      :else
      [:div.modal-wrapper
       [:div.modal
        [:div.modal-content
         [:div.notification
          [:div.loading "Verifying..."]]]]])))
