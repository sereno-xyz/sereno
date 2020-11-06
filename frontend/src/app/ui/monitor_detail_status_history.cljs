;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitor-detail-status-history
  (:require
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
