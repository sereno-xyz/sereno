;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors.http
  (:require
   ["./http_chart" :as ilc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.store :as st]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.forms :as forms]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitors.common :refer [monitor-title monitor-history]]
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

(defn summary-ref
  [{:keys [id] :as monitor}]
  #(l/derived (l/in [:monitor-summary id]) st/state))

(mf/defc monitor-summary
  [{:keys [monitor]}]
  (let [chart-ref    (mf/use-ref)

        summary-ref  (mf/use-memo (mf/deps monitor) (summary-ref monitor))
        summary      (mf/deref summary-ref)

        buckets      (:buckets summary)

        bucket       (mf/use-state nil)
        on-mouse-over
        (mf/use-callback
         (mf/deps buckets)
         (fn [index]
           (reset! bucket (nth buckets index))))

        on-mouse-out
        (mf/use-callback
         (mf/deps buckets)
         (fn []
           (reset! bucket nil)))

        go-back   (mf/use-callback #(st/emit! (r/nav :monitor-list)))
        go-detail #(st/emit! (r/nav :monitor-detail {:id (:id monitor)}))
        ;; go-log    #(st/emit! (r/nav :monitor-log {:id (:id monitor)}))
        pause     #(st/emit! (ev/pause-monitor monitor))
        resume    #(st/emit! (ev/resume-monitor monitor))
        edit      #(modal/show! {::modal/type :monitor-form
                                 :item monitor})]

    ;; Fetch Summary Data
    (mf/use-effect
     (mf/deps monitor)
     (fn []
       (st/emit! (ptk/event :initialize-monitor-summary {:id (:id monitor)}))
       (st/emitf (ptk/event :finalize-monitor-summary {:id (:id monitor)}))))

    ;; Render Chart
    (mf/use-layout-effect
     (mf/deps buckets)
     (fn []
       (when buckets
         (let [dom  (mf/ref-val chart-ref)
               data (clj->js buckets)]
           (ilc/render dom #js {:width 1160
                                :height 90 #_(.-clientHeight ^js dom)
                                :onMouseOver on-mouse-over
                                :onMouseOut on-mouse-out
                                :data data})
           (fn []
             (ilc/clear dom))))))

    [:div.main-content
     [:& monitor-title {:monitor monitor}]
     [:hr]

     [:div.topside-options
      (let [data (deref bucket)]
        [:ul.period-info #_{:class (dom/classnames :invisible (not data))}
         [:li
          [:span.label "Latency: "]
          [:span.value  (if data (str (:avg data) "ms") "")]]
         [:li
          [:span.label "Period: "]
          [:span.value (if data
                         (dt/format (:ts data) :date-med-with-weekday)
                         "")]]])]

     [:div.latency-chart
      [:div.chart {:ref chart-ref}]]

     [:& monitor-info {:summary summary
                       :monitor monitor}]]))

(mf/defc http-monitor-detail
  {::mf/wrap [mf/memo]}
  [{:keys [monitor] :as props}]
  [:main.monitor-detail-section
   [:section
    [:& monitor-summary {:monitor monitor}]
    [:& monitor-history {:monitor monitor}]]])

