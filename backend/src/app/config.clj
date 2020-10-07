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
   [integrant.core :as ig]
   [cuerdas.core :as str]
   [clojure.spec.alpha :as s]
   [buddy.core.codecs :as bc]
   [buddy.core.kdf :as bk]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [environ.core :refer [env]]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.time :as tm]))

(s/def ::database-password (s/nilable ::us/string))
(s/def ::database-uri ::us/string)
(s/def ::database-username (s/nilable ::us/string))
(s/def ::default-profile-type ::us/string)
(s/def ::error-reporter-webhook-uri ::us/string)
(s/def ::google-client-id ::us/string)
(s/def ::google-client-secret ::us/string)
(s/def ::http-server-port ::us/integer)
(s/def ::public-uri ::us/string)
(s/def ::secret-key ::us/string)
(s/def ::sendmail-backend ::us/string)
(s/def ::sendmail-backend-apikey ::us/string)
(s/def ::sendmail-backend-password ::us/string)
(s/def ::sendmail-backend-username ::us/string)
(s/def ::sendmail-from ::us/email)
(s/def ::sendmail-reply-to ::us/email)
(s/def ::smtp-host ::us/string)
(s/def ::smtp-password (s/nilable ::us/string))
(s/def ::smtp-port ::us/integer)
(s/def ::smtp-ssl ::us/boolean)
(s/def ::smtp-tls ::us/boolean)
(s/def ::smtp-user (s/nilable ::us/string))

(s/def ::config
  (s/keys :opt-un [::http-server-port
                   ::database-username
                   ::database-password
                   ::database-uri
                   ::secret-key
                   ::default-profile-type
                   ::sendmail-backend
                   ::sendmail-backend-apikey
                   ::sendmail-backend-username
                   ::sendmail-backend-password
                   ::sendmail-reply-to
                   ::sendmail-from
                   ::error-reporter-webhook-uri
                   ::smtp-host
                   ::smtp-port
                   ::smtp-user
                   ::smtp-password
                   ::smtp-ssl
                   ::smtp-tls
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

(def config
  (->> (env->config env)
       (us/conform ::config)))

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
