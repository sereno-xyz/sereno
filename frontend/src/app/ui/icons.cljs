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
  (:require-macros [app.util.icons :refer [icon-xref]])
  (:require [rumext.alpha :as mf]))

(def user (icon-xref :user))
(def history (icon-xref :history))
(def times (icon-xref :times))
(def download (icon-xref :download))
(def upload (icon-xref :upload))
(def edit (icon-xref :edit))
(def list-alt (icon-xref :list-alt))
(def chevron-left (icon-xref :chevron-left))
(def chevron-circle-down (icon-xref :chevron-circle-down))
(def chevron-circle-up (icon-xref :chevron-circle-up))
(def chevron-down (icon-xref :chevron-down))
(def pen-square (icon-xref :pen-square))
(def envelope (icon-xref :envelope))
(def key (icon-xref :key))
(def pause (icon-xref :pause))
(def play (icon-xref :play))
(def sign-out-alt (icon-xref :sign-out-alt))
(def check-circle (icon-xref :check-circle))
(def info (icon-xref :info))
(def times-circle (icon-xref :times-circle))
(def circle (icon-xref :circle))
(def circle-notch (icon-xref :circle-notch))
(def clock (icon-xref :clock))
(def plus (icon-xref :plus))
(def trash-alt (icon-xref :trash-alt))
(def check (icon-xref :check))
(def bomb (icon-xref :bomb))
(def exclamation (icon-xref :exclamation))
(def google (icon-xref :google))
(def logo (icon-xref :logo))
(def mattermost (icon-xref :mattermost))
(def telegram (icon-xref :telegram))
