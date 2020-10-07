;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.icons
  (:require [rumext.alpha]))

(def base-uri "/fontawesome/sprites/solid.svg")
(def base-uri2 "/fontawesome/sprites/brands.svg")

(defmacro icon-xref
  [id]
  (let [href (str base-uri "#" (name id))]
    `(rumext.alpha/html
      [:svg {:width 500 :height 500}
       [:use {:xlinkHref ~href}]])))

(defmacro define-solid-icon
  [id]
  (let [href (str base-uri "#" (name id))
        sym  (symbol (name id))]
    `(def ~sym
       (rumext.alpha/html
        [:svg {:width 500 :height 500}
         [:use {:xlinkHref ~href}]]))))


(defmacro define-brand-icon
  [id]
  (let [href (str base-uri2 "#" (name id))
        sym  (symbol (name id))]
    `(def ~sym
       (rumext.alpha/html
        [:svg {:width 500 :height 500}
         [:use {:xlinkHref ~href}]]))))
