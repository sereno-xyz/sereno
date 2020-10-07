;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.tasks.sendmail
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [postal.core :as postal])
  (:import java.util.Base64
           java.util.Base64$Encoder
           java.util.Base64$Decoder))

(defmulti sendmail (fn [config email] (:backend config)))

(defmethod sendmail "console"
  [config email]
  (let [out (with-out-str
              (println "email console dump:")
              (println "******** start email" (:id email) "**********")
              (println " from: " (:from email))
              (println " to: " (:to email "---"))
              (println " reply-to: " (:reply-to email))
              (println " subject: " (:subject email))
              (println " content:")
              (doseq [item (:content email)]
                (when (= (:type item) "text/plain")
                  (println (:value item))))
              (println "******** end email "(:id email) "**********"))]
    (log/info out)))

(defn encode-base64
  [^String payload]
  (let [^bytes bdata (.getBytes payload "UTF-8")
        ^Base64$Encoder encoder (-> (Base64/getUrlEncoder)
                                    #_(.withoutPadding))]
    (.encodeToString encoder bdata)))

(defn encode-basic-auth
  [username password]
  (let [payload (str username ":" password)
        payload (encode-base64 payload)]
    (str "Basic " payload)))

(defmethod sendmail "mailjet"
  [config email]
  (let [username (:username config)
        password (:password config)
        auth-hdr (encode-basic-auth username password)

        text-prt (d/seek #(= "text/plain" (:type %)) (:content email))

        params   {"From" {"Email" (:from email)}
                  "To" (mapv #(array-map "Email" %) (:to email))
                  "Subject" (:subject email)
                  "TextPart" (:value text-prt "")}

        params   (cond-> params
                   (:track-id email)
                   (assoc "CustomID" (str (:track-id email)))

                   (:track-payload email)
                   (assoc "Payload" (str (:track-payload email))))

        headers  {"Authorization" auth-hdr
                  "Content-Type" "application/json"}
        body     (json/write-str {"Messages" [params]})]

    (try
      (let [send! (:http-client config)
            resp  (send! {:method :post
                          :headers headers
                          :uri "https://api.mailjet.com/v3.1/send"
                          :body body})]

        (when-not (= 200 (:status resp))
          (log/error "Unexpected status from mailjet:" (pr-str resp))))
      (catch Exception e
        (log/error e "Error on sending email to mailjet.")))))

;; (defmethod sendmail "sendgrid"
;;   [config email]
;;   (let [apikey  (:api-key config)
;;         dest    (mapv #(array-map :email %) (:to email))
;;         params  {:personalizations [{:to dest
;;                                      :subject (:subject email)}]
;;                  :from {:email (:from email)}
;;                  :reply_to {:email (:reply-to email)}
;;                  :content (:content email)}
;;         headers {"Authorization" (str "Bearer " apikey)
;;                  "Content-Type" "application/json"}
;;         body    (json/write-str params)]


;;     (try
;;       (let [send! (:http-client config)
;;             resp  (send! {:method :post
;;                           :headers headers
;;                           :uri "https://api.sendgrid.com/v3/mail/send"
;;                           :body body})]
;;         (when-not (= 202 (:status resp))
;;           (log/error "Unexpected status from sendgrid:" (pr-str resp))))
;;       (catch Exception e
;;         (log/error e "Error on sending email to sendgrid.")))))

(defn- get-smtp-config
  [config]
  {:host (:smtp-host config)
   :port (:smtp-port config)
   :user (:smtp-user config)
   :pass (:smtp-password config)
   :ssl  (:smtp-ssl config)
   :tls  (:smtp-tls config)})

(defn- email->postal
  [email]
  {:from (:from email)
   :to (:to email)
   :subject (:subject email)
   :body (d/concat [:alternative]
                   (map (fn [{:keys [type value]}]
                          {:type (str type "; charset=utf-8")
                           :content value})
                        (:content email)))})

(defmethod sendmail "smtp"
  [config email]
  (let [config (get-smtp-config config)
        email  (email->postal email)
        result (postal/send-message config email)]
    (when (not= (:error result) :SUCCESS)
      (ex/raise :type :sendmail-error
                :code :email-not-sent
                :context result))))


(s/def ::http-client fn?)
(s/def ::backend ::us/not-empty-string)
(s/def ::from ::us/email)
(s/def ::reply-to ::us/email)
(s/def ::api-key ::us/string)
(s/def ::username ::us/string)
(s/def ::password ::us/string)
(s/def ::smtp (s/map-of ::us/keyword any?))

(defmethod ig/pre-init-spec ::handler
  [_]
  (s/keys :req-un [::backend ::from ::reply-to ::http-client ::api-key ::username ::password ::smtp]))

(defmethod ig/init-key ::handler
  [_ config]
  (fn [tdata]
    (let [email (:props tdata)
          email (cond-> email
                  (nil? (:from email)) (assoc :from (:from config))
                  (nil? (:reply-to email)) (assoc :reply-to (:reply-to config)))]
      (sendmail config email))))

