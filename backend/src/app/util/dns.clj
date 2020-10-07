;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.dns
  (:require
   [cuerdas.core :as str])
  (:import
   org.xbill.DNS.Cache
   org.xbill.DNS.Lookup
   org.xbill.DNS.Record
   org.xbill.DNS.Resolver
   org.xbill.DNS.Type
   org.xbill.DNS.SimpleResolver))

(defn- translate-type
  [type]
  (Type/value (str/upper (name type))))

(defn- normalize-hostname
  [hname]
  (if (str/ends-with? hname ".")
    hname
    (str hname ".")))

(defn lookup
  ([hname]
   (lookup hname {}))
  ([hname {:keys [type nameserver timeout]
           :or {nameserver "8.8.8.8"
                type :a
                timeout 1000}}]

   (let [resolver (doto (SimpleResolver. nameserver)
                    (.setTimeout timeout))
         hname    (normalize-hostname hname)
         type     (translate-type type)
         lookup   (doto (Lookup. hname type)
                    (.setCache (Cache.))
                    (.setResolver resolver))]
     (->> (seq (.run lookup))
          (map (fn [^Record item]
                 {:name (.. item getName toString)
                  :type (keyword (str/lower (Type/string (.getType item))))
                  :ttl  (.getTTL item)
                  :data (.rdataToString item)}))))))
