;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.secrets
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [buddy.core.codecs :as bc]
   [buddy.core.kdf :as bk]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(s/def ::key ::us/not-empty-string)
(s/def ::secrets fn?)

(defmethod ig/pre-init-spec ::secrets [_]
  (s/keys :opt-un [::key]))

(defmethod ig/prep-key ::secrets
  [_ cfg]
  (merge {:key "default"}
         (d/remove-nil-vals cfg)))

(defmethod ig/init-key ::secrets
  [type {:keys [key] :as opts}]
  (when (= key "default")
    (log/warn "Using default SECRET-KEY, system will generate insecure tokens."))

  (with-meta
   (fn [salt length]
     (let [engine (bk/engine {:key key
                              :salt (name salt)
                              :alg :hkdf
                              :digest :blake2b-512})]
       (bk/get-bytes engine length)))
    {:key key}))
