;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.modal
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.store :as st]
   [app.util.dom :as dom]
   [app.util.keyboard :as k]
   [app.util.router :as r]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.alpha :as mf])
  (:import goog.events.EventType))

(defonce components (atom {}))

(s/def ::type keyword?)
(s/def ::modal-props
  (s/keys :opt-un [::type]
          :opt [::type]))

(defn show
  [props]
  (us/assert ::modal-props props)
  (ptk/reify ::show
    ptk/UpdateEvent
    (update [_ state]
      (let [id    (uuid/next)
            type  (or (:type props) (::type props))
            props (dissoc props :type ::type)]
        (assoc state ::modal {:id id :type type :props props})))))

(defn hide
  []
  (ptk/reify ::hide
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state ::modal))))

(defn show!
  [props]
  (st/emit! (show props)))

(defn hide!
  []
  (st/emit! (hide)))

(defn- on-esc-clicked
  [event]
  (when (k/esc? event)
    (hide!)
    (dom/stop-propagation event)))

(mf/defc modal-wrapper
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (mf/use-layout-effect
   (fn []
     (let [key1 (events/listen js/document "keydown" on-esc-clicked)]
       #(events/unlistenByKey key1))))
  (let [data (unchecked-get props "data")
        cmp  (get @components (:type data))]
    (when cmp
      [:div.modal-wrapper
       (mf/element cmp (:props data))])))


(def modal-ref
  (l/derived ::modal st/state))

(mf/defc modal []
  (when-let [modal (mf/deref modal-ref)]
    [:& modal-wrapper {:data modal
                       :key (:id modal)}]))



