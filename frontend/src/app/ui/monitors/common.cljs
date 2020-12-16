;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors.common
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.events :as ev]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.confirm]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [app.util.timers :as tm]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(mf/defc monitor-title
  [{:keys [monitor section children]}]
  (let [router (mf/deref st/router-ref)
        url    (r/href router :monitor {:id (:id monitor)})]
    [:div.page-title
     [:div.breadcrumb
      [:a.monitor-name {:href url} (:name monitor)]
      [:span.separator "➤"]
      [:span.section-name (or section "Detail")]]
     children]))

(mf/defc page-title
  [{:keys [monitor section children]}]
  (let [router (mf/deref st/router-ref)
        url    (r/href router :monitor {:id (:id monitor)})]
    [:div.page-title
     [:div.breadcrumb
      [:a.monitor-name {:href url} (:name monitor)]
      (when section
        [:span.separator "➤"]
        [:span.section-name section])]
     children]))

(mf/defc down-cause-modal
  {::mf/wrap [mf/memo]
   ::mf/register modal/components
   ::mf/register-as :monitor-cause-info}
  [{:keys [cause]}]
  (let [cancel-fn  (st/emitf (modal/hide))]
    [:div.modal-overlay
     [:div.modal.monitor-cause-info
      [:div.modal-header
       [:div.modal-header-title
        [:h2 "Downtime Cause"]]
       [:div.modal-close-button
        {:on-click cancel-fn}
        i/times]]

      [:div.modal-content
       [:div.info-row.as-column
        [:span.label "Code: "]
        [:span.content (name (:code cause))]]

       [:div.info-row.as-column
        [:span.label "Hint: "]
        [:span.content (str (:hint cause))]]

       (when-let [msg (:ex-message cause)]
         [:div.info-row.as-column
          [:span.label "Exception Message: "]
          [:span.content msg]])

       (when-let [class (:ex-class cause)]
         [:div.info-row.as-column {:title class}
          [:span.label "Exception Class: "]
          [:span.content.ellipsis class]])

       (when-let [data (:ex-data cause)]
         [:div.info-row.as-column
          [:span.label "Exception Data: "]
          [:span.content (pr-str data)]])

       (when-let [trace (:ex-stack cause)]
         [:div.info-row.as-column {:title trace}
          [:span.label "Stack Trace: "]
          [:span.content.code-block trace]])]]]))


