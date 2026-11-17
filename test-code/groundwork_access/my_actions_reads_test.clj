(ns groundwork-access.my-actions-reads-test
  "\"What may I do?\"'s reads of the vias: each via's function read once
   per call, however many actions and sentences share it; each its query
   made once with the handler; the tables the functions read named for the
   answer's live-update notes. Runs when ACCESS_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.api :as access]
   [groundwork-access.test-answers :refer [answer-message without-request-id]]
   [groundwork-access.test-log :refer [log]]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-access.test-shaper :refer [shaper]]
   [groundwork-postgres.api :as postgres]
   [groundwork-shaped-json.api :as shaped-json-api]))

(def ^:private port (some-> (System/getenv "ACCESS_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table   :shops :parent nil :single? false
           :actions {:parts/see #{:read} :parts/share #{:read} :parts/quote #{:read}
                     :variants/see #{:read} :variants/pick #{:read} :designs/see #{:read}
                     :packages/edit #{:read :insert :update}}
           :roles   {"Staff" #{:packages/edit}}
           :denies  [{:deny #{:parts/quote} :when [:= :shop/status "closed"]}]}}))

(defn- references-shop [k]
  [k :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}])

(defn- index-on
  "One btree index on the columns, in order."
  [& ks]
  {:columns (vec (for [k ks] [k :ascending :nulls-last])) :method :btree :ops {} :unique? false :where nil})

(defn- table-map [table columns indexes access]
  {:table table :columns columns :indexes indexes :uniques [] :checks [] :references [] :triggers [] :on-change nil :access access})

(def ^:private shops
  (table-map :shops [[:shop/id :uuid {:primary-key? true}] [:shop/status :string]] [] :public))

(def ^:private packages
  (table-map :packages
             [[:package/id :uuid {:primary-key? true}] (references-shop :package/shop-id)
              [:package/status :string] [:package/owner-id :uuid {:optional? true}]]
             [(index-on :package/id :package/shop-id) (index-on :package/owner-id)]
             [{:allow :packages/edit :only :all-of-the-action :to [:member :package/shop-id]}]))

(defn- via-row-table
  "A table whose rows' actions come by a via to packages: three columns,
   its id, a package's and a shop's."
  [table to actions]
  (let [ns-name (name table)
        k       #(keyword ns-name %)]
    (table-map table
               [[(k "id") :uuid {:primary-key? true}] [(k "package-id") :uuid] (references-shop (k "shop-id"))]
               [(index-on (k "package-id") (k "shop-id"))]
               [{:allow actions :only :all-of-the-action
                 :via   {:table :packages :match {(k "package-id") :package/id (k "shop-id") :package/shop-id}
                         :to    to :when [:= :package/status "published"]}}])))

;; three vias: anyone's (three actions), someone signed in's (two), the owner's (one)
(def ^:private parts    (via-row-table :part :anyone #{:parts/see :parts/share :parts/quote}))
(def ^:private variants (via-row-table :variant :signed-in #{:variants/see :variants/pick}))
(def ^:private designs  (via-row-table :design [:me :package/owner-id] :designs/see))

(def ^:private tables [shops packages parts variants designs])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))
(def ^:private shop-1 (id 1001))
(def ^:private shop-2 (id 1002))
(def ^:private shop-3 (id 1003))
(def ^:private paul (id 1)) ; Owner of the shops
(def ^:private anna (id 2)) ; Staff in shop 1, owner of shop 2's package
(def ^:private eve  (id 3)) ; signed in, owner of shop 1's package

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30 :outside-issuers []})

(defn- run-sql! [pool text] (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_app') THEN CREATE ROLE groundwork_access_app LOGIN; END IF; END $$")
  (let [ready (into (access/put-gates-on-tables tables scope-kinds) (conj access/access-tables access/sessions-table access/tokens-table access/outside-subjects-table))]
    (with-out-str @(:slow-part (postgres/migrate! owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) [] test-settings/migrate-options)))
    (postgres/grant-tables-to-role! owner-pool ready "groundwork_access_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-1 "', 'open'), ('" shop-2 "', 'open'), ('" shop-3 "', 'closed')"))
  ;; shop 1's and 3's packages published, shop 2's a draft
  (run-sql! owner-pool (str "INSERT INTO packages VALUES ('" (id 51) "', '" shop-1 "', 'published', '" eve "'), "
                            "('" (id 52) "', '" shop-2 "', 'draft', '" anna "'), "
                            "('" (id 53) "', '" shop-3 "', 'published', '" anna "')"))
  (postgres/run-in-transaction! owner-pool {:read-only? false :log nil}
                                (fn [transaction]
                                  (doseq [shop [shop-1 shop-2 shop-3]] (access/add-scope! transaction scope-kinds :shop shop paul nil))
                                  (add-member! transaction scope-kinds [:shop shop-1] anna #{"Staff"}))))

(defn- cookie-of [set-cookie] (first (str/split set-cookie #";")))

(defn- what-may [handlers cookie]
  (let [answer (without-request-id (:groundwork-access/my-actions
                                    (answer-message handlers {:request-method :post :headers {"origin" "http://app.test" "cookie" cookie}}
                                                    {:groundwork-access/my-actions {}} {:shaper shaper :log log})))]
    (:shaped-data (shaped-json-api/turn-json-data-into-shaped-data-or-errors shaper access/my-actions-answer-shape answer))))

(defn- noting-reads
  "(f), and the queries of every via and deny read it ran, in order."
  [f]
  (let [seen      (atom [])
        find-rows postgres/find-rows]
    (with-redefs [postgres/find-rows (fn [pool query params]
                                       (when (re-find #"^the scopes" (:groundwork-postgres/path query))
                                         (swap! seen conj query))
                                       (find-rows pool query params))]
      (f))
    @seen))

(deftest the-vias-are-read-once-each
  (if-not port
    (println "groundwork-access.my-actions-reads-test: skipped, ACCESS_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (make-database! owner-pool)
      (let [app-pool (test-settings/start-pool! "groundwork_access_app" 2)
            handlers {:groundwork-access/my-actions
                      (access/make-my-actions-handler {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                       :find-session-step (access/make-find-session-step app-pool settings)
                                                       :rate-limit nil})}
            sign-in  #(cookie-of (:set-cookie (access/sign-in! app-pool {:headers {}} % {:settings settings :tables tables :session-flags #{}
                                                                                         :session-lasts :session-days :outside-subjects {}})))
            visitor  (cookie-of (:set-cookie (access/start-visitor-session! app-pool {:headers {}} settings)))]
        (try
          (testing "the answer, as v0.8.10 gave it: each via's actions in the shops its published packages name (and, :anyone, :signed-in and [:me …], everywhere); a closed shop's deny"
            (is (= [{:scope-kind nil :scope-id nil :actions [:parts/quote :parts/see :parts/share]}
                    {:scope-kind :shop :scope-id shop-1 :actions [:parts/quote :parts/see :parts/share]}
                    {:scope-kind :shop :scope-id shop-3 :actions [:parts/see :parts/share]}]
                   (what-may handlers visitor))
                "a visitor: anyone's via only")
            (is (= [{:scope-kind nil :scope-id nil :actions [:designs/see :parts/quote :parts/see :parts/share :variants/pick :variants/see]}
                    {:scope-kind :shop :scope-id shop-1 :actions [:designs/see :parts/quote :parts/see :parts/share :variants/pick :variants/see]}
                    {:scope-kind :shop :scope-id shop-3 :actions [:parts/see :parts/share :variants/pick :variants/see]}]
                   (what-may handlers (sign-in eve)))
                "signed in, owner of shop 1's package")
            (is (= [{:scope-kind nil :scope-id nil :actions [:designs/see :parts/quote :parts/see :parts/share :variants/pick :variants/see]}
                    {:scope-kind :shop :scope-id shop-1 :actions [:packages/edit :parts/quote :parts/see :parts/share :variants/pick :variants/see]}
                    {:scope-kind :shop :scope-id shop-3 :actions [:designs/see :parts/see :parts/share :variants/pick :variants/see]}]
                   (what-may handlers (sign-in anna)))
                "Staff in shop 1, owner of shop 2's draft and shop 3's package"))

          (testing "one read per via function and the deny, not one per action: 3 vias and 1 deny, where the plan lists 6 actions"
            (let [cookie (sign-in anna)
                  first  (noting-reads #(what-may handlers cookie))
                  second (noting-reads #(what-may handlers cookie))]
              (is (= 4 (count first) (count second)))
              (is (= 4 (count (distinct (map #(System/identityHashCode %) (concat first second)))))
                  "the same query values each call: the planner is asked once per query")))

          (testing "the answer's notes name the tables the functions read"
            (let [[_ tables-read] (postgres/run-and-list-tables-read {:log log} #(what-may handlers (sign-in anna)))]
              (is (contains? tables-read :packages) "the vias' table")
              (is (contains? tables-read :shops) "the deny's kind's table")))
          (finally
            (postgres/stop-pool! app-pool)
            (postgres/stop-pool! owner-pool)))))))
