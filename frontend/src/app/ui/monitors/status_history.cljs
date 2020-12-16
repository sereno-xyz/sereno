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
   [app.store :as st]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitors.common :refer [monitor-title]]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))


(mf/defc status-history-table
  [{:keys [items] :as props}]
  (let [show-cause-info
        (mf/use-callback
         (fn [item]
           (when (= "down" (:status item))
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
  [:main.main-content.monitor-page
   [:div.single-column-1200
    [:& monitor-title {:monitor monitor :section "Status History"}]
    [:div.main-section
     [:div.section-title "Status History"]
     [:hr]
     [:& monitor-history {:monitor monitor}]]]])

