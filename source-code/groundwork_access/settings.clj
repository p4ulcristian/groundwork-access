(ns groundwork-access.settings
  "groundwork-access's settings, and the options put-access-into-handlers takes,
   checked before anything runs. Nothing has a default: every setting is
   written out, and a missing one refuses, named, with every other problem
   at once. They are checked by malli itself: they are values the app hands
   in, not data crossing an edge."
  (:require
   [clojure.string :as str]
   [malli.core :as malli]
   [malli.error :as malli-error]))

(def jwt-start
  "How every outside issuer's token starts: a JWT's header, {\" in
   base64url."
  "eyJ")

(def random-characters
  "How many random characters a token has after its lead: 32 random
   bytes as URL-safe base64 without padding."
  43)

(def supported-algorithms
  "The signing algorithms an outside issuer's token may be checked by:
   RSA (PKCS#1 v1.5) and ECDSA on the NIST curves. Never \"none\", never
   HMAC: a key an issuer publishes is public, and a token signed with it
   as an HMAC secret proves nothing."
  #{"RS256" "RS384" "RS512" "ES256" "ES384" "ES512"})

(def address-pattern
  "An address groundwork-access fetches an outside issuer's keys from:
   https://, or http:// only on localhost or 127.0.0.1 (a stand-in issuer
   in development and tests)."
  #"^(https://[^\s/?#]+|http://(localhost|127\.0\.0\.1)(:\d+)?)(/[^\s#]*)?$")

(def ^:private address-shape
  [:re {:error/message "should be an https:// address (http:// only on localhost or 127.0.0.1)"} address-pattern])

(def outside-issuer-shape
  "One outside issuer, every key required:
   :name: what the app calls it (:workos), a keyword without a namespace:
   sign-in!'s :outside-subjects, the context and the log name it.
   :issuer: its address, which a token's iss must be, exactly.
   :keys-at: where its signing keys are: [:jwks address] (its JWKS
   document) or [:discovery address] (its discovery document, OpenID's or
   OAuth's, whose issuer must be :issuer and whose jwks_uri says where).
   :audience: what a token's aud must hold (a text, or a list with it).
   :algorithms: the signing algorithms its tokens may use, a set of
   supported-algorithms' (#{\"RS256\"}).
   :subject-claim: the claim holding the token's subject (\"sub\"),
   linked to a person at sign-in (sign-in!'s :outside-subjects).
   :clock-skew-seconds: how far the issuer's clock and this server's may
   differ when exp and nbf are read, at most 300.
   :connect-timeout-ms, :answer-timeout-ms: how long fetching its keys
   may wait to connect, and for the answer.
   :keys-kept-seconds: how long fetched keys are used before they are
   fetched again.
   :fetch-at-most-every-seconds: the keys are fetched at most this often,
   a token with an unknown key id or a failed fetch among them."
  [:map {:closed true}
   [:name simple-keyword?]
   [:issuer address-shape]
   [:keys-at [:or [:tuple [:= :jwks] address-shape] [:tuple [:= :discovery] address-shape]]]
   [:audience [:string {:min 1}]]
   [:algorithms [:set {:min 1} (into [:enum] (sort supported-algorithms))]]
   [:subject-claim [:string {:min 1}]]
   [:clock-skew-seconds [:int {:min 0 :max 300}]]
   [:connect-timeout-ms pos-int?]
   [:answer-timeout-ms pos-int?]
   [:keys-kept-seconds pos-int?]
   [:fetch-at-most-every-seconds pos-int?]])

(def settings-shape
  "The settings, every key required.
   :cookie-name: the session cookie's name. Cookies don't tell ports apart,
   so two apps on one host (localhost in development) need two names.
   :session-days: how long a signed-in person's session lasts unseen.
   :visitor-session-days: how long a visitor's session (someone not
   signed in, for whom the app keeps something) lasts unseen; its rows
   are nobody's once it has ended.
   :renew-after-minutes: a session seen again lasts its days from then,
   at most once in this many minutes (one write, not one per request).
   :allowed-origin: the app's own address; a request that changes something
   must carry it as its Origin.
   :secure-cookie?: false only in local development, over plain http.
   :token-lead: the text every token a person makes starts with, before
   its 43 random characters (\"app_\"), so a leaked one says what it is.
   :token-shown-characters: how many of a token's first characters its
   list shows, the lead among them; at most the lead and half of the
   random part, so the list never gives most of a token away.
   :token-longest-days: the furthest a token's end date may be, in days
   from when it is made.
   :outside-issuers: the outside issuers whose bearer tokens groundwork-access
   checks, each by outside-issuer-shape; [] for none."
  [:map {:closed true}
   [:cookie-name [:re #"^[A-Za-z0-9_-]+$"]]
   [:session-days pos-int?]
   [:visitor-session-days pos-int?]
   [:renew-after-minutes pos-int?]
   [:allowed-origin :string]
   [:secure-cookie? :boolean]
   [:token-lead [:re #"^[A-Za-z0-9_-]+$"]]
   [:token-shown-characters pos-int?]
   [:token-longest-days pos-int?]
   [:outside-issuers [:vector outside-issuer-shape]]])

(def ^:private flags-shape
  [:set [:fn {:error/message "should be a keyword without a namespace, like :impersonated"}
         #(and (simple-keyword? %) (re-matches #"[a-z0-9-]+" (name %)))]])

(def sign-in-options-shape
  "sign-in!'s options, every key required.
   :settings: the settings (settings-shape).
   :tables: the app's table maps; those with a :visitor column that
   :moves-to a person column have the visitor's rows moved to the person.
   :session-flags: the session's flags, #{} for none (#{:impersonated}
   for an admin acting as someone): the denies that name one say no.
   :session-lasts: :session-days, the settings' (unseen this long, it
   ends; seen again, it is pushed out), or {:minutes 60}: it ends that
   long after sign-in, never pushed out (an admin acting as someone).
   :outside-subjects: the person's subject at each outside issuer the
   login proved, {:workos \"user_01…\"}, {} for none: a token of that
   issuer with that subject is this person's."
  [:map {:closed true}
   [:settings map?]
   [:tables [:sequential map?]]
   [:session-flags flags-shape]
   [:session-lasts [:or [:= :session-days] [:map {:closed true} [:minutes pos-int?]]]]
   [:outside-subjects [:map-of simple-keyword? [:string {:min 1}]]]])

(def asker-shape
  "An asker handed in: who explain-refusal explains for, and who
   groundwork-access.repl's step asks as. Every key required: the person (nil for
   nobody signed in), the visitor (for nobody signed in; nil for none)
   and the session's flags (#{} for none)."
  [:map {:closed true}
   [:person-id [:maybe :uuid]]
   [:visitor-id [:maybe :uuid]]
   [:session-flags flags-shape]])

(def explain-options-shape
  "explain-refusal's options, every key required.
   :pool: the app's own pool; the explaining runs read-only.
   :handlers: the app's handlers, by name, each with its :action and
   where it acts (:record or :scope).
   :tables: the app's table maps, with their :access sentences.
   :scope-kinds: the app's kinds of scope (check-scope-kinds)."
  [:map {:closed true}
   [:pool some?]
   [:handlers [:map-of :keyword map?]]
   [:tables [:sequential map?]]
   [:scope-kinds [:map-of :keyword map?]]])

(def access-options-shape
  "put-access-into-handlers's options, every key required.
   :pool: the app's own pool, its role owning nothing, so the gates hold.
   :tables: the app's table maps, with their :access sentences.
   :scope-kinds: the app's kinds of scope (check-scope-kinds).
   :find-session-step: make-find-session-step's step."
  [:map {:closed true}
   [:pool some?]
   [:tables [:sequential map?]]
   [:scope-kinds [:map-of :keyword map?]]
   [:find-session-step [:map [:name :keyword] [:enter fn?]]]])

(def my-actions-options-shape
  "make-my-actions-handler's options, every key required.
   :pool: the app's own pool, as put-access-into-handlers's.
   :tables: the app's table maps, whose :access sentences say which
   actions are open without a membership.
   :scope-kinds: the app's kinds of scope (check-scope-kinds).
   :find-session-step: make-find-session-step's step.
   :rate-limit: bridge's, {:max 60 :per-ms 60000} per asker, or nil for
   none."
  [:map {:closed true}
   [:pool some?]
   [:tables [:sequential map?]]
   [:scope-kinds [:map-of :keyword map?]]
   [:find-session-step [:map [:name :keyword] [:enter fn?]]]
   [:rate-limit [:maybe [:map {:closed true} [:max [:int {:min 1}]] [:per-ms [:int {:min 1}]]]]]])

(def system-options-shape
  "run-as-system!'s options, every key required.
   :read-only?: true, Postgres refuses every write in its transaction.
   :log: the caller's log (a job's, a webhook's), which the transaction
   carries: work groundwork-postgres's run-after-commit! keeps on it that throws is
   written there; nil for a transaction that keeps none (groundwork-postgres's)."
  [:map {:closed true}
   [:read-only? :boolean]
   [:log [:maybe [fn? {:error/message "should be the caller's log function, made by groundwork-logs, or nil"}]]]])

(def asker-run-options-shape
  "run-as-asker-of-request!'s options, every key required.
   :settings: the settings (settings-shape): the cookie's name, the
   allowed origin.
   :read-only?: true, Postgres refuses every write in its transaction.
   :scope-kinds: the app's, checked (check-scope-kinds): which actions the
   person's memberships hold.
   :log: the caller's log (the request's, with its request id), which the
   transaction carries: work groundwork-postgres's run-after-commit! keeps on it that
   throws is written there; nil for a transaction that keeps none (groundwork-postgres's)."
  [:map {:closed true}
   [:settings map?]
   [:read-only? :boolean]
   [:scope-kinds map?]
   [:log [:maybe [fn? {:error/message "should be the caller's log function, made by groundwork-logs, or nil"}]]]])

(def token-options-shape
  "make-token!'s options, every key required.
   :settings: the settings (settings-shape): the lead, the shown
   characters, the longest end date.
   :name: what the person calls it (\"catalog script\").
   :ends-at: when it ends, a java.time.Instant, after now and at most
   :token-longest-days from now."
  [:map {:closed true}
   [:settings map?]
   [:name [:string {:min 1}]]
   [:ends-at [:fn {:error/message "should be a java.time.Instant"} #(instance? java.time.Instant %)]]])

(def leak-options-shape
  "find-leaks's options, every key required: malli's generator makes each
   handler's other params from its :params-shape.
   :answer-message: (fn [handlers request message]) -> the answer keyed by
   name, as the app's runner answers a message over http (groundwork's:
   groundwork-bridge's answer-message, with the app's shaper, log and meter);
   groundwork-access runs no message itself.
   :scope-kinds: the app's (check-scope-kinds): a :scope [kind param]
   handler is asked in the foreign ids of its kind's :table.
   :seed: the same seed makes the same params every run.
   :size: how big the made-up values get (malli's 30 when it chose)."
  [:map {:closed true}
   [:answer-message [fn? {:error/message "should be the app's runner of a message, (fn [handlers request message]) -> the answer by name"}]]
   [:scope-kinds [:map-of :keyword map?]]
   [:seed :int]
   [:size [:int {:min 0}]]])

(defn- check-data-for-shape [what kind shape data]
  (when-let [explanation (malli/explain shape data)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " have problems: " (pr-str errors))
                      {:type kind :errors errors}))))
  data)

(defn check-settings
  "Returns the settings; throws with every problem at once, and for a
   :token-shown-characters showing more than the lead and half of the
   random part."
  [settings]
  (check-data-for-shape "The settings" :groundwork-access/bad-settings settings-shape settings)
  (let [{:keys [token-lead token-shown-characters outside-issuers]} settings
        most     (+ (count token-lead) (quot random-characters 2))
        names    (map :name outside-issuers)
        problems (cond-> {}
                   (< most token-shown-characters)
                   (assoc :token-shown-characters [(str "should be at most " most ", the lead and half of the random part")])

                   (and (seq outside-issuers) (or (str/starts-with? token-lead jwt-start) (str/starts-with? jwt-start token-lead)))
                   (assoc :token-lead [(str "should not start as an outside issuer's token does (\"" jwt-start "\"): the lead tells the two apart")])

                   (not= (count names) (count (set names)))
                   (assoc :outside-issuers [(str "should name each issuer once, not " (pr-str (vec (for [[n c] (frequencies names) :when (< 1 c)] n))))]))]
    (when (seq problems)
      (throw (ex-info (str "The settings have problems: " (pr-str problems))
                      {:type :groundwork-access/bad-settings :errors problems}))))
  settings)

(defn check-sign-in-options
  "Returns the options, the settings in them checked too; throws with
   every problem at once, and for an :outside-subjects naming an issuer
   the settings don't."
  [options]
  (check-data-for-shape "The options" :groundwork-access/bad-options sign-in-options-shape options)
  (let [{:keys [outside-issuers]} (check-settings (:settings options))
        unknown (remove (set (map :name outside-issuers)) (keys (:outside-subjects options)))]
    (when (seq unknown)
      (throw (ex-info (str "The options have problems: " (pr-str {:outside-subjects [(str "names " (vec (sort unknown)) ", which the settings' :outside-issuers don't")]}))
                      {:type :groundwork-access/bad-options :errors {:outside-subjects [(str "names issuers the settings don't: " (vec (sort unknown)))]}}))))
  options)

(defn check-asker
  "Returns the asker; throws with every problem at once."
  [asker]
  (check-data-for-shape "The asker" :groundwork-access/bad-asker asker-shape asker))

(defn check-explain-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :groundwork-access/bad-options explain-options-shape options))

(defn check-access-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :groundwork-access/bad-options access-options-shape options))

(defn check-my-actions-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :groundwork-access/bad-options my-actions-options-shape options))

(defn check-system-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :groundwork-access/bad-options system-options-shape options))

(defn check-asker-run-options
  "Returns the options, the settings in them checked too; throws with
   every problem at once."
  [options]
  (check-data-for-shape "The options" :groundwork-access/bad-options asker-run-options-shape options)
  (check-settings (:settings options))
  options)

(defn check-token-options
  "Returns the options, the settings in them checked too; throws with
   every problem at once."
  [options]
  (check-data-for-shape "The options" :groundwork-access/bad-options token-options-shape options)
  (check-settings (:settings options))
  options)

(defn check-leak-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :groundwork-access/bad-options leak-options-shape options))
