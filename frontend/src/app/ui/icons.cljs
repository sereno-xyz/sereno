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

(def bomb (icon-xref :bomb))
(def check (icon-xref :check))
(def check-circle (icon-xref :check-circle))
(def chevron-circle-down (icon-xref :chevron-circle-down))
(def chevron-circle-up (icon-xref :chevron-circle-up))
(def chevron-down (icon-xref :chevron-down))
(def chevron-left (icon-xref :chevron-left))
(def circle (icon-xref :circle))
(def circle-notch (icon-xref :circle-notch))
(def clock (icon-xref :clock))
(def cloud (icon-xref :cloud))
(def download (icon-xref :download))
(def edit (icon-xref :edit))
(def ellipsis-v (icon-xref :ellipsis-v))
(def envelope (icon-xref :envelope))
(def exclamation (icon-xref :exclamation))
(def globe (icon-xref :globe))
(def google (icon-xref :google))
(def heartbeat (icon-xref :heartbeat))
(def history (icon-xref :history))
(def info (icon-xref :info))
(def info-circle (icon-xref :info-circle))
(def key (icon-xref :key))
(def list-alt (icon-xref :list-alt))
(def logo (icon-xref :logo))
(def mattermost (icon-xref :mattermost))
(def pause (icon-xref :pause))
(def pen-square (icon-xref :pen-square))
(def play (icon-xref :play))
(def plus (icon-xref :plus))
(def shield-alt (icon-xref :shield-alt))
(def sign-out-alt (icon-xref :sign-out-alt))
(def telegram (icon-xref :telegram))
(def times (icon-xref :times))
(def times-circle (icon-xref :times-circle))
(def trash-alt (icon-xref :trash-alt))
(def upload (icon-xref :upload))
(def user (icon-xref :user))
