(ns groundwork-access.where-it-acts-test
  "A handler says where it acts, and Postgres hears only that: its action,
   in its scopes. Anna holds :machines/edit in shop A and is an Employee in
   shop B, whose role holds another action that writes machines; Bob, an
   Order clerk in shop A, holds only :orders/edit, which a sentence's
   :only lets move ledger rows and read workstations. Runs against
   Postgres when ACCESS_TEST_PORT is set; the start's refusals don't need
   it."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.test-answers :refer [answer-message make-answerer refusal refusal-json read-without-request-ids without-request-id]]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.api :as access]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.test-log :refer [log]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-access.test-shaper :refer [shaper]]
   [groundwork-steps.api :as steps]))

(def ^:private port test-settings/port)

;; -----------------------------------------------------------------------------
;; The app

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false
           :denies  [{:deny :writes :when [:= :shop/status "suspended"]}]
           :actions {:billing/mark      #{:read :update}                ; a named system actor's, on shops
                     :machines/edit     #{:read :insert :update}
                     :workstations/edit #{:read :insert :update}
                     :orders/edit       #{:read :insert :update :delete}
                     :payouts/edit      #{:read :insert :update :delete}
                     :shops/edit        #{:read :update}}
           :roles   {"Mechanic"    #{:machines/edit}
                     "Employee"    #{:workstations/edit}
                     "Order clerk" #{:orders/edit}}}
    :platform {:table nil :parent nil :single? true :denies []
               :actions {:shops/lift #{:read :update}}
               :roles   {"Support" #{:shops/lift}}}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}] [:shop/name :string] [:shop/status :string]]
   :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow :shops/edit :only :all-of-the-action :to [:member :shop/id]}              ; the scope's own row
            {:allow :shops/lift :only :all-of-the-action :to [:member :platform :shops/lift]}  ; the app's staff
            {:allow :billing/mark :only :all-of-the-action :to [:system :billing]}]})

(def ^:private machines
  {:table :machines
   :columns [[:machine/id :uuid {:primary-key? true :default :random-uuid}]
             [:machine/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes [{:columns [[:machine/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}] :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow :machines/edit :only :all-of-the-action :to [:member :machine/shop-id]}
            {:allow :workstations/edit :only :all-of-the-action :to [:member :machine/shop-id]}]})   ; another action writes machines

(def ^:private orders
  {:table :orders
   :columns [[:order/id :uuid {:primary-key? true}]
             [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}] :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow :orders/edit :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private ledger-entries
  {:table :ledger-entries
   :columns [[:ledger-entry/id :uuid {:primary-key? true :default :random-uuid}]
             [:ledger-entry/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
             [:ledger-entry/order-id :uuid]
             [:ledger-entry/amount :int]]
   :indexes [{:columns [[:ledger-entry/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}] :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow :payouts/edit :only :all-of-the-action :to [:member :ledger-entry/shop-id]}
            {:allow :orders/edit :only #{:read :insert :update} :to [:member :ledger-entry/shop-id]}]})

(def ^:private workstations
  {:table :workstations
   :columns [[:workstation/id :uuid {:primary-key? true}]
             [:workstation/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
             [:workstation/name :string]]
   :indexes [{:columns [[:workstation/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}] :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow :workstations/edit :only :all-of-the-action :to [:member :workstation/shop-id]}
            {:allow :orders/edit :only #{:read} :to [:member :workstation/shop-id]}]})

(def ^:private tables [shops machines orders ledger-entries workstations])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private shop-a (id 1001))
(def ^:private shop-b (id 2002))
(def ^:private shop-c (id 3003))   ; suspended, Paul's
(def ^:private platform (id 9009))
(def ^:private sam  (id 4))   ; Support in the platform
(def ^:private paul (id 1))   ; Owner of both shops
(def ^:private anna (id 2))   ; Mechanic in A, Employee in B
(def ^:private bob  (id 3))   ; Order clerk in A
(def ^:private order-a (id 11))
(def ^:private order-b (id 22))
(def ^:private ledger-a (id 111))
(def ^:private station-a (id 1111))
(def ^:private station-b (id 2222))

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30 :outside-issuers []})

;; -----------------------------------------------------------------------------
;; Handlers

(defn- query [text] (postgres/make-query-from-sql-text "a test statement" text))

(defn- answer-with [f] {:name :answer :enter #(steps/add-answer-to-context % (f (:groundwork-access/transaction %) (:groundwork-steps/message-data %)))})

(def ^:private insert-machine (query "INSERT INTO machines (machine__shop_id) VALUES (param('machine/shop-id'))"))

(def ^:private bob-does
  {"insert-ledger"      #(postgres/run-statement! %1 (query "INSERT INTO ledger_entries (ledger_entry__shop_id, ledger_entry__order_id, ledger_entry__amount) VALUES (param('target/shop-id'), param('order/id'), 5)") %2)
   "update-ledger"      #(postgres/run-statement! %1 (query "UPDATE ledger_entries SET ledger_entry__amount = ledger_entry__amount + 1 WHERE ledger_entry__order_id = param('order/id')") (select-keys %2 [:order/id]))
   "delete-ledger"      #(postgres/run-statement! %1 (query "DELETE FROM ledger_entries WHERE ledger_entry__order_id = param('order/id')") (select-keys %2 [:order/id]))
   "read-workstations"  (fn [t _] (count (postgres/find-rows t (query "SELECT workstation__id FROM workstations") {})))
   "update-workstation" (fn [t _] (postgres/run-statement! t (query "UPDATE workstations SET workstation__name = 'moved'") {}))})

(def ^:private handlers
  {:machines/create   {:action :machines/edit :scope [:shop :machine/shop-id]
                       :params-shape [:map [:machine/shop-id :uuid]] :answer-shape [:map]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [t data] (postgres/run-statement! t insert-machine data) {}))]}
   ;; names shop A, but its step writes the machine into the shop of another param: a bug, or a crafted param
   :machines/misplace {:action :machines/edit :scope [:shop :shop/id]
                       :params-shape [:map [:shop/id :uuid] [:machine/shop-id :uuid]] :answer-shape [:map]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [t data] (postgres/run-statement! t insert-machine (select-keys data [:machine/shop-id])) {}))]}
   :machines/mine     {:action :machines/edit :scope :every-scope-holding-the-action
                       :params-shape [:map] :answer-shape [:vector :uuid]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [t _] (mapv :machine/shop-id (postgres/find-rows t (query "SELECT machine__shop_id FROM machines ORDER BY machine__shop_id") {}))))]}
   :shops/rename      {:action :shops/edit :scope [:shop :shop/id]
                       :params-shape [:map [:shop/id :uuid]] :answer-shape [:map [:count :int]]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [t data] {:count (postgres/run-statement! t (query "UPDATE shops SET shop__name = 'renamed'") {})}))]}
   :shops/one         {:action :shops/edit :record [:shops :shop/id]
                       :params-shape [:map [:shop/id :uuid]] :answer-shape [:map [:shop/id :uuid]]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [_ data] (select-keys data [:shop/id])))]}
   :stations/rename   {:action :orders/edit :record [:workstations :workstation/id]   ; :only #{:read} there
                       :params-shape [:map [:workstation/id :uuid]] :answer-shape [:map]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [_ _] {}))]}
   :ledger/one        {:action :orders/edit :record [:ledger-entries :ledger-entry/id]  ; :only #{:read :insert :update} there
                       :params-shape [:map [:ledger-entry/id :uuid]] :answer-shape [:map]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [_ _] {}))]}
   :shops/lift        {:action :shops/lift :record [:shops :shop/id]
                       :params-shape [:map [:shop/id :uuid]] :answer-shape [:map [:count :int]]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [t data] {:count (postgres/run-statement! t (query "UPDATE shops SET shop__status = 'open' WHERE shop__id = param('shop/id')") data)}))]}
   :orders/work       {:action :orders/edit :record [:orders :order/id]
                       :params-shape [:map [:order/id :uuid] [:target/shop-id :uuid] [:work (into [:enum] (sort (keys bob-does)))]]
                       :answer-shape [:map [:count :int]]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [t data] {:count ((get bob-does (:work data)) t (dissoc data :work))}))]}})

