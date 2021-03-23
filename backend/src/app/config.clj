;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns app.config
  (:refer-clojure :exclude [get])
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.version :as v]
   [app.util.time :as dt]
   [clojure.core :as c]
   [buddy.core.codecs :as bc]
   [buddy.core.kdf :as bk]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [environ.core :refer [env]]
   [integrant.core :as ig]))

(s/def ::database-password (s/nilable ::us/string))
(s/def ::database-uri ::us/string)
(s/def ::database-username (s/nilable ::us/string))
(s/def ::debug ::us/boolean)
(s/def ::default-profile-type ::us/string)
(s/def ::error-reporter-webhook-uri ::us/string)
(s/def ::host ::us/string)
(s/def ::http-server-port ::us/integer)
(s/def ::public-uri ::us/string)

(s/def ::smtp-default-from ::us/string)
(s/def ::smtp-default-reply-to ::us/string)
(s/def ::smtp-enabled ::us/boolean)
(s/def ::smtp-host ::us/string)
(s/def ::smtp-password (s/nilable ::us/string))
(s/def ::smtp-port ::us/integer)
(s/def ::smtp-ssl ::us/boolean)
(s/def ::smtp-tls ::us/boolean)
(s/def ::smtp-username (s/nilable ::us/string))

(s/def ::smtp-username (s/nilable ::us/string))
(s/def ::telegram-id ::us/integer)
(s/def ::telegram-token ::us/string)
(s/def ::srepl-host ::us/string)
(s/def ::srepl-port ::us/integer)
(s/def ::rlimits-password ::us/integer)

(s/def ::loggers-zmq-uri ::us/string)
(s/def ::error-report-webhook ::us/string)

(s/def ::github-client-id ::us/string)
(s/def ::github-client-secret ::us/string)
(s/def ::gitlab-base-uri ::us/string)
(s/def ::gitlab-client-id ::us/string)
(s/def ::gitlab-client-secret ::us/string)
(s/def ::google-client-id ::us/string)
(s/def ::google-client-secret ::us/string)

(s/def ::profile-bounce-max-age ::dt/duration)
(s/def ::profile-bounce-threshold ::us/integer)
(s/def ::profile-complaint-max-age ::dt/duration)
(s/def ::profile-complaint-threshold ::us/integer)
(s/def ::tenant ::us/string)

(s/def ::config
  (s/keys :opt-un [::database-password
                   ::database-uri
                   ::error-report-webhook
                   ::database-username
                   ::tenant
                   ::github-client-id
                   ::github-client-secret
                   ::gitlab-base-uri
                   ::gitlab-client-id
                   ::gitlab-client-secret
                   ::google-client-id
                   ::google-client-secret
                   ::debug
                   ::tenant
                   ::profile-bounce-max-age
                   ::loggers-zmq-uri
                   ::profile-bounce-threshold
                   ::profile-complaint-max-age
                   ::profile-complaint-threshold
                   ::http-session-idle-max-age
                   ::http-session-updater-batch-max-age
                   ::http-session-updater-batch-max-size
                   ::default-profile-type
                   ::error-reporter-webhook-uri
                   ::host
                   ::http-server-port
                   ::srepl-host
                   ::srepl-port
                   ::rlimits-password
                   ::public-uri
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
  {:http-server-port 4460

   :host "devenv"
   :tenant "dev"

   :database-uri "postgresql://postgres:5432/sereno"
   :database-username "sereno"
   :database-password "sereno"

   :smtp-enabled false
   :smtp-default-reply-to "Sereno <no-reply@example.com>"
   :smtp-default-from "Sereno <no-reply@example.com>"
   :public-uri "http://localhost:4449"

   :rlimits-password 10
   :rlimits-image 2

   :profile-complaint-max-age (dt/duration {:days 7})
   :profile-complaint-threshold 2

   :loggers-zmq-uri "tcp://localhost:45556"

   :profile-bounce-max-age (dt/duration {:days 7})
   :profile-bounce-threshold 10

   :srepl-host "127.0.0.1"
   :srepl-port 4461})

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

(def config  (atom (read-config env)))
(def version (v/parse "%version%"))

(defn get
  "A configuration getter. Helps code be more testable."
  ([key]
   (c/get @config key))
  ([key default]
   (c/get @config key default)))
