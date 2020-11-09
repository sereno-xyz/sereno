;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitor-detail
  (:require
   ["./impl_latency_chart" :as ilc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.store :as st]
   [app.ui.forms :as forms]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitor-form]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))


(mf/defc monitor-info
  [{:keys [summary monitor]}]
  (let [ttsecs  (mth/round (:total-seconds summary))
        usecs   (mth/round (:up-seconds summary))
        dsecs   (mth/round (:down-seconds summary))
        psecs   (mth/round (- ttsecs dsecs usecs))
        uptime  (/ (* (+ usecs psecs) 100) ttsecs)

        on-hover
        (mf/use-callback
         (mf/deps (:id monitor))
         (fn [event]
           (let [target (dom/get-target event)]
             (.setAttribute target "title" (dt/timeago (:modified-at monitor))))))]

    [:div.details-table
     [:div.details-column
      [:div.details-row
       [:div.details-field "Status"]
       [:div.details-field
        {:class (dom/classnames :success (= "up" (:status monitor))
                                :fail    (= "down" (:status monitor)))}
        (str/upper (:status monitor))]]
      [:div.details-row
       [:div.details-field "Type"]
       [:div.details-field (str/upper (:type monitor))]]
      [:div.details-row
       [:div.details-field "URI:"]
       [:div.details-field (get-in monitor [:params :uri])]]
      [:div.details-row
       [:div.details-field "Cadence"]
       [:div.details-field (dt/humanize-duration (* 1000 (:cadence monitor)))]]
      [:div.details-row
       [:div.details-field "Tags"]
       [:div.details-field (apply str (interpose ", " (:tags monitor)))]]]

     [:div.details-column
      [:div.details-row
       [:div.details-field "Monitored"]
       [:div.details-field
        {:on-mouse-enter on-hover}
        (dt/format (:monitored-at monitor) :datetime-med)]]
      [:div.details-row
       [:div.details-field "Uptime (%)"]
       [:div.details-field (mth/precision uptime 2) "%"]]
      [:div.details-row
       [:div.details-field "Downtime"]
       [:div.details-field (dt/humanize-duration (* 1000 dsecs))]]
      [:div.details-row
       [:div.details-field "AVG Latency"]
       [:div.details-field (mth/precision (:latency-avg summary) 0) " ms"]]
      [:div.details-row
       [:div.details-field "Q90 Latency"]
       [:div.details-field (mth/precision (:latency-p90 summary) 0) " ms"]]]]))

(def period-options
  [{:value "24hours" :label "24 hours"}
   {:value "7days"   :label "7 days"}
   {:value "30days"  :label "30 days"}])

(defn summary-ref
  [id]
  (l/derived (l/in [:monitor-summary id]) st/state))

(mf/defc monitor-summary
  [{:keys [monitor]}]
  (let [chart-ref    (mf/use-ref)

        summary-ref  (mf/use-memo (mf/deps (:id monitor)) #(summary-ref (:id monitor)))
        summary      (mf/deref summary-ref)

        summary-data    (:data summary)
        summary-buckets (:buckets summary)
        summary-period  (:period summary)

        selected-bucket (mf/use-state nil)
        value (d/seek #(= summary-period (:value %)) period-options)

        on-period-change
        (mf/use-callback
         (mf/deps (:id monitor))
         (fn [data]
           (let [value (unchecked-get data "value")]
             (st/emit! (ev/update-summary-period {:id (:id monitor)
                                                    :period value})))))
        on-mouse-over
        (mf/use-callback
         (mf/deps summary-buckets)
         (fn [index]
           (reset! selected-bucket (nth summary-buckets index))))

        on-mouse-out
        (mf/use-callback
         (mf/deps summary-buckets)
         (fn []
           (reset! selected-bucket nil)))

        go-back   (mf/use-callback #(st/emit! (r/nav :monitor-list)))
        go-detail #(st/emit! (r/nav :monitor-detail {:id (:id monitor)}))
        ;; go-log    #(st/emit! (r/nav :monitor-log {:id (:id monitor)}))
        pause     #(st/emit! (ev/pause-monitor monitor))
        resume    #(st/emit! (ev/resume-monitor monitor))
        edit      #(modal/show! {::modal/type :monitor-form
                                 :item monitor})]

    (mf/use-effect
     (mf/deps monitor)
     (fn []
       (st/emit! (ptk/event :initialize-monitor-summary {:id (:id monitor)}))
       (st/emitf (ptk/event :finalize-monitor-summary {:id (:id monitor)}))))

    (mf/use-layout-effect
     (mf/deps summary-buckets summary-period)
     (fn []
       (when summary-buckets
         (let [dom  (mf/ref-val chart-ref)
               data (clj->js summary-buckets)]
           (ilc/render dom #js {:width 1160
                                :period summary-period
                                :height (.-clientHeight dom)
                                :onMouseOver on-mouse-over
                                :onMouseOut on-mouse-out
                                :data data})
           (fn []
             (ilc/clear dom))))))

    [:div.main-content
     [:div.section-title-bar
      [:h2 (:name monitor)]
      [:div.options
       (if (= "paused" (:status monitor))
         [:a.inline-button {:on-click resume} i/play "Resume"]
         [:a.inline-button {:on-click pause} i/pause "Pause"])
       [:a.inline-button {:on-click edit} i/edit "Edit"]]]
     [:hr]

     [:div.topside-options
      (when-let [data (deref selected-bucket)]
        [:ul.period-info
         [:li
          [:span.label "Latency: "]
          [:span.value (str (:avg data) "ms")]]
         [:li
          [:span.label "Period: "]
          [:span.value (dt/format (:ts data) :datetime-med)]]])
      [:div.timeframe-selector
       [:> forms/rselect
        {:options (clj->js period-options)
         :className "react-select"
         :classNamePrefix "react-select"
         :isClearable false
         :onChange on-period-change
         :value (clj->js value)}]]]

     [:div.latency-chart
      [:div.chart {:ref chart-ref}]]

     [:& monitor-info {:summary summary-data
                       :monitor monitor}]]))


