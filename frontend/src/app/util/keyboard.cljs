;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.keyboard)

(defn is-keycode?
  [keycode]
  (fn [^js e]
    (= (.-keyCode e) keycode)))

(defn ^boolean alt?
  [^js event]
  (.-altKey event))

(defn ^boolean ctrl?
  [^js event]
  (.-ctrlKey event))

(defn ^boolean shift?
  [^js event]
  (.-shiftKey event))

(def esc? (is-keycode? 27))
(def enter? (is-keycode? 13))
(def space? (is-keycode? 32))
