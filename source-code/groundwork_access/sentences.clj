(ns groundwork-access.sentences
  "The :access sentences on a table map, turned into SQL conditions: for
   each action, one condition a row must meet for the one asking.

     {:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}
     {:allow :orders/see :only :all-of-the-action :to [:me :order/customer-id] :when [:= :order/status \"placed\"]}
     {:allow :orders/see :only :all-of-the-action :to [:member :order/shop-id :orders/edit]}   ; holding another action there
     {:allow :orders/see :only :all-of-the-action :to [:member :platform :platform/support]}   ; the one scope of a kind
     {:allow :shops/edit :only :all-of-the-action :to [:member :shop/id]}                      ; on shops: the scope's own row
     {:allow :orders/edit :only #{:read} :to [:member :workstation/shop-id]}                   ; on workstations: edit reads them
     {:allow :orders/see :only :all-of-the-action :via {:table :agreements
                                                       :match {:order/shop-id :agreement/shop-id}
                                                       :to    [:member :agreement/partner-id :agreements/see]
                                                       :when  [:= :agreement/status \"active\"]}}

   :to    :anyone, :signed-in, [:system name] (the named system actor,
          run-as-system!, never a person: a webhook's function), [:member column] (a member of the scope the
          column names, whose role holds the action; on a scope kind's own
          table its primary key names the row itself), [:member kind] (of the
          one scope of a kind that has exactly one), either with a third
          element, the action they must hold there (the sentence's own
          unless said), [:me column] (the person the column names, or the
          visitor, when it is the table's :visitor column)
   :only  which of the action's operations the sentence allows on this
          table: :all-of-the-action (every one its kind declares), or a
          set of them, #{:read} (written out on every sentence)
   :when  a condition on the row's own columns: [:= col value],
          [:not= col value], [:in col [values]], [:none col] (col is null),
          [:some col] (col is set, not null), [:and …], [:or …]
   :via   instead of :to: the row is allowed while a related row exists
          for which a who (:to) and a :when hold, its :match columns equal
          the row's: SQL's EXISTS, read by a function groundwork-access installs
          (groundwork_access.via_…), which reads the related table past its gates.

   Who is asking is read from groundwork-access's own SQL functions (see
   sql_functions/, declared to groundwork-postgres by groundwork_access.api/function-files). Each is wrapped in (SELECT …), so Postgres
   asks once per query, not once per row; the array one is cast back to
   uuid[], or ANY would read it as a subquery of arrays."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.api :as postgres]))

(defn turn-keyword-into-text [k] (subs (str k) 1))

(defn- turn-value-into-sql [value]
  (cond (string? value)  (postgres/quote-text value)
        (keyword? value) (postgres/quote-text (turn-keyword-into-text value))
        (uuid? value)    (str (postgres/quote-text value) "::uuid")
        :else            (str value)))

(defn column-name [column-key] (postgres/turn-key-into-column-name column-key))

(defn find-primary-key-columns
  "The table's primary key columns, in the order of its :columns."
  [{:keys [columns]}]
  (vec (keep (fn [[k _ options]] (when (:primary-key? options) k)) columns)))

(defn- find-leading-column-keys
  "An index's columns, in order, up to its first expression: what a
   lookup by those columns can use."
  [{index-columns :columns}]
  (vec (take-while keyword? (map first index-columns))))

(defn find-indexed-column-lists
  "The column lists a table has an index on, each in its order: its
   :indexes that are :btree over every row (:where nil), their columns up
   to the first expression; its primary key; its :uniques; and each
   :unique? column."
  [{:keys [indexes uniques columns] :as table}]
  (concat (for [{:keys [method where] :as index} indexes
                :when (and (= :btree method) (nil? where))
                :let [column-keys (find-leading-column-keys index)]
                :when (seq column-keys)]
            column-keys)
          [(find-primary-key-columns table)]
          uniques
          (for [[k _ options] columns :when (:unique? options)] [k])))

(defn is-indexed?
  "Whether an index of the table (find-indexed-column-lists: a :btree one
   over every row, the primary key, a unique key) starts with these
   columns, in any order."
  [table columns]
  (boolean (some #(= (set columns) (set (take (count columns) %))) (find-indexed-column-lists table))))

(defn find-column-type [{:keys [columns]} column-key]
  (some (fn [[k type]] (when (= k column-key) type)) columns))

(defn find-kind-named-by-column
  "The scope kind a column names, the kind whose :table its reference
   points at (that table's primary key), or nil."
  [{:keys [columns]} column-key tables-by-key scope-kinds]
  (let [references (some (fn [[k _ options]] (when (= k column-key) (get-in options [:references :to]))) columns)
        target     (when references
                     (some (fn [[table-key {table-columns :columns}]]
                             (when (some (fn [[k _ options]] (and (= k references) (:primary-key? options))) table-columns)
                               table-key))
                           tables-by-key))]
    (when target
      (some (fn [[kind {:keys [table]}]] (when (= table target) kind)) scope-kinds))))

(defn find-kind-of-own-row
  "The scope kind whose :table this table is, when the column is its
   one-column primary key: the row is the scope itself (a shop's own row
   in shops). nil otherwise."
  [{:keys [table] :as table-map} column-key scope-kinds]
  (when (= [column-key] (find-primary-key-columns table-map))
    (some (fn [[kind {kind-table :table}]] (when (and kind-table (= kind-table table)) kind)) scope-kinds)))

(defn find-kind-of-member-column
  "The scope kind a [:member column …] names: the kind whose table the
   column references, or, for the primary key of a scope kind's own
   table, that kind (the row is the scope). nil for neither."
  [table column-key tables-by-key scope-kinds]
  (or (find-kind-named-by-column table column-key tables-by-key scope-kinds)
      (find-kind-of-own-row table column-key scope-kinds)))

(defn find-scope-kind-of-column
  "The scope kind a [:member column …] names (find-kind-of-member-column);
   throws when it names none."
  [table column-key tables-by-key scope-kinds]
  (or (find-kind-of-member-column table column-key tables-by-key scope-kinds)
      (throw (ex-info (str column-key " names no scope: it must reference the table of a scope kind, or be its primary key")
                      {:type :groundwork-access/column-names-no-scope :column column-key}))))

(defn find-columns-naming-kind
  "The columns of a table that name scopes of a kind."
  [{:keys [columns] :as table} kind tables-by-key scope-kinds]
  (filterv #(= kind (find-kind-named-by-column table % tables-by-key scope-kinds)) (map first columns)))

(defn find-visitor-column [table] (get-in table [:visitor :column]))

(defn is-column-word?
  "Whether a [:member x …] names a column of the row (x has a namespace),
   rather than a kind (one without)."
  [x]
  (qualified-keyword? x))

(defn find-held-action
  "The action a [:member …] word needs held: its third element, or the
   sentence's own action."
  [to action]
  (or (get to 2) action))

(defn turn-who-into-sql
  "A who word as SQL over the table's own columns."
  [to action table {:keys [tables-by-key scope-kinds]}]
  (cond
    (= :anyone to)    "true"
    (= :signed-in to) "(SELECT groundwork_access.find_asking_person()) IS NOT NULL"
    (= :member (first to))
    (let [[_ x] to
          held  (postgres/quote-text (turn-keyword-into-text (find-held-action to action)))]
      (if (is-column-word? x)
        (let [kind (find-scope-kind-of-column table x tables-by-key scope-kinds)]
          (str (column-name x) " = ANY ((SELECT groundwork_access.find_scopes_holding(" (postgres/quote-text (name kind)) ", " held "))::uuid[])"))
        (str "cardinality((SELECT groundwork_access.find_scopes_holding(" (postgres/quote-text (name x)) ", " held "))::uuid[]) > 0")))
    (= :system (first to))
    (str "(SELECT groundwork_access.find_asking_system()) = " (postgres/quote-text (name (second to))))
    (= :me (first to))
    (str (column-name (second to))
         (if (= (second to) (find-visitor-column table))
           " = (SELECT groundwork_access.find_asking_visitor())"
           " = (SELECT groundwork_access.find_asking_person())"))
    :else (throw (ex-info (str "Not a :to groundwork-access knows: " (pr-str to)) {:type :groundwork-access/bad-to :to to}))))

(defn turn-when-into-sql [[word & more :as condition]]
  (case word
    :=    (str (column-name (first more)) " = " (turn-value-into-sql (second more)))
    :not= (str (column-name (first more)) " <> " (turn-value-into-sql (second more)))
    :in   (if (seq (second more))
            (str (column-name (first more)) " IN (" (str/join ", " (map turn-value-into-sql (second more))) ")")
            "false")
    :none (str (column-name (first more)) " IS NULL")
    :some (str (column-name (first more)) " IS NOT NULL")
    :and  (str "(" (str/join " AND " (map turn-when-into-sql more)) ")")
    :or   (str "(" (str/join " OR " (map turn-when-into-sql more)) ")")
    (throw (ex-info (str "Not a :when groundwork-access knows: " (pr-str condition)) {:type :groundwork-access/bad-when :when condition}))))

(defn find-condition-words
  "Every [word column] of a condition, for checking."
  [[word & more :as condition]]
  (case word
    (:and :or)            (mapcat find-condition-words more)
    (:= :not= :in :none :some) [[word (first more)]]
    [[word nil condition]]))

(defn- find-hash [data] (format "%08x" (bit-and (hash data) 0xffffffff)))

(defn find-via-function-name
  "The name of the function a via is read through: the values its related
   rows offer for what it matches, as via_1 …, for the one asking."
  [{:keys [table match to] condition :when} tables-by-key]
  (let [other (get tables-by-key table)
        types (mapv #(find-column-type other (val %)) (sort-by (comp str key) match))]
    (str "groundwork_access.via_" (find-hash [table (sort-by (comp str key) match) to condition types]))))

(defn find-matched-pairs
  "A via's :match as [[the row's column, the related row's column] …], in a stable order."
  [{:keys [match]}]
  (vec (sort-by (comp str first) match)))

(defn turn-via-into-sql
  "A via as SQL over the row's own columns: they are among the values its
   function offers."
  [via {:keys [tables-by-key]}]
  (let [pairs (find-matched-pairs via)
        mine  (map (comp column-name first) pairs)
        outs  (map-indexed (fn [i _] (str "via_" (inc i))) pairs)]
    (str (if (= 1 (count mine)) (first mine) (str "(" (str/join ", " mine) ")"))
         " IN (SELECT " (str/join ", " outs) " FROM " (find-via-function-name via tables-by-key) "())")))

(defn turn-sentence-into-sql
  "One sentence for one action as one SQL condition: its who (or its
   via), and its :when."
  [{:keys [to via] condition :when} action {:keys [table] :as place}]
  (str "(" (if via (turn-via-into-sql via place) (turn-who-into-sql to action table place))
       (when condition (str " AND " (turn-when-into-sql condition)))
       ")"))

(defn find-allowed-actions [{:keys [allow]}]
  (if (set? allow) allow #{allow}))

(defn does-sentence-allow-operation?
  "Whether a sentence's :only lets its actions do the operation on its
   table: :all-of-the-action, or the operation in its set."
  [{:keys [only]} op]
  (or (= :all-of-the-action only) (contains? only op)))

(defn find-sentences-of-action
  "The sentences of a table that allow an action."
  [table action]
  (filter #(contains? (find-allowed-actions %) action) (when (sequential? (:access table)) (:access table))))

(defn is-system-sentence?
  "Whether a sentence gives its actions to a named system actor."
  [{:keys [to]}]
  (and (vector? to) (= :system (first to))))

(defn find-way-of-who
  "How a who word can be had without a row to look at: :anyone,
   :signed-in, :visitor (a [:me …] of a visitor column), [:holds action]
   (a member holding that action somewhere), or [:system name] (only the
   named system actor, never a person or a visitor)."
  [to action table]
  (cond (= :anyone to)       :anyone
        (= :signed-in to)    :signed-in
        (= :system (first to)) to
        (= :me (first to))   (if (= (second to) (find-visitor-column table)) :visitor :signed-in)
        (= :member (first to)) [:holds (find-held-action to action)]))

(defn find-way-of-sentence
  "find-way-of-who of a sentence's :to, or of its via's :to on the related table."
  [{:keys [to via]} action table tables-by-key]
  (if via
    (find-way-of-who (:to via) action (get tables-by-key (:table via)))
    (find-way-of-who to action table)))

(defn find-ways-of-actions
  "{action #{ways}}: how each action can be had, from every table's
   sentences (find-way-of-sentence). A :when narrows which rows, not who."
  [tables]
  (let [tables-by-key (into {} (map (juxt :table identity)) tables)]
    (reduce (fn [ways [table sentence]]
              (reduce #(update %1 %2 (fnil conj #{}) (find-way-of-sentence sentence %2 table tables-by-key))
                      ways (find-allowed-actions sentence)))
            {}
            (for [{:keys [access] :as table} tables :when (sequential? access)
                  sentence access]
              [table sentence]))))

(defn find-open-actions
  "The actions someone can have without a membership: {:anyone #{…}
   :signed-in #{…} :visitor #{…}}, by their ways (find-ways-of-actions)."
  [tables]
  (reduce (fn [open [action ways]]
            (cond-> open
              (contains? ways :anyone)    (update :anyone conj action)
              (contains? ways :signed-in) (update :signed-in conj action)
              (contains? ways :visitor)   (update :visitor conj action)))
          {:anyone #{} :signed-in #{} :visitor #{}}
          (find-ways-of-actions tables)))
