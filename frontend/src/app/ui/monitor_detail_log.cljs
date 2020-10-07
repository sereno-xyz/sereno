;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitor-detail-log
  (:require
   [okulary.core :as l]
   [app.common.math :as mth]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.store :as st]
   [app.repo :as rp]
   [app.ui.forms :as forms]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))


(defn log-data-ref
  [id]
  (l/derived (l/in [:monitor-log id]) st/state))

(mf/defc monitor-log
  [{:keys [monitor]}]
  (let [id           (:id monitor)
        log-data-ref (mf/use-memo (mf/deps id) #(log-data-ref id))
        log-data     (mf/deref log-data-ref)
        lmore        #(st/emit! (ptk/event :load-more-log monitor))]

    (mf/use-effect
     (mf/deps id)
     (fn []
       (st/emit! (ptk/event :initialize-monitor-log monitor))
       (fn []
         (st/emit! (ptk/data-event :finalize-monitor-log monitor)))))

    [:div.main-content
     [:h3 "Monitor log"]
     [:div.table.log-table
      [:ul.table-header
       [:li.icon ""]
       [:li.status "Status"]
       [:li.created-at "Created At"]
       [:li.latency "Latency"]
       [:li.reason "Reason"]]
      [:div.table-body
       (for [item (->> (vals (:items log-data))
                       (sort-by :created-at)
                       (reverse))]
         [:ul.table-body-item {:key (str (:monitor-id item) (inst-ms (:created-at item)))
                               :class (dom/classnames
                                       :status-up (= (:status item) "up")
                                       :status-down (= (:status item) "down"))}
          [:li.icon (case (:status item)
                      "up" i/chevron-circle-up
                      "down" i/chevron-circle-down
                      nil)]
          [:li.status (str/upper (:status item))]
          [:li.created-at (dt/format (:created-at item) "PPp")]
          [:li.latency {:title (:reason item "")} (str (:latency item) "ms")]
          [:li.reason (:reason item "---")]])
       [:div.load-more-button
        (when (:load-more log-data)
          [:a {:on-click lmore} "Load more"])]]]]))



