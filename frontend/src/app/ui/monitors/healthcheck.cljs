;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors.healthcheck
  (:require
   ["./healthcheck_chart" :as ilc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.store :as st]
   [app.repo :as rp]
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

(defn detail-ref
  [{:keys [id] :as monitor}]
  #(l/derived (l/in [:monitor-detail id]) st/state))

(mf/defc monitor-detail
  [{:keys [monitor]}]
  (let [detail-ref  (mf/use-memo (mf/deps monitor) (detail-ref monitor))
        detail      (mf/deref detail-ref)

        go-back     (mf/use-callback #(st/emit! (r/nav :monitors)))
        go-detail   (st/emitf (r/nav :monitor {:id (:id monitor)}))

        pause       (st/emitf (ev/pause-monitor monitor))
        resume      (st/emitf (ev/resume-monitor monitor))
        edit        #(modal/show! {::modal/type :monitor-form
                                   :item monitor})

        on-hover
        (mf/use-callback
         (mf/deps monitor)
         (fn [event]
           (-> (dom/get-target event)
               (dom/set-attr! "title" (dt/timeago (:modified-at monitor))))))]

    ;; Fetch Summary Data
    (mf/use-effect
     (mf/deps monitor)
     (fn []
       (st/emit! (ptk/event :init-monitor-detail-section monitor))
       (st/emitf (ptk/event :stop-monitor-detail-section monitor))))

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
       [:div.details-field "Cadence"]
       [:div.details-field (dt/humanize-duration (* 1000 (:cadence monitor)))]]

      [:div.details-row
       [:div.details-field "Tags"]
       [:div.details-field (apply str (interpose ", " (:tags monitor)))]]
      ]

     [:div.details-column

      [:div.details-row
       [:div.details-field "Last ping"]
       [:div.details-field
        {:on-mouse-enter on-hover}
        (dt/format (:monitored-at monitor) :datetime-med)]]

      [:div.details-row
       [:div.details-field "Log entries"]
       [:div.details-field (:total detail)]]

      [:div.details-row
       [:div.details-field "Incidents"]
       [:div.details-field (:incidents detail)]]
      ]]))

(mf/defc monitor-chart
  [{:keys [monitor] :as props}]
  (let [ref  (mf/use-ref)
        data (mf/use-state [])]

    (mf/use-effect
     (mf/deps monitor)
     (fn []
       (->> (rp/qry! :retrieve-monitor-log-entries {:id (:id monitor)})
            (rx/subs #(reset! data %)))))

    ;; Render Chart
    (mf/use-layout-effect
     (mf/deps @data)
     (fn []
       (when @data
         (let [dom  (mf/ref-val ref)
               data (clj->js @data)]
           (ilc/render dom #js {:width 1160
                                :height 15
                                :data data})
           (fn []
             (ilc/clear dom))))))

    [:div.logentries-chart
     [:div.chart {:ref ref}]]))


(mf/defc healthcheck-monitor
  {::mf/wrap [mf/memo]}
  [{:keys [monitor] :as props}]
  [:main.monitor-detail-section
   [:section

    [:div.main-content
     [:& monitor-title {:monitor monitor}]
     [:hr]
     [:& monitor-chart {:monitor monitor}]
     [:& monitor-detail {:monitor monitor}]]

    [:& monitor-history {:monitor monitor}]]])

