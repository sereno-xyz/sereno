;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.icons
  (:refer-clojure :exclude [key])
  (:require-macros [app.util.icons :refer [define-solid-icon define-brand-icon]])
  (:require [rumext.alpha :as mf]))

(define-solid-icon :times)
(define-solid-icon :download)
(define-solid-icon :upload)
(define-solid-icon :edit)
(define-solid-icon :chevron-left)
(define-solid-icon :chevron-circle-down)
(define-solid-icon :chevron-circle-up)
(define-solid-icon :chevron-down)
(define-solid-icon :bullseye)
(define-solid-icon :mail-bulk)
(define-solid-icon :pen-square)
(define-solid-icon :bell)
(define-solid-icon :bell-slash)
(define-solid-icon :ellipsis-v)
(define-solid-icon :pen)
(define-solid-icon :anchor)
(define-solid-icon :globe)
(define-solid-icon :code-branch)
(define-solid-icon :code)
(define-solid-icon :sms)
(define-solid-icon :shield-alt)
(define-solid-icon :envelope)
(define-solid-icon :link)
(define-solid-icon :key)

(define-brand-icon :slack)
(define-brand-icon :telegram)
(define-brand-icon :google)
(define-solid-icon :pause)
(define-solid-icon :play)
(define-solid-icon :sign-out-alt)
(define-solid-icon :sign-in-alt)
(define-solid-icon :user-plus)
(define-solid-icon :user)
(define-solid-icon :check-circle)
(define-solid-icon :info)
(define-solid-icon :minus-circle)
(define-solid-icon :times-circle)
(define-solid-icon :circle)
(define-solid-icon :circle-notch)
(define-solid-icon :clock)
(define-solid-icon :history)
(define-solid-icon :check-double)
(define-solid-icon :plus)
(define-solid-icon :trash-alt)
(define-solid-icon :check)
(define-solid-icon :bomb)
(define-solid-icon :ban)

(define-solid-icon :eye)
(define-solid-icon :tools)
(define-solid-icon :exclamation)
(define-solid-icon :exclamation-circle)
(define-solid-icon :comment-alt)

(def logo
  (rumext.alpha/html
   [:svg {:width "500"
          :header "500"
          :xmlns "http://www.w3.org/2000/svg"
          :viewBox "0 0 512 512"}
    [:path {:d "M288 39.056v16.659c0 10.804 7.281 20.159 17.686
                23.066C383.204 100.434 440 171.518 440 256c0
                101.689-82.295 184-184 184-101.689
                0-184-82.295-184-184 0-84.47 56.786-155.564
                134.312-177.219C216.719 75.874 224 66.517 224
                55.712V39.064c0-15.709-14.834-27.153-30.046-23.234C86.603
                43.482 7.394 141.206 8.003 257.332c.72 137.052 111.477
                246.956 248.531 246.667C393.255 503.711 504 392.788 504
                256c0-115.633-79.14-212.779-186.211-240.236C302.678
                11.889 288 23.456 288 39.056z"}]]))

(def mattermost
  (rumext.alpha/html
   [:svg {:width "2500" :header "2500"
          :viewBox "0 0 256 256"
          :xmlns "http://www.w3.org/2000/svg"
          :preserveAspectRatio "xMidYMid"}
    [:path {:d "M243.747
                73.364c-8.454-18.258-20.692-33.617-36.9-46.516.432
                8.756 1.374 27.484 1.374 27.484s1.01 1.327 1.42
                1.901c16.38 22.876 22.365 48.231 16.91 75.771-10.94
                55.222-64.772 88.91-119.325
                75.138-47.892-12.091-79.878-61.06-70.82-109.609
                7.15-38.327 29.801-63.859 66.584-76.833l1.333-.504
                1.046-.774c4.458-6.304 8.808-12.685 13.45-19.422C63.07
                2.762 7.003 47.488.58 115.522c-6.216 65.832 38.17
                124.541 101.596 137.424 66.096 13.425 129.017-25.57
                148.031-87.976 9.508-31.206
                7.283-61.924-6.46-91.606zm-157.31 41.172c2.787 26.487
                25.745 44.538 52.302 41.603 26.092-2.884 45.166-28.409
                40.227-54.232-3.85-20.134-8.105-40.19-12.188-60.279-1.689-8.313-3.398-16.623-5.19-25.391-.707.621-1.035.883-1.334
                1.176-8.94 8.764-17.875 17.533-26.815 26.297-10.886
                10.673-21.757 21.36-32.669 32.006-10.944 10.677-15.926
                23.707-14.334 38.82z"}]]))



