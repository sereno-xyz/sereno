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

(s/def ::database-uri ::us/string)
(s/def ::database-password (s/nilable ::us/string))
(s/def ::database-username (s/nilable ::us/string))
(s/def ::default-profile-type ::us/string)
(s/def ::error-reporter-webhook-uri ::us/string)
(s/def ::google-client-id ::us/string)
(s/def ::google-client-secret ::us/string)
(s/def ::http-server-port ::us/integer)
(s/def ::public-uri ::us/string)
(s/def ::secret-key ::us/string)
(s/def ::smtp-enabled ::us/boolean)
(s/def ::smtp-default-from ::us/email)
(s/def ::smtp-default-reply-to ::us/email)
(s/def ::smtp-host ::us/string)
(s/def ::smtp-password (s/nilable ::us/string))
(s/def ::smtp-username (s/nilable ::us/string))
(s/def ::smtp-port ::us/integer)
(s/def ::smtp-ssl ::us/boolean)
(s/def ::smtp-tls ::us/boolean)
(s/def ::telegram-token ::us/string)
(s/def ::telegram-id ::us/integer)
(s/def ::debug ::us/boolean)
(s/def ::host ::us/string)

(s/def ::config
  (s/keys :opt-un [::http-server-port
                   ::database-username
                   ::database-password
                   ::database-uri
                   ::host
                   ::debug
                   ::secret-key
                   ::default-profile-type
                   ::error-reporter-webhook-uri
                   ::sendmail-backend
                   ::smtp-default-reply-to
                   ::smtp-default-from
                   ::smtp-host
                   ::smtp-port
                   ::smtp-from
                   ::smtp-username
                   ::smtp-password
                   ::smtp-ssl
                   ::smtp-tls
                   ::telegram-username
                   ::telegram-id
                   ::telegram-token
                   ::public-uri
                   ::google-client-secret
                   ::google-client-id]))

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

(def default-config
  {:debug true
   :smtp-enabled false
   :smtp-host "localhost"
   :smtp-port 25
   :smtp-default-reply-to "no-reply@example.com"
   :smtp-default-from "no-reply@example.com"
   :public-uri "http://localhost:4449"
   :database-uri "postgresql://postgres:5432/sereno"
   :database-username "sereno"
   :database-password "sereno"
   :repl-server-host "localhost"
   :repl-server-port 4461
   :http-server-port 4460})

(def config
  (->> (env->config env)
       (merge default-config)
       (us/conform ::config)))

(def version
  (v/parse "%version%"))

(defmethod ig/init-key ::secrets
  [type {:keys [key] :as opts}]
  (when (= key "default")
    (log/warn "Using default SECRET-KEY, system will generate insecure tokens."))
  {:key key
   :factory
   (fn [salt length]
     (let [engine (bk/engine {:key key
                              :salt (name salt)
                              :alg :hkdf
                              :digest :blake2b-512})]
       (bk/get-bytes engine length)))})

(defmethod ig/init-key :default
  [type opts]
  opts)
