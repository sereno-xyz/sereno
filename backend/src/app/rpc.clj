;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.rpc
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.http :as http]
   [app.metrics :as mtx]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(defn- default-handler
  [req]
  (ex/raise :type :not-found))

(defn- rpc-handler
  [methods request]
  (let [type    (keyword (get-in request [:path-params :cmd]))
        data    (merge (:params request)
                       (:body-params request)
                       (:uploads request))

        data    (if (:profile-id request)
                  (assoc data :profile-id (:profile-id request))
                  (dissoc data :profile-id))

        result  ((get methods type default-handler) data)
        mdata   (meta result)]

    (cond->> {:status 200 :body result}
      (fn? (:transform-response mdata)) ((:transform-response mdata) request))))

(defn- wrap-impl
  [f mdata cfg]
  (let [mreg  (get-in cfg [:metrics :registry])
        mobj  (mtx/create
               {:name (-> (str "rpc_" (::sv/name mdata) "_response_millis")
                          (str/replace "-" "_"))
                :registry mreg
                :type :summary
                :help (str/format "Service '%s' response time in milliseconds." (::sv/name mdata))})

        f     (mtx/wrap-summary f mobj)

        spec  (or (::sv/spec mdata) (s/spec any?))]

    (log/debugf "Registering '%s' command to rpc service." (::sv/name mdata))
    (fn [params]
      (when (and (:auth mdata true) (not (uuid? (:profile-id params))))
        (ex/raise :type :not-authenticated))
      (f cfg (us/conform spec params)))))

(defmethod ig/pre-init-spec ::rpc [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::rpc
  [_ cfg]
  (let [methods (->> (sv/scan-ns 'app.rpc.profile
                                 'app.rpc.monitors
                                 'app.rpc.contacts
                                 'app.rpc.token
                                 'app.rpc.export)
                     (map (fn [vfn]
                            (let [mdata (meta vfn)]
                              [(keyword (::sv/name mdata))
                               (wrap-impl (deref vfn) mdata cfg)])))
                     (into {}))]
    {:methods methods
     :handler #(rpc-handler methods %)}))
