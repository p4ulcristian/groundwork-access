(ns groundwork-access.rows-of-test
  "A param may say it is a row of a table: [:uuid {:groundwork-access/row-of
   :workstations}]. Anna is Staff in shops A and B. She moves batch #88,
   shop A's, to a workstation: one of shop B's is refused before any step
   (no email, no log row), though she holds the action in B too, since the
   move acts in A. Marks inside lists and nested maps, an optional one,
   Iron Rainbow's 404 rule for a named shop, an open list's hidden row,
   explain-refusal and the start's refusals. Runs against Postgres when
   ACCESS_TEST_PORT is set; the start's refusals don't need it."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.test-answers :refer [answer-message make-answerer refusal without-request-id]]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.api :as access]
   [groundwork-access.test-log :refer [log]]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-access.test-shaper :refer [shaper]]
   [groundwork-steps.api :as steps]))

(def ^:private port test-settings/port)

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false :denies []
           :actions {:batches/edit #{:read :insert :update} :catalog/see #{:read} :machines/see #{:read}
                     :batches/log #{:insert}}
           :roles   {"Staff" #{:batches/edit}}}}))

(defn- table [k columns access]
  {:table k :columns columns :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil :access access})

(def ^:private shops
  (table :shops [[:shop/id :uuid {:primary-key? true}]]
         [{:allow :batches/edit :only #{:read} :to [:member :shop/id]}]))

(def ^:private workstations
  (table :workstations [[:workstation/id :uuid {:primary-key? true}] [:workstation/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
         [{:allow :batches/edit :only #{:read} :to [:member :workstation/shop-id]}]))

(def ^:private batches
  (table :batches [[:batch/id :uuid {:primary-key? true}] [:batch/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
         [{:allow :batches/edit :only :all-of-the-action :to [:member :batch/shop-id]}]))

(def ^:private moves   ; the move's log: the workstation is only a value here, no reference
  (table :moves [[:move/id :uuid {:primary-key? true :default :random-uuid}] [:move/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
                 [:move/batch-id :uuid] [:move/workstation-id :uuid]]
         [{:allow :batches/edit :only #{:read :insert} :to [:member :move/shop-id]}]))

(def ^:private parts
  (table :parts [[:part/id :uuid {:primary-key? true}] [:part/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}] [:part/public :boolean]]
         [{:allow :catalog/see :only #{:read} :to :anyone :when [:= :part/public true]}]))

(def ^:private machines
  (table :machines [[:machine/id :uuid {:primary-key? true}] [:machine/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
         [{:allow :machines/see :only #{:read} :to [:member :machine/shop-id]}]))

(def ^:private tables [shops workstations batches moves parts machines])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private shop-a (id 1001))
(def ^:private shop-b (id 2002))
(def ^:private shop-c (id 3003))
(def ^:private paul (id 1))   ; Owner of A and B
(def ^:private olga (id 2))   ; Owner of C
(def ^:private anna (id 3))   ; Staff in A and B
(def ^:private station-a (id 11))
(def ^:private station-b (id 22))
(def ^:private batch-88 (id 88))   ; shop A's
(def ^:private batch-99 (id 99))   ; shop C's
(def ^:private part-public (id 51))
(def ^:private part-hidden (id 52))

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30 :outside-issuers []})

(defn- query [text] (postgres/make-query-from-sql-text "a test statement" text))

(def ^:private emails (atom []))

(def ^:private send-email
  {:name :send-email :enter #(do (swap! emails conj (:groundwork-steps/message-data %)) %)})

(def ^:private insert-move
  (query (str "INSERT INTO moves (move__shop_id, move__batch_id, move__workstation_id) "
              "SELECT batch__shop_id, batch__id, param('move/workstation-id') FROM batches WHERE batch__id = param('move/batch-id')")))

(defn- answer-with [f] {:name :answer :enter #(steps/add-answer-to-context % (f (:groundwork-access/transaction %) (:groundwork-steps/message-data %)))})

(def ^:private handlers
  {:batches/move      {:action :batches/edit :record [:batches :batch/id]
                       :params-shape [:map
                                      [:batch/id :uuid]
                                      [:workstation-id [:uuid {:groundwork-access/row-of :workstations}]]
                                      [:note-station {:optional true} [:maybe [:uuid {:groundwork-access/row-of :workstations}]]]]
                       :answer-shape [:map [:moved :int]]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [send-email
                               (answer-with (fn [t data] {:moved (postgres/run-statement! t insert-move {:move/batch-id (:batch/id data)
                                                                                                       :move/workstation-id (:workstation-id data)})}))]}
   :batches/move-many {:action :batches/edit :scope [:shop :shop/id]
                       :params-shape [:map
                                      [:shop/id :uuid]
                                      [:moves [:vector [:map [:batch-id :uuid] [:workstation-id [:uuid {:groundwork-access/row-of :workstations}]]]]]]
                       :answer-shape [:map [:count :int]]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [send-email (answer-with (fn [_ data] {:count (count (:moves data))}))]}
   :shops/settings    {:action :batches/edit :scope :every-scope-holding-the-action      ; Iron Rainbow's 404 rule
                       :params-shape [:map [:workspace/id [:uuid {:groundwork-access/row-of :shops}]]]
                       :answer-shape [:map]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [_ _] {}))]}
   :catalog/compare   {:action :catalog/see :scope :no-scope
                       :params-shape [:map [:part-ids [:set [:uuid {:groundwork-access/row-of :parts}]]]]
                       :answer-shape [:map [:count :int]]
                       :refusals {} :rate-limit nil :run-steps-in nil
                       :steps [(answer-with (fn [_ data] {:count (count (:part-ids data))}))]}})

;; -----------------------------------------------------------------------------
;; The start refuses

(defn- start-problems [handlers]
  (try (access/put-access-into-handlers handlers {:pool (Object.) :tables tables :scope-kinds scope-kinds
                                                   :find-session-step {:name :no-session :enter identity}})
       []
       (catch clojure.lang.ExceptionInfo e (:problems (ex-data e)))))

(deftest the-start-refuses-a-mark-it-cant-check
  (let [base    (:shops/settings handlers)
        marked  #(assoc base :params-shape [:map [:x %]])]
    (is (= [] (start-problems handlers)))
    (is (= [":a/unknown: the :groundwork-access/row-of :warehouses at [:x]: :warehouses is none of the tables"
            ":a/unread: the :groundwork-access/row-of :machines at [:x]: no sentence on :machines lets :batches/edit read it"
            ":a/public: the :groundwork-access/row-of :parts at [:x]: no sentence on :parts lets :batches/edit read it"
            ":a/type: the :groundwork-access/row-of :workstations at [:x]: the param is :string, and :workstations's id is :uuid"
            ":a/on-list: the :groundwork-access/row-of :workstations at [:x]: sits on a :vector; put it on the id's shape, [:uuid {:groundwork-access/row-of :workstations}]"
            ":a/in-or: a :groundwork-access/row-of in :params-shape that groundwork-access can't reach; it reads one through :map, :vector, :sequential, :set, :maybe and :and, on an id's shape: [:uuid {:groundwork-access/row-of :table}]"
            ":a/no-read: the :groundwork-access/row-of :workstations at [:x]: :batches/log doesn't read, so it can't read a row of :workstations"]
           (start-problems (array-map :a/unknown (marked [:uuid {:groundwork-access/row-of :warehouses}])
                                      :a/unread  (marked [:uuid {:groundwork-access/row-of :machines}])
                                      :a/public  (marked [:uuid {:groundwork-access/row-of :parts}])
                                      :a/type    (marked [:string {:groundwork-access/row-of :workstations}])
                                      :a/on-list (marked [:vector {:groundwork-access/row-of :workstations} :uuid])
                                      :a/in-or   (marked [:or :string [:uuid {:groundwork-access/row-of :workstations}]])
                                      :a/no-read (assoc (marked [:uuid {:groundwork-access/row-of :workstations}]) :action :batches/log))))
        "every problem at once, each named")
    (is (= [] (start-problems {:a/and (marked [:and [:uuid {:groundwork-access/row-of :workstations}] [:fn some?]])}))
        "an :and is read through")))

;; -----------------------------------------------------------------------------
;; Against Postgres

(defn- run-sql! [pool text] (postgres/run-statement! pool (query text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_app') THEN CREATE ROLE groundwork_access_app LOGIN; END IF; END $$")
  (let [ready (into (access/put-gates-on-tables tables scope-kinds) (conj access/access-tables access/sessions-table access/tokens-table access/outside-subjects-table))]
    (with-out-str @(:slow-part (postgres/migrate! owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) [] test-settings/migrate-options)))
    (postgres/grant-tables-to-role! owner-pool ready "groundwork_access_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-a "'), ('" shop-b "'), ('" shop-c "')"))
  (run-sql! owner-pool (str "INSERT INTO workstations VALUES ('" station-a "', '" shop-a "'), ('" station-b "', '" shop-b "')"))
  (run-sql! owner-pool (str "INSERT INTO batches VALUES ('" batch-88 "', '" shop-a "'), ('" batch-99 "', '" shop-c "')"))
  (run-sql! owner-pool (str "INSERT INTO parts VALUES ('" part-public "', '" shop-a "', true), ('" part-hidden "', '" shop-a "', false)"))
  (postgres/run-in-transaction! owner-pool {:read-only? false :log nil}
                              (fn [transaction]
                                (access/add-scope! transaction scope-kinds :shop shop-a paul nil)
                                (access/add-scope! transaction scope-kinds :shop shop-b paul nil)
                                (access/add-scope! transaction scope-kinds :shop shop-c olga nil)
                                (add-member! transaction scope-kinds [:shop shop-a] anna #{"Staff"})
                                (add-member! transaction scope-kinds [:shop shop-b] anna #{"Staff"}))))

(defn- count-rows [pool text] (count (postgres/find-rows pool (query text) {})))

(deftest a-marked-param-is-checked-before-any-step
  (if-not port
    (println "groundwork-access.rows-of-test: skipped, ACCESS_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (make-database! owner-pool)
      (reset! emails [])
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
                                                handler-name)))
            moves-made #(count-rows owner-pool "SELECT move__id FROM moves")]
        (try
          (testing "Anna moves shop A's batch #88 to shop A's workstation"
            (is (= {:moved 1} (ask anna :batches/move {:batch/id batch-88 :workstation-id station-a})))
            (is (= [1 1] [(count @emails) (moves-made)])))

          (testing "to shop B's workstation: :groundwork-access/not-found, before any step"
            (is (= (refusal :groundwork-access/not-found) (ask anna :batches/move {:batch/id batch-88 :workstation-id station-b}))
                "she holds :batches/edit in B too, but the move acts in A, the batch's shop")
            (is (= [1 1] [(count @emails) (moves-made)]) "no email, no log row")
            (is (= (refusal :groundwork-access/not-found) (ask anna :batches/move {:batch/id batch-88 :workstation-id (id 404)})) "no such row"))

          (testing "an optional mark: missing or nil is no row; set, it is checked"
            (is (= {:moved 1} (ask anna :batches/move {:batch/id batch-88 :workstation-id station-a :note-station nil})))
            (is (= (refusal :groundwork-access/not-found) (ask anna :batches/move {:batch/id batch-88 :workstation-id station-a :note-station station-b}))))

          (testing "marks inside a list of maps: every one checked"
            (reset! emails [])
            (is (= (refusal :groundwork-access/not-found)
                   (ask anna :batches/move-many {:shop/id shop-a :moves [{:batch-id batch-88 :workstation-id station-a}
                                                                         {:batch-id batch-88 :workstation-id station-b}]})))
            (is (= [] @emails))
            (is (= {:count 2} (ask anna :batches/move-many {:shop/id shop-a :moves [{:batch-id batch-88 :workstation-id station-a}
                                                                                    {:batch-id batch-88 :workstation-id station-a}]})))
            (is (= {:count 1} (ask anna :batches/move-many {:shop/id shop-b :moves [{:batch-id batch-88 :workstation-id station-b}]}))
                "in shop B, B's workstation is hers to name"))

          (testing "Iron Rainbow's 404 rule: a request naming another shop"
            (is (= {} (ask anna :shops/settings {:workspace/id shop-a})))
            (is (= (refusal :groundwork-access/not-found) (ask anna :shops/settings {:workspace/id shop-c}))))

          (testing "an open list's hidden row, in a set: signed out for nobody, not found for Anna"
            (is (= {:count 1} (ask nil :catalog/compare {:part-ids #{part-public}})))
            (is (= {:count 0} (ask nil :catalog/compare {:part-ids #{}})))
            (is (= (refusal :groundwork-access/signed-out) (ask nil :catalog/compare {:part-ids #{part-public part-hidden}})))
            (is (= (refusal :groundwork-access/not-found) (ask anna :catalog/compare {:part-ids #{part-hidden}}))))

          (testing "explain-refusal lists the marked rows and whether the action reads each"
            (let [explain #(access/explain-refusal {:pool app-pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                                                    {:person-id anna :visitor-id nil :session-flags #{}} %1 %2)
                  refused (explain :batches/move {:batch/id batch-88 :workstation-id station-b})
                  allowed (explain :batches/move {:batch/id batch-88 :workstation-id station-a})]
              (is (= :groundwork-access/not-found (:answer refused)))
              (is (= [{:path [:workstation-id] :table :workstations :id station-b :readable? false}] (:rows-of refused)))
              (is (= :allowed (:answer allowed)))
              (is (= [{:path [:workstation-id] :table :workstations :id station-a :readable? true}] (:rows-of allowed)))
              (is (= [{:path [:moves 1 :workstation-id] :table :workstations :id station-b :readable? false}]
                     (filterv (complement :readable?)
                              (:rows-of (explain :batches/move-many {:shop/id shop-a :moves [{:batch-id batch-88 :workstation-id station-a}
                                                                                             {:batch-id batch-88 :workstation-id station-b}]})))))
              (is (nil? (:rows-of (explain :batches/move {:batch/id batch-99 :workstation-id station-a})))
                  "the check refuses the record first")))

          (testing "the leak test still passes"
            (is (= [] (access/find-leaks ready (request anna) {:batches [batch-99] :shops [shop-c]}
                                          {:answer-message (make-answerer shaper log) :scope-kinds scope-kinds :seed 7 :size 10}))))
          (finally
            (postgres/stop-pool! app-pool)
            (postgres/stop-pool! owner-pool)))))))
