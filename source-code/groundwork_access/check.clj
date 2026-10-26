(ns groundwork-access.check
  "The check: every handler declares its :action (and its shapes) and
   where it acts, and groundwork-access puts the check in front of its steps; the
   app doesn't write it.

     {:orders/one   {:action       :orders/see
                     :record       [:orders :order/id]        ; the row the message names, by this param
                     :params-shape [:map [:order/id :uuid]]
                     :answer-shape …
                     :rate-limit   nil
                     :run-steps-in nil                          ; groundwork-access's transaction
                     :steps        [find-order add-order-answer]}
      :orders/place {:action :orders/edit
                     :scope  [:shop :order/shop-id]             ; the shop the param names
                     …}
      :orders/mine  {:action :orders/see
                     :scope  :every-scope-holding-the-action    ; or :no-scope: only open sentences
                     …}}

   Each message then runs: find the session; in one transaction (read-only
   when the action only reads), tell Postgres who is asking
   (groundwork_access.asking), with every action, for the check:
   - :record: may this person do the action on the record? Not, and they
     can't see it either: :groundwork-access/not-found (nothing tells them it
     exists); not, but they can see it: :groundwork-access/not-allowed.
   - :scope [kind param]: is the action held in the scope the param names
     (a membership above it counts)? Not: :groundwork-access/not-found; a session
     flag or the scope's state denying it: :groundwork-access/not-allowed.
   - :scope :every-scope-holding-the-action: can the asker have the action
     at all, open to them by a sentence (anyone, anyone signed in, a
     visitor) or held somewhere? :scope :no-scope: by an open sentence
     only. Not: :groundwork-access/not-found; a session flag denying it:
     :groundwork-access/not-allowed.
   Refused while nobody is signed in, the reason is :groundwork-access/signed-out,
   whichever of the two it was: a signed-out asker learns nothing of the
   record. Refused, no step runs (groundwork-steps's refuse; groundwork-access adds its
   reasons to every handler's :refusals). Let through, Postgres is told again, in the same
   transaction: the handler's action alone, in the scopes it acts in
   (groundwork_access.acting). Then every param its :params-shape marks as a row of
   a table, [:uuid {:groundwork-access/row-of :workstations}], must be a row the
   action reads as told (groundwork_access.rows-of), or :groundwork-access/not-found
   (:groundwork-access/signed-out for nobody signed in), still before any step. The
   handler's steps run with :groundwork-access/transaction in the context. The
   gates decide which rows."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.acting :as acting]
   [groundwork-access.asking :as asking]
   [groundwork-access.conditions :as conditions]
   [groundwork-access.rows-of :as rows-of]
   [groundwork-access.scope-kinds :as scope-kinds]
   [groundwork-access.sentences :as sentences]
   [groundwork-access.shapes :as shapes]
   [groundwork-steps.api :as steps]))

(def ^:private not-found :groundwork-access/not-found)

(def ^:private not-allowed :groundwork-access/not-allowed)

(defn find-refusal-reason
  "The reason the check refuses with: :groundwork-access/signed-out when nobody is
   signed in, whatever the check found (so it tells nothing of the
   record), else what it found, :groundwork-access/not-found or
   :groundwork-access/not-allowed."
  [found person-id]
  (if (nil? person-id) :groundwork-access/signed-out found))

(defn- find-where-problems
  "Where the handler acts: exactly one of :record and :scope, each as
   groundwork-access knows it."
  [handler-name {:keys [action record scope] :as handler} {:keys [conditions scope-kinds]}]
  (let [has-record? (contains? handler :record)
        has-scope?  (contains? handler :scope)]
    (cond
      (and has-record? has-scope?)
      [(str handler-name " says both :record and :scope; it acts in one of them")]

      (not (or has-record? has-scope?))
      [(str handler-name " says neither :record nor :scope: write out where it acts, :record [table param], "
            ":scope [kind param], :scope :every-scope-holding-the-action or :scope :no-scope")]

      has-record?
      (if-not (and (vector? record) (= 2 (count record)) (every? keyword? record))
        [(str handler-name ": :record is [table param], like [:orders :order/id], not " (pr-str record))]
        (when (and action (not (get-in conditions [(first record) :actions action])))
          [(str handler-name ": no sentence on " (first record) " allows " action)]))

      (contains? acting/scope-words scope)
      nil

      (and (vector? scope) (= 2 (count scope)) (simple-keyword? (first scope)) (keyword? (second scope)))
      (when-not (contains? scope-kinds (first scope))
        [(str handler-name ": :scope names " (first scope) ", which is no scope kind")])

      :else
      [(str handler-name ": :scope is [kind param], :every-scope-holding-the-action or :no-scope, not " (pr-str scope))])))

