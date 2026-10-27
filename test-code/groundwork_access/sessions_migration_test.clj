(ns groundwork-access.sessions-migration-test
  "groundwork-access v0.7.0 changed groundwork-access.sessions on a database that has rows:
   a visitor column and a flags column added, the person made optional
   (and groundwork_access.scopes got an index). The tables are made as v0.6.0's
   maps say (v0.6.x is the last before the change; the maps as they were,
   with groundwork-postgres's two newer keys written out empty, which change no table),
   people's sessions written into them, then groundwork-postgres brings them to
   v0.7's maps (today's sessions and tokens; the access tables as v0.7
   had them, which groundwork-access.roles-migration-test takes on). The rows must survive and work. The Postgres tests run
   when ACCESS_TEST_PORT is set; they say they were skipped otherwise."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.api :as access]
   [groundwork-access.cookie :as cookie]
   [groundwork-access.test-log :as test-log]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-steps.api :as steps]))

(def ^:private port test-settings/port)

(def ^:private sessions-table-of-v0-6
  {:table      :groundwork-access/sessions
   :columns    [[:session/fingerprint :string       {:primary-key? true}]
                [:session/person-id   :uuid]
                [:session/created-at  :time/instant {:default :now}]
                [:session/ends-at     :time/instant]]
   :indexes    [{:columns [[:session/person-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}
                {:columns [[:session/ends-at :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private access-tables-of-v0-6
  [{:table      :groundwork-access/scopes
    :columns    [[:scope/kind        :string {:primary-key? true}]
                 [:scope/id          :uuid   {:primary-key? true}]
                 [:scope/parent-kind :string {:optional? true}]
                 [:scope/parent-id   :uuid   {:optional? true}]]
    :indexes    []
    :uniques    []
    :checks     []
    :references []
    :triggers   []
    :gates      nil
    :on-change  nil}
   {:table      :groundwork-access/roles
    :columns    [[:role/scope-kind :string {:primary-key? true}]
                 [:role/scope-id   :uuid   {:primary-key? true}]
                 [:role/name       :string {:primary-key? true}]]
    :indexes    []
    :uniques    []
    :checks     []
    :references []
    :triggers   []
    :gates      nil
    :on-change  nil}
   {:table      :groundwork-access/role-actions
    :columns    [[:role-action/scope-kind :string {:primary-key? true}]
                 [:role-action/scope-id   :uuid   {:primary-key? true}]
                 [:role-action/role       :string {:primary-key? true}]
                 [:role-action/action     :string {:primary-key? true}]]
    :indexes    []
    :uniques    []
    :checks     []
    :references []
    :triggers   []
    :gates      nil
    :on-change  nil}
   {:table      :groundwork-access/memberships
    :columns    [[:membership/person-id  :uuid   {:primary-key? true}]
                 [:membership/scope-kind :string {:primary-key? true}]
                 [:membership/scope-id   :uuid   {:primary-key? true}]
                 [:membership/role       :string]]
    :indexes    [{:columns [[:membership/scope-kind :ascending :nulls-last] [:membership/scope-id :ascending :nulls-last]]
                  :method :btree :ops {} :unique? false :where nil}]
    :uniques    []
    :checks     []
    :references []
    :triggers   []
    :gates      nil
    :on-change  nil}])

(def ^:private access-tables-of-v0-7
  "v0.7's access tables, which this migration reached: v0.6's, groundwork_access.scopes
   with its parent index. (v0.7 to today's: groundwork-access.roles-migration-test.)"
  (assoc-in access-tables-of-v0-6 [0 :indexes] [{:columns [[:scope/parent-kind :ascending :nulls-last] [:scope/parent-id :ascending :nulls-last]]
                                                 :method :btree :ops {} :unique? false :where nil}]))

(def ^:private settings
  {:cookie-name "app_session" :session-days 30 :visitor-session-days 2 :renew-after-minutes 60
   :allowed-origin "https://app.test" :secure-cookie? true
   :token-lead "app_" :token-shown-characters 10 :token-longest-days 30 :outside-issuers []})

(def ^:private anna #uuid "a0000000-0000-4000-8000-000000000001")
(def ^:private bob #uuid "b0000000-0000-4000-8000-000000000002")

(defn- run-sql! [pool text] (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- find-rows [pool text] (postgres/find-rows pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- make-request [cookie-value]
  {:request-method :post
   :headers        (cond-> {"origin" "https://app.test"}
                     cookie-value (assoc "cookie" (str "app_session=" cookie-value)))})

(defn- turn-set-cookie-into-value [set-cookie]
  (second (re-find #"^app_session=([^;]*)" set-cookie)))

(defn- find-asker [step cookie-value]
  ((juxt :groundwork-access/person-id :groundwork-access/visitor-id :groundwork-access/session-flags)
   (steps/run-steps-until-answered [step] {:groundwork-steps/request (make-request cookie-value) :groundwork-steps/log test-log/log})))

(defn- write-session-of-v0-6!
  "A person's session as v0.6.x's sign-in! wrote it: the cookie's
   fingerprint, the person, when it ends."
  [pool cookie-value person-id]
  (run-sql! pool (str "INSERT INTO groundwork_access.sessions (session__fingerprint, session__person_id, session__ends_at) VALUES ('"
                      (cookie/turn-cookie-value-into-fingerprint cookie-value) "', '" person-id "', now() + interval '20 days')")))

(deftest the-sessions-of-v0-6-survive-the-migration-and-work
  (if-not port
    (println "groundwork-access.sessions-migration-test: skipped, ACCESS_TEST_PORT is not set")
    (let [pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (try
        (run-sql! pool "DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
        @(:slow-part (postgres/migrate! pool (test-settings/declare-tables (conj access-tables-of-v0-6 sessions-table-of-v0-6)) [] test-settings/migrate-options))
        (write-session-of-v0-6! pool "anna-old-cookie" anna)
        (write-session-of-v0-6! pool "anna-other-device" anna)
        (write-session-of-v0-6! pool "bob-old-cookie" bob)
        (let [today (conj access-tables-of-v0-7 access/sessions-table)
              plan  (postgres/make-plan-from-tables pool (test-settings/declare-tables today) [])]
          (testing "groundwork-postgres's plan has no stop and needs no change entry: three columns added, the person made optional, an index"
            (is (= [] (:stops plan)))
            (is (= #{"ALTER TABLE groundwork_access.sessions ADD COLUMN session__visitor_id uuid"
                     "ALTER TABLE groundwork_access.sessions ADD COLUMN session__flags text NOT NULL DEFAULT ''"
                     "ALTER TABLE groundwork_access.sessions ALTER COLUMN session__person_id DROP NOT NULL"
                     "ALTER TABLE groundwork_access.sessions ADD COLUMN session__renews boolean NOT NULL DEFAULT true"}
                   (set (map :sql (:instant plan)))))
            (is (= 1 (count (:slow plan))))
            ;; the index name is groundwork-postgres's: over 63 bytes, cut with a hash of the whole
            (is (str/starts-with? (:sql (first (:slow plan))) "CREATE INDEX CONCURRENTLY IF NOT EXISTS groundwork_access_scopes_scope__parent_kind_scope__par_9d4928b9 ON groundwork_access.scopes")))
          @(:slow-part (postgres/migrate! pool (test-settings/declare-tables (conj today access/tokens-table access/outside-subjects-table)) [] test-settings/migrate-options))
          (is (postgres/does-database-match-tables? pool (test-settings/declare-tables (conj today access/tokens-table access/outside-subjects-table)) [])
              "nothing left to do; the tokens' and the outside subjects' tables made by the same plan")
          (testing "every row survives, with no flags and no visitor, pushed out when seen again, as before"
            (is (= [[anna "" nil true] [anna "" nil true] [bob "" nil true]]
                   (sort-by (comp str first)
                            (map (juxt :session/person-id :session/flags :session/visitor-id :session/renews)
                                 (find-rows pool "SELECT session__person_id, session__flags, session__visitor_id, session__renews FROM groundwork_access.sessions"))))))
          (let [step (access/make-find-session-step pool settings)]
            (testing "an old cookie still finds its person"
              (is (= [anna nil #{}] (find-asker step "anna-old-cookie")))
              (is (= [bob nil #{}] (find-asker step "bob-old-cookie"))))
            (testing "a visitor's session works after it, and becomes the person's at sign-in"
              (let [{:keys [visitor-id set-cookie]} (access/start-visitor-session! pool (make-request nil) settings)
                    visitor-cookie (turn-set-cookie-into-value set-cookie)]
                (is (uuid? visitor-id))
                (is (= [nil visitor-id #{}] (find-asker step visitor-cookie)))
                (let [signed-in (access/sign-in! pool (make-request visitor-cookie) anna
                                                  {:settings settings :tables [] :session-flags #{:impersonated} :session-lasts :session-days :outside-subjects {}})]
                  (is (= [nil nil #{}] (find-asker step visitor-cookie)) "the visitor's session ended")
                  (is (= [anna nil #{:impersonated}] (find-asker step (turn-set-cookie-into-value (:set-cookie signed-in))))))))
            (testing "signing out everywhere ends the old sessions with the new"
              (is (= {:sessions 3 :tokens 0 :outside-subjects 0} (access/sign-out-everywhere! pool anna)))
              (is (= [nil nil #{}] (find-asker step "anna-other-device"))))))
        (finally (postgres/stop-pool! pool))))))
