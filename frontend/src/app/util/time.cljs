;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.time
  (:require
   ["luxon" :as lxn]
   ["humanize-duration" :as hmd]))

(def DateTime lxn/DateTime)

(extend-protocol Inst
  DateTime
  (inst-ms* [inst] (.toMillis ^js inst)))

(extend-protocol IComparable
  DateTime
  (-compare [it other]
    (if ^boolean (.equals it other)
      0
      (if (< (inst-ms it) (inst-ms other)) -1 1))))

(defn now
  "Return the current Instant."
  []
  (.local ^js DateTime))

(defn- resolve-format
  [v]
  (case v
    :datetime-short (.-DATETIME_SHORT ^js DateTime)
    :datetime-med   (.-DATETIME_MED ^js DateTime)
    :datetime-full  (.-DATETIME_FULL ^js DateTime)
    :date-full      (.-DATE_FULL ^js DateTime)
    v))

(defn format
  ([v] (format v :datatime-short))
  ([v fmt]
   (when v
     (let [f (resolve-format fmt)]
       (.toLocaleString ^js v f)))))

(defn format-iso
  [d]
  (.toISO ^js d))

(defn parse
  [s]
  (.fromISO ^js DateTime s))

(def ^:private humanizer-options
  #js {:language "shortEn"
       :spacer ""
       :round true
       :largest 2
       :languages #js {:shortEn #js {:y  (constantly "y")
                                     :mo (constantly "mo")
                                     :w  (constantly "w")
                                     :d  (constantly "d")
                                     :h  (constantly "h")
                                     :m  (constantly "m")
                                     :s  (constantly "s")
                                     :ms (constantly "ms")}}})

(def ^js js-humanize
  (.humanizer hmd humanizer-options))

(defn humanize-duration
  ([ms] (js-humanize ms))
  ([ms {:keys [locale largest round]
        :or {largest 2 round true}}]
   (js-humanize ms #js {:language "shortEn"
                        :largest largest
                        :round round})))

(defn format-time-distance
  [t1 t2]
  (let [t1 (inst-ms t1)
        t2 (inst-ms t2)]
    (humanize-duration (- t1 t2))))

(defn timeago
  [v]
  (when v
    (let [nowms (inst-ms (now))
          vms   (inst-ms v)]
      (humanize-duration (- nowms vms)))))

