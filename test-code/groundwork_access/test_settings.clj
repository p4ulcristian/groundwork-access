(ns groundwork-access.test-settings
  "The pool settings and migrate! options the tests run with, each written
   out, as groundwork-postgres requires them, and a pool started as an app starts its
   own: with a log and a meter of its own."
  (:require
   [groundwork-postgres.api :as postgres]
   [groundwork-access.api :as access]
   [groundwork-logs.api :as logs]
   [groundwork-metrics.api :as metrics]))

(def port (some-> (System/getenv "ACCESS_TEST_PORT") parse-long))

(defn make-pool-settings
  "A test Postgres on localhost, no password, no TLS."
  [user pool-size]
  {:host                           "localhost"
   :port                           port
   :database                       "postgres"
   :user                           user
   :password                       nil
   :tls                            :off
   :tls-root-cert                  nil
   :pool-size                      pool-size
   :connection-wait-ms             30000
   :connection-check-wait-ms       5000
   :connection-max-lifetime-ms     1800000
   :connection-keepalive-every-ms  120000
   :leak-warning-after-ms          :none
   :connect-timeout-seconds        10
   :read-timeout-seconds           :none
   :tcp-keepalive?                 false
   :statement-timeout-ms           5000
   :lock-timeout-ms                :none
   :idle-in-transaction-timeout-ms :none
   :time-zone                      "UTC"
   :metric-buckets                 {:query-seconds              [0.01 0.1 1]
                                    :connection-acquire-seconds [0.01 0.1 1]
                                    :connection-usage-seconds   [0.01 0.1 1]}})

(defn declare-tables
  "What the tests declare to groundwork-postgres beside the tables: groundwork-access's own
   functions, no extensions or views."
  [tables]
  {:tables tables :extensions [] :provider-extensions [] :functions access/function-files :views []})

(defn declare-tables-and-access
  "What the tests declare to groundwork-postgres beside the tables, for tables with
   sentences: groundwork-access's own functions and those it makes from the
   sentences of tables and the scope kinds, no extensions or views."
  [ready-tables tables scope-kinds]
  {:tables     ready-tables
   :extensions []
   :provider-extensions []
   :functions  (into access/function-files (access/make-access-functions tables scope-kinds))
   :views      []})

(def migrate-options
  {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
   :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
   :log          (fn [_level _event _data])})

(def log
  "The tests' log, as an app makes its own; its lines go nowhere."
  (logs/log-function (logs/make-logger {:lowest-level :error :format :json :errors-kept 10
                                               :write-to     (fn [_line])})))

(defn make-meter
  "A meter, as an app makes its own."
  []
  (metrics/make-meter {:jvm-metrics false :most-series-per-metric 10000} {:log log}))

(defn start-pool!
  "A pool on the test Postgres, as user, with the tests' log and a meter of
   its own (groundwork-postgres takes one meter per pool)."
  [user pool-size]
  (postgres/start-pool! (make-pool-settings user pool-size)
                      {:log log :meter (make-meter)}))
