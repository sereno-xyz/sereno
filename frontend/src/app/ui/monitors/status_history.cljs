;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors.status-history
  (:require
   [app.common.data :as d]
   [app.config :as cfg]
   [app.store :as st]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitors.common :refer [monitor-title]]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
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
                          (assoc :path "/rpc/export-monitor-status-history")
                          (u/assoc-query :id (:id monitor)
                                         :format format))]
             (dom/set-attr! node "href" (str url))
             (dom/set-attr! node "download" (str "status-history." format))
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



(mf/defc status-history-table
  [{:keys [items] :as props}]
  (let [show-cause-info
        (mf/use-callback
         (fn [item]
           (when (and (= "down" (:status item))
                      (some? (:cause item)))
             (st/emit! (modal/show {:type :monitor-cause-info
                                    :cause (:cause item)})))))]
    [:div.history-table
     [:ul.table-header
      [:li.icon ""]
      [:li.status "Status"]
      [:li.created-at "Created At"]
      [:li.duration "Duration"]]
     [:div.table-body
      (for [item items]
        [:ul.table-item
         {:key (:id item)
          :title (get-in item [:cause :hint])
          :class (dom/classnames
                  :status-warn (= (:status item) "warn")
                  :status-up (= (:status item) "up")
                  :status-down (= (:status item) "down"))}

         [:li.icon (case (:status item)
                     "warn"    i/info-circle
                     "up"      i/chevron-circle-up
                     "down"    i/chevron-circle-down
                     "paused"  i/chevron-circle-down
                     "started" i/chevron-circle-up
                     "created" i/circle
                     nil)]
         [:li.status {:on-click #(show-cause-info item)
                      :style (when (= "down" (:status item))
                               #js {"cursor" "pointer"})}
          (str/upper (:status item))]
         [:li.created-at (dt/format (:created-at item) :datetime-med)]
         [:li.duration (dt/format-time-distance (:created-at item)
                                                (:finished-at item (dt/now)))]])]]))

(defn history-ref
  [{:keys [id] :as monitor}]
  #(l/derived (l/in [:monitor-status-history id]) st/state))


(mf/defc monitor-brief-history
  {::mf/wrap [mf/memo]}
  [{:keys [monitor] :as props}]
  (let [history-ref (mf/use-memo (mf/deps monitor) (history-ref monitor))
        history     (mf/deref history-ref)
        items       (->> (vals (:items history))
                         (sort-by :created-at)
                         (reverse)
                         (take 10))

        router      (mf/deref st/router-ref)
        history-url (r/href router :monitor-status-history {:id (:id monitor)})
        params      (assoc monitor
                           :brief true
                           :limit 10)]


    (mf/use-effect
     (mf/deps monitor)
     (fn []
       (st/emit! (ptk/event :init-monitor-status-history params))
       (st/emitf (ptk/event :stop-monitor-status-history))))

    [:*
     [:& status-history-table {:items items}]
     [:div.load-more
      [:a {:href history-url} "Show all"]]]))

(mf/defc monitor-history
  {::mf/wrap [mf/memo]}
  [{:keys [monitor] :as props}]
  (let [history-ref (mf/use-memo (mf/deps monitor) (history-ref monitor))
        history     (mf/deref history-ref)
        load        (st/emitf (ptk/event :load-more-status-history monitor))

        items       (->> (vals (:items history))
                         (sort-by #(inst-ms (:created-at %)))
                         (reverse))]

    (mf/use-effect
     (mf/deps monitor)
     (fn []
       (st/emit! (ptk/event :init-monitor-status-history monitor))
       (st/emitf (ptk/event :stop-monitor-status-history monitor))))

    [:*
     [:& status-history-table {:items items}]
     [:div.load-more
      (when (:load-more history)
        [:a {:on-click load} "Load more"])]]))

(mf/defc monitor-status-history-page
  {::mf/wrap [mf/memo]}
  [{:keys [monitor] :as props}]
  [:main.main-content.monitor-page.status-history-page
   [:div.single-column-1200
    [:& monitor-title {:monitor monitor :section "Status History"}
     [:& options-select {:monitor monitor}]]
    [:div.main-section
     [:& monitor-history {:monitor monitor}]]]])

