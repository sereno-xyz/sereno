;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.transit
  (:require
   [cognitect.transit :as t]
   [clojure.java.io :as io]
   [linked.core :as lk])
  (:import
   linked.set.LinkedSet
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.time.format.DateTimeFormatter
   java.time.Instant
   java.time.OffsetDateTime
   java.io.File))

;; --- Handlers

(def ^:private instant-write-handler
  (t/write-handler
   (constantly "instant")
   (fn [v] (.format DateTimeFormatter/ISO_INSTANT v))))

(def ^:private offset-datetime-write-handler
  (t/write-handler
   (constantly "instant")
   (fn [v] (.format DateTimeFormatter/ISO_INSTANT (.toInstant ^OffsetDateTime v)))))

(def ^:private instant-read-handler-legacy
  (t/read-handler
   (fn [v] (-> (Long/parseLong v)
               (Instant/ofEpochMilli)))))

(def ^:private instant-read-handler
  (t/read-handler
   (fn [v] (Instant/from (.parse DateTimeFormatter/ISO_INSTANT v)))))

(def ^:private file-write-handler
  (t/write-handler
   (constantly "file")
   (fn [v] (str v))))

(def ordered-set-write-handler
  (t/write-handler
   (constantly "ordered-set")
   (fn [v] (vec v))))

(def ordered-set-read-handler
  (t/read-handler #(into (lk/set) %)))

;; ---

(def +read-handlers+
  {"ordered-set" ordered-set-read-handler
   "m" instant-read-handler-legacy
   "instant" instant-read-handler})

(def +write-handlers+
  {Instant instant-write-handler
   OffsetDateTime offset-datetime-write-handler
   File file-write-handler
   LinkedSet ordered-set-write-handler})

;; --- Low-Level Api

(defn reader
  ([istream]
   (reader istream nil))
  ([istream {:keys [type] :or {type :json}}]
   (t/reader istream type {:handlers +read-handlers+})))

(defn read!
  "Read value from streamed transit reader."
  [reader]
  (t/read reader))

(defn writer
  ([ostream]
   (writer ostream nil))
  ([ostream {:keys [type] :or {type :json}}]
   (t/writer ostream type {:handlers +write-handlers+})))

(defn write!
  [writer data]
  (t/write writer data))

;; --- High-Level Api

(declare str->bytes)
(declare bytes->str)

(defn decode
  ([data]
   (decode data nil))
  ([data opts]
   (with-open [input (ByteArrayInputStream. ^bytes data)]
     (read! (reader input opts)))))

(defn encode
  ([data]
   (encode data nil))
  ([data opts]
   (with-open [out (ByteArrayOutputStream.)]
     (let [w (writer out opts)]
       (write! w data)
       (.toByteArray out)))))

(defn decode-str
  [message]
  (->> (str->bytes message)
       (decode)))

(defn encode-str
  ([message]
   (->> (encode message)
        (bytes->str)))
  ([message opts]
   (->> (encode message opts)
        (bytes->str))))

(defn encode-verbose-str
  [message]
  (->> (encode message {:type :json-verbose})
       (bytes->str)))

;; --- Helpers

(defn str->bytes
  "Convert string to byte array."
  ([^String s]
   (str->bytes s "UTF-8"))
  ([^String s, ^String encoding]
   (.getBytes s encoding)))

(defn bytes->str
  "Convert byte array to String."
  ([^bytes data]
   (bytes->str data "UTF-8"))
  ([^bytes data, ^String encoding]
   (String. data encoding)))