(defn history-ref
  [id]
  (l/derived (l/in [:monitor-status-history id]) st/state))

(mf/defc monitor-status-history
  {::mf/wrap [mf/memo]}
  [{:keys [monitor] :as props}]
  (let [history-ref (mf/use-memo (mf/deps (:id monitor)) #(history-ref (:id monitor)))
        history     (mf/deref history-ref)
        load        #(st/emit! (ptk/event :load-more-status-history monitor))]

    (mf/use-effect
     (mf/deps (:id monitor))
     (fn []
       (st/emit! (ptk/event :initialize-monitor-status-history monitor))
       (st/emitf (ptk/event :finalize-monitor-status-history monitor))))

    [:div.main-content
     [:div.section-title-bar.secondary
      [:h2 "Status History"]]
     [:hr]

     [:div.history-table
      [:ul.table-header
       [:li.icon ""]
       [:li.status "Status"]
       [:li.created-at "Created At"]
       [:li.duration "Duration"]]
      [:div.table-body
       (for [item (->> (vals (:items history))
                       (sort-by :created-at)
                       (reverse))]
         [:ul.table-body-item {:key (:id item)
                               :title (:reason item "")
                               :class (dom/classnames
                                       :status-up (= (:status item) "up")
                                       :status-down (= (:status item) "down"))}
          [:li.icon (case (:status item)
                      "up" i/chevron-circle-up
                      "down" i/chevron-circle-down
                      "paused" i/chevron-circle-down
                      "started" i/chevron-circle-up
                      "created" i/circle
                      nil)]
          [:li.status (str/upper (:status item))]
          [:li.created-at (dt/format (:created-at item) :datetime-med)]
          [:li.duration (dt/format-time-distance (:created-at item)
                                                 (:finished-at item (dt/now)))]])
       [:div.load-more-button
        (when (:load-more history)
          [:a {:on-click load} "Load more"])]]]]))

(defn monitor-ref
  [id]
  (l/derived (l/in [:monitors id]) st/state))

(defn monitor-local-ref
  [id]
  (l/derived (l/in [:monitor-local id]) st/state))

(mf/defc monitor-detail-page
  {::mf/wrap [mf/memo]}
  [{:keys [id section] :as props}]
  (let [monitor-ref (mf/use-memo (mf/deps id) #(monitor-ref id))
        monitor     (mf/deref monitor-ref)]

    (mf/use-effect
     (fn []
       (st/emit! (ptk/event :initialize-monitor-detail {:id id}))
       (st/emitf (ptk/event :finalize-monitor-detail {:id id}))))

    (when monitor
      [:main.monitor-detail-section
       [:section
        (case section
          :monitor-detail
          [:*
           [:& monitor-summary {:monitor monitor}]
           [:& monitor-status-history {:monitor monitor}]]

          ;; :monitor-log
          ;; [:& monitor-log {:monitor monitor}]

          nil)]])))

