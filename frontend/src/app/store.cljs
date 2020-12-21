;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.store
  (:require-macros [app.store])
  (:require
   [beicon.core :as rx]
   [okulary.core :as l]
   [potok.core :as ptk]
   [app.config :as cfg]
   [app.common.uuid :as uuid]
   [app.util.storage :refer [storage]]
   [app.util.http-api :as http]))

(enable-console-print!)

(defonce state  (ptk/store {:resolve ptk/resolve}))
(defonce stream (ptk/input-stream state))

(defmethod ptk/resolve :default
  [type params]
  (ptk/data-event type params))

;; Refs

(def route-ref
  (l/derived :route state))

(def router-ref
  (l/derived :router state))

(def message-ref
  (l/derived :message state))

(def profile-ref
  (l/derived :profile state))

(def monitors-ref
  (l/derived :monitors state))

(def contacts-ref
  (l/derived :contacts state))

(defn contact-ref
  [id]
  (l/derived (l/in [:contacts id]) state))

(defn emit!
  ([] nil)
  ([event]
   (ptk/emit! state event)
   nil)
  ([event & events]
   (apply ptk/emit! state (cons event events))
   nil))

(defn emitf
  [& events]
  #(apply emit! events))

(def initial-state
  {:profile (:profile storage)})

(defn init
  "Initialize the state materialization."
  ([] (init {}))
  ([props]
   (emit! #(merge % initial-state props))))

