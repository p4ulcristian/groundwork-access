(ns groundwork-access.roles-test
  "Roles have ids, and a member holds a set of roles. RC Hotcoats' owner,
   Paul, renames \"Painter\" to \"Coater\", gives Anna one extra right as a
   small role (\"QC approver\") beside \"Employee\", invites Tom with no role
   yet, and removes a role once nobody holds it. The check, \"what may I
   do?\", explain-refusal and the leak test read the union of a member's
   roles. Runs against Postgres when ACCESS_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.test-answers :refer [answer-message make-answerer refusal without-request-id]]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.api :as access]
   [groundwork-access.test-log :refer [log]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-access.test-shaper :refer [shaper]]
   [groundwork-steps.api :as steps]
   [groundwork-shaped-json.api :as shaped-json-api]))

(def ^:private port test-settings/port)

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false :denies []
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update} :qc/approve #{:read :update}}
           :roles   {"Painter" #{:orders/see :orders/edit} "Employee" #{:orders/see}}}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]]
   :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private orders
  {:table :orders
   :columns [[:order/id :uuid {:primary-key? true}] [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}] [:order/approved :boolean]]
   :indexes [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}] :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow #{:orders/see :orders/edit :qc/approve} :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private tables [shops orders])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private hotcoats (id 1001))
(def ^:private other-shop (id 2002))
(def ^:private paul (id 1))   ; Owner of RC Hotcoats
(def ^:private anna (id 2))   ; Employee and QC approver
(def ^:private dora (id 3))   ; Painter, then Coater
(def ^:private tom  (id 4))   ; invited, no role yet
(def ^:private olga (id 5))   ; Owner of the other shop
(def ^:private order-1 (id 11))
(def ^:private order-2 (id 22))   ; the other shop's

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30})

(defn- query [text] (postgres/make-query-from-sql-text "a test statement" text))

(defn- run-sql! [pool text] (postgres/run-statement! pool (query text) {}))

