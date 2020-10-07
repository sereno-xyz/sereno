;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.common.spec
  "Data manipulation and query helper functions."
  (:refer-clojure :exclude [assert])
  #?(:cljs (:require-macros [app.common.spec :refer [assert]]))
  (:require
   #?(:clj [clojure.spec.alpha :as s]
      :cljs [cljs.spec.alpha :as s])
   [expound.alpha :as expound]
   [app.common.uuid :as uuid]
   [app.common.exceptions :as ex]
   [cuerdas.core :as str]))

(s/check-asserts true)

;; --- Constants

(def email-rx
  #"[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+")

(def uuid-rx
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(def uri-rx
  #"^(http|https)://[\w-]+(\.[\w-]+)*([\w.,@?^=%&amp;:/~+#-]*[\w@?^=%&amp;/~+#-])?$")

;; --- Conformers

(defn- uuid-conformer
  [v]
  (if (uuid? v)
    v
    (if (string? v)
      (if (re-matches uuid-rx v)
        (uuid/uuid v)
        (if (str/empty? v) nil ::s/invalid))
      ::s/invalid)))

(defn boolean-conformer
  [v]
  (if (boolean? v)
    v
    (if (string? v)
      (if (re-matches #"^(?:t|true|false|f|0|1)$" v)
        (contains? #{"t" "true" "1"} v)
        ::s/invalid)
      ::s/invalid)))

(defn keyword-conformer
  [v]
  (cond
    (keyword? v)
    v

    (string? v)
    (keyword v)

    :else
    ::s/invalid))

(defn boolean-unformer
  [v]
  (if v "true" "false"))

(defn- number-conformer
  [v]
  (cond
    (number? v) v
    (str/numeric? v)
    #?(:clj (Double/parseDouble v)
       :cljs (js/parseFloat v))
    :else ::s/invalid))

(defn- integer-conformer
  [v]
  (cond
    (integer? v) v
    (string? v)
    (if (re-matches #"^[-+]?\d+$" v)
      #?(:clj (Long/parseLong v)
         :cljs (js/parseInt v 10))
      ::s/invalid)
    :else ::s/invalid))

(defn email-string?
  [s]
  (boolean (re-matches email-rx s)))

(defn uri-string?
  [s]
  (boolean (re-matches uri-rx s)))

(defn- email-conformer
  [v]
  (if (and (string? v) (re-matches email-rx v))
    v
    ::s/invalid))

;; --- Default Specs

(s/def ::keyword (s/conformer keyword-conformer name))
(s/def ::inst inst?)
(s/def ::string string?)
(s/def ::email (s/conformer email-conformer str))
(s/def ::uuid (s/conformer uuid-conformer str))
(s/def ::boolean (s/conformer boolean-conformer boolean-unformer))
(s/def ::number (s/conformer number-conformer str))
(s/def ::integer (s/conformer integer-conformer str))
(s/def ::not-empty-string (s/and string? #(not (str/empty? %))))
(s/def ::uri uri-string?)
(s/def ::url uri-string?)
(s/def ::fn fn?)
(s/def ::set-of-uuid (s/coll-of ::uuid :kind set?))
(s/def ::set-of-email (s/coll-of ::email :kind set?))

(letfn [(conformer [s]
          (cond
            (set? s)    (s/conform ::set-of-uuid s)
            (string? s) (into #{} (map uuid/uuid) (re-seq uuid-rx s))
            :else       ::s/invalid))
        (unformer [s]
          (str/join "," s))]
  (s/def ::str-of-uuid (s/conformer conformer unformer)))

(letfn [(conformer [s]
          (cond
            (set? s)    (s/conform ::set-of-email s)
            (string? s) (into #{} (re-seq email-rx s))
            :else       ::s/invalid))
        (unformer [s]
          (str/join "," s))]
  (s/def ::str-of-email (s/conformer conformer unformer)))


;; --- Macros

(defn spec-assert
  [spec x]
  (s/assert* spec x))

(defmacro assert
  "Development only assertion macro."
  [spec x]
  (when *assert*
    `(spec-assert ~spec ~x)))

(defmacro verify
  "Always active assertion macro (does not obey to :elide-asserts)"
  [spec x]
  `(spec-assert ~spec ~x))

;; --- Public Api

(defn conform
  [spec data]
  (let [result (s/conform spec data)]
    (when (= result ::s/invalid)
      (let [edata (s/explain-data spec data)]
        (throw (ex/error :type :validation
                         :code :spec-validation
                         :explain (with-out-str
                                    (expound/printer edata))
                         :data (::s/problems edata)))))
    result))
