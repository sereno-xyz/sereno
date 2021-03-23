;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.init
  (:require
   [app.config :as cf]
   [app.common.data :as d]
   [app.util.time :as dt]
   [clojure.tools.logging :as log]
   [clojure.tools.namespace.repl :as repl]
   [integrant.core :as ig])
  (:gen-class))

(defn- enable-asserts
  [& args]
  (let [m (System/getProperty "app.enable-asserts")]
    (or (nil? m) (= "true" m))))

;; Set value for all new threads bindings.
(alter-var-root #'*assert* enable-asserts)

(defmethod ig/init-key :default [_ data] data)
(defmethod ig/prep-key :default [_ data]
  (d/remove-nil-vals data))

;; (Thread/setDefaultUncaughtExceptionHandler
;;  (reify Thread$UncaughtExceptionHandler
;;    (uncaughtException [_ thread throwable]
;;      (cond
;;        (instance? java.lang.OutOfMemoryError throwable)
;;        (do
;;          (log/error throwable)
;;          (System/exit 1))

;;        :else
;;        (do
;;          (log/error throwable "Uncaught exception")
;;          (System/exit 1))))))

;; --- Entry point

(def system-config
  {:app.metrics/metrics
   {:definitions
    {:profile-register
     {:name "actions_profile_register_count"
      :help "A global counter of user registrations."
      :type :counter}
     :profile-activation
     {:name "actions_profile_activation_count"
      :help "A global counter of profile activations"
      :type :counter}}}

   :app.migrations/all
   {:main (ig/ref :app.migrations/migrations)}

   :app.db/pool
   {:uri         (:database-uri cf/config)
    :username    (:database-username cf/config)
    :password    (:database-password cf/config)
    :metrics     (ig/ref :app.metrics/metrics)
    :migrations  (ig/ref :app.migrations/all)
    :name "main"
    :min-pool-size 0
    :max-pool-size 20}

   :app.setup/props
   {:pool (ig/ref :app.db/pool)}

   :app.secrets/secrets
   {:key (:secret-key cf/config)}

   :app.tokens/instance
   {:secrets (ig/ref :app.secrets/secrets)}

   ;; :app.loggers.zmq/receiver
   ;; {:endpoint (:loggers-zmq-uri config)}

   ;; :app.loggers.loki/reporter
   ;; {:uri      (:loggers-loki-uri config)
   ;;  :receiver (ig/ref :app.loggers.zmq/receiver)
   ;;  :executor (ig/ref :app.worker/executor)}

   ;; :app.loggers.mattermost/reporter
   ;; {:uri      (:error-report-webhook config)
   ;;  :receiver (ig/ref :app.loggers.zmq/receiver)
   ;;  :pool     (ig/ref :app.db/pool)
   ;;  :executor (ig/ref :app.worker/executor)}

   ;; :app.loggers.mattermost/handler
   ;; {:pool (ig/ref :app.db/pool)}


   :app.msgbus/instance
   {:pool     (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)}

   :app.ws/notifications-handler
   {:msgbus (ig/ref :app.msgbus/instance)
    :pool   (ig/ref :app.db/pool)}

   :app.rpc/rpc
   {:pool        (ig/ref :app.db/pool)
    :tokens      (ig/ref :app.tokens/instance)
    :http-client (ig/ref :app.http/client)
    :metrics     (ig/ref :app.metrics/metrics)

    :public-uri  (cf/get :public-uri)
    :default-profile-type (:default-profile-type cf/config "default")}

   :app.http.auth/handlers
   {:http-client (ig/ref :app.http/client)
    :tokens      (ig/ref :app.tokens/instance)
    :pool        (ig/ref :app.db/pool)
    :rpc         (ig/ref :app.rpc/rpc)

    :public-uri           (:public-uri cf/config)
    :google-client-id     (:google-client-id cf/config)
    :google-client-secret (:google-client-secret cf/config)}

   :app.http/router
   {:rpc      (ig/ref :app.rpc/rpc)
    :auth     (ig/ref :app.http.auth/handlers)
    ;; The database connection is used for the session management.
    :pool     (ig/ref :app.db/pool)
    :webhooks (ig/ref :app.webhooks/handlers)
    :metrics  (ig/ref :app.metrics/metrics)}

   :app.error-reporter/instance
   {:uri (:error-reporter-webhook-uri cf/config)
    :http-client (ig/ref :app.http/client)
    :executor (ig/ref :app.worker/executor)}

   :app.http/client
   {:executor (ig/ref :app.worker/executor)}

   :app.http/server
   {:port (:http-server-port cf/config)
    :ws {"/ws/notifications" (ig/ref :app.ws/notifications-handler)}
    :router (ig/ref :app.http/router)}

   :app.repl/server
   {:name "default"
    :port (:repl-server-port cf/config)
    :host (:repl-server-host cf/config)}

   :app.worker/executor
   {:name "worker"
    :min-threads 0
    :max-threads 64
    :idle-timeout 60000}

   :app.scheduler/scheduler
   {:pool (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)
    :poll-interval #app/duration "5s"
    :batch-size 50
    :schedule
    [{:id 5001
      :task "clean-old-tasks"
      :cron #app/cron "0 0 */1 * * ?"}
     {:id 5002
      :task "vacuum-tables"
      :cron #app/cron "0 0 */1 * * ?"}]}

   :app.worker/worker
   {:pool (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)
    :tasks (ig/ref :app.tasks/all)
    :poll-interval #app/duration "5s"
    :queue "default"
    :name "worker-1"
    :batch-size 8}

   :app.tasks/all
   {"monitor"          (ig/ref :app.tasks.monitor/handler)
    "check-monitor"    (ig/ref :app.tasks.check-monitor/handler)
    "sendmail"         (ig/ref :app.tasks.sendmail/handler)
    "notify"           (ig/ref :app.tasks.notify/handler)
    "clean-old-tasks"  (ig/ref :app.tasks.maintenance/clean-old-tasks)
    "vacuum-tables"    (ig/ref :app.tasks.maintenance/vacuum-tables)}

   :app.tasks.monitor/handler
   {:pool (ig/ref :app.db/pool)
    :http-client (ig/ref :app.http/client)}

   :app.tasks.check-monitor/handler
   {:pool (ig/ref :app.db/pool)}

   :app.tasks.maintenance/clean-old-tasks
   {:pool (ig/ref :app.db/pool)
    :tasks-max-age #app/duration "120h"} ; 5 days

   :app.tasks.maintenance/vacuum-tables
   {:pool (ig/ref :app.db/pool)}

   :app.tasks.notify/handler
   {:pool        (ig/ref :app.db/pool)
    :tokens      (ig/ref :app.tokens/instance)
    :telegram    (ig/ref :app.telegram/service)
    :public-uri  (:public-uri cf/config)
    :http-client (ig/ref :app.http/client)}

   :app.tasks.sendmail/handler
   {:host     (:smtp-host cf/config)
    :port     (:smtp-port cf/config)
    :enabled  (:smtp-enabled cf/config)
    :tls      (:smtp-tls cf/config)
    :username (:smtp-username cf/config)
    :password (:smtp-password cf/config)
    :metrics  (ig/ref :app.metrics/metrics)
    :default-reply-to (:smtp-default-reply-to cf/config)
    :default-from     (:smtp-default-from cf/config)}

   :app.telegram/service
   {:id (:telegram-id cf/config)
    :token (:telegram-token cf/config)
    :http-client (ig/ref :app.http/client)
    :pool (ig/ref :app.db/pool)}

   :app.telegram/webhook
   {:telegram    (ig/ref :app.telegram/service)
    :pool        (ig/ref :app.db/pool)
    :shared-key  (:webhook-shared-key cf/config "sample-key")}

   :app.webhooks/handlers
   {:awssns      (ig/ref :app.webhooks.awssns/handler)
    :telegram    (ig/ref :app.telegram/webhook)
    :healthcheck (ig/ref :app.webhooks.healthcheck/handler)}

   ;; TODO: maybe move to a specific AWS SNS service?
   :app.webhooks.awssns/handler
   {:pool        (ig/ref :app.db/pool)
    :http-client (ig/ref :app.http/client)
    :shared-key  (:webhook-shared-key cf/config "sample-key")}

   :app.webhooks.healthcheck/handler
   {:pool        (ig/ref :app.db/pool)
    :executor    (ig/ref :app.worker/executor)
    :metrics     (ig/ref :app.metrics/metrics)}

   })

(defonce system nil)

(defn stop
  []
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             nil)))

(defn start
  []
  (ig/load-namespaces system-config)
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             (-> system-config
                                 (ig/prep)
                                 (ig/init))))
  (log/infof "Welcome to sereno! Version: '%s'." (:full cf/version)))

(defn refresh-and-restart
  []
  (stop)
  (repl/refresh :after 'app.init/start))

(defn refresh-all-and-restart
  []
  (stop)
  (repl/refresh-all :after 'app.init/start))

(defn -main
  [& args]
  (start))

