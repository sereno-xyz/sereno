;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors.monitor
  (:require
   [app.config :as cfg]
   [app.events :as ev]
   [app.store :as st]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitors.common :refer [monitor-title]]
   [app.ui.monitors.healthcheck :refer [healthcheck-monitor]]
   [app.ui.monitors.http :refer [http-monitor]]
   [app.ui.monitors.ssl :refer [ssl-monitor]]
   [app.ui.monitors.status-history :refer [monitor-status-history-page]]
   [app.ui.monitors.logs :refer [monitor-logs-page]]
   [app.util.dom :as dom]
   [lambdaisland.uri :as u]
   [okulary.core :as l]
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
             "healthcheck" (st/emit! (modal/show {:type :healthcheck-monitor-form :item monitor})))))

        on-export-click
        (mf/use-callback
         (mf/deps monitor)
         (fn [event]
           (let [node (dom/create-element "a")
                 url  (-> (u/uri cfg/public-uri)
                          (assoc :path "/rpc/export-monitors")
                          (u/assoc-query :ids (str (:id monitor))))]
             (dom/set-attr! node "href" (str url))
             (dom/set-attr! node "download" (str "export-" (:id monitor) ".data"))
             (dom/click node))))]

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
     [:li {:title "Export monitor"
           :on-click on-export-click}
      [:div.icon i/download]
      [:div.text "Export"]]
     [:li.danger {:on-click on-delete
                  :title "Delete this monitor"}
      [:div.icon i/trash-alt]
      [:div.text "Delete"]]]))

(mf/defc monitor-options-button
  [{:keys [monitor]}]
  (let [show? (mf/use-state false)
        show  (mf/use-callback #(reset! show? true))
        hide  (mf/use-callback #(reset! show? false))]

    [:*
     [:span.options {:on-click show}
      [:span.label "Options"]
      [:span.icon i/chevron-down]]

     [:& dropdown {:show @show?
                   :on-close hide}
      [:& monitor-options {:monitor monitor}]]]))

(defn monitor-ref
  [id]
  #(l/derived (l/in [:monitors id]) st/state))

(mf/defc monitor-page
  [{:keys [id section] :as props}]
  (mf/use-effect
   (mf/deps id)
   (fn []
     (st/emit! (ptk/event :init-monitor-page {:id id}))
     (st/emitf (ptk/event :stop-monitor-page {:id id}))))

  (let [monitor-ref (mf/use-memo (mf/deps id) (monitor-ref id))
        monitor     (mf/deref monitor-ref)]
    (when monitor
      (case section
        :monitor
        [:main.main-content.monitor-page
         [:div.single-column-1200
          [:& monitor-title {:monitor monitor}
           [:& monitor-options-button {:monitor monitor}]]

          (case (:type monitor)
            "http"        [:& http-monitor {:monitor monitor}]
            "ssl"         [:& ssl-monitor {:monitor monitor}]
            "healthcheck" [:& healthcheck-monitor {:monitor monitor}]
            nil)]]

        :monitor-status-history
        [:& monitor-status-history-page {:monitor monitor}]

        :monitor-logs
        [:& monitor-logs-page {:monitor monitor}]

        nil))))
