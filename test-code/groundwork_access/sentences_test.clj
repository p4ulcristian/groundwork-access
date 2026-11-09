(ns groundwork-access.sentences-test
  "Sentences into SQL: pure, no database."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.api :as access]
   [groundwork-access.check :as check]
   [groundwork-access.sentences :as sentences]))

(def ^:private scope-kinds
  {:shop {:table   :shops
          :actions {:orders/see #{:read} :orders/edit #{:read :insert :update} :orders/place #{:insert}}
          :roles   {}}})

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]]})

(def ^:private orders
  {:table   :orders
   :columns [[:order/id :uuid {:primary-key? true}]
             [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
             [:order/customer-id :uuid {:optional? true}]
             [:order/status :string]]
   :access  [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}
             {:allow :orders/see :only :all-of-the-action :to [:me :order/customer-id]}
             {:allow :orders/place :only :all-of-the-action :to :signed-in :when [:= :order/status "placed"]}]})

(def ^:private member-sees "order__shop_id = ANY ((SELECT groundwork_access.find_scopes_holding('shop', 'orders/see'))::uuid[])")
(def ^:private member-edits "order__shop_id = ANY ((SELECT groundwork_access.find_scopes_holding('shop', 'orders/edit'))::uuid[])")

(defn- acting [action sql] (str "((SELECT groundwork_access.is_acting('" action "')) AND (" sql "))"))

(deftest each-action-gets-one-condition
  (let [conditions (access/make-sql-conditions [shops orders] scope-kinds)]
    (testing "its sentences, joined by OR"
      (is (= (acting "orders/see" (str "(" member-sees ") OR (order__customer_id = (SELECT groundwork_access.find_asking_person()))"))
             (get-in conditions [:orders :actions :orders/see]))
          "a person's sentences count only while the handler acts as the action"))
    (testing "a :when narrows its sentence"
      (is (= (acting "orders/place" "((SELECT groundwork_access.find_asking_person()) IS NOT NULL AND order__status = 'placed')")
             (get-in conditions [:orders :actions :orders/place]))))
    (testing "an operation joins the actions that do it"
      (is (= (str "(" (acting "orders/edit" (str "(" member-edits ")")) ")") (get-in conditions [:orders :operations :update])))
      (is (= "false" (get-in conditions [:orders :operations :delete])) "no action deletes orders"))))

(deftest the-sentences-are-checked
  (testing "an action no scope kind has"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":orders/fly is no scope kind's action"
                          (access/make-sql-conditions [shops (assoc orders :access [{:allow :orders/fly :only :all-of-the-action :to :anyone}])] scope-kinds))))
  (testing "a column the table doesn't have"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":order/owner-id is no column"
                          (access/make-sql-conditions [shops (assoc orders :access [{:allow :orders/see :only :all-of-the-action :to [:me :order/owner-id]}])] scope-kinds))))
  (testing "an open write without a :when"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"writes"
                          (access/make-sql-conditions [shops (assoc orders :access [{:allow :orders/edit :only :all-of-the-action :to :anyone}])] scope-kinds)))))

