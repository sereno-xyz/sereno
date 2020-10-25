;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.object
  "A collection of helpers for work with javascript objects."
  (:refer-clojure :exclude [set! get get-in merge clone])
  (:require
   [cuerdas.core :as str]
   [goog.object :as gobj]
   ["camelcase" :as camelcase]))

(defn get
  ([obj k]
   (when-not (nil? obj)
     (unchecked-get obj k)))
  ([obj k default]
   (or (get obj k) default)))

(defn get-in
  [obj keys]
  (loop [key (first keys)
         keys (rest keys)
         res obj]
    (if (nil? key)
      res
      (if (nil? res)
        res
        (recur (first keys)
               (rest keys)
               (unchecked-get res key))))))

(defn clone
  [a]
  (js/Object.assign #js {} a))

(defn merge!
  ([a b]
   (js/Object.assign a b))
  ([a b & more]
   (reduce merge! (merge! a b) more)))

(defn merge
  ([a b]
   (js/Object.assign #js {} a b))
  ([a b & more]
   (reduce merge! (merge a b) more)))

(defn set!
  [obj key value]
  (unchecked-set obj key value)
  obj)

(defn clj->props
  [props]
  (clj->js props :keyword-fn (comp camelcase name)))


(defn without
  [obj keys]
  (reduce (fn [obj key]
            (js-delete obj key)
            obj)
          obj
          keys))
