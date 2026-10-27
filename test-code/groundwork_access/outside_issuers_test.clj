(ns groundwork-access.outside-issuers-test
  "Outside issuers: a stand-in issuer on this machine, with keys of its
   own (an RSA one and an EC one), its discovery document and its JWKS
   document, signs tokens; groundwork-access checks them by its keys, issuer,
   audience, times and algorithm, and their subject is the person whose
   sign-in linked it, asking like any other asker. Every failed check is
   :groundwork-access/token-refused, named, never nobody. Anna is Staff in shop A,
   her subject at the stand-in issuer \"user_anna\". The settings run
   without Postgres; the rest when ACCESS_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.api :as access]
   [groundwork-access.outside :as outside]
   [groundwork-access.test-answers :refer [answer-message refusal without-request-id]]
   [groundwork-access.test-log :as test-log :refer [log]]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-access.test-shaper :refer [shaper]]
   [groundwork-postgres.api :as postgres]
   [groundwork-steps.api :as steps]
   [jsonista.core :as json])
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.math BigInteger)
   (java.net InetSocketAddress)
   (java.security KeyPairGenerator Signature)
   (java.security.interfaces ECPublicKey RSAPublicKey)
   (java.security.spec ECGenParameterSpec)
   (java.time Duration Instant)
   (java.util Arrays Base64)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)))

(def ^:private port test-settings/port)

;; -----------------------------------------------------------------------------
;; The stand-in issuer

(defn- encode [^bytes bytes] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))

(defn- encode-json [value] (encode (.getBytes ^String (json/write-value-as-string value) "UTF-8")))

(defn- encode-number
  "A JWK's number: big-endian, no sign byte, padded to length bytes (0: as it is)."
  [^BigInteger n length]
  (let [bytes (.toByteArray n)
        bytes (if (and (< 1 (alength bytes)) (zero? (aget bytes 0))) (Arrays/copyOfRange bytes 1 (alength bytes)) bytes)]
    (encode (if (< (alength bytes) length)
              (let [padded (byte-array length)]
                (System/arraycopy bytes 0 padded (- length (alength bytes)) (alength bytes))
                padded)
              bytes))))

(defn- make-rsa-pair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(defn- make-ec-pair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "EC") (.initialize (ECGenParameterSpec. "secp256r1")))))

(defn- turn-into-jwk [kid pair]
  (let [public-key (.getPublic pair)]
    (if (instance? RSAPublicKey public-key)
      {"kty" "RSA" "kid" kid "use" "sig" "alg" "RS256"
       "n" (encode-number (.getModulus ^RSAPublicKey public-key) 0) "e" (encode-number (.getPublicExponent ^RSAPublicKey public-key) 0)}
      {"kty" "EC" "kid" kid "use" "sig" "crv" "P-256"
       "x" (encode-number (.getAffineX (.getW ^ECPublicKey public-key)) 32) "y" (encode-number (.getAffineY (.getW ^ECPublicKey public-key)) 32)})))

