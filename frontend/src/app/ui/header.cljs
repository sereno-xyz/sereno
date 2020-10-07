;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.header
  "Main header component."
  (:require
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.store :as st]
   [app.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [rumext.alpha :as mf]))

;; --- Routes

(mf/defc logo
  {::mf/wrap [mf/memo]}
  []
  [:div.logo {:on-click #(st/emit! (r/nav :monitor-list))}
   [:div.logo-image i/logo]
   [:div.logo-text "sereno"]])

(mf/defc header
  {::mf/wrap [#(mf/memo % =)]}
  [{:keys [section profile] :as props}]
  [:header
   [:nav
    [:section.left-menu
     [:& logo]
     [:ul.menu
      [:li {:class (dom/classnames :current (= section :monitor-list))
            :on-click #(st/emit! (r/nav :monitor-list))}
       [:span "Monitors"]]
      [:li {:class (dom/classnames :current (= section :contacts))
            :on-click #(st/emit! (r/nav :contacts))}
       [:span "Contacts"]]
      [:li {:class (dom/classnames :current (= section :profile))
            :on-click #(st/emit! (r/nav :profile))}
       [:span "Profile"]]
      ]]
    [:section.right-menu.authenticated
     #_[:a.user-icon
      {:title (:email profile)
       :on-click #(st/emit! (r/nav :profile))}
      i/user]
     [:a {:title "Logout"
          :on-click #(st/emit! ev/logout)} i/sign-out-alt
      [:span.label "logout"]]]]])
