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
   [integrant.core :as ig]
   [clojure.tools.logging :as log]
   [app.util.time :as dt]
   [app.config :as cfg])
  (:gen-class))

(defn- enable-asserts
  [& args]
  (let [m (System/getProperty "app.enable-asserts")]
    (or (nil? m) (= "true" m))))

;; Set value for all new threads bindings.
(alter-var-root #'*assert* enable-asserts)

;; Set value for current thread binding.
;; (set! *assert* (enable-asserts))

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
  {:app.config/public-uri
   (:public-uri cfg/config "http://localhost:4449")

   :app.config/google
   {:client-id (:google-client-id cfg/config)
    :client-secret (:google-client-secret cfg/config)}

   :app.metrics/registry
   {}

   :app.db/migrations
   {}

   :app.db/pool
   {:uri (:database-uri cfg/config "postgresql://postgres:5432/sereno")
    :username (:database-username cfg/config "sereno")
    :password (:database-password cfg/config "sereno")
    :metrics-registry (ig/ref :app.metrics/registry)
    :migrations (ig/ref :app.db/migrations)
    :name "main"
    :min-pool-size 0
    :max-pool-size 10}

   :app.config/secrets
   {:key (:secret-key cfg/config "default")}

   :app.tokens/instance
   {:secrets (ig/ref :app.config/secrets)}

   :app.msgbus/instance
   {:pool (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)}

   :app.ws/notifications-handler
   {:msgbus (ig/ref :app.msgbus/instance)
    :pool   (ig/ref :app.db/pool)}

   :app.api/impl
   {:pool        (ig/ref :app.db/pool)
    :tokens      (ig/ref :app.tokens/instance)
    :public-uri  (ig/ref :app.config/public-uri)
    :http-client (ig/ref :app.http/client)
    :metrics-registry (ig/ref :app.metrics/registry)
    :default-profile-type (:default-profile-type cfg/config "default")}

   :app.api/handler
   {:impl        (ig/ref :app.api/impl)
    :tokens      (ig/ref :app.tokens/instance)
    :pool        (ig/ref :app.db/pool)
    :http-client (ig/ref :app.http/client)
    :public-uri  (ig/ref :app.config/public-uri)
    :metrics-registry (ig/ref :app.metrics/registry)
    :webhooks    (ig/ref :app.webhooks/handlers)
    :google      (ig/ref :app.config/google)}

   :app.error-reporter/instance
   {:uri (:error-reporter-webhook-uri cfg/config)
    :http-client (ig/ref :app.http/client)
    :executor (ig/ref :app.worker/executor)}

   :app.http/client
   {:executor (ig/ref :app.worker/executor)}

   :app.http/server
   {:port (:http-server-port cfg/config 4460)
    :ws {"/ws/notifications" (ig/ref :app.ws/notifications-handler)}
    :handler (ig/ref :app.api/handler)}

   :app.repl/server
   {:name "default"
    :port (:repl-server-port cfg/config 4461)
    :host "localhost"}

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
   {"monitor"  (ig/ref :app.tasks.monitor/handler)
    "sendmail" (ig/ref :app.tasks.sendmail/handler)
    "notify"   (ig/ref :app.tasks.notify/handler)
    "handle-bounces"  (ig/ref :app.tasks.handle-bounces/handler)
    "clean-old-tasks" (ig/ref :app.tasks.maintenance/clean-old-tasks)
    "vacuum-tabes"    (ig/ref :app.tasks.maintenance/vacuum-tables)}

   :app.tasks.monitor/handler
   {:pool (ig/ref :app.db/pool)
    :http-client (ig/ref :app.http/client)}

   :app.tasks.maintenance/clean-old-tasks
   {:pool (ig/ref :app.db/pool)
    :tasks-max-age #app/duration "120h"} ; 5 days

   :app.tasks.maintenance/vacuum-tables
   {:pool (ig/ref :app.db/pool)}

   :app.tasks.handle-bounces/handler
   {:pool (ig/ref :app.db/pool)
    :tokens (ig/ref :app.tokens/instance)}

   :app.tasks.notify/handler
   {:pool (ig/ref :app.db/pool)
    :tokens (ig/ref :app.tokens/instance)
    :public-uri  (ig/ref :app.config/public-uri)
    :http-client (ig/ref :app.http/client)}

   :app.tasks.sendmail/handler
   {:backend (:sendmail-backend cfg/config "console")
    :api-key (:sendmail-backend-apikey cfg/config "")
    :username (:sendmail-backend-username cfg/config "")
    :password (:sendmail-backend-password cfg/config "")
    :reply-to (:sendmail-reply-to cfg/config "no-reply@sereno.xyz")
    :from (:sendmail-from cfg/config "no-reply@sereno.xyz")
    :http-client (ig/ref :app.http/client)
    :smtp {:host (:smtp-host cfg/config)
           :port (:smtp-port cfg/config)
           :user (:smtp-user cfg/config)
           :pass (:smtp-password cfg/config)
           :tls  (:smtp-tls cfg/config)
           :ssl  (:smtp-ssl cfg/config)}}

   :app.tasks.mailjet-webhook/handler
   {:pool (ig/ref :app.db/pool)
    :http-client (ig/ref :app.http/client)}

   :app.webhooks/handlers
   {:mailjet (ig/ref :app.webhooks.mailjet/handler)}

   :app.webhooks.mailjet/handler
   {:pool (ig/ref :app.db/pool)
    :shared-key (:mailjet-webhook-shared-key cfg/config "sample-key")}
   })

(defonce system {})

(defn run
  [params]
  (ig/load-namespaces system-config)
  (let [system (ig/init system-config)]
    (alter-var-root #'system (constantly system))
    system))

(defn -main
  [& args]
  (run {}))
