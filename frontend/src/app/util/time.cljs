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
   ["date-fns/parseISO" :as dt-parse-iso]
   ["date-fns/format" :as dt-format]
   ["date-fns/formatISO" :as dt-format-iso]
   ["date-fns/formatDistanceToNow" :as dt-format-distance]
   ["date-fns/locale/fr" :as dt-fr-locale]
   ["date-fns/locale/en-US" :as dt-en-locale]
   ["humanize-duration" :as hmd]
   [goog.object :as gobj]))

(def ^:private locales
  #js {:default dt-en-locale
       :en dt-en-locale
       :en_US dt-en-locale
       :fr dt-fr-locale
       :fr_FR dt-fr-locale})

(defn now
  "Return the current Instant."
  []
  (js/Date.))

(defn format
  ([v fmt] (format v fmt nil))
  ([v fmt {:keys [locale]
           :or {locale "default"}}]
   (when v
     (dt-format v fmt #js {:locale (gobj/get locales locale)}))))

(defn format-iso
  [v]
  (dt-format-iso v))

(defn parse
  [s]
  (dt-parse-iso s))

(def ^:private humanizer-options
  #js {:language "shortEn"
       :spacer ""
       :round true
       :largest 2
       :languages #js {:shortEn #js {:y (constantly "y")
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
  (let [nowms (inst-ms (now))
        vms   (inst-ms v)]
    (humanize-duration (- nowms vms))))

