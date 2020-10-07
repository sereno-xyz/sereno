;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.transit
  "A lightweight abstraction for transit serialization."
  (:require
   [cognitect.transit :as t]
   [app.util.time :as dt]))

(deftype Blob [content]
  IDeref
  (-deref [_] content))

(defn blob?
  [v]
  (instance? Blob v))

(def blob-read-handler
  (t/read-handler
   (fn [value]
     (->Blob (js/JSON.parse value)))))

(def blob-write-handler
  (t/write-handler
   (constantly "jsonblob")
   (fn [v] (js/JSON.stringify @v))))

(def instant-read-handler
  (t/read-handler (fn [value] (dt/parse value))))

(def instant-write-handler
  (t/write-handler
   (constantly "instant")
   (fn [v] (dt/format-iso v))))


;; --- Transit Handlers

(def ^:privare +read-handlers+
  {"u" uuid
   "instant" instant-read-handler
   "jsonblob" blob-read-handler})

(def ^:privare +write-handlers+
  {Blob blob-write-handler
   js/Date instant-write-handler})

;; --- Public Api

(defn decode
  [data]
  (let [r (t/reader :json {:handlers +read-handlers+})]
    (t/read r data)))

(defn encode
  [data]
  (try
    (let [w (t/writer :json {:handlers +write-handlers+})]
      (t/write w data))
    (catch :default e
      (throw e))))