(def ^:private handlers
  {:qc/approve {:action :qc/approve :record [:orders :order/id]
                :params-shape [:map [:order/id :uuid]] :answer-shape [:map [:count :int]]
                :refusals {} :rate-limit nil :run-steps-in nil
                :steps [{:name :approve
                         :enter #(steps/add-answer-to-context
                                  % {:count (postgres/run-statement! (:groundwork-access/transaction %)
                                                                   (query "UPDATE orders SET order__approved = true WHERE order__id = param('order/id')")
                                                                   (:groundwork-steps/message-data %))})}]}
   :qc/in-shop {:action :qc/approve :scope [:shop :shop/id]
                :params-shape [:map [:shop/id :uuid]] :answer-shape [:map]
                :refusals {} :rate-limit nil :run-steps-in nil
                :steps [{:name :answer :enter #(steps/add-answer-to-context % {})}]}})

(defn- refused-with [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :name :held-by]))))

(defn- role-names [pool scope] (mapv :name (access/list-roles pool scope-kinds scope)))

(deftest a-member-holds-a-set-of-roles
  (if-not port
    (println "groundwork-access.roles-test: skipped, ACCESS_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
      (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_app') THEN CREATE ROLE groundwork_access_app LOGIN; END IF; END $$")
      (let [ready-tables (into (access/put-gates-on-tables tables scope-kinds) (conj access/access-tables access/sessions-table access/tokens-table))]
        (with-out-str @(:slow-part (postgres/migrate! owner-pool (test-settings/declare-tables-and-access ready-tables tables scope-kinds) [] test-settings/migrate-options)))
        (postgres/grant-tables-to-role! owner-pool ready-tables "groundwork_access_app"))
      (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" hotcoats "'), ('" other-shop "')"))
      (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-1 "', '" hotcoats "', false), ('" order-2 "', '" other-shop "', false)"))
      (let [shop     [:shop hotcoats]
            roles    (postgres/run-in-transaction! owner-pool {:read-only? false :log nil}
                                                 #(do (access/add-scope! % scope-kinds :shop other-shop olga nil)
                                                      (access/add-scope! % scope-kinds :shop hotcoats paul nil)))
            painter  (get roles "Painter")
            employee (get roles "Employee")
            app-pool (test-settings/start-pool! "groundwork_access_app" 4)
            ready    (access/put-access-into-handlers handlers {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                 :find-session-step (access/make-find-session-step app-pool settings)})
            request  (fn [person]
                       {:request-method :post
                        :headers {"origin" "http://app.test"
                                  "cookie" (first (str/split (:set-cookie (access/sign-in! app-pool {:headers {}} person
                                                                                            {:settings settings :tables [] :session-flags #{}}))
                                                             #";"))}})
            ask      (fn [person handler-name params]
                       (without-request-id (get (answer-message ready (request person) {handler-name params} {:shaper shaper :log log})
                                                handler-name)))
            my-actions (let [handlers {:groundwork-access/my-actions (access/make-my-actions-handler {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                                             :find-session-step (access/make-find-session-step app-pool settings)
                                                                                             :rate-limit nil})}]
                         (fn [person]
                           (:shaped-data (shaped-json-api/turn-json-data-into-shaped-data-or-errors
                                          shaper access/my-actions-answer-shape
                                          (get (answer-message handlers (request person) {:groundwork-access/my-actions {}} {:shaper shaper :log log})
                                               :groundwork-access/my-actions)))))
            explain  (fn [person handler-name params]
                       (access/explain-refusal {:pool app-pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                                                {:person-id person :visitor-id nil :session-flags #{}} handler-name params))]
        (try
          (testing "add-scope! answers the new roles' ids by name"
            (is (= #{"Owner" "Painter" "Employee"} (set (keys roles))))
            (is (every? uuid? (vals roles))))

          (let [qc-approver (access/add-role! owner-pool scope-kinds shop "QC approver" #{:qc/approve})]
            (testing "an extra right is a small role; a member holds a set of roles; Tom holds none yet"
              (postgres/run-in-transaction! owner-pool {:read-only? false :log nil}
                                          (fn [transaction]
                                            (access/add-membership! transaction shop anna #{employee qc-approver})
                                            (access/add-membership! transaction shop dora #{painter})
                                            (access/add-membership! transaction shop tom #{})))
              (is (= {shop #{:orders/see :qc/approve}} (access/list-actions-of-person owner-pool scope-kinds anna)) "the union of her roles'")
              (is (= {shop #{}} (access/list-actions-of-person owner-pool scope-kinds tom)) "a member, holding nothing")
              (is (= ["Employee" "Owner" "Painter" "QC approver"] (role-names owner-pool shop)))
              (is (= #{:orders/see :orders/edit :qc/approve}
                     (:actions (first (filter #(= "Owner" (:name %)) (access/list-roles owner-pool scope-kinds shop)))))
                  "Owner: every action of the kind"))

            (testing "the check reads the union"
              (is (= {:count 1} (ask anna :qc/approve {:order/id order-1})) "QC approver gives her :qc/approve")
              (is (= (refusal :groundwork-access/not-allowed) (ask dora :qc/approve {:order/id order-1})) "Dora sees the order, but no role of hers approves")
              (is (= (refusal :groundwork-access/not-found) (ask tom :qc/approve {:order/id order-1})) "Tom, no role yet: nothing")
              (is (= {} (ask anna :qc/in-shop {:shop/id hotcoats})))
              (is (= (refusal :groundwork-access/not-found) (ask tom :qc/in-shop {:shop/id hotcoats}))))

            (testing "\"what may I do?\": the union, and nothing for a member with no role"
              (is (= [{:scope-kind :shop :scope-id hotcoats :actions [:orders/see :qc/approve]}] (my-actions anna)))
              (is (= [] (my-actions tom))))

            (testing "explain-refusal names the roles that gave the action, and those held"
              (let [answer (explain anna :qc/approve {:order/id order-1})]
                (is (= :allowed (:answer answer)))
                (is (= [{:scope-kind :shop :scope-id hotcoats :role-id qc-approver :name "QC approver" :gives :qc/approve}]
                       (:given-by (:to (first (:sentences answer))))))
                (is (= [{:scope-kind :shop :scope-id hotcoats
                         :roles [{:role-id employee :name "Employee" :actions [:orders/see]}
                                 {:role-id qc-approver :name "QC approver" :actions [:qc/approve]}]}]
                       (:memberships answer))))
              (let [answer (explain dora :qc/approve {:order/id order-1})]
                (is (= :groundwork-access/not-allowed (:answer answer)))
                (is (= [] (:given-by (:to (first (:sentences answer))))) "no role of hers gives it")
                (is (= [{:scope-kind :shop :scope-id hotcoats :roles [{:role-id painter :name "Painter" :actions [:orders/edit :orders/see]}]}]
                       (:memberships answer))))
              (let [answer (explain tom :qc/in-shop {:shop/id hotcoats})]
                (is (= :groundwork-access/not-found (:answer answer)))
                (is (= {:says [:shop :shop/id] :scope-kind :shop :scope-id hotcoats :held-there? false :given-by []} (:scope answer)))
                (is (= [{:scope-kind :shop :scope-id hotcoats :roles []}] (:memberships answer)) "a member, holding no role")))

            (testing "the leak test: Anna's roles and Tom's none reach nothing of the other shop"
              (let [options {:answer-message (make-answerer shaper log) :scope-kinds scope-kinds :seed 7 :size 10}]
                (is (= [] (access/find-leaks ready (request anna) {:orders [order-2] :shops [other-shop]} options)))
                (is (= [] (access/find-leaks ready (request tom) {:orders [order-1 order-2] :shops [hotcoats other-shop]} options)))))

            (testing "a rename is one row: Dora keeps her role"
              (access/rename-role! owner-pool shop painter "Coater")
              (is (= ["Coater" "Employee" "Owner" "QC approver"] (role-names owner-pool shop)))
              (is (= {shop #{:orders/see :orders/edit}} (access/list-actions-of-person owner-pool scope-kinds dora))))

            (testing "a name is unique in its scope; Owner is groundwork-access's"
              (is (= {:type :groundwork-access/role-name-taken :name "Employee"} (refused-with #(access/rename-role! owner-pool shop painter "Employee"))))
              (is (= {:type :groundwork-access/role-name-taken :name "Employee"} (refused-with #(access/add-role! owner-pool scope-kinds shop "Employee" #{}))))
              (is (= :groundwork-access/owner-role (:type (refused-with #(access/rename-role! owner-pool shop painter "Owner")))))
              (is (= :groundwork-access/owner-role (:type (refused-with #(access/rename-role! owner-pool shop (get roles "Owner") "Boss")))))
              (is (= :groundwork-access/owner-role (:type (refused-with #(access/add-role! owner-pool scope-kinds shop "Owner" #{})))))
              (is (= :groundwork-access/owner-role (:type (refused-with #(access/set-role-actions! owner-pool scope-kinds shop (get roles "Owner") #{})))))
              (is (= :groundwork-access/owner-role (:type (refused-with #(access/remove-role! owner-pool shop (get roles "Owner"))))))
              (is (uuid? (access/add-role! owner-pool scope-kinds [:shop other-shop] "Coater" #{})) "the same name in another shop"))

            (testing "set-role-actions! reaches every member holding the role at once"
              (access/set-role-actions! owner-pool scope-kinds shop qc-approver #{:qc/approve :orders/edit})
              (is (= {shop #{:orders/see :orders/edit :qc/approve}} (access/list-actions-of-person owner-pool scope-kinds anna)))
              (is (= :groundwork-access/unknown-action (:type (refused-with #(access/set-role-actions! owner-pool scope-kinds shop qc-approver #{:orders/fly}))))))

            (testing "a role from another shop is refused, by groundwork-access and by Postgres"
              (let [elsewhere (get (into {} (map (juxt :name :role-id)) (access/list-roles owner-pool scope-kinds [:shop other-shop])) "Painter")]
                (is (= :groundwork-access/unknown-role (:type (refused-with #(access/set-membership-roles! owner-pool shop tom #{elsewhere})))))
                (is (= :groundwork-access/unknown-role (:type (refused-with #(access/rename-role! owner-pool shop elsewhere "Mine")))))
                (is (thrown? Exception (run-sql! owner-pool (str "INSERT INTO groundwork_access.membership_roles VALUES ('" tom "', 'shop', '" hotcoats "', '" elsewhere "')")))
                    "the reference holds the role to the membership's scope")))

            (testing "a role is removed only once nobody holds it"
              (is (= {:type :groundwork-access/role-held :name "Coater" :held-by 1} (refused-with #(access/remove-role! owner-pool shop painter))))
              (is (true? (access/set-membership-roles! owner-pool shop dora #{employee})) "Dora moves to Employee")
              (is (false? (access/set-membership-roles! owner-pool shop olga #{employee})) "Olga isn't a member here: nothing changes")
              (is (= {shop #{:orders/see}} (access/list-actions-of-person owner-pool scope-kinds dora)))
              (is (nil? (access/remove-role! owner-pool shop painter)))
              (is (= ["Employee" "Owner" "QC approver"] (role-names owner-pool shop))))

            (testing "Tom gets his role in place"
              (is (true? (access/set-membership-roles! owner-pool shop tom #{qc-approver})))
              (is (= {:count 1} (ask tom :qc/approve {:order/id order-1}))))

            (testing "leaving a scope takes the roles held there"
              (is (true? (access/remove-membership! owner-pool shop anna)))
              (is (= {} (access/list-actions-of-person owner-pool scope-kinds anna)))
              (is (= [] (postgres/find-rows owner-pool (query (str "SELECT membership_role__role_id FROM groundwork_access.membership_roles WHERE membership_role__person_id = '" anna "'")) {})))))
          (finally
            (postgres/stop-pool! app-pool)
            (postgres/stop-pool! owner-pool)))))))
