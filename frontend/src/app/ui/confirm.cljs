;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.confirm
  (:require
   [app.store :as st]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc confirm-dialog
  {::mf/register modal/components
   ::mf/register-as :confirm}
  [{:keys [message on-accept title on-cancel hint cancel-label accept-label] :as props}]
  (let [message      (or message "NO MESSAGE")
        title        (or title "NO TITLE")
        cancel-label (or cancel-label "Cancel")
        accept-label (or accept-label "Ok")
        on-accept    (or on-accept (constantly nil))
        on-cancel    (or on-cancel (constantly nil))

        accept
        (mf/use-callback
         #(do (st/emit! (modal/hide))
              (on-accept props)))

        cancel
        (mf/use-callback
         #(do (st/emit! (modal/hide))
              (on-cancel props)))]

    [:div.modal-overlay
     [:div.modal.confirm-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 title]]
       [:div.modal-close-button
        {:on-click cancel} i/times]]
      [:div.modal-content
       [:p message]
       (when (string? hint)
         [:p.hint hint])]
      [:div.modal-footer
       [:div.action-buttons
        [:a.accept-button {:on-click accept} accept-label]
        [:a.cancel-button {:on-click cancel} cancel-label]]]]]))
