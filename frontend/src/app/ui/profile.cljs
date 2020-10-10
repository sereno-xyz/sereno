;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.profile
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.events :as ev]
   [app.events.messages :as em]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.confirm]
   [app.ui.forms :as fm]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.header :refer [header]]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.profile-email]
   [app.ui.profile-password]
   [app.ui.profile-delete]
   [app.util.dom :as dom]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

;; --- Routes
(s/def ::profile-form map?)

(mf/defc profile-section
  [props]
  (let [profile (mf/deref st/profile-ref)
        form    (fm/use-form :spec ::profile-form
                             :initial profile)
        change-email
        (mf/use-callback
         (st/emitf (modal/show {:type :email-change
                                :profile profile})))

        on-success
        (mf/use-callback
         (fn []
           (let [msg {:type :success
                      :timeout 2000
                      :content "Profile saved successfully."}]
             (st/emit! (ev/show-message msg)))))

        on-submit
        (mf/use-callback
         (fn [form]
           (let [params (with-meta (:clean-data form)
                          {:on-success on-success})]
             (st/emit! (ptk/event :update-profile params)))))]

    [:section.personal-data
     [:h2 "Personal Data"]
     [:div.form-container
      [:& fm/form {:on-submit on-submit
                   :form form}
       [:div.form-row
        [:& fm/input
         {:name :fullname
          :type "text"
          :label "Full Name"}]]

       [:div.form-row
        [:& fm/input
         {:name :email
          :type "text"
          :disabled true
          :label "Email"}

         [:div.input-link
          [:a {:on-click change-email} "Change"]]]]

       [:div.form-row
        [:div.action-buttons
         [:& fm/submit-button
          {:label "Save"}]]]]]]))


(mf/defc profile-limits-section
  [{:keys [profile]}]
  [:section.profile-limits
   [:h2 "Plan & Limits"]
   [:div.table
    [:div.row
     [:div.field.name
      [:span.label "Plan name"]]
     [:div.field.value
      [:span.label (:type profile)]]]

    [:div.row
     [:div.field.name
      [:span.label "Min Check Interval"]]
     [:div.field.value
      (let [num (:quotas-min-cadence profile)]
        [:span.label (dt/humanize-duration (* num 1000) {:largest 1})])]]

    [:div.row
     [:div.field.name
      [:span.label "Contacts"]]
     [:div.field.value
      [:span.label (:counters-contacts profile)]
      [:div.context
       (if-let [num (:quotas-max-contacts profile)]
         [:span.label " max " num]
         [:span.label " without limit"])]]]

    [:div.row
     [:div.field.name
      [:span.label "Monitors"]]
     [:div.field.value
      [:span.label (str (:counters-monitors profile 0))]
      [:div.context
       (if-let [num (:quotas-max-monitors profile)]
         [:span.label " max " num]
         [:span.label "without limit"])]]]

    [:div.row
     [:div.field.name
      [:span.label "Notifications (email)"]]
     [:div.field.value
      [:span.label (str (:counters-email-notifications profile 0))]
      [:div.context
       [:span.label "this month, "]
       (if-let [num (:quotas-max-email-notifications profile)]
         [:span.label "max " (str num) " per month "]
         [:span.label "without limit"])]]]

    ]])




(mf/defc options
  [{:keys [profile] :as props}]
  (let [input-ref      (mf/use-ref)
        importing?     (mf/use-state false)

        show-dropdown? (mf/use-state false)
        show-dropdown  (mf/use-callback #(reset! show-dropdown? true))
        hide-dropdown  (mf/use-callback #(reset! show-dropdown? false))

        on-password-change
        (mf/use-callback (st/emitf (modal/show {:type :password-change
                                                :profile profile})))

        on-delete
        (mf/use-callback (st/emitf (modal/show {:type :delete-profile-confirmation})))

        on-import-click
        (mf/use-callback
         (fn [event]
           (let [node  (mf/ref-val input-ref)]
             (.click ^js node))))

        on-export-click
        (mf/use-callback
         (fn [event]
           (let [node (dom/create-element "a")
                 uri  (assoc (d/uri cfg/public-uri) :path "/api/rpc/request-export")]
             (dom/set-attr! node "href" (str uri))
             (dom/set-attr! node "download" "export.data")
             (dom/click node))))

        on-selected
        (mf/use-callback
         (fn [event]
           (let [node  (dom/get-target event)
                 files (array-seq (.-files node))]

             (reset! importing? true)
             (->> (rp/req! :request-import {:file (first files)})
                  (rx/subs (fn [resp]
                             (reset! importing? false)
                             (.reload ^js js/location))
                           (fn [err]
                             (reset! importing? false)
                             (st/emit! (ev/show-message {:content "Error on import data."
                                                         :type :error
                                                         :timeout 2000}))))))))]

    [:div.topside-options
     [:div.select-like {:on-click show-dropdown}
      [:span.text "Options"]
      [:span.icon i/chevron-down]]
     [:& dropdown {:show @show-dropdown?
                   :on-close hide-dropdown}
      [:ul.dropdown
       [:li {:on-click on-export-click}
        [:span.icon i/download]
        [:span.text "Export"]]
       [:li {:on-click on-import-click}
        [:span.icon i/upload]
        [:span.text "Import"]
        [:input {:style {:display "none"}
                 :ref input-ref
                 :accept ".data"
                 :on-change on-selected
                 :type "file"}]]
       [:li {:on-click on-password-change
             :title "Change assword"}
        [:span.icon i/key]
        [:span.text "Password"]]
       [:li.delete {:on-click on-delete}
        [:span.icon i/trash-alt]
        [:span.text "Delete profile"]]]]]))


(mf/defc profile-page
  [props]
  (let [profile (mf/deref st/profile-ref)]
    (mf/use-effect (st/emitf (ptk/event :retrieve-profile)))
    [:section.profile
     [:div.single-column-1200
      [:& options {:profile profile}]
      [:div.column-wrapper
       [:div.left-column
        [:& profile-section]
        ]

       [:div.middle-line]
       [:div.right-column
        [:& profile-limits-section {:profile profile}]
        ]]]]))
