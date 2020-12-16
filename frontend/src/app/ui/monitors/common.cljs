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


(mf/defc monitor-options
  [{:keys [monitor]}]
  (let [on-pause
        (mf/use-callback
         (mf/deps monitor)
         (st/emitf (ev/pause-monitor monitor)))

        on-resume
        (mf/use-callback
         (mf/deps monitor)
         (st/emitf (ev/resume-monitor monitor)))

        delete-fn
        (mf/use-callback
         (mf/deps monitor)
         (st/emitf (ev/delete-monitor monitor)))

        on-delete
        (mf/use-callback
         (mf/deps monitor)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (modal/show
                      {:type :confirm
                       :title "Deleting monitor"
                       :on-accept delete-fn
                       :accept-label "Delete monitor"
                       :message "Do you want to delete this monitor?"}))))

        on-edit
        (mf/use-callback
         (mf/deps monitor)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (case (:type monitor)
             "http" (st/emit! (modal/show {:type :http-monitor-form :item monitor}))
             "ssl"  (st/emit! (modal/show {:type :ssl-monitor-form :item monitor}))
             "healthcheck" (st/emit! (modal/show {:type :healthcheck-monitor-form :item monitor})))))]

    [:ul.dropdown
     (if (= "paused" (:status monitor))
       [:li {:on-click on-resume
             :title "Resume this monitor"}
        [:div.icon i/play]
        [:div.text "Resume"]]
       [:li {:on-click on-pause
             :title "Pause this monitor"}
        [:div.icon i/pause]
        [:div.text "Pause"]])
     [:li {:on-click on-edit
           :title "Edit"}
      [:div.icon i/pen-square]
      [:div.text "Edit"]]
     [:li.disabled {:title "Export (not implemented yet)"}
      [:div.icon i/download]
      [:div.text "Export"]]
     [:li.danger {:on-click on-delete
                  :title "Delete this monitor"}
      [:div.icon i/trash-alt]
      [:div.text "Delete"]]]))

(mf/defc monitor-title
  [{:keys [monitor]}]
  (let [show? (mf/use-state false)
        show  (mf/use-callback #(reset! show? true))
        hide  (mf/use-callback #(reset! show? false))]

    [:div.section-title-bar
     [:h2 (:name monitor)]
     [:span.options {:on-click show}
      [:span.label "Options"]
      [:span.icon i/chevron-down]]

     [:& dropdown {:show @show?
                    :on-close hide}
      [:& monitor-options {:monitor monitor}]]]))

(defn history-ref
  [id]
  #(l/derived (l/in [:monitor-status-history id]) st/state))

(mf/defc monitor-history
  {::mf/wrap [mf/memo]}
  [{:keys [monitor history] :as props}]
  (let [history-ref (mf/use-memo (mf/deps (:id monitor)) (history-ref (:id monitor)))
        history     (mf/deref history-ref)
        load        (st/emitf (ptk/event :load-more-status-history monitor))

        show-cause-info
        (mf/use-callback
         (mf/deps monitor)
         (fn [item]
           (when (= "down" (:status item))
             (st/emit! (modal/show {:type :monitor-cause-info
                                    :cause (:cause item)})))))]

    (mf/use-effect
     (mf/deps monitor)
     (fn []
       (st/emit! (ptk/event :init-monitor-status-history monitor))
       (st/emitf (ptk/event :stop-monitor-status-history monitor))))

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
         [:ul.table-body-item
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
                       :style (if (= "down" (:status item))
                                #js {"cursor" "pointer"})}
           (str/upper (:status item))]
          [:li.created-at (dt/format (:created-at item) :datetime-med)]
          [:li.duration (dt/format-time-distance (:created-at item)
                                                 (:finished-at item (dt/now)))]])
       [:div.load-more-button
        (when (:load-more history)
          [:a {:on-click load} "Load more"])]]]]))


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


