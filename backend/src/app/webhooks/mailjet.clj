;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.webhooks.mailjet
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.tasks :as tasks]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [integrant.core :as ig]))


(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool shared-key]}]
  (fn [request]
    (let [skey   (get-in request [:query-params "shared-key"])
          events (->> (:body-params request)
                      (filter (fn [{:keys [type]}] (or (= "spam" type)
                                                       (= "bounce" type)
                                                       (= "blocked" type))))
                      (mapv (fn [item]
                              {:track-id      (:CustomID item)
                               :track-payload (:Payload item)
                               :type       (:event item)
                               :reason     (:error_related_to item)
                               :recipient  (:email item)})))]

      (when (not= skey shared-key)
        (log/warnf "Unauthorized request from webhook ('%s')." skey)
        (ex/raise :type :not-authorized
                  :code :invalid-shared-key))

      (tasks/submit! pool {:name "handle-bounces"
                           :props {:bounces events}
                           :delay 0
                           :priority 100
                           :max-retries 0})
      {:status 200
       :body ""})))
