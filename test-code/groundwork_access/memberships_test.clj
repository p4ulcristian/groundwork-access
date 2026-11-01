(ns groundwork-access.memberships-test
  "The Postgres tests run when ACCESS_TEST_PORT is set; they say
   they were skipped otherwise."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.api :as access]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-postgres.api :as postgres]))

(def ^:private port (some-> (System/getenv "ACCESS_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit} "Bookkeeper" #{:orders/see}}
           :parent nil :single? false :denies []}}))

(def ^:private kinds-with-a-parent
  (access/check-scope-kinds
   {:company {:table :companies :actions {:company/report #{:read}} :roles {}
              :parent nil :single? false :denies []}
    :shop    {:table :shops :actions {:orders/see #{:read}} :roles {}
              :parent :company :single? false :denies []}}))

(def ^:private company-1 #uuid "c0000000-0000-4000-8000-000000000001")
(def ^:private shop-8 #uuid "80000000-0000-4000-8000-000000000008")

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private dora #uuid "00000000-0000-4000-8000-00000000000c")
(def ^:private bob  #uuid "00000000-0000-4000-8000-00000000000d")
(def ^:private carl #uuid "00000000-0000-4000-8000-00000000000e")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")

(deftest the-scope-kinds-are-checked
  (testing "a role naming an action its kind doesn't have"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names :orders/fly"
                          (access/check-scope-kinds {:shop {:table nil :parent nil :single? false :denies []
                                                                      :actions {:orders/see #{:read}} :roles {"Pilot" #{:orders/fly}}}}))))
  (testing "Owner is groundwork-access's own"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Owner"
                          (access/check-scope-kinds {:shop {:table nil :parent nil :single? false :denies []
                                                                      :actions {:orders/see #{:read}} :roles {"Owner" #{}}}})))))

(deftest who-belongs-where
  (if-not port
    (println "groundwork-access.memberships-test: skipped, ACCESS_TEST_PORT is not set")
    (let [pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (try
        (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" "DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE") {})
        (with-out-str @(:slow-part (postgres/migrate! pool (test-settings/declare-tables access/access-tables) [] test-settings/migrate-options)))
        (postgres/run-in-transaction! pool {:read-only? false :log nil}
          (fn [transaction]
            (access/add-scope! transaction scope-kinds :shop shop-7 paul nil)
            (add-member! transaction scope-kinds [:shop shop-7] anna #{"Staff"})
            (add-member! transaction scope-kinds [:shop shop-7] dora #{"Bookkeeper"})))
        (testing "each person's actions, by scope"
          (is (= {[:shop shop-7] #{:orders/see :orders/edit}} (access/list-actions-of-person pool scope-kinds paul)) "the founder is Owner: everything")
          (is (= {[:shop shop-7] #{:orders/see :orders/edit}} (access/list-actions-of-person pool scope-kinds anna)))
          (is (= {[:shop shop-7] #{:orders/see}} (access/list-actions-of-person pool scope-kinds dora)))
          (is (= {} (access/list-actions-of-person pool scope-kinds bob)) "Bob is in no scope"))
        (testing "a shop makes a role of its own, live"
          (let [driver (access/add-role! pool scope-kinds [:shop shop-7] "Driver" #{:orders/see})]
            (is (uuid? driver))
            (access/add-membership! pool [:shop shop-7] bob #{driver})
            (is (= {[:shop shop-7] #{:orders/see}} (access/list-actions-of-person pool scope-kinds bob)))))
        (testing "a role the scope doesn't have, or an action its kind doesn't have, is refused"
          (is (= :groundwork-access/unknown-role (:type (ex-data (try (access/add-membership! pool [:shop shop-7] carl #{(random-uuid)})
                                                            (catch clojure.lang.ExceptionInfo e e))))))
          (is (= :groundwork-access/unknown-action (:type (ex-data (try (access/add-role! pool scope-kinds [:shop shop-7] "Pilot" #{:orders/fly})
                                                              (catch clojure.lang.ExceptionInfo e e)))))))
        (testing "leaving a scope"
          (is (true? (access/remove-membership! pool [:shop shop-7] dora)))
          (is (= {} (access/list-actions-of-person pool scope-kinds dora))))
        (testing "a scope removed takes its memberships, roles and row with it"
          (let [count-of (fn [table column]
                           (:rows/count (postgres/find-one-row pool (postgres/make-query-from-sql-text
                                                                     "test count"
                                                                     (str "SELECT count(*) AS rows__count FROM groundwork_access." table
                                                                          " WHERE " column " = param('id')"))
                                                               {:id shop-7})))]
            (is (true? (postgres/run-in-transaction! pool {:read-only? false :log nil}
                         #(access/remove-scope! % [:shop shop-7]))))
            (is (= {} (access/list-actions-of-person pool scope-kinds paul)) "the founder too")
            (is (= {} (access/list-actions-of-person pool scope-kinds anna)))
            (is (= {} (access/list-actions-of-person pool scope-kinds bob)))
            (is (= [0 0 0 0] [(count-of "scopes" "scope__id") (count-of "roles" "role__scope_id")
                              (count-of "memberships" "membership__scope_id") (count-of "membership_roles" "membership_role__scope_id")]))
            (is (false? (access/remove-scope! pool [:shop shop-7])) "a second time: there was none")))
        (testing "a scope with scopes under it is refused until those are removed"
          (postgres/run-in-transaction! pool {:read-only? false :log nil}
            (fn [transaction]
              (access/add-scope! transaction kinds-with-a-parent :company company-1 paul nil)
              (access/add-scope! transaction kinds-with-a-parent :shop shop-8 anna company-1)))
          (let [refused (try (access/remove-scope! pool [:company company-1]) nil
                             (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= {:type :groundwork-access/scope-has-scopes-below :scope [:company company-1] :below 1} refused)))
          (is (= #{[:company company-1] [:shop shop-8]} (set (keys (access/list-actions-of-person pool kinds-with-a-parent paul))))
              "nothing went")
          (is (true? (access/remove-scope! pool [:shop shop-8])))
          (is (true? (access/remove-scope! pool [:company company-1])))
          (is (= {} (access/list-actions-of-person pool kinds-with-a-parent paul))))
        (finally (postgres/stop-pool! pool))))))
