;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.auth.notification
  (:require
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(mf/defc notification-modal
  {::mf/register modal/components
   ::mf/register-as :notification}
  [{:keys [message] :as props}]
  [:div.modal.notification-modal
   [:span.close {:on-click #(modal/hide!)} i/times]

   [:div.modal-content
    [:div.notification
     [:span message]]]])

