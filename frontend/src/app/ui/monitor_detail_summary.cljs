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
   [okulary.core :as l]
   [app.common.math :as mth]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.store :as st]
   [app.repo :as rp]
   [app.ui.monitor-form]
   [app.ui.monitor-detail-log]
   [app.ui.forms :as forms]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   ["chart.js" :as cht]))

(mf/defc latency-chart-section
  {::mf/wrap [mf/memo]}
  [{:keys [data] :as props}]
  (let [series1 (map (fn [item]
                       {:y (:avg item)
                        :x (:ts item)})
                     data)
        series2 (map (fn [item]
                       {:y (:p90 item)
                        :x (:ts item)})
                     data)

        ref  (mf/use-ref)
        hint (mf/use-state nil)

        on-nearest-x
        (mf/use-callback
         (fn [value props]
           (let [ts (unchecked-get value "x")
                 ms (unchecked-get value "y")]
             (reset! hint value))))

        on-mouse-out
        (mf/use-callback
         (fn []
           (reset! hint nil)))
        ]

    (mf/use-effect
     (mf/deps data)
     (fn []
       (let [node (mf/ref-val ref)
             ctx  (.getContext ^js node "2d")
             data {:datasets
                   [{:label "AVG Latency"
                     :fill false
                     :pointStrokeColor "#fff"
                     :pointRadius 5
                     :pointBackgroundColor "#000"
                     :pointBorderColor "#fff"
                     :pointHitRadius 5
                     :borderColor "#573010"
                     :data series1}
                    #_{:label "90th percentile"
                     :fill false
                     :pointColor "#000"
                     :pointRadius 5
                     :pointBackgroundColor "#000"
                     :pointBorderColor "#fff"
                     :borderColor "#FC8802"
                     :data series2}]}

            opts {:responsive true
                  :animation {:duration 0}
                  :maintainAspectRatio false
                  :tooltips {:mode "x"}
                  :scales {:xAxes [{:type "time",
                                    :distribution "series"
                                    ;; :display false
                                    :ticks {:maxRotation 120
                                            :display false
                                            :minRotation 60}
                                    ;; :gridLines {:display true}
                                    :time {:unit "minute"
                                           :displayFormats {:quarter "MMM YYYY"
                                                            :minute "DD/MM HH:mm a"}}}]}}
             prms {:type "line"
                   :data data
                   :options opts}

             chart (cht/Chart. ctx (clj->js prms))]
         (fn []
           (.destroy ^js chart)))))

    [:div.chart
     [:canvas {:ref ref
               :style {:width "100%" :height "100%"}
               }]]))


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

    [:div.details
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
        (dt/format (:monitored-at monitor) "PPpp")]]
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

(def interval-options
  [{:value "24 hours" :label "24 hours"}
   {:value "7 days" :label "7 days"}
   {:value "30 days" :label "30 days"}])


(defn summary-ref
  [id]
  (l/derived (l/in [:monitor-summary id]) st/state))

(mf/defc monitor-summary
  [{:keys [monitor]}]
  (let [summary-ref (mf/use-memo (mf/deps (:id monitor)) #(summary-ref (:id monitor)))
        {:keys [summary buckets interval]} (mf/deref summary-ref)

        value (d/seek #(= interval (:value %)) interval-options)

        on-interval-change
        (mf/use-callback
         (mf/deps (:id monitor))
         (fn [data]
           (let [value (unchecked-get data "value")]
             (st/emit! (ev/update-summary-interval {:id (:id monitor)
                                                    :interval value})))))]

    (mf/use-effect
     (fn []
       (st/emit! (ptk/event :initialize-monitor-summary {:id (:id monitor)}))
       (fn []
         (st/emit! ::ev/finalize-monitor-summary))))

    [:div.main-content
     [:h3 "Summary"]

     [:div.topside-options
      [:div.timeframe-selector
       [:> forms/rselect
        {:options (clj->js interval-options)
          :styles (clj->js forms/select-styles)
         :isClearable false
         :onChange on-interval-change
         :value (clj->js value)}]]]

     [:div.latency-chart
      [:& latency-chart-section {:data buckets}]]

     [:& details-table {:summary summary
                        :monitor monitor}]]))
