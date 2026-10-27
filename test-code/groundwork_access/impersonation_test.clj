(ns groundwork-access.impersonation-test
  "What an impersonating admin may not do: actions of their own (sending a
   message, taking a payment), denied by the scope kind to a session
   flagged :impersonated, {:deny #{…} :session :impersonated}. The check
   answers :groundwork-access/not-allowed for them, wherever the handler acts; the
   gates refuse them too; every other action of the scope stays the
   person's; \"what may I do?\" leaves them out; explain-refusal names the
   deny. Anna is Staff in shop A; an admin acts as her. Runs against
   Postgres when ACCESS_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.api :as access]
   [groundwork-access.test-answers :refer [answer-message refusal without-request-id]]
   [groundwork-access.test-log :refer [log]]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-access.test-shaper :refer [shaper]]
   [groundwork-postgres.api :as postgres]
   [groundwork-shaped-json.api :as shaped-json]
   [groundwork-steps.api :as steps]))

(def ^:private port test-settings/port)

(def ^:private settings {:cookie-name "app_session" :session-days 30 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "shop_" :token-shown-characters 11 :token-longest-days 90 :outside-issuers []})

(def ^:private impersonation-deny {:deny #{:messages/send :payments/take} :session :impersonated})

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false
           :actions {:orders/see #{:read} :orders/edit #{:read :update}
                     :messages/send #{:read :insert} :payments/take #{:read :update}}
           :roles   {"Staff" #{:orders/see :orders/edit :messages/send :payments/take}}
           :denies  [impersonation-deny]}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]]
   :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private orders
  {:table :orders
   :columns [[:order/id :uuid {:primary-key? true}]
             [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
             [:order/paid :boolean {:default false}]]
   :indexes [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow #{:orders/see :orders/edit :payments/take} :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private messages
  {:table :messages
   :columns [[:message/id :uuid {:primary-key? true :default :random-uuid}]
             [:message/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
             [:message/text :string]]
   :indexes [{:columns [[:message/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow :messages/send :only :all-of-the-action :to [:member :message/shop-id]}]})

(def ^:private tables [shops orders messages])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private shop-a (id 1001))
(def ^:private order-1 (id 5001))
(def ^:private paul (id 1))
(def ^:private anna (id 2))

(defn- query [text] (postgres/make-query-from-sql-text "a test statement" text))

(defn- answer-with [f] {:name :answer :enter #(steps/add-answer-to-context % (f (:groundwork-access/transaction %) (:groundwork-steps/message-data %)))})

(def ^:private insert-message (query "INSERT INTO messages (message__shop_id, message__text) VALUES (param('message/shop-id'), 'hello')"))
(def ^:private mark-paid (query "UPDATE orders SET order__paid = true WHERE order__id = param('order/id')"))

(def ^:private handlers
  {:messages/send {:action :messages/send :scope [:shop :message/shop-id]
                   :params-shape [:map [:message/shop-id :uuid]] :answer-shape [:map]
                   :refusals {} :rate-limit nil :run-steps-in nil
                   :steps [(answer-with (fn [t data] (postgres/run-statement! t insert-message data) {}))]}
   :payments/take {:action :payments/take :record [:orders :order/id]
                   :params-shape [:map [:order/id :uuid]] :answer-shape [:map]
                   :refusals {} :rate-limit nil :run-steps-in nil
                   :steps [(answer-with (fn [t data] (postgres/run-statement! t mark-paid data) {}))]}
   :payments/all  {:action :payments/take :scope :every-scope-holding-the-action
                   :params-shape [:map] :answer-shape [:vector :uuid]
                   :refusals {} :rate-limit nil :run-steps-in nil
                   :steps [(answer-with (fn [t _] (mapv :order/id (postgres/find-rows t (query "SELECT order__id FROM orders") {}))))]}
   :orders/edit   {:action :orders/edit :record [:orders :order/id]
                   :params-shape [:map [:order/id :uuid]] :answer-shape [:map]
                   :refusals {} :rate-limit nil :run-steps-in nil
                   :steps [(answer-with (fn [_ _] {}))]}
   ;; an action the deny doesn't name, whose step tries a denied action's write
   :orders/sneak  {:action :orders/edit :record [:orders :order/id]
                   :params-shape [:map [:order/id :uuid] [:message/shop-id :uuid]] :answer-shape [:map]
                   :refusals {} :rate-limit nil :run-steps-in nil
                   :steps [(answer-with (fn [t data] (postgres/run-statement! t insert-message (select-keys data [:message/shop-id])) {}))]}})

(defn- run-sql! [pool text] (postgres/run-statement! pool (query text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_app') THEN CREATE ROLE groundwork_access_app LOGIN; END IF; END $$")
  (let [ready (into (access/put-gates-on-tables tables scope-kinds)
                    (conj access/access-tables access/sessions-table access/tokens-table access/outside-subjects-table))]
    (with-out-str @(:slow-part (postgres/migrate! owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) [] test-settings/migrate-options)))
    (postgres/grant-tables-to-role! owner-pool ready "groundwork_access_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-a "')"))
  (run-sql! owner-pool (str "INSERT INTO orders (order__id, order__shop_id) VALUES ('" order-1 "', '" shop-a "')"))
  (postgres/run-in-transaction! owner-pool {:read-only? false :log nil}
                                (fn [transaction]
                                  (access/add-scope! transaction scope-kinds :shop shop-a paul nil)
                                  (add-member! transaction scope-kinds [:shop shop-a] anna #{"Staff"}))))

(deftest an-impersonated-session-is-denied-the-actions-its-flag-names
  (if-not port
    (println "groundwork-access.impersonation-test: skipped, ACCESS_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (make-database! owner-pool)
      (let [app-pool   (test-settings/start-pool! "groundwork_access_app" 4)
            step       (access/make-find-session-step app-pool settings)
            ready      (assoc (access/put-access-into-handlers handlers {:pool app-pool :tables tables :scope-kinds scope-kinds :find-session-step step})
                              :groundwork-access/my-actions (access/make-my-actions-handler {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                                             :find-session-step step :rate-limit nil}))
            browser    (fn [flags lasts]
                         {:request-method :post
                          :headers {"origin" "http://app.test"
                                    "cookie" (first (str/split (:set-cookie (access/sign-in! app-pool {:headers {}} anna
                                                                                             {:settings settings :tables [] :session-flags flags
                                                                                              :session-lasts lasts :outside-subjects {}}))
                                                               #";"))}})
            anna-own   (browser #{} :session-days)
            acting-as  (browser #{:impersonated} {:minutes 60})          ; an admin, acting as Anna for an hour
            ask        (fn [request handler-name params]
                         (without-request-id (get (answer-message ready request {handler-name params} {:shaper shaper :log log}) handler-name)))
            messages-n #(count (postgres/find-rows owner-pool (query "SELECT message__id FROM messages") {}))
            explain    #(access/explain-refusal {:pool app-pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                                                {:person-id anna :visitor-id nil :session-flags %1} %2 %3)]
        (try
          (testing "Anna herself: every action of her role"
            (is (= {} (ask anna-own :messages/send {:message/shop-id shop-a})))
            (is (= {} (ask anna-own :payments/take {:order/id order-1})))
            (is (= [(str order-1)] (ask anna-own :payments/all {}))))

          (testing "an admin acting as her: the denied actions, Not allowed, wherever the handler acts"
            (is (= (refusal :groundwork-access/not-allowed) (ask acting-as :messages/send {:message/shop-id shop-a})) ":scope [kind param]")
            (is (= (refusal :groundwork-access/not-allowed) (ask acting-as :payments/take {:order/id order-1})) ":record")
            (is (= (refusal :groundwork-access/not-allowed) (ask acting-as :payments/all {})) ":every-scope-holding-the-action")
            (is (= 1 (messages-n)) "nothing sent"))

          (testing "every other action stays hers"
            (is (= {} (ask acting-as :orders/edit {:order/id order-1}))))

          (testing "the gates refuse a denied action's write: from another action's step, and where every action counts"
            (is (= (refusal :groundwork-steps/failed) (ask acting-as :orders/sneak {:order/id order-1 :message/shop-id shop-a})))
            (let [send-as! (fn [request]
                             (try (access/run-as-asker-of-request! app-pool request {:settings settings :read-only? false :scope-kinds scope-kinds :log log}
                                                                   (fn [transaction _asker] (postgres/run-statement! transaction insert-message {:message/shop-id shop-a})))
                                  (catch Exception _ :refused)))]
              (is (= :refused (send-as! acting-as)) "the deny is in the gate's condition")
              (is (= 1 (messages-n)))
              (is (= 1 (send-as! anna-own)) "her own session writes it")
              (is (= 2 (messages-n)))))

          (testing "\"what may I do?\" leaves the denied actions out"
            (let [actions-of #(:actions (first (filter :scope-kind (:shaped-data (shaped-json/turn-json-data-into-shaped-data-or-errors
                                                                                  shaper access/my-actions-answer-shape
                                                                                  (ask % :groundwork-access/my-actions {}))))))]
              (is (= [:messages/send :orders/edit :orders/see :payments/take] (actions-of anna-own)))
              (is (= [:orders/edit :orders/see] (actions-of acting-as)))))

          (testing "explain-refusal names the deny"
            (let [answer (explain #{:impersonated} :payments/take {:order/id order-1})]
              (is (= :groundwork-access/not-allowed (:answer answer)))
              (is (true? (:holds? (:to (first (:sentences answer))))) "her role gives it there: the deny alone says no")
              (is (= [{:scope-kind :shop :deny impersonation-deny :says-no? true}] (:denies answer))))
            (let [answer (explain #{:impersonated} :messages/send {:message/shop-id shop-a})]
              (is (= :groundwork-access/not-allowed (:answer answer)))
              (is (= [{:scope-kind :shop :deny impersonation-deny :says-no? true}] (:denies answer))))
            (is (= [] (:denies (explain #{:impersonated} :orders/edit {:order/id order-1}))) "an action it doesn't name: no deny listed")
            (is (= [{:scope-kind :shop :deny impersonation-deny :says-no? false}] (:denies (explain #{} :payments/take {:order/id order-1})))
                "her own session: listed, saying no to nothing"))
          (finally
            (postgres/stop-pool! app-pool)
            (postgres/stop-pool! owner-pool)))))))
