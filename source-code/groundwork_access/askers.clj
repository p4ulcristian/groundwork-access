(ns groundwork-access.askers
  "Who is asking, found from a request by the proof groundwork-access made and reads
   itself, and nothing else:

     a cookie   Cookie: app_session=…           a browser's session; a request that changes
                                                something must carry the app's Origin
     a token    Authorization: Bearer app_…     a script's, as the person who made it; no
                                                Origin check (a browser never sends it on its own)
     an outside Authorization: Bearer eyJ…      an outside issuer's (the settings' :outside-issuers),
     token                                      checked by its keys, as the person its subject is
                                                linked to (groundwork-access.outside); no Origin check

   A request carrying a cookie and a token is refused; one carrying
   neither is nobody's. A bearer token that starts with the settings'
   :token-lead is one of groundwork-access's; with no outside issuers named, every
   one is. A token's asker is its person with no session flags (a token
   has none), and no visitor; so is an outside token's. A wrong, ended or
   revoked token of groundwork-access's is nobody; an outside token that fails its
   check is refused, :groundwork-access/token-refused, never nobody. The
   find-session step puts the asker in the context, looking them up in the
   question's own transaction when it runs in one;
   run-as-asker-of-request! runs a transaction as the asker of a request,
   found in it; a token is made, listed and revoked by the asker of the
   request."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.asking :as asking]
   [groundwork-access.cookie :as cookie]
   [groundwork-access.origin :as origin]
   [groundwork-access.outside :as outside]
   [groundwork-access.sessions :as sessions]
   [groundwork-access.settings :as settings]
   [groundwork-access.tokens :as tokens]
   [groundwork-logs.api :as logs]
   [groundwork-steps.api :as steps]))

