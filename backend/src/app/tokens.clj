;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tokens
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [buddy.sign.jwe :as jwe]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare create)
(declare verify)

;; --- Public API

(s/def ::secrets map?)

(defmethod ig/pre-init-spec ::instance [_]
  (s/keys :req-un [::secrets]))

(defmethod ig/init-key ::instance
  [type {:keys [secrets] :as opts}]
  (let [key ((:factory secrets) :tokens 32)]
    {:key key
     :create (partial create key)
     :verify (partial verify key)}))

(s/def ::key bytes?)
(s/def ::create fn?)
(s/def ::verify fn?)

(s/def ::service
  (s/keys :req-un [::key ::create ::verify]))

;; --- Impl

(defn- create
  [key claims]
  (let [payload (t/encode claims)]
    (jwe/encrypt payload key {:alg :a256kw :enc :a256gcm})))

(defn- verify
  ([key token] (verify key token nil))
  ([key token params]
   (let [payload (jwe/decrypt token key {:alg :a256kw :enc :a256gcm})
         claims  (t/decode payload)]
     (when (and (dt/instant? (:exp claims))
                (dt/is-before? (:exp claims) (dt/now)))
       (ex/raise :type :validation
                 :code :invalid-token
                 :reason :token-expired
                 :params params
                 :claims claims))
     (when (and (contains? params :iss)
                (not= (:iss claims)
                      (:iss params)))
       (ex/raise :type :validation
                 :code :invalid-token
                 :reason :invalid-issuer
                 :claims claims
                 :params params))
     claims)))

