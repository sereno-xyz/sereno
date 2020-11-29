;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.migrations
  (:require
   [app.util.migrations :as mg]
   [integrant.core :as ig]))

(def migrations
  {:name "main"
   :steps
   [{:name "0001-add-extensions"
     :fn (mg/resource "app/migrations/sql/0001-add-extensions.sql")}

    {:name "0002-add-profile-tables"
     :fn (mg/resource "app/migrations/sql/0002-add-profile-tables.sql")}

    {:name "0003-add-monitors-tables"
     :fn (mg/resource "app/migrations/sql/0003-add-monitors-tables.sql")}

    {:name "0004-add-tasks-tables"
     :fn (mg/resource "app/migrations/sql/0004-add-tasks-tables.sql")}

    {:name "0005-add-generic-token-table"
     :fn (mg/resource "app/migrations/sql/0005-add-generic-token-table.sql")}

    {:name "0006-add-http-session-table"
     :fn (mg/resource "app/migrations/sql/0006-add-http-session-table.sql")}

    {:name "0007-mod-contact-table"
     :fn (mg/resource "app/migrations/sql/0007-mod-contact-table.sql")}

    {:name "0008-add-contact-bounce-table"
     :fn (mg/resource "app/migrations/sql/0008-add-contact-bounce-table.sql")}

    {:name "0009-mod-profile-table"
     :fn (mg/resource "app/migrations/sql/0009-mod-profile-table.sql")}

    {:name "0010-add-profile-counters-table"
     :fn (mg/resource "app/migrations/sql/0010-add-profile-counters-table.sql")}

    {:name "0011-mod-contact-table"
     :fn (mg/resource "app/migrations/sql/0011-mod-contact-table.sql")}

    {:name "0012-add-profile-incident-table"
     :fn (mg/resource "app/migrations/sql/0012-add-profile-incident-table.sql")}

    {:name "0013-mod-contact-table-add-email-index"
     :fn (mg/resource "app/migrations/sql/0013-mod-contact-table-add-email-index.sql")}

    {:name "0014-del-contact-bounce-table"
     :fn (mg/resource "app/migrations/sql/0014-del-contact-bounce-table.sql")}

    {:name "0015-del-generic-token-table"
     :fn (mg/resource "app/migrations/sql/0015-del-generic-token-table.sql")}

    {:name "0016-mod-contact-table-add-telegram-fields"
     :fn (mg/resource "app/migrations/sql/0016-mod-contact-table-add-telegram-fields.sql")}

    {:name "0017-mod-change-notify-trigger"
     :fn (mg/resource "app/migrations/sql/0017-mod-change-notify-trigger.sql")}

    {:name "0018-mod-monitor-table-add-expired-at"
     :fn (mg/resource "app/migrations/sql/0018-mod-monitor-table-add-expired-at.sql")}

    {:name "0019-mod-monitor-status-table-add-cause"
     :fn (mg/resource "app/migrations/sql/0019-mod-monitor-status-table-add-cause.sql")}

    {:name "0020-mod-contact-table"
     :fn (mg/resource "app/migrations/sql/0020-mod-contact-table.sql")}

    ]})

(defmethod ig/init-key ::migrations
  [_ _]
  (fn [conn]
    (mg/setup! conn)
    (mg/migrate! conn migrations)))

