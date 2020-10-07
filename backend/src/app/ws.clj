;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ws
  (:require
   [app.util.transit :as t]
   [clojure.core.async :as a]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [app.session :as session]
   [ring.adapter.jetty9 :as jetty]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]))

(defn- start-rcv-loop!
  [{:keys [conn out ev] :as ws}]
  (a/go
   (loop []
     (let [timeout (a/timeout 10000)
           [val port] (a/alts! [out ev timeout] :priority true)]
       (cond
         (= port timeout)
         (do
           (a/>! out {:type :ping})
           (recur))

         (nil? val)
         (do
           (a/close! out)
           (a/close! ev))

         (= port ev)
         (do
           (a/>! out {:type :message :payload val})
           (recur))

         (= port out)
         (do
           (a/<! (a/thread (jetty/send! conn (t/encode-str val))))
           (recur)))))))

(defn websocket
  [{:keys [msgbus profile-id] :as params}]
  (let [in  (a/chan 32)
        out (a/chan 32)]
    {:on-connect (fn [conn]
                   #_(metrics-active-connections :inc)
                   (let [xf  (filter #(= (:owner-id %) profile-id))
                         ch  (a/chan 1 xf)]

                     ;; Subscribe to internal msgbus
                     (msgbus ch)

                     ;; Start connection rcv/snd loop
                     (start-rcv-loop! (assoc params :in in :out out :ev ch :conn conn))))

     :on-error (fn [conn e]
                 (a/close! out)
                 (a/close! in))

     :on-close (fn [conn status-code reason]
                 #_(metrics-active-connections :dec)
                 (a/close! out)
                 (a/close! in))

     :on-text (constantly nil)
     :on-bytes (constantly nil)}))


(defn handler
  [opts {:keys [profile-id] :as req}]
  (if (not profile-id)
    {:error {:code 403 :message "Authentication required"}}
    (websocket (assoc opts :profile-id profile-id))))

(defmethod ig/init-key ::notifications-handler
  [_ cfg]
  (-> (partial handler cfg)
      (session/middleware cfg)
      (wrap-keyword-params)
      (wrap-cookies)
      (wrap-params)))