;; -----------------------------------------------------------------------------
;; The start refuses

(defn- start-problems [handlers]
  (try (access/put-access-into-handlers handlers {:pool (Object.) :tables tables :scope-kinds scope-kinds
                                                   :find-session-step {:name :no-session :enter identity}})
       []
       (catch clojure.lang.ExceptionInfo e (:problems (ex-data e)))))

(deftest the-start-refuses-a-handler-that-says-not-where-it-acts
  (let [base (:machines/create handlers)]
    (is (= [":a/none says neither :record nor :scope: write out where it acts, :record [table param], :scope [kind param], :scope :every-scope-holding-the-action or :scope :no-scope"
            ":a/both says both :record and :scope; it acts in one of them"
            ":a/word: :scope is [kind param], :every-scope-holding-the-action or :no-scope, not :everywhere"
            ":a/kind: :scope names :warehouse, which is no scope kind"]
           (start-problems (array-map :a/none (dissoc base :scope)
                                      :a/both (assoc base :record [:machines :machine/id])
                                      :a/word (assoc base :scope :everywhere)
                                      :a/kind (assoc base :scope [:warehouse :machine/shop-id]))))
        "every problem at once")
    (is (= [] (start-problems handlers)))))

(deftest the-start-refuses-a-sentence-without-only
  (let [problems #(try (access/make-sql-conditions % scope-kinds) []
                       (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (some #(re-find #"say which operations it allows" %)
              (problems [shops (update-in workstations [:access 1] dissoc :only)])))
    (is (some #(re-find #":only #\{:delete\} is more than :shops/edit does" %)
              (problems [(assoc-in shops [:access 0 :only] #{:delete})])))))

;; -----------------------------------------------------------------------------
;; Against Postgres

(defn- run-sql! [pool text] (postgres/run-statement! pool (query text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_app') THEN CREATE ROLE groundwork_access_app LOGIN; END IF; END $$")
  (let [ready (into (access/put-gates-on-tables tables scope-kinds) (conj access/access-tables access/sessions-table access/tokens-table access/outside-subjects-table))]
    (with-out-str @(:slow-part (postgres/migrate! owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) [] test-settings/migrate-options)))
    (postgres/grant-tables-to-role! owner-pool ready "groundwork_access_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-a "', 'A', 'open'), ('" shop-b "', 'B', 'open'), ('" shop-c "', 'C', 'suspended')"))
  (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-a "', '" shop-a "'), ('" order-b "', '" shop-b "')"))
  (run-sql! owner-pool (str "INSERT INTO ledger_entries VALUES ('" ledger-a "', '" shop-a "', '" order-a "', 10)"))
  (run-sql! owner-pool (str "INSERT INTO workstations VALUES ('" station-a "', '" shop-a "', 'oven'), ('" station-b "', '" shop-b "', 'booth')"))
  (postgres/run-in-transaction! owner-pool {:read-only? false :log nil}
                              (fn [transaction]
                                (access/add-scope! transaction scope-kinds :shop shop-a paul nil)
                                (access/add-scope! transaction scope-kinds :shop shop-b paul nil)
                                (access/add-scope! transaction scope-kinds :shop shop-c paul nil)
                                (access/add-scope! transaction scope-kinds :platform platform sam nil)
                                (access/remove-membership! transaction [:platform platform] sam)
                                (add-member! transaction scope-kinds [:platform platform] sam #{"Support"})
                                (add-member! transaction scope-kinds [:shop shop-a] anna #{"Mechanic"})
                                (add-member! transaction scope-kinds [:shop shop-b] anna #{"Employee"})
                                (add-member! transaction scope-kinds [:shop shop-a] bob #{"Order clerk"}))))

(defn- count-rows [pool text] (count (postgres/find-rows pool (query text) {})))

(deftest a-handler-acts-where-it-says
  (if-not port
    (println "groundwork-access.where-it-acts-test: skipped, ACCESS_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (make-database! owner-pool)
      (let [app-pool (test-settings/start-pool! "groundwork_access_app" 4)
            ready    (access/put-access-into-handlers handlers {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                 :find-session-step (access/make-find-session-step app-pool settings)})
            request  (fn [person]
                       {:request-method :post
                        :headers {"origin" "http://app.test"
                                  "cookie" (first (str/split (:set-cookie (access/sign-in! app-pool {:headers {}} person
                                                                                            {:settings settings :tables [] :session-flags #{} :session-lasts :session-days :outside-subjects {}}))
                                                             #";"))}})
            ask      (fn [person handler-name params]
                       (without-request-id (get (answer-message ready (request person) {handler-name params} {:shaper shaper :log log})
                                                handler-name)))
            machines-in #(count-rows owner-pool (str "SELECT 1 AS row__found FROM machines WHERE machine__shop_id = '" % "'"))]
        (try
          (testing "Anna: :machines/edit in A, an Employee in B whose role writes machines too"
            (is (= {} (ask anna :machines/create {:machine/shop-id shop-a})) "in A, where she holds it")
            (is (= (refusal :groundwork-access/not-found) (ask anna :machines/create {:machine/shop-id shop-b}))
                "in B: refused by the check, before any step")
            (is (= [1 0] [(machines-in shop-a) (machines-in shop-b)])))

          (testing "a handler that names A but inserts a row in B is refused by the gate"
            (is (= (refusal :groundwork-steps/failed) (ask anna :machines/misplace {:shop/id shop-a :machine/shop-id shop-b})))
            (is (= 0 (machines-in shop-b)) "Postgres heard :machines/edit in A only, not her Employee action in B")
            (is (= {} (ask anna :machines/misplace {:shop/id shop-a :machine/shop-id shop-a}))))

          (testing ":every-scope-holding-the-action: every shop holding the action, no other"
            (run-sql! owner-pool (str "INSERT INTO machines (machine__shop_id) VALUES ('" shop-b "')"))
            (is (= [(str shop-a) (str shop-a)] (ask anna :machines/mine {})) "B's machine is reached by another action only")
            (is (= (refusal :groundwork-access/not-found) (ask bob :machines/mine {})) "Bob holds it nowhere"))

          (testing "a sentence on the scope's own row"
            (is (= {:count 1} (ask paul :shops/rename {:shop/id shop-a})) "the Owner renames A, and only A")
            (is (= ["renamed" "C" "B"] (map :shop/name (postgres/find-rows owner-pool (query "SELECT shop__name FROM shops ORDER BY shop__name DESC") {}))))
            (is (= (refusal :groundwork-access/not-found) (ask anna :shops/rename {:shop/id shop-a})) "Anna doesn't hold :shops/edit")
            (is (= {:shop/id (str shop-b)} (ask paul :shops/one {:shop/id shop-b})))
            (is (= (refusal :groundwork-access/not-found) (ask bob :shops/one {:shop/id shop-a}))))

          (testing "Bob, Order clerk in A holding only :orders/edit: what :only lets it do on other tables"
            (let [work #(ask bob :orders/work {:order/id order-a :target/shop-id shop-a :work %})]
              (is (= {:count 1} (work "insert-ledger")) "a ledger row goes in")
              (is (= {:count 2} (work "update-ledger")) "the ledger rows move")
              (is (= {:count 0} (work "delete-ledger")) "never deleted: :only has no :delete")
              (is (= 2 (count-rows owner-pool "SELECT 1 AS row__found FROM ledger_entries")))
              (is (= {:count 1} (work "read-workstations")) "workstations read")
              (is (= {:count 0} (work "update-workstation")) "and only read")
              (is (= #{"oven" "booth"} (set (map :workstation/name (postgres/find-rows owner-pool (query "SELECT workstation__name FROM workstations") {}))))))
            (is (= (refusal :groundwork-steps/failed) (ask bob :orders/work {:order/id order-a :target/shop-id shop-b :work "insert-ledger"}))
                "a ledger row in shop B, from shop A's order: refused by the gate")
            (is (= (refusal :groundwork-access/not-found) (ask bob :orders/work {:order/id order-b :target/shop-id shop-b :work "insert-ledger"}))
                "shop B's order: refused by the check")
            (is (= 2 (count-rows owner-pool "SELECT 1 AS row__found FROM ledger_entries"))))

          (testing "the check respects :only: a record the action only reads there is not allowed, one it doesn't reach not found"
            (is (= (refusal :groundwork-access/not-allowed) (ask bob :stations/rename {:workstation/id station-a}))
                ":orders/edit reads shop A's workstations, never writes them")
            (is (= (refusal :groundwork-access/not-found) (ask bob :stations/rename {:workstation/id station-b})) "shop B's: no sentence reaches it")
            (is (= {} (ask bob :ledger/one {:ledger-entry/id ledger-a})) "the ledger: :only lets it write there"))

          (testing "a suspended shop's own row is frozen for its own kind's actions"
            (let [shop-c-row #(first (postgres/find-rows owner-pool (query (str "SELECT shop__name, shop__status FROM shops WHERE shop__id = '" shop-c "'")) {}))
                  as-paul    #(access/run-as-asker-of-request! app-pool (request paul)
                                                                 {:settings settings :read-only? false :scope-kinds scope-kinds :log nil} %)]
              (is (= (refusal :groundwork-access/not-allowed) (ask paul :shops/rename {:shop/id shop-c})) "the check: the Owner can't change its settings")
              (is (= 0 (as-paul #(postgres/run-statement! % (query (str "UPDATE shops SET shop__status = 'open' WHERE shop__id = '" shop-c "'")) {})))
                  "the gate: nor lift the suspension")
              (is (= 1 (as-paul #(count (postgres/find-rows % (query (str "SELECT shop__id FROM shops WHERE shop__id = '" shop-c "'")) {}))))
                  "it still reads")
              (is (= 1 (access/run-as-system! app-pool :billing {:read-only? false :log nil}
                                               #(postgres/run-statement! % (query (str "UPDATE shops SET shop__name = 'billed' WHERE shop__id = '" shop-c "'")) {})))
                  "a named system actor writes it: the denies hold people")
              (is (= {:count 1} (ask sam :shops/lift {:shop/id shop-c})) "the platform's Support lifts it, by an action of another kind")
              (is (= {:shop/name "billed" :shop/status "open"} (shop-c-row)))
              (is (= {:count 1} (ask paul :shops/rename {:shop/id shop-c})) "lifted, the Owner writes it again")))

          (testing "explain-refusal names the scope and that the action isn't held there"
            (let [answer (access/explain-refusal {:pool app-pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                                                  {:person-id anna :visitor-id nil :session-flags #{}}
                                                  :machines/create {:machine/shop-id shop-b})]
              (is (= :groundwork-access/not-found (:answer answer)))
              (is (= {:says [:shop :machine/shop-id] :scope-kind :shop :scope-id shop-b :held-there? false :given-by []} (:scope answer))
                  "her Employee role in B gives another action")))

          (testing "the leak test asks a :scope handler in each foreign scope"
            (let [options {:answer-message (make-answerer shaper log) :scope-kinds scope-kinds :seed 7 :size 10}]
              (is (= [] (access/find-leaks (select-keys ready [:machines/create]) (request anna) {:shops [shop-b]} options)))
              (let [unchecked (update-in ready [:machines/create :steps] #(vec (remove (comp #{:groundwork-access/check} :name) %)))]
                (is (= [{:handler :machines/create :asked-in [:shop shop-b] :answer {}}]
                       (access/find-leaks (select-keys unchecked [:machines/create]) (request anna) {:shops [shop-b]} options))
                    "without the check, Postgres hears every action she holds, and the machine goes into B: caught"))))
          (finally
            (postgres/stop-pool! app-pool)
            (postgres/stop-pool! owner-pool)))))))
