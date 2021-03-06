;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.websockets
  "A interface to webworkers exposed functionality."
  (:require
   [goog.events :as ev]
   [app.config :as cfg]
   [beicon.core :as rx]
   [potok.core :as ptk]
   ["reconnecting-websocket" :as RWebSocket])
  (:import
   goog.Uri
   goog.net.WebSocket
   goog.net.WebSocket.EventType))

(defn uri
  ([path] (uri path {}))
  ([path params]
   (let [uri (.parse ^js Uri cfg/public-uri)]
     (.setPath ^js uri path)
     (if (= (.getScheme ^js uri) "http")
       (.setScheme ^js uri "ws")
       (.setScheme ^js uri "wss"))
     (run! (fn [[k v]]
             (.setParameterValue ^js uri (name k) (str v)))
           params)
     (.toString uri))))

(defn websocket
  [uri]
  (letfn [(on-create [sink]
            (let [ws  (RWebSocket. uri)
                  lk1 (ev/listen ws "message"
                                 (fn [event]
                                   (let [event (.getBrowserEvent ^js event)]
                                     (sink (unchecked-get event "data")))))
                  ;; lk2 (ev/listen ws "error" (fn [e] (js/console.log "E" e)))
                  ]
              (fn []
                (.close ^js ws)
                (ev/unlistenByKey lk1)
                ;; (ev/unlistenByKey lk2)
                )))]

    (rx/create on-create)))
