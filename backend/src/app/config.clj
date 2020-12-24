;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.config
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.version :as v]
   [app.util.time :as tm]
   [buddy.core.codecs :as bc]
   [buddy.core.kdf :as bk]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [cuerdas.core :as str]
   [environ.core :refer [env]]
   [integrant.core :as ig]))

(s/def ::database-password (s/nilable ::us/string))
(s/def ::database-uri ::us/string)
(s/def ::database-username (s/nilable ::us/string))
(s/def ::debug ::us/boolean)
(s/def ::default-profile-type ::us/string)
(s/def ::error-reporter-webhook-uri ::us/string)
(s/def ::google-client-id ::us/string)
(s/def ::google-client-secret ::us/string)
(s/def ::host ::us/string)
(s/def ::http-server-port ::us/integer)
(s/def ::password-hashing-permits ::us/integer)
(s/def ::public-uri ::us/string)
(s/def ::secret-key ::us/string)
(s/def ::smtp-default-from ::us/email)
(s/def ::smtp-default-reply-to ::us/email)
(s/def ::smtp-enabled ::us/boolean)
(s/def ::smtp-host ::us/string)
(s/def ::smtp-password (s/nilable ::us/string))
(s/def ::smtp-port ::us/integer)
(s/def ::smtp-ssl ::us/boolean)
(s/def ::smtp-tls ::us/boolean)
(s/def ::smtp-username (s/nilable ::us/string))
(s/def ::telegram-id ::us/integer)
(s/def ::telegram-token ::us/string)

(s/def ::config
  (s/keys :opt-un [::database-password
                   ::database-uri
                   ::database-username
                   ::debug
                   ::default-profile-type
                   ::error-reporter-webhook-uri
                   ::google-client-id
                   ::google-client-secret
                   ::host
                   ::http-server-port
                   ::password-hashing-permits
                   ::public-uri
                   ::secret-key
                   ::sendmail-backend
                   ::smtp-enabled
                   ::smtp-default-from
                   ::smtp-default-reply-to
                   ::smtp-from
                   ::smtp-host
                   ::smtp-password
                   ::smtp-port
                   ::smtp-ssl
                   ::smtp-tls
                   ::smtp-username
                   ::telegram-id
                   ::telegram-token
                   ::telegram-username]))

(def defaults
  {:debug true
   :host "devenv"
   :smtp-enabled false
   :smtp-host "localhost"
   :smtp-port 25
   :smtp-default-reply-to "no-reply@example.com"
   :smtp-default-from "no-reply@example.com"
   :public-uri "http://localhost:4449"
   :password-hashing-permits 3
   :database-uri "postgresql://postgres:5432/sereno"
   :database-username "sereno"
   :database-password "sereno"
   :repl-server-host "localhost"
   :repl-server-port 4461
   :http-server-port 4460})

(defn- env->config
  [env]
  (reduce-kv (fn [acc k v]
               (cond-> acc
                 (str/starts-with? (name k) "sereno-")
                 (assoc (keyword (subs (name k) 7)) v)

                 (str/starts-with? (name k) "app-")
                 (assoc (keyword (subs (name k) 4)) v)))
             {}
             env))

(defn read-config
  [env]
  (->> (env->config env)
       (merge defaults)
       (us/conform ::config)))

(def config (read-config env))
(def version (v/parse "%version%"))
