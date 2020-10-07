;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.handle-bounces
  "A generic task for handling contact bounce events."
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))


;; TODO: maintenance task that reset bounces when some time pases without bounces
;; TODO: mainentance task that check the contact bounces and notify profiles


(declare handle)
(declare handle-bounce)
(declare process-bounce)

(def MAX-BOUNCES 3)

(s/def ::track-id ::us/uuid)
(s/def ::track-payload string?)
(s/def ::type string?)
(s/def ::reason string?)
(s/def ::recipient string?)

(s/def ::bounce-event
  (s/keys :req-un [::type ::track-id ::track-payload ::reason ::recipient]))

(s/def ::bounces (s/coll-of ::bounce-event))
(s/def ::props  (s/keys :req-un [::bounces]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as tdata}]
    (let [props (us/conform ::props props)]
      (handle cfg props))))

(defmethod ig/pre-init-spec ::handler
  [_]
  (s/keys :req-un [::db/pool]))


;; --- IMPL

(defmulti process-token (fn [cfg claims event] (:iss claims)))

(defn- handle
  [{:keys [pool tokens] :as cfg} {:keys [bounces] :as props}]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg :conn conn)]
      (run! #(process-bounce cfg %) bounces))))

(defn handle-bounce
  [{:keys [tokens] :as cfg} {:keys [track-payload track-id] :as event}]
  (try
    (let [claims ((:verify tokens) track-payload)]
      (process-bounce cfg (assoc event :claims claims)))
    (catch clojure.lang.ExceptionInfo e
      (let [edata (ex-data e)]
        (if (and (= :validation (:type edata))
                 (= :invalid-token (:code edata)))
          (log/warnf "Received invalid token on bounce handling (track-id=%s, data=%s)." track-id (pr-str edata))
          (throw e))))))

(defmethod process-token :contact
  [{:keys [conn]} {:keys [contact-id profile-id]} {:keys [type reason recipient]}]
  (log/debugf "Processing contact bounce event (contact-id=% recipient=%s type=%s reason=%s)"
              contact-id recipient type reason)

  (let [now     (dt/now)
        contact (db/get-by-id conn :contact
                              {:id contact-id}
                              {:for-update true})]

    ;; Insert new entry on the contact bounce table
    (db/insert! conn :contact-bounce
                {:profile-id profile-id
                 :contact-id contact-id
                 :metadata   (db/json {:type type :explain reason})
                 :created-at now})

    ;; Handle the pausing/disabling logic
    (if (>= (:bounces contact) (dec MAX-BOUNCES))
      (db/update! conn :contact
                  {:is-disabled true
                   :is-paused true
                   :disable-reason (db/json {:type type :explain reason})
                   :bounces MAX-BOUNCES
                   :bounced-at now}
                  {:id contact-id})
      (db/update! conn :contact
                  {:is-paused true
                   :pause-reason (db/json {:type type :explain reason})
                   :bounces (inc (:bounces contact))
                   :bounced-at now}
                  {:id contact-id}))))
