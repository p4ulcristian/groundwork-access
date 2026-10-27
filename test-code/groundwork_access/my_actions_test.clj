(ns groundwork-access.my-actions-test
  "\"What may I do?\": the my-actions handler answered as over http, by
   the app's shaper, for people with and without memberships, and nobody.
   The Postgres one runs when ACCESS_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.test-answers :refer [answer-message refusal refusal-json read-without-request-ids without-request-id]]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.api :as access]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.shapes]
   [groundwork-access.test-log :refer [log]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-access.test-shaper :refer [shaper]]
   [groundwork-steps.api :as steps]
   [groundwork-shaped-json.api :as shaped-json-api]))

(def ^:private port (some-> (System/getenv "ACCESS_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}
                     :posts/read #{:read} :profile/edit #{:read :update} :comments/write #{:insert}}
           :roles   {"Staff" #{:orders/see :orders/edit}}
           :parent  nil :single? false :denies []}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]] :indexes [] :uniques [] :checks [] :references [] :triggers [] :gates nil :on-change nil :access :public})

(def ^:private orders
  {:table     :orders
   :columns   [[:order/id :uuid {:primary-key? true}] [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil
   :access    [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private posts
  {:table     :posts
   :columns   [[:post/id :uuid {:primary-key? true}] [:post/status :string]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil
   :access    [{:allow :posts/read :only :all-of-the-action :to :anyone}
               {:allow :comments/write :only :all-of-the-action :to :signed-in :when [:= :post/status "open"]}]})

(def ^:private profiles
  {:table     :profiles
   :columns   [[:profile/id :uuid {:primary-key? true}] [:profile/person-id :uuid]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil
   :access    [{:allow :profile/edit :only :all-of-the-action :to [:me :profile/person-id]}]})

(def ^:private tables [shops orders posts profiles])

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private carl #uuid "00000000-0000-4000-8000-00000000000e")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")
(def ^:private shop-9 #uuid "90000000-0000-4000-8000-000000000009")

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30 :outside-issuers []})

(def ^:private no-session-step {:name :no-session :enter identity})

(deftest the-options-are-required
  (let [errors (try (access/make-my-actions-handler {:pool (Object.) :tables tables :scope-kinds scope-kinds
                                                      :find-session-step no-session-step})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :groundwork-access/bad-options (:type errors)))
    (is (= #{:rate-limit} (set (keys (:errors errors)))) "nil written out for no rate limit")))

(deftest the-handler-writes-out-its-runner-s-keys
  (let [handler (access/make-my-actions-handler {:pool (Object.) :tables tables :scope-kinds scope-kinds
                                                  :find-session-step no-session-step :rate-limit {:max 60 :per-ms 60000}})]
    (is (= #{:params-shape :answer-shape :refusals :rate-limit :run-steps-in :steps} (set (keys handler))))
    (is (= access/refusals (:refusals handler)) "its find-session step refuses with groundwork-access's reasons")
    (is (= access/my-actions-answer-shape (:answer-shape handler)))
    (is (= {:max 60 :per-ms 60000} (:rate-limit handler)))))

(defn- run-sql! [pool text] (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- ask-as
  "The answer as the browser reads it: over http (JSON data by the answer
   shape), back into shaped data by the same shape."
  [handlers cookie origin]
  (let [answer (:groundwork-access/my-actions
                (answer-message handlers
                                {:request-method :post
                                 :headers        (cond-> {}
                                                   origin (assoc "origin" origin)
                                                   cookie (assoc "cookie" cookie))}
                                {:groundwork-access/my-actions {}}
                                {:shaper shaper :log log}))]
    (if (steps/is-error-answer? answer)
      (without-request-id answer)
      (:shaped-data (shaped-json-api/turn-json-data-into-shaped-data-or-errors shaper access/my-actions-answer-shape answer)))))

(deftest what-may-i-do
  (if-not port
    (println "groundwork-access.my-actions-test: skipped, ACCESS_TEST_PORT is not set")
    (let [pool     (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)
          handlers {:groundwork-access/my-actions
                    (access/make-my-actions-handler {:pool pool :tables tables :scope-kinds scope-kinds
                                                      :find-session-step (access/make-find-session-step pool settings)
                                                      :rate-limit nil})}
          cookie   #(first (str/split (:set-cookie (access/sign-in! pool {:headers {}} % {:settings settings :tables [] :session-flags #{} :session-lasts :session-days :outside-subjects {}})) #";"))
          ask      #(ask-as handlers %1 %2)]
      (try
        (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
        (with-out-str @(:slow-part (postgres/migrate! pool (test-settings/declare-tables (into [shops access/sessions-table access/tokens-table access/outside-subjects-table] access/access-tables)) [] test-settings/migrate-options)))
        (postgres/run-in-transaction! pool {:read-only? false :log nil}
          (fn [transaction]
            (access/add-scope! transaction scope-kinds :shop shop-9 paul nil)
            (access/add-scope! transaction scope-kinds :shop shop-7 paul nil)
            (add-member! transaction scope-kinds [:shop shop-7] anna #{"Staff"})))
        (testing "Anna, Staff in shop 7: what is open to someone signed in, everywhere; her role's in shop 7"
          (is (= [{:scope-kind nil :scope-id nil :actions [:comments/write :posts/read :profile/edit]}
                  {:scope-kind :shop :scope-id shop-7 :actions [:orders/edit :orders/see]}]
                 (ask (cookie anna) "http://app.test"))
              "[:me …]'s :profile/edit is listed for every scope; the row decides"))
        (testing "Paul, Owner of two shops: every action of the kind in each, by id"
          (let [every-action [:comments/write :orders/edit :orders/see :posts/read :profile/edit]]
            (is (= [{:scope-kind nil :scope-id nil :actions [:comments/write :posts/read :profile/edit]}
                    {:scope-kind :shop :scope-id shop-7 :actions every-action}
                    {:scope-kind :shop :scope-id shop-9 :actions every-action}]
                   (ask (cookie paul) "http://app.test")))))
        (testing "Carl, in no shop: only what is open"
          (is (= [{:scope-kind nil :scope-id nil :actions [:comments/write :posts/read :profile/edit]}]
                 (ask (cookie carl) "http://app.test"))))
        (testing "nobody signed in: what is open to anyone"
          (is (= [{:scope-kind nil :scope-id nil :actions [:posts/read]}] (ask nil "http://app.test"))))
        (testing "a request from another site is refused before anything is read"
          (is (= (refusal :groundwork-access/not-allowed) (ask (cookie anna) "https://elsewhere.test"))))
        (testing "an app with no open actions: no entry of nil kind and id"
          (let [closed {:groundwork-access/my-actions
                        (access/make-my-actions-handler {:pool pool :tables [shops orders] :scope-kinds scope-kinds
                                                          :find-session-step (access/make-find-session-step pool settings)
                                                          :rate-limit nil})}]
            (is (= [] (ask-as closed nil "http://app.test")))))
        (finally (postgres/stop-pool! pool))))))

(deftest the-answer-shape-is-one-on-both-sides
  (is (identical? groundwork-access.shapes/my-actions-answer-shape access/my-actions-answer-shape)
      "the server's is groundwork-access.shapes's, the .cljc the browser's half reads"))
