;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.semaphore
  "Helpers for working with semaphores."
  (:refer-clojure :exclude [locking])
  (:import
   java.util.concurrent.Semaphore))

(defn- acquire
  [^Semaphore sem]
  (.acquire sem))

(defn- release
  [^Semaphore sem]
  (.release sem))

(defmacro locking
  [ssym & body]
  `(try
     (acquire ~ssym)
     ~@body
     (finally
       (release ~ssym))))

(defn semaphore
  ([permits]
   (Semaphore. (int permits) true))
  ([permits fair]
   (Semaphore. (int permits) ^Boolean fair)))

(defn wrap
  [f ^Semaphore sem]
  (fn
    ([] (locking sem (f)))
    ([a] (locking sem (f a)))
    ([a b] (locking sem (f a b)))
    ([a b c] (locking sem (f a b c)))
    ([a b c d] (locking sem (f a b c d)))
    ([a b c d & rest] (locking sem (apply f a b c d rest)))))
