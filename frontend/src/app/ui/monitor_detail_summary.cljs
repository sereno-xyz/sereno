;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitor-detail-summary
  (:require
   ["./impl_latency_chart" :as ilc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.forms :as forms]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitor-detail-log]
   [app.ui.monitor-form]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(mf/defc details-table
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
       [:div.details-field (str/upper (:status monitor))]]
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
       [:div.details-field "Last downtime"]
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
           (reset! selected-bucket nil)))]

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
     [:h3 i/info "Summary"]

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

     [:& details-table {:summary summary-data
                        :monitor monitor}]]))
