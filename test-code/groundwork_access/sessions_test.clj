(ns groundwork-access.sessions-test
  "The Postgres tests run when ACCESS_TEST_PORT is set; they say
   they were skipped otherwise."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.test-answers :refer [refusal refusal-json read-without-request-ids without-request-id]]
   [groundwork-access.api :as access]
   [groundwork-access.test-log :as test-log]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-access.cookie :as cookie]
   [groundwork-postgres.api :as postgres]
   [groundwork-steps.api :as steps]))

(def ^:private port (some-> (System/getenv "ACCESS_TEST_PORT") parse-long))

(def ^:private settings {:cookie-name "app_session" :session-days 30 :visitor-session-days 2 :renew-after-minutes 60 :allowed-origin "https://app.test" :secure-cookie? true
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30 :outside-issuers []})

(def ^:private anna #uuid "a0000000-0000-4000-8000-000000000001")

(defn- sign-in! [pool person-id]
  (access/sign-in! pool {:headers {}} person-id {:settings settings :tables [] :session-flags #{} :session-lasts :session-days :outside-subjects {}}))

(defn- make-request [set-cookie origin]
  {:request-method :post
   :headers        (cond-> {"origin" origin}
                     set-cookie (assoc "cookie" (first (clojure.string/split set-cookie #";"))))})

(defn- run-find-session [step request]
  (steps/run-steps-until-answered [step] {:groundwork-steps/request request :groundwork-steps/log test-log/log}))

(deftest the-cookie
  (testing "only the fingerprint is kept, and it is the same for the same cookie"
    (is (= 64 (count (cookie/turn-cookie-value-into-fingerprint "abc"))))
    (is (= (cookie/turn-cookie-value-into-fingerprint "abc") (cookie/turn-cookie-value-into-fingerprint "abc"))))
  (testing "the cookie is HttpOnly, SameSite=Lax and Secure"
    (is (= "app_session=xyz; Path=/; HttpOnly; SameSite=Lax; Max-Age=60; Secure"
           (cookie/make-set-cookie-text "app_session" "xyz" 60 true)))))

(deftest the-settings-are-required
  (let [errors (try (access/make-find-session-step nil (dissoc settings :cookie-name :renew-after-minutes))
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :groundwork-access/bad-settings (:type errors)))
    (is (= #{:cookie-name :renew-after-minutes} (set (keys (:errors errors)))) "each missing one is named")))

(deftest sign-in-s-options-are-required
  (let [errors (try (access/sign-in! nil {} anna {:settings settings :tables []})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :groundwork-access/bad-options (:type errors)))
    (is (= #{:session-flags :session-lasts :outside-subjects} (set (keys (:errors errors)))) "#{}, :session-days and {} written out for none")))

(deftest the-session-key-reads-the-named-cookie
  (let [request {:headers {"cookie" "other=1; app_session=abc"}}]
    (is (= (cookie/turn-cookie-value-into-fingerprint "abc") (access/find-session-key-of-request request settings)))
    (is (nil? (access/find-session-key-of-request request (assoc settings :cookie-name "another_app_session")))
        "another app's cookie on the same host is not this one's")))

(defn- run-sql! [pool text] (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(deftest sessions-against-postgres
  (if-not port
    (println "groundwork-access.sessions-test: skipped, ACCESS_TEST_PORT is not set")
    (let [pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)
          step (access/make-find-session-step pool settings)]
      (try
        (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" "DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE") {})
        (with-out-str @(:slow-part (postgres/migrate! pool (test-settings/declare-tables [access/sessions-table access/tokens-table access/outside-subjects-table]) [] test-settings/migrate-options)))
        (let [{:keys [set-cookie]} (sign-in! pool anna)]
          (testing "a signed-in request: the person is in the context, and in the message's log"
            (let [context (run-find-session step (make-request set-cookie "https://app.test"))]
              (is (= anna (:groundwork-access/person-id context)))
              (reset! test-log/lines [])
              ((:groundwork-steps/log context) :info :test/later-step {})
              (is (= [{"person-id" (str anna)}] (mapv #(get % "context") @test-log/lines))
                  "every line a later step writes says who asked")))
          (testing "another site's request is answered with an error, before any work"
            (is (= (refusal :groundwork-access/not-allowed) (:groundwork-steps/answer (run-find-session step (make-request set-cookie "https://evil.test"))))))
          (testing "no cookie: nobody"
            (is (nil? (:groundwork-access/person-id (run-find-session step (make-request nil "https://app.test"))))))
          (testing "signed out: the same cookie finds nobody"
            (access/sign-out! pool (make-request set-cookie "https://app.test") settings)
            (is (nil? (:groundwork-access/person-id (run-find-session step (make-request set-cookie "https://app.test")))))))
        (testing "a session seen again lasts :session-days from then, once :renew-after-minutes have passed"
          (let [{:keys [set-cookie]} (sign-in! pool anna)
                find-minutes-left #(:session/minutes-left
                                 (postgres/find-one-row pool (postgres/make-query-from-sql-text
                                                            "minutes left"
                                                            "SELECT round(extract(epoch FROM max(session__ends_at) - now()) / 60)::int AS session__minutes_left FROM groundwork_access.sessions")
                                                      {}))]
            (run-sql! pool "UPDATE groundwork_access.sessions SET session__ends_at = now() + interval '30 days' - interval '59 minutes'")
            (run-find-session step (make-request set-cookie "https://app.test"))
            (is (= (- (* 30 24 60) 59) (find-minutes-left)) "seen within the hour: not written")
            (run-sql! pool "UPDATE groundwork_access.sessions SET session__ends_at = now() + interval '20 days'")
            (run-find-session step (make-request set-cookie "https://app.test"))
            (is (= (* 30 24 60) (find-minutes-left)) "seen after it: 30 days from now")
            (access/sign-out-everywhere! pool anna)))
        (testing "a session that lasts a fixed time: its minutes from sign-in, never pushed out"
          (let [{:keys [set-cookie]} (access/sign-in! pool {:headers {}} anna {:settings settings :tables [] :session-flags #{:impersonated}
                                                                                 :session-lasts {:minutes 60} :outside-subjects {}})
                find-minutes-left #(:session/minutes-left
                                    (postgres/find-one-row pool (postgres/make-query-from-sql-text
                                                                 "minutes left"
                                                                 "SELECT round(extract(epoch FROM max(session__ends_at) - now()) / 60)::int AS session__minutes_left FROM groundwork_access.sessions")
                                                           {}))]
            (is (re-find #"Max-Age=3600" set-cookie) "the cookie lasts the hour too")
            (is (= 60 (find-minutes-left)))
            (run-sql! pool "UPDATE groundwork_access.sessions SET session__ends_at = now() + interval '30 minutes'")
            (is (= [anna #{:impersonated}] ((juxt :groundwork-access/person-id :groundwork-access/session-flags)
                                            (run-find-session step (make-request set-cookie "https://app.test")))))
            (is (= 30 (find-minutes-left)) "seen again: not pushed out, though :renew-after-minutes have passed")
            (run-sql! pool "UPDATE groundwork_access.sessions SET session__ends_at = now() - interval '1 minute'")
            (is (nil? (:groundwork-access/person-id (run-find-session step (make-request set-cookie "https://app.test")))) "then it has ended")
            (access/sign-out-everywhere! pool anna)))
        (testing "a visitor's session: its id in the context and the log, no person, no flags"
          (let [{:keys [visitor-id set-cookie]} (access/start-visitor-session! pool (make-request nil "https://app.test") settings)
                context (run-find-session step (make-request set-cookie "https://app.test"))]
            (is (uuid? visitor-id))
            (is (= [nil visitor-id #{}] ((juxt :groundwork-access/person-id :groundwork-access/visitor-id :groundwork-access/session-flags) context)))
            (reset! test-log/lines [])
            ((:groundwork-steps/log context) :info :test/later-step {})
            (is (= [{"visitor-id" (str visitor-id)}] (mapv #(get % "context") @test-log/lines)))
            (is (re-find #"Max-Age=172800" set-cookie) ":visitor-session-days, 2")
            (testing "it lasts :visitor-session-days from when it is seen again"
              (run-sql! pool (str "UPDATE groundwork_access.sessions SET session__ends_at = now() + interval '1 hour' WHERE session__visitor_id = '" visitor-id "'"))
              (run-find-session step (make-request set-cookie "https://app.test"))
              (is (= (* 2 24 60) (:session/minutes-left (postgres/find-one-row pool (postgres/make-query-from-sql-text
                                                                                  "minutes left"
                                                                                  (str "SELECT round(extract(epoch FROM session__ends_at - now()) / 60)::int AS session__minutes_left "
                                                                                       "FROM groundwork_access.sessions WHERE session__visitor_id = '" visitor-id "'"))
                                                                             {})))))
            (testing "signing in ends it, and a person's session carries its flags"
              (let [signed-in (access/sign-in! pool (make-request set-cookie "https://app.test") anna
                                                {:settings settings :tables [] :session-flags #{:impersonated} :session-lasts :session-days :outside-subjects {}})]
                (is (nil? (:groundwork-access/visitor-id (run-find-session step (make-request set-cookie "https://app.test")))))
                (is (= [anna nil #{:impersonated}]
                       ((juxt :groundwork-access/person-id :groundwork-access/visitor-id :groundwork-access/session-flags)
                        (run-find-session step (make-request (:set-cookie signed-in) "https://app.test")))))
                (testing "a key planted before a login is worthless after it"
                  (access/sign-in! pool (make-request (:set-cookie signed-in) "https://app.test") anna
                                    {:settings settings :tables [] :session-flags #{} :session-lasts :session-days :outside-subjects {}})
                  (is (nil? (:groundwork-access/person-id (run-find-session step (make-request (:set-cookie signed-in) "https://app.test"))))))))
            (access/sign-out-everywhere! pool anna)))
        (testing "signing out everywhere ends every session of the person"
          (sign-in! pool anna)
          (sign-in! pool anna)
          (is (= {:sessions 2 :tokens 0 :outside-subjects 0} (access/sign-out-everywhere! pool anna))))
        (finally (postgres/stop-pool! pool))))))