(def ^:private nobody {:person-id nil :visitor-id nil :session-flags #{}})

(def ^:private not-from-the-app
  {:reason :groundwork-access/not-allowed :because "This request didn't come from the app's own pages."})

(def ^:private cookie-and-token
  {:reason :groundwork-access/not-allowed :because "A request carries a session's cookie or a token, not both."})

(def ^:private no-proof
  "No token, no outside issuer."
  {:token-id nil :outside-issuer nil})

(defn- find-asker-of-outside-token
  "The asker of an outside issuer's token, or a refusal naming what is
   wrong with it."
  [pool-or-transaction token-text outside-issuers]
  (let [{:keys [problem because issuer-name subject]} (outside/check-token token-text outside-issuers)
        person-id (when-not problem (outside/find-linked-person pool-or-transaction issuer-name subject))]
    (cond
      problem
      {:refusal {:reason :groundwork-access/token-refused :problem problem :issuer-name issuer-name
                 :because (str "An outside token was refused: " because ".")}}

      (nil? person-id)
      {:refusal {:reason :groundwork-access/token-refused :problem :subject-not-linked :issuer-name issuer-name
                 :because (str "An outside token was refused: no sign-in linked its subject at " (name issuer-name) " to a person.")}}

      :else
      {:asker {:person-id person-id :visitor-id nil :session-flags #{}} :token-id nil :outside-issuer issuer-name})))

(defn find-token-text
  "The text of the request's Authorization: Bearer header, or nil. Any
   other kind of Authorization (Basic) is not groundwork-access's, and is no token."
  [request]
  (some->> (get-in request [:headers "authorization"])
           (re-matches #"(?i)bearer\s+(\S+)\s*")
           second))

(defn find-asker-of-request
  "{:asker {:person-id :visitor-id :session-flags} :token-id id-or-nil
   :outside-issuer name-or-nil}, or {:refusal {:reason :because …}} for a
   request groundwork-access refuses: :groundwork-access/not-allowed, one carrying both a
   cookie and a token, or one changing something by a cookie (or nothing)
   without the app's Origin; :groundwork-access/token-refused, an outside token
   that fails its check ({:problem :issuer-name} beside). A token of
   groundwork-access's that is wrong, ended or revoked is nobody's. Read on what it
   is handed: the pool, or the question's own transaction."
  [pool-or-transaction request {:keys [cookie-name allowed-origin token-lead outside-issuers] :as settings}]
  (let [token-text (find-token-text request)
        cookie?    (some? (cookie/find-cookie-value request cookie-name))]
    (cond
      (and token-text cookie?)
      {:refusal cookie-and-token}

      (and token-text (seq outside-issuers) (not (str/starts-with? token-text token-lead)))
      (find-asker-of-outside-token pool-or-transaction token-text outside-issuers)

      token-text
      (if-let [{:token/keys [id person-id]} (tokens/find-live-token pool-or-transaction token-text)]
        {:asker {:person-id person-id :visitor-id nil :session-flags #{}} :token-id id :outside-issuer nil}
        (assoc no-proof :asker nobody))

      (not (origin/does-request-come-from-allowed-origin? request allowed-origin))
      {:refusal not-from-the-app}

      :else
      (assoc no-proof :asker (sessions/find-session-asker! pool-or-transaction request settings)))))

(defn put-asker-into-context
  "The context with the asker at :groundwork-access/person-id, :groundwork-access/visitor-id and
   :groundwork-access/session-flags, the proof they asked by at :groundwork-access/token-id (the
   token's id, nil for none) and :groundwork-access/outside-issuer (the outside
   issuer's name, nil for none), and the person (and the token or the
   issuer) or the visitor added to the message's log, so every line the
   later steps write says who asked."
  [context {:keys [person-id visitor-id session-flags]} {:keys [token-id outside-issuer]}]
  (cond-> (assoc context
                 :groundwork-access/person-id person-id
                 :groundwork-access/visitor-id visitor-id
                 :groundwork-access/session-flags session-flags
                 :groundwork-access/token-id token-id
                 :groundwork-access/outside-issuer outside-issuer)
    person-id  (update :groundwork-steps/log logs/with-context (cond-> {:person-id person-id}
                                                                 token-id       (assoc :token-id token-id)
                                                                 outside-issuer (assoc :outside-issuer (name outside-issuer))))
    visitor-id (update :groundwork-steps/log logs/with-context {:visitor-id visitor-id})))

(defn make-find-session-step [pool settings]
  (let [settings (settings/check-settings settings)]
    {:name  :groundwork-access/find-session
     :enter (fn [context]
              ;; in the question's own transaction when it runs in one (the check's, "what may I do?"'s): one
              ;; connection, the lookup its first statement; on its own, on the pool
              (let [{:keys [refusal asker] :as found} (find-asker-of-request (or (:groundwork-access/transaction context) pool)
                                                                             (:groundwork-steps/request context) settings)]
                (if refusal
                  (do ((:groundwork-steps/log context) :info :groundwork-access/request-refused
                       (cond-> {:because (:because refusal)}
                         (:problem refusal)     (assoc :problem (name (:problem refusal)))
                         (:issuer-name refusal) (assoc :outside-issuer (name (:issuer-name refusal)))))
                      (steps/refuse context (:reason refusal) nil))
                  (put-asker-into-context context asker found))))}))

(defn find-session-key-of-request
  "A hash of the request's token, else of its cookie, or nil."
  [request {:keys [cookie-name]}]
  (if-let [token-text (find-token-text request)]
    (tokens/turn-text-into-fingerprint token-text)
    (some-> (cookie/find-cookie-value request cookie-name) cookie/turn-cookie-value-into-fingerprint)))

(defn- find-asker-or-throw [pool-or-transaction request settings]
  (let [{:keys [refusal] :as found} (find-asker-of-request pool-or-transaction request settings)]
    (when refusal
      (throw (ex-info (:because refusal) (merge {:type :groundwork-access/refused-request :reason (:reason refusal)}
                                                (select-keys refusal [:problem :issuer-name])))))
    found))

(defn run-as-asker-of-request!
  "Runs (f transaction asker) in one transaction of its own, as the asker
   of the request, found in it: the lookup is its first statement, on the
   same connection, and the only one; f gets what it found, {:person-id
   :visitor-id :session-flags :token-id :outside-issuer}. Opened
   read-write, so a session seen again is pushed out in it; read-only?
   true, it is made read-only before f, in the statement that tells
   Postgres who asks. A transaction handed in is refused: who asks would
   change under its other statements."
  [pool request {:keys [settings read-only? scope-kinds log]} f]
  (when (postgres/is-transaction? pool)
    (throw (ex-info "run-as-asker-of-request! runs in a transaction of its own: hand it the pool"
                    {:type :groundwork-access/asker-in-a-transaction})))
  (postgres/run-in-transaction! pool {:read-only? false :log log}
                              (fn [transaction]
                                (let [{:keys [asker token-id outside-issuer]} (find-asker-or-throw transaction request settings)]
                                  (asking/set-asking-with-every-action! transaction scope-kinds asker read-only?)
                                  (f transaction (assoc asker :token-id token-id :outside-issuer outside-issuer))))))

(defn make-token! [pool request {:keys [settings] :as options}]
  (let [{{:keys [person-id session-flags]} :asker :keys [token-id outside-issuer]} (find-asker-or-throw pool request settings)]
    (cond
      (nil? person-id)
      (throw (ex-info "Only a signed-in person makes a token" {:type :groundwork-access/no-token-for-asker :reason :nobody}))

      token-id
      (throw (ex-info "A token makes no token: a signed-in session does" {:type :groundwork-access/no-token-for-asker :reason :token}))

      outside-issuer
      (throw (ex-info "An outside issuer's token makes no token: a signed-in session does"
                      {:type :groundwork-access/no-token-for-asker :reason :outside-token}))

      (seq session-flags)
      (throw (ex-info (str "A session with flags makes no token: the token, having none, would shed them " (pr-str session-flags))
                      {:type :groundwork-access/no-token-for-asker :reason :session-flags :session-flags session-flags}))

      :else
      (tokens/insert-token! pool person-id options))))

(defn list-tokens [pool request settings]
  (let [{{:keys [person-id]} :asker} (find-asker-or-throw pool request settings)]
    (if person-id (tokens/list-tokens-of-person pool person-id) [])))

(defn revoke-token! [pool request settings token-id]
  (let [{{:keys [person-id]} :asker} (find-asker-or-throw pool request settings)]
    (boolean (and person-id (tokens/delete-token-of-person! pool person-id token-id)))))
