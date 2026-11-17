(ns groundwork-access.my-actions
  "\"What may I do?\", for the browser: a handler answering the asker's
   actions, every scope they may do something in with those actions, and
   the actions open to them in every scope:

     [{:scope-kind nil   :scope-id nil      :actions [:posts/read :profile/edit]}  ; in every scope
      {:scope-kind :shop :scope-id #uuid \"…\" :actions [:orders/edit :orders/see]}]

   A scope's actions: those the person's roles hold there (and in the
   scopes above it); and an action a :via gives through related rows that
   name the scope (a partner's agreement with a shop), when the via's
   [:member …] needs that action itself held. A [:member … other-action]
   sentence, its own :to or its :via's, gives rows, never the action: the
   check counts no such sentence, and neither does this answer
   (decisions/2026-11-09-the-check-counts-an-action-held-only-by-the-persons-roles.md).

   The entry of nil kind and id holds the actions no scope lists: :anyone
   sentences' always; for someone signed in, :signed-in and [:me column]
   sentences'; for a visitor, [:me <visitor column>] sentences'; and a
   [:member <kind of one scope>] or a :via's [:member …] whose related
   rows name no scope of the row, needing the action itself held, when
   the person's roles hold it somewhere. Such an action is listed for every scope, and the
   row decides. Without such actions the nil entry is left out.

   A session flag a deny names takes its actions out everywhere; a
   scope's state, out of that scope. Scopes come by kind, then id;
   actions sorted.

   It runs as the asker, in one transaction: the find-session step first
   (the Origin checked, the person or visitor, the lookup the
   transaction's first statement), then, read-only from there, it tells
   Postgres who is asking (every action, in every scope holding it), in which the step reads groundwork-access's tables and
   the vias' and denies' functions. It is a hint for screens: the check
   and the gates decide."
  (:require
   [groundwork-postgres.api :as postgres]
   [groundwork-access.asking :as asking]
   [groundwork-access.check :as check]
   [groundwork-access.conditions :as conditions]
   [groundwork-access.scope-kinds :as scope-kinds]
   [groundwork-access.shapes :as shapes]
   [groundwork-access.sentences :as sentences]
   [groundwork-steps.api :as steps]))

(def answer-shape
  "The answer, groundwork-access.shapes's, the same on both sides."
  shapes/my-actions-answer-shape)

(defn- sort-actions [actions]
  (vec (sort-by #(subs (str %) 1) actions)))

(defn make-answer
  "The answer, from the actions by scope ({[:shop id] #{…}}) and the
   actions open everywhere (#{…})."
  [actions-by-scope everywhere]
  (into (if (seq everywhere)
          [{:scope-kind nil :scope-id nil :actions (sort-actions everywhere)}]
          [])
        (for [[[kind id] actions] (sort-by (fn [[[kind id]]] [(name kind) (str id)]) actions-by-scope)
              :when (seq actions)]
          {:scope-kind kind :scope-id id :actions (sort-actions actions)})))

(defn- holds-anywhere? [actions-by-scope action]
  (boolean (some #(contains? % action) (vals actions-by-scope))))

(defn- find-plan
  "What the answer is made of, read once from the sentences: the open
   actions; vias whose related rows name the row's scope; and the actions
   open to whoever holds them somewhere. Only sentences giving the action
   itself (sentences/gives-the-action-itself?)."
  [tables scope-kinds]
  (let [tables-by-key (into {} (map (juxt :table identity)) tables)
        sentences     (for [{:keys [access] :as table} tables :when (sequential? access)
                            sentence access
                            action (sentences/find-allowed-actions sentence)
                            :when (sentences/gives-the-action-itself? sentence action)]
                        [table sentence action])]
    {:open      (sentences/find-open-actions tables)
     :via-scope (distinct (for [[table {:keys [via]} action] sentences :when via
                                [i [mine _]] (map-indexed vector (sentences/find-matched-pairs via))
                                :let [kind (sentences/find-kind-named-by-column table mine tables-by-key scope-kinds)]
                                :when kind]
                            {:action action :kind kind :function (sentences/find-via-function-name via tables-by-key)
                             :out (str "via_" (inc i)) :table (:table via)}))
     :held-anywhere (distinct (for [[table {:keys [to via]} action] sentences
                                    :let [to (if via (:to via) to)]
                                    :when (and (vector? to) (= :member (first to))
                                               (or (not (sentences/is-column-word? (second to)))
                                                   (and via (not-any? #(sentences/find-kind-named-by-column table % tables-by-key scope-kinds)
                                                                      (keys (:match via))))))]
                                {:action action :needed (sentences/find-held-action to action)}))}))

(defn- name-table-read
  "The line naming the table a function reads: the function hides it from
   Postgres's planner, so the answer's live-update notes name it by this
   line (groundwork-postgres's \"-- reads:\")."
  [table-key]
  (str "-- reads: " (subs (str table-key) 1) "\n"))

(defn- make-via-reads
  "One query per via function and column, made once with the handler
   (groundwork-postgres asks the planner once per query made), each with
   what its scopes give: [{:query … :gives [[kind action] …]}]. Many
   actions, and many sentences, share one via's function."
  [via-scope]
  (for [[[function out table] vias] (group-by (juxt :function :out :table) via-scope)]
    {:query (postgres/make-query-from-sql-text
             (str "the scopes " function " names")
             (str (name-table-read table) "SELECT DISTINCT " out " AS scope__id FROM " function "()"))
     :gives (distinct (map (juxt :kind :action) vias))}))

(defn- make-denied-read
  "A scope-state deny's query, made once with the handler; its function
   reads the kind's table."
  [scope-kinds {:keys [kind] :as deny}]
  [deny (postgres/make-query-from-sql-text
         "the scopes in a denied state"
         (str (name-table-read (get-in scope-kinds [kind :table]))
              "SELECT " (conditions/find-denied-function-name deny) "() AS scope__id"))])

(defn- add-via-scopes [transaction by-scope {:keys [query gives]}]
  (let [ids (map :scope/id (postgres/find-rows transaction query {}))]
    (reduce (fn [by-scope [kind action]]
              (reduce #(update %1 [kind %2] (fnil conj #{}) action) by-scope ids))
            by-scope gives)))

(defn- find-denied-scopes [transaction query]
  (set (map :scope/id (postgres/find-rows transaction query {}))))

(defn- take-out-denied [actions-by-scope everywhere scope-kinds session-flags denied-by-state]
  (let [flagged   (set (for [deny (scope-kinds/find-denies scope-kinds)
                             :when (and (:session deny) (contains? session-flags (:session deny)))
                             action (:actions deny)]
                         action))
        by-state  (fn [[kind id] actions]
                    (reduce (fn [actions [{deny-kind :kind :as deny} denied-ids]]
                              (if (and (= kind deny-kind) (contains? denied-ids id))
                                (remove (:actions deny) actions)
                                actions))
                            actions denied-by-state))]
    [(into {} (for [[scope actions] actions-by-scope]
                [scope (set (remove flagged (by-state scope actions)))]))
     (set (remove flagged everywhere))]))

(defn- make-answer-step [tables scope-kinds]
  (let [{:keys [open via-scope held-anywhere]} (find-plan tables scope-kinds)
        via-reads    (vec (make-via-reads via-scope))
        denied-reads (mapv #(make-denied-read scope-kinds %) (filter :when (scope-kinds/find-denies scope-kinds)))]
    {:name  :groundwork-access/answer-my-actions
     :enter (fn [{:groundwork-access/keys [person-id visitor-id session-flags transaction] :as context}]
              (let [held     (asking/set-asking-with-every-action! transaction scope-kinds
                                                                       {:person-id person-id :visitor-id visitor-id :session-flags session-flags}
                                                                       true)   ; read-only from here on
                    by-scope (reduce #(add-via-scopes transaction %1 %2) held via-reads)
                    open-now (cond-> (:anyone open)
                               person-id                        (into (:signed-in open))
                               (and (nil? person-id) visitor-id) (into (:visitor open))
                               true                             (into (for [{:keys [action needed]} held-anywhere
                                                                            :when (holds-anywhere? held needed)]
                                                                        action)))
                    denied   (for [[deny query] denied-reads] [deny (find-denied-scopes transaction query)])
                    [by-scope open-now] (take-out-denied by-scope open-now scope-kinds session-flags denied)]
                (steps/add-answer-to-context context (make-answer by-scope open-now))))}))

(defn- make-run-steps-in
  "The session step, then the steps, in the question's own transaction
   at :groundwork-access/transaction (check/run-in-question-transaction!); the answer
   step makes it read-only before it reads."
  [{:keys [pool find-session-step]}]
  (fn [run context]
    (check/run-in-question-transaction! pool find-session-step context
                                        (fn [_transaction context] (run context)))))

(defn make-my-actions-handler
  "The handler, bridge's keys written out, groundwork-access's reasons its
   :refusals (the find-session step's); the options checked already."
  [{:keys [tables scope-kinds rate-limit] :as options}]
  (conditions/check-sentences tables scope-kinds)
  {:params-shape [:map {:closed true}]
   :answer-shape answer-shape
   :refusals     shapes/refusals
   :rate-limit   rate-limit
   :run-steps-in (make-run-steps-in options)
   :steps        [(make-answer-step tables scope-kinds)]})