(defn find-reads-of-action
  "{table sql}: the action's read condition on each table whose sentences
   let it read (make-sql-conditions' :reads)."
  [conditions action]
  (into {} (for [[table {:keys [reads]}] conditions
                 :let [sql (get reads action)]
                 :when sql]
             [table sql])))

(defn- find-problems-of-handler
  "groundwork-access's own keys, and bridge's written out: :steps, :refusals ({} for
   none; never groundwork-access's own, which groundwork-access adds), :rate-limit (nil for
   none) and :run-steps-in, nil, since groundwork-access runs the steps in its
   transaction and an app's own would be replaced unseen. Both shapes must
   be real ones: the leak test makes params from :params-shape, and an
   answer leaves only as :answer-shape allows. Where it acts: exactly one
   of :record and :scope. Its :params-shape's marks (groundwork-access.rows-of)."
  [setup [handler-name {:keys [action params-shape answer-shape run-steps-in refusals] :as handler}]]
  (concat
   (when-not action [(str handler-name " has no :action")])
   (when-not params-shape [(str handler-name " has no :params-shape")])
   (when-not answer-shape [(str handler-name " has no :answer-shape")])
   (for [k [:steps :refusals :rate-limit :run-steps-in]
         :when (not (contains? handler k))]
     (str handler-name " has no " k " (nil written out where nothing applies, {} for no :refusals)"))
   (for [reason (keys shapes/refusals)
         :when (and (map? refusals) (contains? refusals reason))]
     (str handler-name " declares " reason ", groundwork-access's own: groundwork-access adds it to every handler it wraps"))
   (when run-steps-in [(str handler-name " has its own :run-steps-in; groundwork-access runs the steps in its transaction, so it must be nil")])
   (find-where-problems handler-name handler setup)
   (when (and action params-shape)
     (rows-of/find-problems-of-marks handler-name handler (:tables setup) (:scope-kinds setup)
                                     (find-reads-of-action (:conditions setup) action)))))

