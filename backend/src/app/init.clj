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
   [app.config :as cfg]
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

(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread throwable]
     (cond
       (instance? java.lang.OutOfMemoryError throwable)
       (do
         (log/error throwable)
         (System/exit 1))

       :else
       (do
         (log/error throwable "Uncaught exception")
         (System/exit 1))))))

;; --- Entry point

(def system-config
  {:app.metrics/metrics
   {}

   :app.migrations/migrations
   {}

   :app.db/pool
   {:uri         (:database-uri cfg/config)
    :username    (:database-username cfg/config)
    :password    (:database-password cfg/config)
    :metrics     (ig/ref :app.metrics/metrics)
    :migrations  (ig/ref :app.migrations/migrations)
    :name "main"
    :min-pool-size 0
    :max-pool-size 20}

   :app.secrets/secrets
   {:key (:secret-key cfg/config)}

   :app.tokens/instance
   {:secrets (ig/ref :app.secrets/secrets)}

   :app.msgbus/instance
   {:pool (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)}

   :app.ws/notifications-handler
   {:msgbus (ig/ref :app.msgbus/instance)
    :pool   (ig/ref :app.db/pool)}

   :app.rpc/rpc
   {:pool        (ig/ref :app.db/pool)
    :tokens      (ig/ref :app.tokens/instance)
    :http-client (ig/ref :app.http/client)
    :metrics     (ig/ref :app.metrics/metrics)

    :public-uri  (:public-uri cfg/config)
    :default-profile-type (:default-profile-type cfg/config "default")}

   :app.http.auth/handlers
   {:http-client (ig/ref :app.http/client)
    :tokens      (ig/ref :app.tokens/instance)
    :pool        (ig/ref :app.db/pool)
    :rpc         (ig/ref :app.rpc/rpc)

    :public-uri           (:public-uri cfg/config)
    :google-client-id     (:google-client-id cfg/config)
    :google-client-secret (:google-client-secret cfg/config)}

   :app.http/router
   {:rpc      (ig/ref :app.rpc/rpc)
    :auth     (ig/ref :app.http.auth/handlers)
    ;; The database connection is used for the session management.
    :pool     (ig/ref :app.db/pool)
    :webhooks (ig/ref :app.webhooks/handlers)
    :metrics  (ig/ref :app.metrics/metrics)}

   :app.error-reporter/instance
   {:uri (:error-reporter-webhook-uri cfg/config)
    :http-client (ig/ref :app.http/client)
    :executor (ig/ref :app.worker/executor)}

   :app.http/client
   {:executor (ig/ref :app.worker/executor)}

   :app.http/server
   {:port (:http-server-port cfg/config)
    :ws {"/ws/notifications" (ig/ref :app.ws/notifications-handler)}
    :router (ig/ref :app.http/router)}

   :app.repl/server
   {:name "default"
    :port (:repl-server-port cfg/config)
    :host (:repl-server-host cfg/config)}

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
    :public-uri  (:public-uri cfg/config)
    :http-client (ig/ref :app.http/client)}

   :app.tasks.sendmail/handler
   {:host     (:smtp-host cfg/config)
    :port     (:smtp-port cfg/config)
    :enabled  (:smtp-enabled cfg/config)
    :tls      (:smtp-tls cfg/config)
    :username (:smtp-username cfg/config)
    :password (:smtp-password cfg/config)
    :metrics  (ig/ref :app.metrics/metrics)
    :default-reply-to (:smtp-default-reply-to cfg/config)
    :default-from     (:smtp-default-from cfg/config)}

   :app.telegram/service
   {:id (:telegram-id cfg/config)
    :token (:telegram-token cfg/config)
    :http-client (ig/ref :app.http/client)
    :pool (ig/ref :app.db/pool)}

   :app.telegram/webhook
   {:telegram    (ig/ref :app.telegram/service)
    :pool        (ig/ref :app.db/pool)
    :shared-key  (:webhook-shared-key cfg/config "sample-key")}

   :app.webhooks/handlers
   {:awssns      (ig/ref :app.webhooks.awssns/handler)
    :telegram    (ig/ref :app.telegram/webhook)
    :healthcheck (ig/ref :app.webhooks.healthcheck/handler)}

   ;; TODO: maybe move to a specific AWS SNS service?
   :app.webhooks.awssns/handler
   {:pool        (ig/ref :app.db/pool)
    :http-client (ig/ref :app.http/client)
    :shared-key  (:webhook-shared-key cfg/config "sample-key")}

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
  (log/infof "Welcome to sereno! Version: '%s'." (:full cfg/version)))

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

