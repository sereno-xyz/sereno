;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitor-detail-latency-chart
  (:require
   ["chart.js" :as cht]
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

(defn prepare-data
  [data]
  (clj->js
   (map (fn [item]
          {:value (:avg item)
           :key "avg"
           :ts (.toJSDate ^js (:ts item))})
        data)))

(mf/defc latency-chart
  {::mf/wrap [mf/memo]}
  [{:keys [data] :as props}]
  (let [ref (mf/use-ref)]
    (mf/use-effect
     (mf/deps data)
     (fn []
       (when data
         (let [dom  (mf/ref-val ref)
               data (prepare-data data)]
           (ilc/render dom #js {:width 1160
                                :height (.-clientHeight dom)
                                :data data})
           (fn []
             (ilc/clear dom))))))
    [:div.chart {:ref ref}]))


