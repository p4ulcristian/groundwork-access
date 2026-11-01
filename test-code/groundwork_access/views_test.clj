(ns groundwork-access.views-test
  "The views that read past the gates (find-views-past-the-gates,
   check-views), end to end. The tables belong to groundwork_access_owner, a
   role that is not a superuser, as in production; the app runs as
   groundwork_access_app, which owns nothing. A view the superuser owns
   shows the hole: Postgres runs a view with its owner's rights unless it is
   security_invoker, and a superuser passes every gate. Runs when
   ACCESS_TEST_PORT is set."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.api :as access]
   [groundwork-access.asking :as asking]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-postgres.api :as postgres]))

(def ^:private port test-settings/port)

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false
           :actions {:orders/see #{:read}}
           :roles   {"Staff" #{:orders/see}}
           :denies  []}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]]
   :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private orders
  {:table      :orders
   :columns    [[:order/id :uuid {:primary-key? true}]
                [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes    [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :on-change  nil
   :access     [{:allow :orders/see :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private tables [shops orders])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private shop-7 (id 7007))
(def ^:private shop-9 (id 9009))
(def ^:private anna (id 2)) ; Staff in shop 7
(def ^:private order-42 (id 42)) ; shop 7
(def ^:private order-99 (id 99)) ; shop 9

(defn- run-sql! [pool text] (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- make-database!
  "The tables made by groundwork_access_owner, the rows written by the superuser."
  [super-pool]
  (run-sql! super-pool (str "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS reports CASCADE; "
                            "DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE"))
  (run-sql! super-pool (str "DO $$ BEGIN "
                            "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_app') THEN CREATE ROLE groundwork_access_app LOGIN; END IF; "
                            "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_owner') THEN CREATE ROLE groundwork_access_owner LOGIN; END IF; "
                            "END $$"))
  (run-sql! super-pool "GRANT CREATE ON DATABASE postgres TO groundwork_access_owner; ALTER SCHEMA public OWNER TO groundwork_access_owner")
  (let [owner-pool (test-settings/start-pool! "groundwork_access_owner" 2)
        ready      (into (access/put-gates-on-tables tables scope-kinds) access/access-tables)]
    (try
      (with-out-str @(:slow-part (postgres/migrate! owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) [] test-settings/migrate-options)))
      (postgres/grant-tables-to-role! owner-pool ready "groundwork_access_app")
      (finally (postgres/stop-pool! owner-pool))))
  (run-sql! super-pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
  (postgres/run-in-transaction! super-pool {:read-only? false :log nil}
                                (fn [transaction]
                                  (doseq [shop [shop-7 shop-9]]
                                    (access/add-scope! transaction scope-kinds :shop shop (id 1) nil))
                                  (add-member! transaction scope-kinds [:shop shop-7] anna #{"Staff"})))
  (run-sql! super-pool (str "INSERT INTO orders VALUES ('" order-42 "', '" shop-7 "'), ('" order-99 "', '" shop-9 "')")))

(defn- make-views!
  "As the tables' owner: views in public (a folder of the tables) and one in
   reports (no table's), then the app's role given what it is given after a
   migrate, every table and view of public. The first view handed to the
   superuser, as a view a superuser (or a BYPASSRLS owner) made."
  [super-pool]
  (let [owner-pool (test-settings/start-pool! "groundwork_access_owner" 1)]
    (try
      (run-sql! owner-pool (str "CREATE VIEW orders_as_owner AS SELECT order__id FROM orders; "
                                "CREATE VIEW orders_as_asker WITH (security_invoker = true) AS SELECT order__id FROM orders; "
                                "CREATE VIEW orders_as_asker_on WITH (security_invoker = on) AS SELECT order__id FROM orders; "
                                "CREATE VIEW orders_said_false WITH (security_invoker = false) AS SELECT order__id FROM orders; "
                                "CREATE MATERIALIZED VIEW order_count AS SELECT count(*) AS n FROM orders; "
                                "CREATE SCHEMA reports; "
                                "CREATE VIEW reports.order_totals AS SELECT count(*) AS n FROM public.orders; "
                                "GRANT SELECT ON ALL TABLES IN SCHEMA public TO groundwork_access_app"))
      (finally (postgres/stop-pool! owner-pool))))
  (run-sql! super-pool (str "ALTER VIEW orders_as_owner OWNER TO " (System/getenv "ACCESS_TEST_USER"))))

(defn- order-ids-as-anna
  "The orders anna reads through a view, on the app's role, through the gates."
  [app-pool view]
  (postgres/run-in-transaction! app-pool {:read-only? true :log nil}
                                (fn [transaction]
                                  (asking/set-asking! transaction {:person-id anna :visitor-id nil :session-flags #{}}
                                                      {:acting :every-action :holding (access/list-actions-of-person transaction scope-kinds anna)})
                                  (set (map :order/id (postgres/find-rows transaction (postgres/make-query-from-sql-text "test" (str "SELECT order__id FROM " view)) {}))))))

(deftest a-view-in-the-apps-folders-reads-as-its-asker
  (if-not port
    (println "groundwork-access.views-test: skipped, ACCESS_TEST_PORT is not set")
    (let [super-pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (try
        (make-database! super-pool)
        (let [app-pool (test-settings/start-pool! "groundwork_access_app" 2)
              ready    (into (access/put-gates-on-tables tables scope-kinds) access/access-tables)]
          (try
            (testing "with no view, nothing to refuse"
              (is (= [] (access/find-views-past-the-gates app-pool ready)))
              (is (nil? (access/check-views app-pool ready))))
            (make-views! super-pool)
            (testing "the hole: a view run as an owner who passes the gates hands anna shop 9's order"
              (is (= #{order-42 order-99} (order-ids-as-anna app-pool "orders_as_owner")))
              (is (= #{order-42} (order-ids-as-anna app-pool "orders"))))
            (testing "a security_invoker view reads as anna, through her gates"
              (is (= #{order-42} (order-ids-as-anna app-pool "orders_as_asker")))
              (is (= #{order-42} (order-ids-as-anna app-pool "orders_as_asker_on"))))
            (testing "found: every view of the tables' folders that isn't security_invoker, and every materialized view"
              (is (= [{:name "order_count" :materialized? true}
                      {:name "orders_as_owner" :materialized? false}
                      {:name "orders_said_false" :materialized? false}]
                     (access/find-views-past-the-gates app-pool ready)))
              (is (= [] (access/find-views-past-the-gates app-pool access/access-tables))
                  "only the tables' folders are looked at: groundwork_access has none of these"))
            (testing "check-views refuses them, every one at once"
              (let [e (try (access/check-views app-pool ready) nil (catch clojure.lang.ExceptionInfo e e))]
                (is (= :groundwork-access/views-past-the-gates (:type (ex-data e))))
                (is (= ["order_count" "orders_as_owner" "orders_said_false"] (mapv :name (:views (ex-data e)))))
                (is (re-find #"orders_as_owner \(not security_invoker: it reads as its owner\)" (ex-message e)))
                (is (re-find #"order_count \(a materialized view" (ex-message e)))))
            (testing "a view in a folder of no table, which the app's role isn't granted, is left to its outside reader"
              (run-sql! super-pool "DROP VIEW orders_as_owner, orders_said_false; DROP MATERIALIZED VIEW order_count")
              (is (= [] (access/find-views-past-the-gates app-pool ready)))
              (is (nil? (access/check-views app-pool ready))))
            (finally (postgres/stop-pool! app-pool))))
        (finally (postgres/stop-pool! super-pool))))))
