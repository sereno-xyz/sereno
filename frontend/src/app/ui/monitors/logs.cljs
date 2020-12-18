;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors.logs
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.events :as ev]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.forms :as forms]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitors.common :refer [monitor-title]]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(mf/defc options
  [{:keys [monitor] :as props}]
  (let [on-export-click
        (mf/use-callback
         (fn [event]
           (let [node   (dom/create-element "a")
                 target (dom/get-current-target event)
                 format (dom/get-data-attr! target "format")
                 url  (-> (u/uri cfg/public-uri)
                          (assoc :path "/rpc/export-monitor-logs")
                          (u/assoc-query :id (:id monitor)
                                         :format format))]
             (dom/set-attr! node "href" (str url))
             (dom/set-attr! node "download" (str "logs." format))
             (dom/click node))))]

    [:ul.dropdown
     [:li {:title "Export as CSV"
           :data-id (:id monitor)
           :data-format "csv"
           :on-click on-export-click}
      [:div.icon i/download]
      [:div.text "Export as CSV"]]
     [:li {:title "Export as JSON"
           :data-id (:id monitor)
           :data-format "json"
           :on-click on-export-click}
      [:div.icon i/download]
      [:div.text "Export as JSON"]]]))

(mf/defc options-select
  [{:keys [monitor] :as props}]
  (let [show? (mf/use-state false)
        show  (mf/use-callback #(reset! show? true))
        hide  (mf/use-callback #(reset! show? false))]

    [:*
     [:span.options {:on-click show}
      [:span.label "Options"]
      [:span.icon i/chevron-down]]

     [:& dropdown {:show @show?
                   :on-close hide}
      [:& options {:monitor monitor}]]]))


(mf/defc healthcheck-log-detail-modal
  {::mf/wrap [mf/memo]
   ::mf/register modal/components
   ::mf/register-as :healthcheck-log-detail}
  [{:keys [item]}]
  (let [cancel-fn  (st/emitf (modal/hide))
        mdata      (:metadata item)]
    [:div.modal-overlay
     [:div.modal.monitor-cause-info
      [:div.modal-header
       [:div.modal-header-title
        [:h2 "Log Explain"]]
       [:div.modal-close-button
        {:on-click cancel-fn}
        i/times]]
      [:div.modal-content
       [:div.info-row.as-column
        [:span.label "Time received: "]
        [:span.content (dt/format (:created-at item) :datetime-full)]]

       [:div.info-row.as-column
        [:span.label "Client: "]
        [:span.content (:host mdata)]]

       [:div.info-row.as-column
        [:span.label "Method: "]
        [:span.content (str/upper (name (:method mdata)))]]

       [:div.info-row.as-column
        [:span.label "User Agent "]
        [:span.content (:user-agent mdata)]]

       (let [exit (:exit-code mdata :empty)]
         (when (not= :empty exit)
           [:div.info-row.as-column
            [:span.label "Exit code: "]
            [:span.content (str exit)]]))

       (when-let [content (:log mdata)]
         [:div.info-row.as-column
          [:span.label "Attached Log: "]
          [:span.content.code-block content]])


       ]]]))

(mf/defc healthcheck-logs-table
  [{:keys [items] :as props}]
  (let [show-detail
        (mf/use-callback
         (fn [item]
           (st/emit! (modal/show {:type :healthcheck-log-detail
                                  :item item}))))]
    [:div.logs-table
     [:div.table-body
      (for [item items]
        [:ul.table-item.healthcheck
         {:key   (str (:monitor-id item) "-" (inst-ms (:created-at item)))
          :on-click #(show-detail item)
          :class (dom/classnames
                  :status-warn (= (:status item) "warn")
                  :status-up (= (:status item) "up")
                  :status-down (= (:status item) "down"))}
         [:li.created-at {:title (dt/format (:created-at item) :datetime-full)}
          (dt/format (:created-at item) :datetime-full)]

         [:li.method
          (str/upper (name (get-in item [:metadata :method])))]

         [:li.host {:title "From IP"}
          (get-in item [:metadata :host])]

         [:li.user-agent
          [:span (get-in item [:metadata :user-agent])]]])]]))


(defn logs-data-ref
  [{:keys [id] :as monitor}]
  #(l/derived (l/in [:monitor-logs id]) st/state))


(mf/defc monitor-logs
  {::mf/wrap [mf/memo]}
  [{:keys [monitor] :as props}]
  (let [data-ref (mf/use-memo (mf/deps monitor) (logs-data-ref monitor))
        data     (mf/deref data-ref)
        load     (st/emitf (ptk/event :load-more-logs monitor))
        items    (->> (vals (:items data))
                      (sort-by #(inst-ms (:created-at %)))
                      (reverse))]

    (mf/use-effect
     (mf/deps (:id monitor))
     (fn []
       (st/emit! (ptk/event :init-monitor-logs monitor))
       (st/emitf (ptk/event :stop-monitor-logs monitor))))

    [:*
     [:& healthcheck-logs-table {:items items}]
     [:div.load-more
      (when (:load-more data)
        [:a {:on-click load} "Load more"])]]))

(mf/defc monitor-logs-page
  {::mf/wrap [mf/memo]}
  [{:keys [monitor] :as props}]
  [:main.main-content.monitor-page.monitor-logs-page
   [:div.single-column-1200
    [:& monitor-title {:monitor monitor :section "Monitor Log"}
     [:& options-select {:monitor monitor}]]
    [:div.main-section
     [:& monitor-logs {:monitor monitor}]]]])

