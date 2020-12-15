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
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [rumext.alpha :as mf]))

;; --- Routes

(mf/defc logo
  {::mf/wrap [mf/memo]}
  []
  [:div.logo {:on-click #(st/emit! (r/nav :monitors))}
   [:div.logo-image i/logo]
   [:div.logo-text "sereno"]])

(mf/defc header
  {::mf/wrap [#(mf/memo % =)]}
  [{:keys [section profile] :as props}]
  (let [menu? (mf/use-state false)]
    [:header
     [:nav
      [:section.left-menu
       [:& logo]
       [:ul.menu
        [:li {:class (dom/classnames :current (or (= section :monitors)
                                                  (= section :monitor)))
              :on-click #(st/emit! (r/nav :monitors))}
         [:span "Monitors"]]
        [:li {:class (dom/classnames :current (= section :contacts))
              :on-click #(st/emit! (r/nav :contacts))}
         [:span "Contacts"]]
        #_[:li {:class (dom/classnames :current (= section :profile))
              :on-click #(st/emit! (r/nav :profile))}
         [:span "Profile"]]
        ]]
      [:section.right-menu.authenticated
       [:a.profile-section {:title "Logout" :on-click #(reset! menu? true)}
        [:span.icon i/user]
        [:span.label (:email profile)]]
       [:& dropdown {:show @menu?
                     :on-close #(reset! menu? false)}
        [:ul.dropdown.dark
         [:li {:on-click (st/emitf (r/nav :profile))
               :title "Go to profile"}
          [:div.icon i/user]
          [:div.text "Profile"]]

         [:li {:on-click (st/emitf ev/logout) :title "Logout"}
          [:div.icon i/sign-out-alt]
          [:div.text "Logout"]]]]]]]))



