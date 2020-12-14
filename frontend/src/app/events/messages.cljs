;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.events.messages
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [app.common.spec :as us]
   [potok.core :as ptk]))

(def default-animation-timeout 600)
(def default-timeout 2000)

(s/def ::content ::us/not-empty-string)
(s/def ::type (s/or :kw keyword? :str string?))
(s/def ::timeout ::us/integer)

(s/def ::show-params
  (s/keys :req-un [::content ::type]
          :opt-un [::timeout]))

(defn hide
  []
  (ptk/reify ::hide
    ptk/UpdateEvent
    (update [_ state]
      (update state :message assoc :status :hide))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter (ptk/type? ::show) stream)]
        (->> (rx/of #(dissoc % :message))
             (rx/delay default-animation-timeout)
             (rx/take-until stoper))))))

(defn show
  [{:keys [timeout] :or {timeout default-timeout} :as data}]
  (us/assert ::show-params data)
  (ptk/reify ::show
    ptk/UpdateEvent
    (update [_ state]
      (let [message (assoc data :status :visible :timeout timeout)]
        (assoc state :message message)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [message (get state :message)
            stoper  (rx/filter (ptk/type? ::show-message) stream)]
        (when (pos? (:timeout message))
          (->> (rx/of (hide))
               (rx/delay (:timeout message))
               (rx/take-until stoper)))))))
