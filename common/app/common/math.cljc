;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.common.math
  "A collection of math utils."
  #?(:cljs
     (:require [goog.math :as math])))

(defn nan?
  [v]
  #?(:cljs (js/isNaN v)
     :clj (Double/isNaN v)))

(defn finite?
  [v]
  #?(:cljs (js/isFinite v)
     :clj (Double/isFinite v)))

(defn abs
  [v]
  #?(:cljs (js/Math.abs v)
     :clj (Math/abs v)))

(defn neg
  "Negate the number"
  [v]
  (- v))

(defn sqrt
  "Returns the square root of a number."
  [v]
  #?(:cljs (js/Math.sqrt v)
     :clj (Math/sqrt v)))

(defn pow
  "Returns the base to the exponent power."
  [b e]
  #?(:cljs (js/Math.pow b e)
     :clj (Math/pow b e)))

(defn floor
  "Returns the largest integer less than or
  equal to a given number."
  [v]
  #?(:cljs (js/Math.floor v)
     :clj (Math/floor v)))

(defn round
  "Returns the value of a number rounded to
  the nearest integer."
  [v]
  #?(:cljs (js/Math.round v)
     :clj (Math/round v)))

(defn ceil
  "Returns the smallest integer greater than
  or equal to a given number."
  [v]
  #?(:cljs (js/Math.ceil v)
     :clj (Math/ceil v)))

(defn precision
  [v n]
  (when (and (number? v) (number? n))
    (let [d (pow 10 n)]
      (/ (round (* v d)) d))))
