(ns groundwork-access.conditions
  "Every table's sentences checked, then turned into SQL conditions: one per
   action (the check's: its sentences whose :only allows what the action
   is for, its writes or else :read), and one per operation (read,
   insert, update, delete) for the gates, joining the actions that do that
   operation, each by its sentences whose :only allows it on this table.

   An action's condition is its sentences joined by OR, and no deny of
   its kind saying no: a session flag (NOT groundwork_access.has_session_flag), or
   a scope's state (the row's columns naming that kind name no scope in
   that state, groundwork_access.denied_…()). A person's sentences count only while
   the transaction acts as that action (groundwork_access.is_acting(action)): a
   handler's steps act as its action alone, so a row another action
   reaches is neither read nor written by them. A named system actor's
   sentences ([:system name]) stand beside them, unfiltered.

   A gate adds to that: reads pass while groundwork-access reads past the gates
   (groundwork_access.is_passing(), in its own functions only); a table with a
   :visitor column lets sign-in move a visitor's rows to the person; and
   a table a :via reads is changed in what it matches only by someone who
   may create such a row (groundwork_access.unchanged_…)."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.parents :as parents]
   [groundwork-access.scope-kinds :as scope-kinds]
   [groundwork-access.sentences :as sentences]))

(defn- find-what-actions-do
  "{:orders/see #{:read} …}: every action of every scope kind."
  [scope-kinds]
  (apply merge {} (map :actions (vals scope-kinds))))

(defn- has-sentences? [table]
  (sequential? (:access table)))

(def max-name-bytes
  "Postgres keeps a name's first 63 bytes."
  63)

;; -----------------------------------------------------------------------------
;; Checking

(defn is-indexed?
  "Whether an index of the table (sentences/find-indexed-column-lists: a :btree one
   over every row, the primary key, a unique key) starts with these
   columns, in any order."
  [table columns]
  (sentences/is-indexed? table columns))

