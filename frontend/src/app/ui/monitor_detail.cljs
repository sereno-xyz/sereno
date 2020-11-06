;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitor-detail
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.store :as st]
   [app.ui.forms :as forms]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitor-detail-log :refer [monitor-log]]
   [app.ui.monitor-detail-status-history :refer [monitor-status-history]]
   [app.ui.monitor-detail-summary :refer [monitor-summary]]
   [app.ui.monitor-form]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(defn monitor-ref
  [id]
  (l/derived (l/in [:monitors id]) st/state))

(defn monitor-local-ref
  [id]
  (l/derived (l/in [:monitor-local id]) st/state))

(mf/defc monitor-detail-page
  {::mf/wrap [mf/memo]}
  [{:keys [id section] :as props}]
  (let [monitor-ref (mf/use-memo (mf/deps id) #(monitor-ref id))
        monitor     (mf/deref monitor-ref)]

    (mf/use-effect
     (fn []
       (st/emit! (ptk/event :initialize-monitor-detail {:id id}))
       (st/emitf (ptk/event :finalize-monitor-detail {:id id}))))

    (when monitor
      [:main.monitor-detail-section
       [:section
        (case section
          :monitor-detail
          [:*
           [:& monitor-summary {:monitor monitor}]
           [:& monitor-status-history {:monitor monitor}]]

          :monitor-log
          [:& monitor-log {:monitor monitor}]

          nil)]])))