(defn check-handlers
  "Throws with every problem of every handler at once. setup:
   {:conditions (make-sql-conditions) :scope-kinds}."
  [handlers setup]
  (let [problems (mapcat #(find-problems-of-handler setup %) handlers)]
    (when (seq problems)
      (throw (ex-info (str "The handlers have problems:\n  " (str/join "\n  " problems))
                      {:type :groundwork-access/bad-handlers :problems (vec problems)})))))

(defn- find-primary-key-column [tables table-key]
  (some (fn [{:keys [table columns]}]
          (when (= table table-key)
            (some (fn [[k _ options]] (when (:primary-key? options) (postgres/turn-key-into-column-name k))) columns)))
        tables))

(defn- make-record-query
  "Whether the person may do the action on the record, and whether they
   may see it at all: one may see an order and not change it."
  [{:keys [tables conditions]} {:keys [action] [table-key] :record}]
  (let [row (str "SELECT 1 FROM " (postgres/turn-table-key-into-table-name table-key)
                 " WHERE " (find-primary-key-column tables table-key) " = param('record/id')")]
    (postgres/make-query-from-sql-text
     (str "may " action " on " table-key)
     (str "SELECT EXISTS (" row " AND (" (get-in conditions [table-key :actions action]) ")) AS allowed, "
          "EXISTS (" row " AND (" (get-in conditions [table-key :operations :read]) ")) AS visible"))))

(defn find-scope-columns
  "[[column kind] …]: the columns of a table that name a scope for a
   [:member …], its own primary key on a scope kind's table among them."
  [table tables scope-kinds]
  (let [tables-by-key (into {} (map (juxt :table identity)) tables)]
    (vec (for [[column] (:columns table)
               :let [kind (sentences/find-kind-of-member-column table column tables-by-key scope-kinds)]
               :when kind]
           [column kind]))))

(defn- make-record-scopes-query
  "The scopes the record's columns name, read once the check has let it
   through; nil for a table with none."
  [{:keys [tables scope-kinds]} {[table-key] :record}]
  (let [table   (some #(when (= table-key (:table %)) %) tables)
        columns (find-scope-columns table tables scope-kinds)]
    (when (seq columns)
      {:columns columns
       :query   (postgres/make-query-from-sql-text
                 (str "the scopes of a row of " table-key)
                 (str "SELECT " (str/join ", " (map (comp sentences/column-name first) columns))
                      " FROM " (postgres/turn-table-key-into-table-name table-key)
                      " WHERE " (find-primary-key-column tables table-key) " = param('record/id')"))})))

(defn find-flag-deny
  "The deny a session's flags say no to an action with, or nil."
  [scope-kinds action session-flags]
  (first (filter #(and (:session %) (contains? session-flags (:session %))
                       (scope-kinds/does-deny-apply? % action nil))
                 (scope-kinds/find-denies scope-kinds))))

(defn find-state-deny
  "The deny a scope's state says no to an action with in that scope, or
   nil: a deny of the scope's kind, by its :when, the scope in that state
   (groundwork_access.denied_…(), read past the gates)."
  [transaction scope-kinds action [kind scope-id]]
  (first (filter (fn [deny]
                   (and (= kind (:kind deny)) (:when deny) (scope-kinds/does-deny-apply? deny action nil)
                        (:scope/denied (postgres/find-one-row
                                        transaction
                                        (postgres/make-query-from-sql-text
                                         "whether a scope is in a denied state"
                                         (str "SELECT CAST(param('scope/id') AS uuid) IN (SELECT * FROM "
                                              (conditions/find-denied-function-name deny) "()) AS scope__denied"))
                                        {:scope/id scope-id}))))
                 (scope-kinds/find-denies scope-kinds))))

(def open-ways
  "The ways of having an action that need no scope: the only ones a
   :scope :no-scope handler counts."
  #{:anyone :signed-in :visitor})

(defn does-way-hold?
  "Whether one way of having an action (sentences/find-way-of-sentence)
   holds for the asker: open to anyone, to anyone signed in, to a
   visitor, or to someone holding an action somewhere."
  [way {:groundwork-access/keys [person-id visitor-id actions]}]
  (cond (= :anyone way)    true
        (= :signed-in way) (some? person-id)
        (= :visitor way)   (and (nil? person-id) (some? visitor-id))
        (and (vector? way) (= :system (first way))) false
        (vector? way)      (boolean (some #(contains? % (second way)) (vals actions)))
        :else              false))

(defn find-counted-ways
  "The ways of having the action that count for a handler acting where
   :scope says: every one, or for :no-scope the open ones."
  [ways scope]
  (if (= :no-scope scope) (set (filter open-ways ways)) ways))

(defn- find-reach-of-record
  "{:allowed bool :visible bool} for the record the message names."
  [query record context]
  (postgres/find-one-row (:groundwork-access/transaction context) query
                       {:record/id (get-in context [:groundwork-steps/message-data (second record)])}))

(defn- find-record-scopes [scopes-query record context]
  (when scopes-query
    (let [row (postgres/find-one-row (:groundwork-access/transaction context) (:query scopes-query)
                                   {:record/id (get-in context [:groundwork-steps/message-data (second record)])})]
      (for [[column kind] (:columns scopes-query)
            :let [id (get row column)]
            :when id]
        [kind id]))))

(defn- check-record
  "{:answer …} or {:scopes […]}: the record's scopes, and the sideways ones."
  [{:keys [query scopes-query sideways]} {:keys [record]} context]
  (let [{:keys [allowed visible]} (find-reach-of-record query record context)]
    (cond allowed {:scopes (concat (find-record-scopes scopes-query record context)
                                   (filter #(contains? sideways (first %)) (keys (:groundwork-access/actions context))))}
          visible {:answer not-allowed}
          :else   {:answer not-found})))

(defn- check-named-scope
  "{:answer …} or {:scopes [the scope]}: the action held there, and no
   deny saying no."
  [{:keys [scope-kinds needs]} {:keys [action] [kind param] :scope} context]
  (let [scope-id (get-in context [:groundwork-steps/message-data param])
        scope    [kind scope-id]]
    (cond (not (and (uuid? scope-id) (acting/is-held-there? needs action scope (:groundwork-access/actions context))))
          {:answer not-found}
          (find-flag-deny scope-kinds action (:groundwork-access/session-flags context))
          {:answer not-allowed}
          (find-state-deny (:groundwork-access/transaction context) scope-kinds action scope)
          {:answer not-allowed}
          :else
          {:scopes [scope]})))

(defn- check-ways
  "{:answer …} or {:scopes […]}, for :every-scope-holding-the-action and
   :no-scope: the asker can have the action by a way that counts."
  [{:keys [scope-kinds ways]} {:keys [action scope]} context]
  (cond (not-any? #(does-way-hold? % context) (find-counted-ways ways scope))
        {:answer not-found}
        (find-flag-deny scope-kinds action (:groundwork-access/session-flags context))
        {:answer not-allowed}
        :else
        {:scopes (if (= :no-scope scope) [] (keys (:groundwork-access/actions context)))}))

(defn- make-check-step [{:keys [tables needs conditions] :as setup} {:keys [record scope action params-shape] :as handler}]
  (let [marks    (rows-of/find-marks params-shape)
        prepared (assoc setup
                        :query        (when record (make-record-query setup handler))
                        :scopes-query (when record (make-record-scopes-query setup handler))
                        :sideways     (acting/find-sideways-kinds needs action)
                        :ways         (get (sentences/find-ways-of-actions tables) action #{}))
        readable (when (seq marks) (rows-of/make-readable-queries marks tables (find-reads-of-action conditions action) action))]
    {:name  :groundwork-access/check
     :enter (fn [{:groundwork-access/keys [transaction person-id visitor-id session-flags actions] :as context}]
              (let [{:keys [answer scopes]} (cond record         (check-record prepared handler context)
                                                  (vector? scope) (check-named-scope prepared handler context)
                                                  :else           (check-ways prepared handler context))]
                (if answer
                  (steps/refuse context (find-refusal-reason answer person-id) nil)
                  ;; from here on, Postgres hears only the handler's action, in its scopes
                  (do (asking/set-asking! transaction {:person-id person-id :visitor-id visitor-id :session-flags session-flags}
                                          (acting/make-telling needs action scopes actions))
                      ;; every marked param a row the action reads there, before any step
                      (if (and (seq marks)
                               (not-every? :readable? (rows-of/find-readable transaction readable
                                                                             (rows-of/find-marked-ids marks (:groundwork-steps/message-data context)))))
                        (steps/refuse context (find-refusal-reason not-found person-id) nil)
                        context)))))}))

(defn- does-action-only-read?
  "An action that does nothing but :read runs in a read-only transaction:
   Postgres refuses any write its steps try."
  [{:keys [scope-kinds]} action]
  (scope-kinds/does-action-only-read? scope-kinds action))

(defn- make-run-steps-in [{:keys [pool find-session-step scope-kinds] :as setup} {:keys [action]}]
  (fn [run context]
    (let [context (steps/run-steps-until-answered [find-session-step] context)]
      (if (steps/does-context-have-answer? context)
        context
        ;; the message's log, with its request id (and the person, once found): a
        ;; step's after-commit work that fails is written there
        (postgres/run-in-transaction! pool {:read-only? (does-action-only-read? setup action) :log (:groundwork-steps/log context)}
                                    (fn [transaction]
                                      (let [{:groundwork-access/keys [person-id visitor-id session-flags]} context
                                            ;; the check reads with every action; it then tells only the handler's
                                            actions (asking/set-asking-with-every-action! transaction scope-kinds
                                                                                          {:person-id person-id :visitor-id visitor-id :session-flags session-flags})]
                                        (run (assoc context
                                                    :groundwork-access/transaction transaction
                                                    :groundwork-access/actions actions)))))))))

(defn put-access-into-handlers
  "Every handler checked, then given the check in front of its steps, the
   running of them in a transaction that knows who is asking, and
   groundwork-access's reasons in its :refusals. setup: {:pool :tables :scope-kinds
   :find-session-step :conditions}."
  [handlers {:keys [tables scope-kinds] :as setup}]
  (check-handlers handlers setup)
  (let [setup (assoc setup :needs (acting/find-needs tables scope-kinds))]
    (update-vals handlers
                 (fn [handler]
                   (-> handler
                       (update :steps #(into [(make-check-step setup handler)] %))
                       (update :refusals merge shapes/refusals)
                       (assoc :run-steps-in (make-run-steps-in setup handler)))))))