(def ^:private workstations
  {:table   :workstations
   :columns [[:workstation/id :uuid {:primary-key? true}]
             [:workstation/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :access  [{:allow :orders/edit :only #{:read} :to [:member :workstation/shop-id]}]})

(deftest only-narrows-a-sentence-to-some-operations
  (let [conditions (access/make-sql-conditions [shops orders workstations] scope-kinds)
        edits      "workstation__shop_id = ANY ((SELECT groundwork_access.find_scopes_holding('shop', 'orders/edit'))::uuid[])"]
    (is (= (str "((SELECT groundwork_access.is_passing())) OR ((" (acting "orders/edit" (str "(" edits ")")) "))")
           (get-in conditions [:workstations :operations :read])))
    (is (= "false" (get-in conditions [:workstations :operations :update])) ":only #{:read}: no update on this table")
    (is (= "false" (get-in conditions [:workstations :operations :insert])))
    (is (= "false" (get-in conditions [:workstations :actions :orders/edit]))
        "the check's condition: orders/edit writes, and no sentence lets it write here")))

(deftest the-checks-condition-asks-for-what-the-action-is-for
  (let [ledger     {:table   :ledger
                    :columns [[:ledger/id :uuid {:primary-key? true}] [:ledger/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
                    :access  [{:allow :orders/edit :only #{:read :update} :to [:member :ledger/shop-id]}
                              {:allow :orders/see :only #{:read} :to [:me :ledger/shop-id]}]}
        conditions (access/make-sql-conditions [shops orders ledger] scope-kinds)]
    (is (= (acting "orders/edit" "(ledger__shop_id = ANY ((SELECT groundwork_access.find_scopes_holding('shop', 'orders/edit'))::uuid[]))")
           (get-in conditions [:ledger :actions :orders/edit]))
        "one of its writes is allowed here: it acts")
    (is (= (acting "orders/see" "(ledger__shop_id = (SELECT groundwork_access.find_asking_person()))")
           (get-in conditions [:ledger :actions :orders/see]))
        "an action that only reads: a sentence allowing :read")))

(deftest a-sentence-writes-out-only
  (let [problems #(try (access/make-sql-conditions [shops (assoc orders :access %)] scope-kinds) []
                       (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (= 2 (count (problems [{:allow :orders/see :to :signed-in}
                               {:allow :orders/see :only #{:delete} :to [:member :order/shop-id]}])))
        "every problem at once")
    (is (some #(re-find #"say which operations it allows" %) (problems [{:allow :orders/see :to :signed-in}])))
    (is (some #(re-find #":only #\{:delete\} is more than :orders/see does" %)
              (problems [{:allow :orders/see :only #{:delete} :to [:member :order/shop-id]}])))
    (is (some #(re-find #"is more than :orders/see does" %)
              (problems [{:allow #{:orders/see :orders/edit} :only #{:read :update} :to [:member :order/shop-id]}]))
        "with several actions, :only must fit each")
    (is (some #(re-find #"non-empty set" %) (problems [{:allow :orders/see :only #{} :to [:member :order/shop-id]}])))
    (is (= [] (problems [{:allow #{:orders/see :orders/edit} :only #{:read} :to [:member :order/shop-id]}])))))

(deftest a-member-sentence-may-name-the-scopes-own-row
  (let [kinds (assoc-in scope-kinds [:shop :actions :shops/edit] #{:read :update})
        shops (assoc shops :access [{:allow :shops/edit :only :all-of-the-action :to [:member :shop/id]}])]
    (is (= (acting "shops/edit" "(shop__id = ANY ((SELECT groundwork_access.find_scopes_holding('shop', 'shops/edit'))::uuid[]))")
           (get-in (access/make-sql-conditions [shops orders] kinds) [:shops :actions :shops/edit]))
        "the row is the scope itself")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":order/status names no scope"
                          (access/make-sql-conditions [shops (assoc orders :access [{:allow :orders/see :only :all-of-the-action
                                                                                      :to [:member :order/status]}])]
                                                       kinds))
        "another column still names none")))

(deftest a-when-may-say-a-column-is-set
  (let [kinds      (assoc-in scope-kinds [:shop :actions :catalogue/see] #{:read})
        categories {:table   :categories
                    :columns [[:category/id :uuid {:primary-key? true}]
                              [:category/shop-id :uuid {:optional? true :references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
                    :access  [{:allow :catalogue/see :only #{:read} :to :anyone :when [:some :category/shop-id]}]}
        problems   #(try (access/make-sql-conditions [shops orders %] kinds) []
                         (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (testing "[:some col] is SQL's IS NOT NULL, the twin of [:none col]"
      (is (= (acting "catalogue/see" "(true AND category__shop_id IS NOT NULL)")
             (get-in (access/make-sql-conditions [shops orders categories] kinds) [:categories :actions :catalogue/see])))
      (is (= (acting "orders/see" "(true AND (order__customer_id IS NOT NULL OR order__customer_id IS NULL))")
             (get-in (access/make-sql-conditions [shops (assoc orders :access [{:allow :orders/see :only :all-of-the-action :to :anyone
                                                                                 :when [:or [:some :order/customer-id] [:none :order/customer-id]]}])]
                                                  kinds)
                     [:orders :actions :orders/see]))
          "inside :or, beside :none"))
    (testing "its column is checked as every :when's"
      (is (some #(re-find #":category/owner-id is no column of :categories" %)
                (problems (assoc-in categories [:access 0 :when] [:some :category/owner-id])))))
    (testing "an open write may be narrowed by it"
      (is (= [] (problems (assoc categories :access [{:allow :orders/edit :only #{:update} :to :anyone :when [:some :category/shop-id]}])))))))

(deftest a-vias-when-may-say-a-column-is-set
  (let [kinds      {:shop    {:table :shops :actions {:orders/see #{:read}} :roles {}}
                    :partner {:table :partners :actions {:agreements/see #{:read}} :roles {}}}
        partners   {:table :partners :columns [[:partner/id :uuid {:primary-key? true}]]}
        agreements {:table   :agreements
                    :columns [[:agreement/id :uuid {:primary-key? true}]
                              [:agreement/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
                              [:agreement/partner-id :uuid {:references {:to :partner/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
                              [:agreement/accepted-at :time/instant {:optional? true}]]
                    :indexes [{:columns [[:agreement/shop-id :ascending :nulls-last]]
                               :method :btree :ops {} :unique? false :where nil}
                              {:columns [[:agreement/partner-id :ascending :nulls-last]]
                               :method :btree :ops {} :unique? false :where nil}]
                    :uniques []
                    :checks  []
                    :access  :public}
        orders     (assoc orders :indexes [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}])
        via        #(assoc orders :access [{:allow :orders/see :only #{:read}
                                            :via {:table :agreements :match {:order/shop-id :agreement/shop-id}
                                                  :to [:member :agreement/partner-id :agreements/see] :when %}}])
        problems   #(try (access/make-sql-conditions [shops partners agreements %] kinds) []
                         (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (= [] (problems (via [:some :agreement/accepted-at]))) "a related row that is accepted: allowed")
    (is (some #(re-find #"can't say \[:none" %) (problems (via [:none :agreement/accepted-at]))) "[:none …] there stays refused")))

(deftest a-member-sentence-naming-another-action-gives-rows-not-the-action
  ;; decisions/2026-11-09-the-check-counts-an-action-held-only-by-the-persons-roles.md
  (testing "sentences that give the action itself"
    (is (sentences/gives-the-action-itself? {:allow :orders/see :to [:member :order/shop-id]} :orders/see))
    (is (sentences/gives-the-action-itself? {:allow :orders/see :to [:member :order/shop-id :orders/see]} :orders/see) "the action named as held")
    (is (sentences/gives-the-action-itself? {:allow :orders/see :to [:member :platform]} :orders/see))
    (is (sentences/gives-the-action-itself? {:allow :orders/see :to [:me :order/customer-id]} :orders/see))
    (is (sentences/gives-the-action-itself? {:allow :orders/see :to :signed-in} :orders/see))
    (is (sentences/gives-the-action-itself? {:allow :orders/see :via {:table :agreements :match {} :to [:member :agreement/partner-id]}} :orders/see)))
  (testing "a [:member …] needing another action held: rows only"
    (is (not (sentences/gives-the-action-itself? {:allow :orders/note :to [:member :order/shop-id :orders/edit]} :orders/note)))
    (is (not (sentences/gives-the-action-itself? {:allow :orders/see :to [:member :platform :platform/support]} :orders/see)))
    (is (not (sentences/gives-the-action-itself? {:allow :orders/see :via {:table :agreements :match {} :to [:member :agreement/partner-id :agreements/see]}}
                                                 :orders/see)))))

(deftest the-check-counts-open-ways-and-the-action-held-itself
  (let [ways #{:anyone :signed-in :visitor [:holds :orders/see] [:holds :shop/access] [:system :mailer]}]
    (is (= #{:anyone :signed-in :visitor [:holds :orders/see]} (check/find-counted-ways ways :every-scope-holding-the-action :orders/see))
        "[:holds :shop/access], a [:member … :shop/access] sentence's way, gives rows, not orders/see")
    (is (= #{:anyone :signed-in :visitor} (check/find-counted-ways ways :no-scope :orders/see)) "no scope: the open ways only")
    (is (= #{} (check/find-counted-ways #{[:holds :shop/access]} :every-scope-holding-the-action :orders/note))
        "a member whose only way is another action held there does not hold it")))
