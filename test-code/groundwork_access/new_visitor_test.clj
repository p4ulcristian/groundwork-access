(ns groundwork-access.new-visitor-test
  "run-as-new-visitor!, end to end, as the app's own role, which owns
   nothing: a request with no asker writes a row as a visitor made for it,
   and only that visitor's; a request with an asker is refused. Runs when
   ACCESS_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.api :as access]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-postgres.api :as postgres]))

(def ^:private port (some-> (System/getenv "ACCESS_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table   :shops
           :actions {:carts/edit #{:read :insert :update :delete}}
           :roles   {}
           :parent  nil :single? false :denies []}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]] :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access :public})

(def ^:private carts
  {:table      :carts
   :columns    [[:cart/id :uuid {:primary-key? true :default :random-uuid}]
                [:cart/person-id :uuid {:optional? true}]
                [:cart/visitor-id :uuid {:optional? true}]]
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :on-change  nil
   :visitor    {:column :cart/visitor-id :moves-to :cart/person-id}
   :access     [{:allow :carts/edit :only :all-of-the-action :to [:me :cart/person-id]}
                {:allow :carts/edit :only :all-of-the-action :to [:me :cart/visitor-id]}]})

(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000a")

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60 :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30 :outside-issuers []})

(def ^:private asking-visitor (postgres/make-query-from-sql-text "the asking visitor" "SELECT groundwork_access.find_asking_visitor() AS asking__visitor"))
(def ^:private insert-cart (postgres/make-query-from-sql-text "insert cart" "INSERT INTO carts (cart__visitor_id) VALUES (param('cart/visitor-id'))"))

(defn- run-sql! [pool text] (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_app') THEN CREATE ROLE groundwork_access_app LOGIN; END IF; END $$")
  (let [tables (into (access/put-gates-on-tables [shops carts] scope-kinds) (conj access/access-tables access/sessions-table access/tokens-table access/outside-subjects-table))]
    (with-out-str @(:slow-part (postgres/migrate! owner-pool (test-settings/declare-tables-and-access tables [shops carts] scope-kinds) [] test-settings/migrate-options)))
    (postgres/grant-tables-to-role! owner-pool tables "groundwork_access_app")))

(defn- refusal-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest a-new-visitor-for-a-request-with-no-asker
  (if-not port
    (println "groundwork-access.new-visitor-test: skipped, ACCESS_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (make-database! owner-pool)
      (let [app-pool (test-settings/start-pool! "groundwork_access_app" 2)
            options  {:settings settings :log nil}
            nobody   {:request-method :post :headers {"origin" "http://app.test"}}
            cookie-of #(first (str/split (:set-cookie %) #";"))
            with     #(assoc-in nobody [:headers "cookie"] %)
            run      #(access/run-as-new-visitor! app-pool %1 options %2)]
        (try
          (testing "nobody asking: a fresh visitor, the one Postgres hears, who writes a row of their own"
            (let [[visitor-id heard] (run nobody (fn [transaction visitor-id]
                                                   (postgres/run-statement! transaction insert-cart {:cart/visitor-id visitor-id})
                                                   [visitor-id (:asking/visitor (postgres/find-one-row transaction asking-visitor {}))]))]
              (is (uuid? visitor-id))
              (is (= visitor-id heard))
              (is (not= visitor-id (run nobody (fn [_transaction id] id))) "each run makes its own")))
          (testing "a row for another visitor is still refused by the gates"
            (is (thrown? Exception (run nobody (fn [transaction _visitor-id]
                                                 (postgres/run-statement! transaction insert-cart {:cart/visitor-id (random-uuid)}))))))
          (testing "a request with an asker is refused: a visitor session, a signed-in person"
            (is (= :groundwork-access/request-has-asker
                   (refusal-type #(run (with (cookie-of (access/start-visitor-session! app-pool {:headers {}} settings))) (fn [_ id] id)))))
            (is (= :groundwork-access/request-has-asker
                   (refusal-type #(run (with (cookie-of (access/sign-in! app-pool {:headers {}} anna {:settings settings :tables [] :session-flags #{}
                                                                                                      :session-lasts :session-days :outside-subjects {}})))
                                       (fn [_ id] id))))))
          (testing "a request the step refuses throws as run-as-asker-of-request!'s does"
            (is (= :groundwork-access/refused-request (refusal-type #(run (assoc nobody :headers {}) (fn [_ id] id))))))
          (testing "the options are required"
            (is (= :groundwork-access/bad-options (refusal-type #(access/run-as-new-visitor! app-pool nobody {:settings settings} (fn [_ id] id))))))
          (finally
            (postgres/stop-pool! app-pool)
            (postgres/stop-pool! owner-pool)))))))