(defn- find-who-problems
  "A :to word said on a table: [problem …]."
  [where to action table {:keys [tables-by-key scope-kinds]}]
  (let [columns (set (map first (:columns table)))]
    (cond
      (#{:anyone :signed-in} to) nil

      (and (vector? to) (= :system (first to)) (= 2 (count to)))
      (when-not (and (simple-keyword? (second to)) (re-matches #"[a-z0-9-]+" (name (second to))))
        [(str where ": [:system " (pr-str (second to)) "] names a system actor by a keyword without a namespace, like :email")])

      (and (vector? to) (= :me (first to)) (= 2 (count to)))
      (when-not (contains? columns (second to))
        [(str where ": " (second to) " is no column of " (:table table))])

      (and (vector? to) (= :member (first to)) (<= 2 (count to) 3))
      (let [[_ x held] to
            held        (or held action)
            held-kind   (scope-kinds/find-kind-of-action scope-kinds held)
            scope-kind  (if (sentences/is-column-word? x)
                          (sentences/find-kind-of-member-column table x tables-by-key scope-kinds)
                          x)]
        (concat
         (when (and (sentences/is-column-word? x) (not (contains? columns x)))
           [(str where ": " x " is no column of " (:table table))])
         (when (and (sentences/is-column-word? x) (contains? columns x) (nil? scope-kind))
           [(str where ": " x " names no scope: it must reference the table of a scope kind, or be its primary key")])
         (when (and (not (sentences/is-column-word? x)) (not (contains? scope-kinds x)))
           [(str where ": " x " is no scope kind")])
         (when (and (not (sentences/is-column-word? x)) (contains? scope-kinds x) (not (get-in scope-kinds [x :single?])))
           [(str where ": [:member " x "] names a kind, which must have exactly one scope (:single? true); "
                 "or name a column of the row")])
         (when (and (get to 2) (not held-kind))
           [(str where ": " held " is no scope kind's action")])
         (when (and scope-kind held-kind (contains? scope-kinds scope-kind)
                    (not (scope-kinds/is-kind-at-or-below? scope-kinds held-kind scope-kind)))
           [(str where ": nobody in a " (name scope-kind) " can hold " held ", an action of " held-kind
                 (when (nil? (get to 2)) "; name the action they hold there: [:member … <action>]"))])))

      :else [(str where ": " (pr-str to) " isn't a :to groundwork-access knows")])))

(defn- find-condition-problems [where condition table]
  (let [columns (set (map first (:columns table)))]
    (for [[word column bad] (sentences/find-condition-words condition)
          :let [problem (cond bad                                (str where ": " (pr-str bad) " isn't a condition groundwork-access knows")
                              (not (contains? columns column))   (str where ": " column " is no column of " (:table table)))]
          :when problem]
      problem)))

(defn- find-via-problems [where {:keys [table match to] condition :when :as via} row-table {:keys [tables-by-key] :as place} action]
  (let [other   (get tables-by-key table)
        mine    (set (map first (:columns row-table)))
        theirs  (set (map first (:columns other)))]
    (cond
      (not (map? via))
      [(str where ": :via is {:table … :match {the row's column the related row's column} :to <who> :when <condition>}")]

      (seq (remove #{:table :match :to :when} (keys via)))
      [(str where ": :via doesn't know " (str/join " " (remove #{:table :match :to :when} (keys via))))]

      (nil? other)
      [(str where ": :via's table " table " is none of the tables")]

      (not (and (map? match) (seq match)))
      [(str where ": :via's :match is {the row's column the related row's column …}, at least one")]

      :else
      (concat
       (for [c (keys match) :when (not (contains? mine c))] (str where ": " c " is no column of " (:table row-table)))
       (for [c (vals match) :when (not (contains? theirs c))] (str where ": " c " is no column of " table))
       (when-not (contains? via :to) [(str where ": :via needs :to, who must hold on the related row")])
       (when (and (vector? to) (= :system (first to)))
         [(str where ": a :via's :to is who asks through the related row; a system actor is named by the sentence's own :to")])
       (when (contains? via :to) (find-who-problems (str where ", on the related row") to action other place))
       (when (and (vector? to) (= :member (first to)) (not= 3 (count to)))
         [(str where ": a :via's [:member …] names the action held on the related row, [:member " (second to) " <action>]")])
       (when condition (find-condition-problems (str where ", on the related row") condition other))
       (when (some #(= :none (first %)) (when condition (sentences/find-condition-words condition)))
         [(str where ": :via's :when can't say [:none …]: the related row is found by its columns")])
       (when (and (every? mine (keys match)) (not (is-indexed? row-table (keys match))))
         [(str where ": " (:table row-table) " needs an index on " (vec (keys match)) ", what the via matches")])
       (when (and (every? theirs (vals match)) (not (is-indexed? other (vals match))))
         [(str where ": " table " needs an index on " (vec (vals match)) ", what the via matches")])
       (let [who-column (when (and (vector? to) (sentences/is-column-word? (second to))) (second to))]
         (when (and who-column (contains? theirs who-column) (not (is-indexed? other [who-column])))
           [(str where ": " table " needs an index on [" who-column "], the via's who")]))))))

(defn- find-only-problems
  "A sentence's :only, written out: :all-of-the-action, or a non-empty set
   of operations each of its actions does."
  [where {:keys [only] :as sentence} actions actions-do]
  (cond
    (not (contains? sentence :only))
    [(str where ": say which operations it allows, :only #{:read …} or :only :all-of-the-action")]

    (= :all-of-the-action only)
    nil

    (not (and (set? only) (seq only) (every? scope-kinds/operations only)))
    [(str where ": :only is :all-of-the-action or a non-empty set of " (pr-str (sort scope-kinds/operations)) ", not " (pr-str only))]

    :else
    (for [action (sort-by str actions)
          :let [does (get actions-do action)]
          :when (and does (not (every? does only)))]
      (str where ": :only " (pr-str only) " is more than " action " does, " (pr-str does)))))

(defn- find-problems-of-sentence [table place actions-do index sentence]
  (let [where   (str (:table table) " sentence " index " " (pr-str sentence))
        actions (sentences/find-allowed-actions sentence)]
    (concat
     (when-let [extra (seq (remove #{:allow :only :to :when :via} (keys sentence)))]
       [(str where ": a sentence doesn't know " (str/join " " extra))])
     (find-only-problems where sentence actions actions-do)
     (when-not (= 1 (count (filter #(contains? sentence %) [:to :via])))
       [(str where ": say to whom, :to <who>, or through which related row, :via {…}; one of them")])
     (for [action actions :when (not (contains? actions-do action))]
       (str where ": " action " is no scope kind's action"))
     (when (contains? sentence :to)
       (mapcat #(find-who-problems where (:to sentence) % table place) actions))
     (when (contains? sentence :via)
       (mapcat #(find-via-problems where (:via sentence) table place %) actions))
     (when-let [condition (:when sentence)]
       (find-condition-problems where condition table))
     (for [action actions
           :let [allowed (filter #(sentences/does-sentence-allow-operation? sentence %) (get actions-do action))]
           :when (and (#{:anyone :signed-in} (:to sentence))
                      (some scope-kinds/writing-operations allowed)
                      (nil? (:when sentence)))]
       (str where ": " action " writes, so " (:to sentence) " may have it only with a :when, or through a :via")))))

(defn- find-scope-tables [scope-kinds]
  (set (keep :table (vals scope-kinds))))

(defn- names-a-scope? [{:keys [columns]} tables scope-kinds]
  (let [scope-tables (find-scope-tables scope-kinds)
        pk-of-scope  (set (for [{:keys [table columns]} tables
                                :when (contains? scope-tables table)
                                [k _ options] columns :when (:primary-key? options)]
                            k))]
    (some (fn [[_ _ options]] (contains? pk-of-scope (get-in options [:references :to]))) columns)))

(defn- find-vias
  "Every via of every table's sentences: [{:via … :table row-table-key}]."
  [tables]
  (distinct (for [{:keys [access table]} tables :when (sequential? access)
                  {:keys [via]} access :when (map? via)]
              {:via via :table table})))

(defn- find-problems-of-table [table tables scope-kinds actions-do]
  (let [columns (set (map first (:columns table)))
        visitor (:visitor table)]
    (concat
     (parents/find-problems-of-parent table (into {} (map (juxt :table identity)) tables))
     (when (and (nil? (:access table)) (names-a-scope? table tables scope-kinds))
       [(str (:table table) " names a scope but has no :access: say who may see it, or :access :public")])
     (when (contains? table :visitor)
       (if-not (and (map? visitor) (= #{:column :moves-to} (set (keys visitor))))
         [(str (:table table) ": :visitor is {:column <its visitor column> :moves-to <its person column, or nil>}")]
         (concat
          (when-not (= :uuid (sentences/find-column-type table (:column visitor)))
            [(str (:table table) ": :visitor's column " (:column visitor) " must be one of its :uuid columns")])
          (when (and (:moves-to visitor) (not (contains? columns (:moves-to visitor))))
            [(str (:table table) ": :visitor's :moves-to " (:moves-to visitor) " is no column of it")]))))
     (when (and (some #(= (:table table) (get-in % [:via :table])) (find-vias tables))
                (some (fn [{:keys [allow]}] (some #(contains? (get actions-do %) :update) (if (set? allow) allow #{allow})))
                      (when (has-sentences? table) (:access table)))
                (not= 1 (count (sentences/find-primary-key-columns table))))
       [(str (:table table) " is read by a :via and may be updated, so it needs a one-column primary key: "
             "changing what it matches is checked by its row's id")]))))

(defn- find-problems-of-denies [tables scope-kinds]
  (let [tables-by-key (into {} (map (juxt :table identity)) tables)]
    (for [{:keys [kind deny] condition :when} (scope-kinds/find-denies scope-kinds)
          :when condition
          :let [table-key (get-in scope-kinds [kind :table])
                table     (get tables-by-key table-key)]
          problem (cond
                    (nil? table)
                    [(str kind "'s deny " (pr-str deny) " reads " table-key ", which is none of the tables")]
                    (not= [:uuid] (mapv #(sentences/find-column-type table %) (sentences/find-primary-key-columns table)))
                    [(str kind "'s deny " (pr-str deny) ": " table-key " needs a one-column :uuid primary key, the scope's id")]
                    :else
                    (find-condition-problems (str kind "'s deny " (pr-str deny)) condition table))]
      problem)))

(defn find-may-function-name
  "The function a query file calls to narrow a list to the rows the asker
   may do an action on: groundwork_access.\"may orders/edit on orders\"()."
  [action table-key]
  (str "groundwork_access.\"may " (sentences/turn-keyword-into-text action) " on "
       (postgres/turn-table-key-into-table-name table-key) "\""))

(defn- find-may-function-problems [tables]
  (for [{:keys [table access] :as table-map} tables
        :when (and (sequential? access) (= 1 (count (sentences/find-primary-key-columns table-map))))
        action (sort-by str (set (mapcat sentences/find-allowed-actions access)))
        :let [function-name (str "may " (sentences/turn-keyword-into-text action) " on "
                                 (postgres/turn-table-key-into-table-name table))]
        :when (< max-name-bytes (count (.getBytes function-name "UTF-8")))]
    (str table ": the function \"" function-name "\" is longer than Postgres's 63-byte names; shorten the action or the table's name")))

(defn check-sentences
  "Throws with every problem of every table's sentences at once."
  [tables scope-kinds]
  (let [actions-do (find-what-actions-do scope-kinds)
        place      {:tables-by-key (into {} (map (juxt :table identity)) tables) :scope-kinds scope-kinds}
        problems   (concat
                    (mapcat #(find-problems-of-table % tables scope-kinds actions-do) tables)
                    (for [table tables :when (has-sentences? table)
                          [index sentence] (map-indexed vector (:access table))
                          problem (find-problems-of-sentence table place actions-do index sentence)]
                      problem)
                    (find-problems-of-denies tables scope-kinds)
                    (find-may-function-problems tables))]
    (when (seq problems)
      (throw (ex-info (str "The access sentences have problems:\n  " (str/join "\n  " (distinct problems)))
                      {:type :groundwork-access/bad-sentences :problems (vec (distinct problems))})))))

;; -----------------------------------------------------------------------------
;; Denies, as SQL

(defn- find-hash [data] (format "%08x" (bit-and (hash data) 0xffffffff)))

(defn find-denied-function-name
  "The function a scope-state deny reads: the ids of the kind's scopes in
   that state, read past the gates."
  [{:keys [kind] condition :when}]
  (str "groundwork_access.denied_" (find-hash [kind condition])))

(defn turn-deny-into-sql
  "A deny's NOT for a row of the table, or nil when it can't say no there
   (a state deny on a table with no column naming its kind). A state deny
   holds for a row whose columns name a scope in the state, the scope's
   own row on the kind's table among them."
  [{:keys [kind session] :as deny} table {:keys [tables-by-key scope-kinds]}]
  (if session
    (str "NOT (SELECT groundwork_access.has_session_flag(" (postgres/quote-text (name session)) "))")
    (when-let [columns (seq (distinct (concat (sentences/find-columns-naming-kind table kind tables-by-key scope-kinds)
                                              ;; the scope's own row (the shop in shops) is frozen too
                                              (for [column (sentences/find-primary-key-columns table)
                                                    :when (= kind (sentences/find-kind-of-own-row table column scope-kinds))]
                                                column))))]
      (str/join " AND " (for [column columns
                              :let [c (sentences/column-name column)]]
                          (str "(" c " IS NULL OR " c " NOT IN (SELECT " (find-denied-function-name deny) "()))"))))))

(defn- add-denies [sql action operation table {:keys [scope-kinds] :as place}]
  (let [nots (keep #(when (scope-kinds/does-deny-apply? % action operation) (turn-deny-into-sql % table place))
                   (scope-kinds/find-denies scope-kinds))]
    (if (seq nots)
      (str "((" sql ") AND " (str/join " AND " nots) ")")
      sql)))

;; -----------------------------------------------------------------------------
;; The guard on a table a :via reads

(defn find-guard
  "For a table a :via reads: the columns that, changed, would make one of
   its rows give something else (what a via matches, its who column), and
   the vias' :when, which, holding again, would too. nil for a table no via
   reads."
  [table-key tables]
  (let [vias (map :via (filter #(= table-key (get-in % [:via :table])) (find-vias tables)))]
    (when (seq vias)
      {:columns (vec (sort-by str (distinct (concat (mapcat vals (map :match vias))
                                                    (keep #(let [to (:to %)]
                                                             (when (and (vector? to) (sentences/is-column-word? (second to)))
                                                               (second to)))
                                                          vias)))))
       :whens   (vec (distinct (keep :when vias)))})))

(defn find-unchanged-function-name [table-key guard]
  (str "groundwork_access.unchanged_" (find-hash [table-key guard])))

(defn- turn-guard-into-sql [table-key guard table insert-condition]
  (let [pk (first (sentences/find-primary-key-columns table))]
    (str "(" insert-condition " OR " (find-unchanged-function-name table-key guard) "("
         (str/join ", " (concat [(sentences/column-name pk)]
                                (map sentences/column-name (:columns guard))
                                (map #(str "(" (sentences/turn-when-into-sql %) ") IS TRUE") (:whens guard))))
         "))")))

;; -----------------------------------------------------------------------------
;; The conditions

(defn- join-with-or
  "Conditions joined by OR, each in brackets; one alone as it is; none, false."
  [parts]
  (case (count parts)
    0 "false"
    1 (first parts)
    (str/join " OR " (map #(str "(" % ")") parts))))

(defn- join-actions-with-or
  "Actions' conditions joined by OR, each in brackets; none, false."
  [parts]
  (if (seq parts) (str/join " OR " (map #(str "(" % ")") parts)) "false"))

(defn- make-moving-branch
  "The gates' branch for sign-in's move of a visitor's rows to the person,
   for reading and for updating: the visitor's rows, and the row after
   the move, the person's with the visitor emptied (an UPDATE with a WHERE
   holds the new row to the read gate too)."
  [{{:keys [column moves-to]} :visitor}]
  (when moves-to
    (let [v (sentences/column-name column)
          p (sentences/column-name moves-to)]
      (str "(" v " = (SELECT groundwork_access.find_moving_visitor()) OR (" v " IS NULL AND "
           p " = (SELECT groundwork_access.find_moving_person())))"))))

(defn- join-sentences-with-or
  "Sentences of an action as SQL joined by OR, or nil for none."
  [sentences action place]
  (when (seq sentences)
    (str/join " OR " (map #(sentences/turn-sentence-into-sql % action place) sentences))))

(defn check-operations
  "The operations the check asks a sentence's :only for, of what an
   action does: its writes, for an action that writes (a sentence letting
   it only read there doesn't let it act); else :read."
  [does]
  (let [writes (filter scope-kinds/writing-operations does)]
    (if (seq writes) (set writes) #{:read})))

(defn- make-conditions-of-table [table place actions-do tables]
  (let [place        (assoc place :table table)
        actions      (set (mapcat sentences/find-allowed-actions (:access table)))
        ;; the denies hold people (a session's flag, a scope's state); a
        ;; named system actor has no session and lifts a state, so its
        ;; sentences stand beside them. A person's sentences count only for
        ;; the action the handler acts as (groundwork_access.is_acting), so another
        ;; action held in the same scope opens nothing. In an operation, only
        ;; the sentences whose :only allows it; for the check (op nil), those
        ;; allowing what the action is for (check-operations).
        condition-of (fn [action op]
                       (let [ops    (if op #{op} (check-operations (get actions-do action)))
                             all    (filter (fn [sentence] (some #(sentences/does-sentence-allow-operation? sentence %) ops))
                                            (sentences/find-sentences-of-action table action))
                             people (join-sentences-with-or (remove sentences/is-system-sentence? all) action place)
                             system (join-sentences-with-or (filter sentences/is-system-sentence? all) action place)]
                         (when (or people system)
                           (join-with-or (remove nil? [(when people (str "((SELECT groundwork_access.is_acting("
                                                                         (postgres/quote-text (sentences/turn-keyword-into-text action))
                                                                         ")) AND (" (add-denies people action op table place) "))"))
                                                       system])))))
        for-op       (fn [op] (join-actions-with-or (for [action (sort-by str actions)
                                                          :when (contains? (get actions-do action) op)
                                                          :let [condition (condition-of action op)]
                                                          :when condition]
                                                      condition)))
        moving       (make-moving-branch table)
        guard        (find-guard (:table table) tables)
        update-gate  (let [updates (for-op :update)]
                       (if (and guard (not= "false" updates))
                         (str "(" updates ") AND " (turn-guard-into-sql (:table table) guard table (for-op :insert)))
                         updates))]
    {:actions    (into {} (for [action actions] [action (or (condition-of action nil) "false")]))
     ;; each action that reads, by its sentences allowing :read here: a
     ;; marked param's row (groundwork-access.rows-of), as the read gate reads it
     :reads      (into {} (for [action actions
                                :when (contains? (get actions-do action) :read)
                                :let [condition (condition-of action :read)]
                                :when condition]
                            [action condition]))
     :operations {:read   (join-with-or (remove #(or (nil? %) (= "false" %)) ["(SELECT groundwork_access.is_passing())" moving (for-op :read)]))
                  :insert (for-op :insert)
                  :update (join-with-or (remove #(or (nil? %) (= "false" %)) [moving update-gate]))
                  :delete (for-op :delete)}}))

(defn make-sql-conditions
  "{table-key {:actions {action sql} :reads {action sql} :operations {:read
   sql …}}} for every table with sentences: :reads, for each action that
   reads, its sentences allowing :read on the table."
  [tables scope-kinds]
  (check-sentences tables scope-kinds)
  (let [place      {:tables-by-key (into {} (map (juxt :table identity)) tables) :scope-kinds scope-kinds}
        actions-do (find-what-actions-do scope-kinds)]
    (into {} (for [table tables :when (has-sentences? table)]
               [(:table table) (make-conditions-of-table table place actions-do tables)]))))
