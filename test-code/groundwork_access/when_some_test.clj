(ns groundwork-access.when-some-test
  "A sentence's :when may say a column is set, [:some col]: in the check,
   the gates, a scope's state deny and explain-refusal. Shop A's own
   categories are read by anyone, Iron Rainbow's universal ones (no shop)
   are not; a part's public price shows once its shop set a price or a
   template; a shop with a suspension date is frozen. Anna is Staff in shop
   A; Bob is nobody signed in. Runs against Postgres when
   ACCESS_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.test-answers :refer [answer-message make-answerer refusal without-request-id]]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.api :as access]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.test-log :refer [log]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-access.test-shaper :refer [shaper]]
   [groundwork-steps.api :as steps]))

(def ^:private port test-settings/port)

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false
           :denies  [{:deny :writes :when [:some :shop/suspended-at]}]   ; a suspension date set: frozen
           :actions {:catalogue/see #{:read} :prices/see #{:read} :parts/edit #{:read :update}}
           :roles   {"Staff" #{:parts/edit}}}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}] [:shop/suspended-at :time/instant {:optional? true}]]
   :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private categories
  {:table :categories
   :columns [[:category/id :uuid {:primary-key? true}]
             [:category/shop-id :uuid {:optional? true :references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]   ; nil: the universal catalogue's
             [:category/name :string]]
   :indexes [{:columns [[:category/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}] :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow :catalogue/see :only #{:read} :to :anyone :when [:some :category/shop-id]}]})

(def ^:private parts
  {:table :parts
   :columns [[:part/id :uuid {:primary-key? true}]
             [:part/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
             [:part/pricing :int {:optional? true}]
             [:part/template-id :uuid {:optional? true}]]
   :indexes [{:columns [[:part/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}] :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow :prices/see :only #{:read} :to :anyone :when [:or [:some :part/pricing] [:some :part/template-id]]}
            {:allow :parts/edit :only :all-of-the-action :to [:member :part/shop-id]}]})

(def ^:private tables [shops categories parts])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private shop-a (id 1001))
(def ^:private shop-c (id 3003))   ; suspended
(def ^:private paul (id 1))        ; Owner of both
(def ^:private anna (id 2))        ; Staff in A and C
(def ^:private category-a (id 11))
(def ^:private category-universal (id 12))
(def ^:private part-priced (id 21))
(def ^:private part-templated (id 22))
(def ^:private part-bare (id 23))
(def ^:private part-c (id 31))

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30 :outside-issuers []})

(defn- query [text] (postgres/make-query-from-sql-text "a test statement" text))

(defn- answer-with [f] {:name :answer :enter #(steps/add-answer-to-context % (f (:groundwork-access/transaction %) (:groundwork-steps/message-data %)))})

(def ^:private handlers
  {:categories/all {:action :catalogue/see :scope :no-scope
                    :params-shape [:map] :answer-shape [:vector :string]
                    :refusals {} :rate-limit nil :run-steps-in nil
                    :steps [(answer-with (fn [t _] (mapv :category/name (postgres/find-rows t (query "SELECT category__name FROM categories ORDER BY category__name") {}))))]}
   :categories/one {:action :catalogue/see :record [:categories :category/id]
                    :params-shape [:map [:category/id :uuid]] :answer-shape [:map [:category/id :uuid]]
                    :refusals {} :rate-limit nil :run-steps-in nil
                    :steps [(answer-with (fn [_ data] (select-keys data [:category/id])))]}
   :prices/all     {:action :prices/see :scope :no-scope
                    :params-shape [:map] :answer-shape [:vector :uuid]
                    :refusals {} :rate-limit nil :run-steps-in nil
                    :steps [(answer-with (fn [t _] (mapv :part/id (postgres/find-rows t (query "SELECT part__id FROM parts ORDER BY part__id") {}))))]}
   :parts/price    {:action :parts/edit :record [:parts :part/id]
                    :params-shape [:map [:part/id :uuid]] :answer-shape [:map [:count :int]]
                    :refusals {} :rate-limit nil :run-steps-in nil
                    :steps [(answer-with (fn [t data] {:count (postgres/run-statement! t (query "UPDATE parts SET part__pricing = 5 WHERE part__id = param('part/id')") data)}))]}})

(defn- run-sql! [pool text] (postgres/run-statement! pool (query text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_app') THEN CREATE ROLE groundwork_access_app LOGIN; END IF; END $$")
  (let [ready (into (access/put-gates-on-tables tables scope-kinds) (conj access/access-tables access/sessions-table access/tokens-table access/outside-subjects-table))]
    (with-out-str @(:slow-part (postgres/migrate! owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) [] test-settings/migrate-options)))
    (postgres/grant-tables-to-role! owner-pool ready "groundwork_access_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-a "', NULL), ('" shop-c "', now())"))
  (run-sql! owner-pool (str "INSERT INTO categories VALUES ('" category-a "', '" shop-a "', 'Wheels'), ('" category-universal "', NULL, 'Universal frames')"))
  (run-sql! owner-pool (str "INSERT INTO parts VALUES ('" part-priced "', '" shop-a "', 120, NULL), ('" part-templated "', '" shop-a "', NULL, '" (id 99) "'), "
                            "('" part-bare "', '" shop-a "', NULL, NULL), ('" part-c "', '" shop-c "', NULL, NULL)"))
  (postgres/run-in-transaction! owner-pool {:read-only? false :log nil}
                              (fn [transaction]
                                (access/add-scope! transaction scope-kinds :shop shop-a paul nil)
                                (access/add-scope! transaction scope-kinds :shop shop-c paul nil)
                                (add-member! transaction scope-kinds [:shop shop-a] anna #{"Staff"})
                                (add-member! transaction scope-kinds [:shop shop-c] anna #{"Staff"}))))

(deftest a-when-says-a-column-is-set
  (if-not port
    (println "groundwork-access.when-some-test: skipped, ACCESS_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (make-database! owner-pool)
      (let [app-pool (test-settings/start-pool! "groundwork_access_app" 4)
            ready    (access/put-access-into-handlers handlers {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                 :find-session-step (access/make-find-session-step app-pool settings)})
            request  (fn [person]
                       {:request-method :post
                        :headers (cond-> {"origin" "http://app.test"}
                                   person (assoc "cookie" (first (str/split (:set-cookie (access/sign-in! app-pool {:headers {}} person
                                                                                                           {:settings settings :tables [] :session-flags #{} :session-lasts :session-days :outside-subjects {}}))
                                                                            #";"))))})
            ask      (fn [person handler-name params]
                       (without-request-id (get (answer-message ready (request person) {handler-name params} {:shaper shaper :log log})
                                                handler-name)))]
        (try
          (testing "the gate: a shop's own categories are read by anyone, the universal ones are not"
            (is (= ["Wheels"] (ask nil :categories/all {})) "Bob, nobody signed in")
            (is (= ["Wheels"] (ask anna :categories/all {}))))
          (testing "the check: a universal category is not found"
            (is (= {:category/id (str category-a)} (ask nil :categories/one {:category/id category-a})))
            (is (= (refusal :groundwork-access/signed-out) (ask nil :categories/one {:category/id category-universal})) "Bob, signed out")
            (is (= (refusal :groundwork-access/not-found) (ask anna :categories/one {:category/id category-universal}))))
          (testing "[:or [:some …] [:some …]]: a price shows once the shop set a price or a template"
            (is (= [(str part-priced) (str part-templated)] (ask nil :prices/all {})))
            (is (= {:count 1} (ask anna :parts/price {:part/id part-bare})) "Anna sets the bare part's price")
            (is (= [(str part-priced) (str part-templated) (str part-bare)] (ask nil :prices/all {})) "and now it shows"))
          (testing "a scope's state deny by [:some …]: shop C has a suspension date, so it is frozen"
            (is (= (refusal :groundwork-access/not-allowed) (ask anna :parts/price {:part/id part-c})) "the check")
            (is (= 0 (access/run-as-asker-of-request! app-pool (request anna) {:settings settings :read-only? false :scope-kinds scope-kinds :log nil}
                                                       #(postgres/run-statement! % (query (str "UPDATE parts SET part__pricing = 1 WHERE part__id = '" part-c "'")) {})))
                "the gate")
            (run-sql! owner-pool (str "UPDATE shops SET shop__suspended_at = NULL WHERE shop__id = '" shop-c "'"))
            (is (= {:count 1} (ask anna :parts/price {:part/id part-c})) "the date cleared, she writes again"))
          (testing "explain-refusal says the :when doesn't hold"
            (let [answer (access/explain-refusal {:pool app-pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                                                  {:person-id anna :visitor-id nil :session-flags #{}}
                                                  :categories/one {:category/id category-universal})]
              (is (= :groundwork-access/not-found (:answer answer)))
              (is (= [{:says [:some :category/shop-id] :holds? false}] (map :when (:sentences answer))))))
          (finally
            (postgres/stop-pool! app-pool)
            (postgres/stop-pool! owner-pool)))))))