(defn- start-issuer!
  "The stand-in issuer on 127.0.0.1: /.well-known/openid-configuration
   (its issuer, its jwks_uri), /jwks (the keys in jwks, an atom), and
   /lying-discovery, which names another issuer. Counts every fetch."
  [jwks]
  (let [server  (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        base    (str "http://127.0.0.1:" (.getPort (.getAddress server)))
        fetches (atom {})
        answer! (fn [path body]
                  (.createContext server path
                                  (reify HttpHandler
                                    (handle [_ exchange]
                                      (swap! fetches update path (fnil inc 0))
                                      (let [bytes (.getBytes ^String (json/write-value-as-string (body)) "UTF-8")]
                                        (.add (.getResponseHeaders ^HttpExchange exchange) "content-type" "application/json")
                                        (.sendResponseHeaders ^HttpExchange exchange 200 (alength bytes))
                                        (with-open [out (.getResponseBody ^HttpExchange exchange)] (.write out bytes)))))))]
    (answer! "/.well-known/openid-configuration" (fn [] {"issuer" base "jwks_uri" (str base "/jwks")}))
    (answer! "/lying-discovery" (fn [] {"issuer" "https://someone-else.test" "jwks_uri" (str base "/jwks")}))
    (answer! "/jwks" (fn [] {"keys" @jwks}))
    (.start server)
    {:server server :base base :fetches fetches}))

(defn- sign [alg private-key ^String input]
  (let [signer (Signature/getInstance (case alg "RS256" "SHA256withRSA" "RS384" "SHA384withRSA" "ES256" "SHA256withECDSAinP1363Format"))]
    (.initSign signer private-key)
    (.update signer (.getBytes input "US-ASCII"))
    (.sign signer)))

(defn- now-seconds [] (quot (System/currentTimeMillis) 1000))

(defn- make-claims [issuer subject]
  {"iss" issuer "aud" "https://app.test/mcp" "sub" subject "exp" (+ (now-seconds) 300) "nbf" (- (now-seconds) 10) "iat" (now-seconds)})

(defn- make-token
  "A JWT the stand-in signs: header and claims as given, signed by alg with
   the pair's private key."
  [alg pair header claims]
  (let [input (str (encode-json (merge {"alg" alg "typ" "JWT"} header)) "." (encode-json claims))]
    (str input "." (encode (sign alg (.getPrivate pair) input)))))

;; -----------------------------------------------------------------------------
;; The app

(defn- make-issuer-settings [base]
  {:name :stand-in
   :issuer base
   :keys-at [:discovery (str base "/.well-known/openid-configuration")]
   :audience "https://app.test/mcp"
   :algorithms #{"RS256" "ES256"}
   :subject-claim "sub"
   :clock-skew-seconds 30
   :connect-timeout-ms 2000
   :answer-timeout-ms 2000
   :keys-kept-seconds 3600
   :fetch-at-most-every-seconds 1})

(defn- make-settings [outside-issuers]
  {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
   :allowed-origin "http://app.test" :secure-cookie? false
   :token-lead "shop_" :token-shown-characters 11 :token-longest-days 90
   :outside-issuers outside-issuers})

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false :denies []
           :actions {:orders/see #{:read}}
           :roles   {"Staff" #{:orders/see}}}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]]
   :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private orders
  {:table :orders
   :columns [[:order/id :uuid {:primary-key? true :default :random-uuid}]
             [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow :orders/see :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private tables [shops orders])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private shop-a (id 1001))
(def ^:private shop-b (id 2002))
(def ^:private paul (id 1))
(def ^:private anna (id 2))

(defn- query [text] (postgres/make-query-from-sql-text "a test statement" text))

(def ^:private handlers
  {:orders/all      {:action :orders/see :scope :every-scope-holding-the-action
                     :params-shape [:map] :answer-shape [:vector :uuid]
                     :refusals {} :rate-limit nil :run-steps-in nil
                     :steps [{:name :answer :enter #(steps/add-answer-to-context
                                                     % (mapv :order/shop-id (postgres/find-rows (:groundwork-access/transaction %)
                                                                                                (query "SELECT order__shop_id FROM orders") {})))}]}
   :orders/asked-by {:action :orders/see :scope :every-scope-holding-the-action
                     :params-shape [:map] :answer-shape [:map [:outside-issuer [:maybe :keyword]] [:token-id [:maybe :uuid]]]
                     :refusals {} :rate-limit nil :run-steps-in nil
                     :steps [{:name :answer :enter #(steps/add-answer-to-context % {:outside-issuer (:groundwork-access/outside-issuer %)
                                                                                    :token-id       (:groundwork-access/token-id %)})}]}})

(defn- run-sql! [pool text] (postgres/run-statement! pool (query text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_access_app') THEN CREATE ROLE groundwork_access_app LOGIN; END IF; END $$")
  (let [ready (into (access/put-gates-on-tables tables scope-kinds)
                    (conj access/access-tables access/sessions-table access/tokens-table access/outside-subjects-table))]
    (with-out-str @(:slow-part (postgres/migrate! owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) [] test-settings/migrate-options)))
    (postgres/grant-tables-to-role! owner-pool ready "groundwork_access_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-a "'), ('" shop-b "')"))
  (run-sql! owner-pool (str "INSERT INTO orders (order__shop_id) VALUES ('" shop-a "'), ('" shop-b "')"))
  (postgres/run-in-transaction! owner-pool {:read-only? false :log nil}
                                (fn [transaction]
                                  (access/add-scope! transaction scope-kinds :shop shop-a paul nil)
                                  (access/add-scope! transaction scope-kinds :shop shop-b paul nil)
                                  (add-member! transaction scope-kinds [:shop shop-a] anna #{"Staff"}))))

(defn- refusal-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; -----------------------------------------------------------------------------
;; Without Postgres

(deftest an-outside-issuer-writes-out-every-key
  (let [issuer   (make-issuer-settings "https://auth.app.test")
        problems #(try (access/make-find-session-step nil (make-settings %)) nil (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (nil? (problems [issuer])))
    (is (= #{:outside-issuers} (set (keys (problems [(dissoc issuer :audience :clock-skew-seconds)])))))
    (is (= [{:audience ["missing required key"] :clock-skew-seconds ["missing required key"]}]
           (:outside-issuers (problems [(dissoc issuer :audience :clock-skew-seconds)])))
        "each missing one is named")
    (is (= {:outside-issuers ["missing required key"]}
           (try (access/make-find-session-step nil (dissoc (make-settings []) :outside-issuers)) nil
                (catch clojure.lang.ExceptionInfo e (:errors (ex-data e)))))
        ":outside-issuers itself is required: [] for none")
    (testing "never none, never HMAC: the shape knows only RSA and ECDSA"
      (is (some? (problems [(assoc issuer :algorithms #{"none"})])))
      (is (some? (problems [(assoc issuer :algorithms #{"HS256"})])))
      (is (some? (problems [(assoc issuer :algorithms #{})])) "at least one"))
    (is (some? (problems [(assoc issuer :clock-skew-seconds 301)])) "a small skew: at most 300 seconds")
    (is (some? (problems [(assoc issuer :keys-at [:jwks "http://keys.app.test/jwks"])])) "https only, but on localhost")
    (is (nil? (problems [(assoc issuer :keys-at [:jwks "http://127.0.0.1:8080/jwks"])])))
    (is (= {:outside-issuers ["should name each issuer once, not [:stand-in]"]} (problems [issuer issuer])))
    (is (= #{:token-lead} (set (keys (try (access/make-find-session-step nil (assoc (make-settings [issuer]) :token-lead "eyJ"))
                                          (catch clojure.lang.ExceptionInfo e (:errors (ex-data e)))))))
        "the lead tells an own token from an outside one")
    (is (nil? (try (access/make-find-session-step nil (assoc (make-settings []) :token-lead "eyJ")) nil
                   (catch clojure.lang.ExceptionInfo e (:errors (ex-data e)))))
        "with no outside issuers, any lead")))

(deftest sign-in-links-only-named-issuers
  (let [settings (make-settings [(make-issuer-settings "https://auth.app.test")])
        problem  (refusal-of #(access/sign-in! nil {:headers {}} anna {:settings settings :tables [] :session-flags #{}
                                                                       :session-lasts :session-days :outside-subjects {:workos "user_1"}}))]
    (is (= :groundwork-access/bad-options (:type problem)))
    (is (= #{:outside-subjects} (set (keys (:errors problem)))))))

(deftest a-token-is-read-before-anything-is-fetched
  (is (= :malformed (:problem (outside/check-token "not.a-token" []))))
  (is (= :malformed (:problem (outside/check-token "a.b.c" []))) "parts that aren't JSON")
  (is (= :unknown-issuer (:problem (outside/check-token (str (encode-json {"alg" "RS256"}) "." (encode-json {"iss" "https://x.test"}) ".c2ln") []))))
  (is (= :unsupported-header (:problem (outside/check-token (str (encode-json {"alg" "RS256" "crit" ["b64"]}) "." (encode-json {}) ".c2ln") [])))))

;; -----------------------------------------------------------------------------
;; Against Postgres

(deftest outside-tokens-against-postgres
  (if-not port
    (println "groundwork-access.outside-issuers-test: skipped, ACCESS_TEST_PORT is not set")
    (let [rsa        (make-rsa-pair)
          ec         (make-ec-pair)
          later-rsa  (make-rsa-pair)
          jwks       (atom [(turn-into-jwk "rsa-1" rsa) (turn-into-jwk "ec-1" ec)])
          {:keys [server base fetches]} (start-issuer! jwks)
          issuer     (make-issuer-settings base)
          settings   (make-settings [issuer])
          owner-pool (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 2)]
      (make-database! owner-pool)
      (let [app-pool  (test-settings/start-pool! "groundwork_access_app" 4)
            step      (access/make-find-session-step app-pool settings)
            ready     (access/put-access-into-handlers handlers {:pool app-pool :tables tables :scope-kinds scope-kinds :find-session-step step})
            signed-in (access/sign-in! app-pool {:headers {}} anna {:settings settings :tables [] :session-flags #{}
                                                                    :session-lasts :session-days :outside-subjects {:stand-in "user_anna"}})
            browser   {:request-method :post :headers {"origin" "http://app.test" "cookie" (first (str/split (:set-cookie signed-in) #";"))}}
            bearer    (fn [text] {:request-method :post :headers {"authorization" (str "Bearer " text)}})
            ask       (fn [request handler-name]
                        (without-request-id (get (answer-message ready request {handler-name {}} {:shaper shaper :log log}) handler-name)))
            asker-of  (fn [text]
                        (let [context (postgres/run-in-transaction! app-pool {:read-only? false :log nil}
                                                                    #(steps/run-steps-until-answered
                                                                      [step] {:groundwork-steps/request (bearer text) :groundwork-steps/log log
                                                                              :groundwork-access/transaction %}))]
                          (or (:groundwork-steps/answer context)
                              ((juxt :groundwork-access/person-id :groundwork-access/visitor-id :groundwork-access/session-flags
                                     :groundwork-access/token-id :groundwork-access/outside-issuer)
                               context))))
            problem-of (fn [text]
                         (reset! test-log/lines [])
                         (let [answer (asker-of text)
                               [line] (test-log/find-lines "groundwork-access/request-refused")]
                           (when (= (refusal :groundwork-access/token-refused) answer)
                             (keyword (get-in line ["data" "problem"])))))
            claims    (make-claims base "user_anna")
            good      (make-token "RS256" rsa {"kid" "rsa-1"} claims)]
        (try
          (testing "a token the issuer signed, for the app's audience, whose subject Anna's sign-in linked: Anna's"
            (is (= [anna nil #{} nil :stand-in] (asker-of good)) "no session flags, no visitor, no token of groundwork-access's: the issuer named")
            (is (= [(str shop-a)] (ask (bearer good) :orders/all)) "she asks like any asker: her shop's orders, the gates deciding")
            (is (= {:outside-issuer "stand-in" :token-id nil} (ask (bearer good) :orders/asked-by)) "a handler sees what asked")
            (reset! test-log/lines [])
            (let [context (postgres/run-in-transaction! app-pool {:read-only? false :log nil}
                                                        #(steps/run-steps-until-answered [step] {:groundwork-steps/request (bearer good) :groundwork-steps/log log
                                                                                                 :groundwork-access/transaction %}))]
              ((:groundwork-steps/log context) :info :test/later-step {}))
            (is (= [{"person-id" (str anna) "outside-issuer" "stand-in"}] (mapv #(get % "context") (test-log/find-lines "test/later-step"))))
            (is (= [anna nil #{} nil :stand-in] (asker-of (make-token "ES256" ec {"kid" "ec-1"} claims))) "ES256, by the EC key")
            (is (= [anna nil #{} nil :stand-in] (asker-of (make-token "RS256" rsa {"kid" "rsa-1"} (assoc claims "aud" ["other" "https://app.test/mcp"]))))
                "aud a list holding the audience"))

          (testing "the keys are fetched once and kept: the discovery document, then the JWKS document"
            (dotimes [_ 20] (asker-of good))
            (is (= {"/.well-known/openid-configuration" 1 "/jwks" 1} @fetches)))

          (testing "every failed check is refused, named"
            (let [now (now-seconds)]
              (is (= :expired (problem-of (make-token "RS256" rsa {"kid" "rsa-1"} (assoc claims "exp" (- now 60))))) "past exp, beyond the skew")
              (is (= [anna nil #{} nil :stand-in] (asker-of (make-token "RS256" rsa {"kid" "rsa-1"} (assoc claims "exp" (- now 10)))))
                  "within :clock-skew-seconds, 30")
              (is (= :expired (problem-of (make-token "RS256" rsa {"kid" "rsa-1"} (dissoc claims "exp")))) "no exp")
              (is (= :not-yet-valid (problem-of (make-token "RS256" rsa {"kid" "rsa-1"} (assoc claims "nbf" (+ now 60))))))
              (is (= [anna nil #{} nil :stand-in] (asker-of (make-token "RS256" rsa {"kid" "rsa-1"} (assoc claims "nbf" (+ now 10))))) "nbf within the skew"))
            (is (= :wrong-audience (problem-of (make-token "RS256" rsa {"kid" "rsa-1"} (assoc claims "aud" "https://other.test")))))
            (is (= :unknown-issuer (problem-of (make-token "RS256" rsa {"kid" "rsa-1"} (assoc claims "iss" "https://someone-else.test")))))
            (is (= :no-subject (problem-of (make-token "RS256" rsa {"kid" "rsa-1"} (dissoc claims "sub")))))
            (is (= :subject-not-linked (problem-of (make-token "RS256" rsa {"kid" "rsa-1"} (assoc claims "sub" "user_nobody")))))
            (is (= :no-key-id (problem-of (make-token "RS256" rsa {} claims))))
            (is (= :algorithm-not-allowed (problem-of (make-token "RS384" rsa {"kid" "rsa-1"} claims))) "RS384: not one of the issuer's")
            (is (= :key-does-not-fit (problem-of (make-token "ES256" ec {"kid" "rsa-1"} claims))) "an EC signature by the RSA key's kid")
            (let [[header-part claims-part signature-part] (str/split good #"\.")]
              (is (= :bad-signature (problem-of (str header-part "." (encode-json (assoc claims "sub" "user_paul")) "." signature-part)))
                  "the claims changed after signing")
              (is (= :malformed (problem-of (str header-part "." claims-part))) "two parts")))

          (testing "never none, never HMAC with the issuer's public key"
            (let [unsigned (str (encode-json {"alg" "none" "kid" "rsa-1"}) "." (encode-json claims) ".")
                  input    (str (encode-json {"alg" "HS256" "kid" "rsa-1"}) "." (encode-json claims))
                  mac      (doto (Mac/getInstance "HmacSHA256")
                             (.init (SecretKeySpec. (.getEncoded (.getPublic rsa)) "HmacSHA256")))
                  forged   (str input "." (encode (.doFinal mac (.getBytes input "US-ASCII"))))]
              (is (= :algorithm-not-allowed (problem-of unsigned)))
              (is (= :algorithm-not-allowed (problem-of forged)))))

          (testing "a kid the kept keys lack: fetched again, at most every :fetch-at-most-every-seconds"
            (let [rotated (make-token "RS256" later-rsa {"kid" "rsa-2"} claims)]
              (Thread/sleep 1100)
              (is (= :unknown-key (problem-of rotated)) "fetched again: the issuer doesn't have it yet")
              (is (= 2 (get @fetches "/jwks")))
              (swap! jwks conj (turn-into-jwk "rsa-2" later-rsa))
              (is (= :unknown-key (problem-of rotated)) "within the second: not fetched again")
              (is (= 2 (get @fetches "/jwks")))
              (Thread/sleep 1100)
              (is (= [anna nil #{} nil :stand-in] (asker-of rotated)) "the issuer's new key, fetched")
              (is (= 3 (get @fetches "/jwks")))
              (is (= [anna nil #{} nil :stand-in] (asker-of good)) "the old one still in its document")))

          (testing "keys that can't be fetched: refused, named"
            (let [lying    (access/make-find-session-step app-pool (make-settings [(assoc issuer :keys-at [:discovery (str base "/lying-discovery")])]))
                  gone     (access/make-find-session-step app-pool (make-settings [(assoc issuer :keys-at [:jwks "http://127.0.0.1:1/jwks"])]))
                  problem  (fn [step]
                             (reset! test-log/lines [])
                             (postgres/run-in-transaction! app-pool {:read-only? false :log nil}
                                                           #(steps/run-steps-until-answered [step] {:groundwork-steps/request (bearer good) :groundwork-steps/log log
                                                                                                    :groundwork-access/transaction %}))
                             (get-in (first (test-log/find-lines "groundwork-access/request-refused")) ["data" "problem"]))]
              (is (= "keys-unavailable" (problem lying)) "a discovery document naming another issuer")
              (is (= "keys-unavailable" (problem gone)) "nothing listening")))

          (testing "a cookie and an outside token at once: refused, as with a token of groundwork-access's"
            (is (= (refusal :groundwork-access/not-allowed)
                   (ask (assoc-in browser [:headers "authorization"] (str "Bearer " good)) :orders/all))))

          (testing "a token of groundwork-access's still works beside them: its lead tells it apart"
            (let [{:keys [text token-id]} (access/make-token! app-pool browser {:settings settings :name "script" :ends-at (.plus (Instant/now) (Duration/ofDays 1))})]
              (is (= {:outside-issuer nil :token-id (str token-id)} (ask (bearer text) :orders/asked-by)))
              (is (= [nil nil #{} nil nil] (asker-of "shop_wrong")) "a wrong one of its own is nobody, as before")))

          (testing "an outside token makes no token, and runs a page as its person"
            (is (= :outside-token (:reason (refusal-of #(access/make-token! app-pool (bearer good) {:settings settings :name "x" :ends-at (.plus (Instant/now) (Duration/ofDays 1))})))))
            (is (= [{:order/shop-id shop-a}]
                   (access/run-as-asker-of-request! app-pool (bearer good) {:settings settings :read-only? true :scope-kinds scope-kinds :log log}
                                                    #(postgres/find-rows % (query "SELECT order__shop_id FROM orders") {}))))
            (is (= {:type :groundwork-access/refused-request :reason :groundwork-access/token-refused :problem :wrong-audience :issuer-name :stand-in}
                   (refusal-of #(access/run-as-asker-of-request! app-pool (bearer (make-token "RS256" rsa {"kid" "rsa-1"} (assoc claims "aud" "x")))
                                                                 {:settings settings :read-only? true :scope-kinds scope-kinds :log log}
                                                                 (fn [_] nil))))))

          (testing "signing out everywhere unlinks her subject: her outside tokens are refused at once"
            (is (= 1 (:outside-subjects (access/sign-out-everywhere! app-pool anna))))
            (is (= :subject-not-linked (problem-of good)))
            (testing "until she signs in again"
              (access/sign-in! app-pool {:headers {}} anna {:settings settings :tables [] :session-flags #{}
                                                            :session-lasts :session-days :outside-subjects {:stand-in "user_anna"}})
              (is (= [anna nil #{} nil :stand-in] (asker-of good)))))
          (finally
            (.stop ^HttpServer server 0)
            (postgres/stop-pool! app-pool)
            (postgres/stop-pool! owner-pool)))))))
