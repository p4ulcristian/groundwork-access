(ns groundwork-access.question-transaction-test
  "The session lookup joins the question's own transaction: one pool
   checkout per question, by a cookie or a token, an action that only
   reads or one that writes; a session seen again is pushed out in it,
   read-only question or not; a read-only question still writes nothing;
   a question whose session another is pushing out doesn't wait for it.
   Measured on the pool's meter (groundwork-postgres's connection use and
   queries), and timed; the numbers are printed. Anna is Staff in shop A.
   Runs against Postgres when ACCESS_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.api :as access]
   [groundwork-access.test-answers :refer [answer-message without-request-id]]
   [groundwork-access.test-log :refer [log]]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-access.test-shaper :refer [shaper]]
   [groundwork-metrics.api :as metrics]
   [groundwork-postgres.api :as postgres]
   [groundwork-steps.api :as steps])
  (:import
   (java.time Duration Instant)))

(def ^:private port test-settings/port)

(def ^:private settings {:cookie-name "app_session" :session-days 30 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "shop_" :token-shown-characters 11 :token-longest-days 90 :outside-issuers []})

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false :denies []
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit}}}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]]
   :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private orders
  {:table :orders
   :columns [[:order/id :uuid {:primary-key? true :default :random-uuid}]
             [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private tables [shops orders])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private shop-a (id 1001))
(def ^:private paul (id 1))
(def ^:private anna (id 2))

(defn- query [text] (postgres/make-query-from-sql-text "a test statement" text))

(defn- answer-with [f] {:name :answer :enter #(steps/add-answer-to-context % (f (:groundwork-access/transaction %) (:groundwork-steps/message-data %)))})

(def ^:private insert-order (query "INSERT INTO orders (order__shop_id) VALUES (param('order/shop-id'))"))

(def ^:private handlers
  {:orders/all   {:action :orders/see :scope :every-scope-holding-the-action
                  :params-shape [:map] :answer-shape [:vector :uuid]
                  :refusals {} :rate-limit nil :run-steps-in nil
                  :steps [(answer-with (fn [t _] (mapv :order/shop-id (postgres/find-rows t (query "SELECT order__shop_id FROM orders") {}))))]}
   :orders/place {:action :orders/edit :scope [:shop :order/shop-id]
                  :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map]
                  :refusals {} :rate-limit nil :run-steps-in nil
                  :steps [(answer-with (fn [t data] (postgres/run-statement! t insert-order data) {}))]}
   ;; an action that only reads, whose step tries a write anyway
   :orders/sneak {:action :orders/see :scope :every-scope-holding-the-action
                  :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map]
                  :refusals {} :rate-limit nil :run-steps-in nil
                  :steps [(answer-with (fn [t data] (postgres/run-statement! t insert-order data) {}))]}})

(defn- run-sql! [pool text] (postgres/run-statement! pool (query text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_app') THEN CREATE ROLE groundwork_access_app LOGIN; END IF; END $$")
  (let [ready (into (access/put-gates-on-tables tables scope-kinds)
                    (conj access/access-tables access/sessions-table access/tokens-table access/outside-subjects-table))]
    (with-out-str @(:slow-part (postgres/migrate! owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) [] test-settings/migrate-options)))
    (postgres/grant-tables-to-role! owner-pool ready "groundwork_access_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-a "')"))
  (postgres/run-in-transaction! owner-pool {:read-only? false :log nil}
                                (fn [transaction]
                                  (access/add-scope! transaction scope-kinds :shop shop-a paul nil)
                                  (add-member! transaction scope-kinds [:shop shop-a] anna #{"Staff"}))))

(defn- read-counts
  "{:checkouts n :statements m} the pool's meter has counted so far: the
   connections lent out and given back, and the queries run."
  [pool]
  (let [text  (metrics/write-metrics-text (:groundwork-postgres/meter pool))
        sum-of (fn [pattern] (reduce + 0 (map (comp parse-double second) (re-seq pattern text))))]
    {:checkouts  (long (sum-of #"(?m)^groundwork_postgres_connection_usage_seconds_count(?:\{\})? (\S+)$"))
     :statements (long (sum-of #"(?m)^groundwork_postgres_query_seconds_count\{[^}]*\} (\S+)$"))}))

(defn- measure
  "Asks f n times: {:checkouts :statements} per question, and the median
   and the 90th percentile of its time, in ms. The pool's meter records a
   connection's use as it goes back, so it is read once every ask is done."
  [pool n f]
  (dotimes [_ 20] (f))                             ; warm: the JIT, the pool, Postgres's plans
  (let [before (read-counts pool)
        times  (sort (vec (repeatedly n #(let [started (System/nanoTime)] (f) (/ (- (System/nanoTime) started) 1e6)))))
        after  (read-counts pool)]
    {:checkouts  (/ (double (- (:checkouts after) (:checkouts before))) n)
     :statements (/ (double (- (:statements after) (:statements before))) n)
     :median-ms  (nth times (quot n 2))
     :p90-ms     (nth times (quot (* 9 n) 10))}))

(defn- minutes-left [owner-pool]
  (:session/minutes-left
   (postgres/find-one-row owner-pool (query "SELECT round(extract(epoch FROM max(session__ends_at) - now()) / 60)::int AS session__minutes_left FROM groundwork_access.sessions WHERE session__person_id IS NOT NULL") {})))

(deftest the-lookup-joins-the-question-s-own-transaction
  (if-not port
    (println "groundwork-access.question-transaction-test: skipped, ACCESS_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (make-database! owner-pool)
      (let [app-pool (test-settings/start-pool! "groundwork_access_app" 4)
            ready    (access/put-access-into-handlers handlers {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                :find-session-step (access/make-find-session-step app-pool settings)})
            cookie   (first (str/split (:set-cookie (access/sign-in! app-pool {:headers {}} anna
                                                                     {:settings settings :tables [] :session-flags #{}
                                                                      :session-lasts :session-days :outside-subjects {}}))
                                       #";"))
            browser  {:request-method :post :headers {"origin" "http://app.test" "cookie" cookie}}
            token    (:text (access/make-token! app-pool browser {:settings settings :name "script" :ends-at (.plus (Instant/now) (Duration/ofDays 1))}))
            bearer   {:request-method :post :headers {"authorization" (str "Bearer " token)}}
            ask      (fn [request handler-name params]
                       (without-request-id (get (answer-message ready request {handler-name params} {:shaper shaper :log log}) handler-name)))
            n        200]
        (try
          (testing "one pool checkout per question: the lookup is the first statement of the question's transaction"
            (let [reading  (measure app-pool n #(ask browser :orders/all {}))
                  writing  (measure app-pool n #(ask browser :orders/place {:order/shop-id shop-a}))
                  by-token (measure app-pool n #(ask bearer :orders/all {}))]
              (println (str "groundwork-access.question-transaction-test: per question, " n " each:\n"
                            "  a cookie, an action that only reads: " (pr-str reading) "\n"
                            "  a cookie, an action that writes:     " (pr-str writing) "\n"
                            "  a token, an action that only reads:  " (pr-str by-token)))
              (is (= 1.0 (:checkouts reading) (:checkouts writing) (:checkouts by-token)))
              (is (= [(str shop-a)] (distinct (ask browser :orders/all {}))) "and the answers are right")))

          (testing "a session seen again is pushed out in the question's transaction, a read-only one too"
            (run-sql! owner-pool "UPDATE groundwork_access.sessions SET session__ends_at = now() + interval '20 days'")
            (let [before (read-counts app-pool)]
              (is (vector? (ask browser :orders/all {})))
              (is (= (* 30 24 60) (minutes-left owner-pool)) "30 days from now")
              (is (= 1 (- (:checkouts (read-counts app-pool)) (:checkouts before))) "still one checkout")))

          (testing "an action that only reads still writes nothing: the transaction is made read-only before the check"
            (is (= (steps/make-refusal :groundwork-steps/failed nil) (ask browser :orders/sneak {:order/shop-id shop-a}))))

          (testing "a question whose session another transaction is pushing out doesn't wait for it, and leaves it to that one"
            (run-sql! owner-pool "UPDATE groundwork_access.sessions SET session__ends_at = now() + interval '20 days'")
            (let [held    (promise)
                  release (promise)
                  holder  (future
                            (postgres/run-in-transaction! owner-pool {:read-only? false :log nil}
                                                          (fn [transaction]
                                                            (run-sql! transaction "UPDATE groundwork_access.sessions SET session__flags = session__flags WHERE session__person_id IS NOT NULL")
                                                            (deliver held true)
                                                            @release)))]
              @held
              (let [started (System/nanoTime)
                    answer  (ask browser :orders/all {})
                    took-ms (/ (- (System/nanoTime) started) 1e6)]
                (deliver release true)
                @holder
                (is (vector? answer))
                (is (< took-ms 1000) (str "answered in " took-ms " ms, not after the other's commit"))
                (is (= (* 20 24 60) (minutes-left owner-pool)) "not pushed out by this one"))))
          (finally
            (postgres/stop-pool! app-pool)
            (postgres/stop-pool! owner-pool)))))))
