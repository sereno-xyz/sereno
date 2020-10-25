;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui
  (:require
   [app.common.uuid :as uuid]
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.events :as ev]
   [app.events.messages :as em]
   [app.store :as st]
   [app.ui.auth.login :refer [login-page]]
   [app.ui.auth.recovery :refer [recovery-page]]
   [app.ui.auth.recovery-request :refer [recovery-request-page]]
   [app.ui.auth.register :refer [register-page]]
   [app.ui.auth.verify-token :refer [verify-token-page]]
   [app.ui.contacts :refer [contacts-page]]
   [app.ui.header :refer [header]]
   [app.ui.monitor-detail :refer [monitor-detail-page]]
   [app.ui.monitor-list :refer [monitor-list-page]]
   [app.ui.notifications :refer [notifications]]
   [app.ui.profile :refer [profile-page]]
   [app.ui.static :refer [not-found-page not-authorized-page goodbye-page]]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [expound.alpha :as expound]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

;; --- Routes

(def routes
  [["/auth"
    ["/login" :auth-login]
    ["/register" :auth-register]
    ["/recovery/request" :auth-recovery-request]
    ["/recovery" :auth-recovery]
    ["/verify-token" :auth-verify-token]
    ["/goodbye" :auth-goodbye]]

   ["/not-found" :not-found]
   ["/not-authorized" :not-authorized]

   ["/profile" :profile]
   ["/contacts" :contacts]
   ["/monitors" :monitor-list]
   ["/monitors/:id"
    ["/detail" :monitor-detail]
    ["/log" :monitor-log]]])

(mf/defc app-error
  [{:keys [error] :as props}]
  (let [data (ex-data error)]
    (case (:type data)
      :not-found [:& not-found-page {:error data}]
      [:span "Internal application errror"])))

(mf/defc main-layout
  [{:keys [route children] :as props}]
  (let [section (get-in route [:data :name])
        profile (mf/deref st/profile-ref)]

    (mf/use-effect
     (mf/deps (:id profile))
     (fn []
       (when (and profile (not= uuid/zero (:id profile)))
         (st/emit! (ptk/event :initialize-websocket))
         (st/emitf ::ev/finalize-websocket))))

    [:section.main-layout
     [:& header {:section section
                 :profile profile}]
     (when (= (:email profile)
              (:pending-email profile))
       [:div.inline-notifications
        [:div.notification-item.warning
         [:span "The email " [:strong (:email profile)] " is not verified. "
          " Not verified emails will be deleted after 48 hours."]]])
     children]))

(mf/defc app
  {::mf/wrap [#(mf/catch % {:fallback app-error})]}
  []
  (let [route   (mf/deref st/route-ref)
        section (get-in route [:data :name])]
    [:*
     [:& notifications]
     (when section
       (case section
         :auth-register
         [:& register-page]

         :auth-login
         [:& login-page]

         :auth-goodbye
         [:& goodbye-page]

         :auth-verify-token
         [:& verify-token-page {:route route}]

         :auth-recovery-request
         [:& recovery-request-page]

         :auth-recovery
         [:& recovery-page {:params (:query-params route)}]

         :not-authorized
         [:& not-authorized-page]

         :not-found
         [:& not-found-page]

         [:& main-layout {:route route}
          (case section
            :monitor-list
            [:& monitor-list-page]

            (:monitor-detail
             :monitor-log)
            (let [id (uuid (get-in route [:path-params :id]))]
              [:& monitor-detail-page {:id id :section section}])

            :contacts
            [:& contacts-page]

            :profile
            [:& profile-page]

            nil)]))]))

;; --- Error Handling

(defmethod ptk/handle-error :validation
  [error]
  (ts/schedule
   (st/emitf (em/show {:content "Unexpected validation error."
                       :type :error
                       :timeout 5000})))
  (when-let [explain (:explain error)]
    (js/console.group "Server Error")
    (js/console.error (if (map? error) (pr-str error) error))
    (js/console.error explain)
    (js/console.endGroup "Server Error")))

(defmethod ptk/handle-error :assertion
  [{:keys [data stack message context] :as error}]
  (js/console.group message)
  (js/console.info (str/format "ns: '%s'\nname: '%s'\nfile: '%s:%s'"
                                (:ns context)
                                (:name context)
                                (str cfg/public-uri "/js/cljs-runtime/" (:file context))
                                (:line context)))
  (js/console.groupCollapsed "Stack Trace")
  (js/console.info stack)
  (js/console.groupEnd "Stack Trace")

  (js/console.error (with-out-str (expound/printer data)))
  (js/console.groupEnd message))

(defmethod ptk/handle-error :authentication
  [error]
  (ts/schedule 0 #(st/emit! ev/logout)))

(defmethod ptk/handle-error :internal-error
  [{:keys [status] :as error}]
  (cond
    (= status 429)
    (ts/schedule
     (st/emitf (em/show {:content "Too many requests, wait a little bit and retry."
                         :type :error
                         :timeout 5000})))

    :else
    (ts/schedule
     (st/emitf (em/show {:content "Unable to connect to backend, wait a little bit and refresh."
                         :type :error})))))

(defmethod ptk/handle-error :default
  [error]
  (if (instance? ExceptionInfo error)
    (ptk/handle-error (ex-data error))
    (do
      (js/console.group "Generic Error:")
      (ex/ignoring
       (js/console.error (pr-str error))
       (js/console.error (.-stack error)))
      (js/console.groupEnd "Generic error")
      (ts/schedule (st/emitf (em/show
                              {:content "Something wrong has happened."
                               :type :error
                               :timeout 5000}))))))

(defonce uncaught-error-handler
  (letfn [(on-error [event]
            (ptk/handle-error (unchecked-get event "error"))
            (.preventDefault ^js event))]
    (.addEventListener js/window "error" on-error)
    (fn []
      (.removeEventListener js/window "error" on-error))))
