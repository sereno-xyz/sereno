;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.repl
  "Server repl."
  (:require
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [app.common.spec :as us]
   [clojure.core.server :as srv]
   [integrant.core :as ig]))

(s/def ::name ::us/string)
(s/def ::host ::us/string)
(s/def ::port ::us/integer)

(defmethod ig/pre-init-spec ::server [_]
  (s/keys :req-un [::name
                   ::port
                   ::host]))

(defmethod ig/init-key ::server
  [_ {:keys [name port host]}]
  (log/infof "Starting repl server '%s'." name)
  (srv/start-server
   {:name name
    :port port
    :address host
    :accept 'clojure.core.server/repl})
  name)

(defmethod ig/halt-key! ::server
  [_ name]
  (srv/stop-server name))


