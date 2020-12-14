;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.notifications
  (:require
   [app.events.messages :as em]
   [app.store :as st]
   [app.ui.icons :as i]
   [app.util.dom :as dom]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(defn- type->icon
  [type]
  (case type
    :warning i/exclamation
    :error i/bomb
    :success i/check
    :info i/info))

(mf/defc notification-item
  [{:keys [type status on-close quick? content] :as props}]
  (let [klass (dom/classnames
               :success (= type :success)
               :error   (= type :error)
               :info    (= type :info)
               :warning (= type :warning)
               :hide    (= status :hide)
               :quick   quick?)]
    [:section.banner {:class klass}
     [:div.content
      [:div.icon (type->icon type)]
      [:span content]]
     [:div.close {:on-click on-close} i/times]]))

(mf/defc notifications
  []
  (let [message  (mf/deref st/message-ref)
        on-close (st/emitf (em/hide))]
    (when message
      [:& notification-item
       {:type (:type message)
        :quick? (boolean (:timeout message))
        :status (:status message)
        :content (:content message)
        :on-close on-close}])))



