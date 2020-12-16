;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors.ssl
  (:require
   [app.common.data :as d]
   [app.store :as st]
   [app.ui.monitors.common :refer [monitor-history]]
   [app.ui.monitors.http :refer [monitor-detail]]
   [rumext.alpha :as mf]))

(mf/defc ssl-monitor
  {::mf/wrap [mf/memo]}
  [{:keys [monitor] :as props}]
  [:main.monitor-detail-section
   [:section
    [:& monitor-detail {:monitor monitor}]
    [:& monitor-history {:monitor monitor}]]])

