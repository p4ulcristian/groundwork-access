(ns groundwork-access.parents
  "A record under a record carries its parent's columns: a job carries
   its order's shop, so its sentences compare its own column, one direct
   comparison, however deep it sits. The table map says it:

     {:table   :jobs
      :columns [[:job/id       :uuid {:primary-key? true}]
                [:job/order-id :uuid {:references {:to :order/id :on-delete :cascade    ; the parent
                                                   :on-update :refuse :checked-at :statement}}]
                [:job/shop-id  :uuid {:references {:to :shop/id :on-delete :refuse      ; carried from it
                                                   :on-update :refuse :checked-at :statement}}]]
      :indexes [{:columns [[:job/order-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
      :uniques []
      :checks  []
      :parent  {:column :job/order-id :carries {:job/shop-id :order/shop-id}}
      :access  [{:allow :jobs/see :to [:member :job/shop-id]}]}

   groundwork-access keeps the carried columns equal to the parent's, with groundwork-postgres:

   - the link: a reference over several columns, the parent column and the
     carried ones pointing at the parent's id and columns, ON UPDATE
     CASCADE: a parent's change is carried to its children by Postgres,
     past the gates (a referential action runs as the table's owner), in
     the same statement, and a child can hold no other value. On a
     parent's delete, and when it is checked, the link does what the
     parent column's own reference says: :cascade deletes the children
     with their parent, :refuse and :restrict refuse a parent that has
     any (:set-null can't hold a NOT NULL column, so it is refused at the
     start). The map doesn't say the link among its own :references;
   - the fill: a trigger before each insert, and each update of the parent
     column, sets the carried columns from the parent, read past the gates
     (groundwork_access.fill_…()), so the app's SQL never names them.

   A carried column that may be empty (an order's customer: a guest's
   order has none, until she signs in and claims it) is said apart,
   :carries-optional {:job/customer-id :order/customer-id}. It is no part of
   the link (a reference holds nothing over an empty column); the fill sets
   it on insert as it sets the others, and a trigger on the parent after a
   change of its column carries the change down to the children
   (groundwork_access.follow_…()), past the gates: the follow's own update
   is let through the children's write gate while it runs
   (groundwork_access.carrying, is_carrying()), so a child the asker may
   not write still follows its parent.

   A carried column may be a scope's (several, for a record of two scopes)
   or a person's. The parent column, the carried columns and the parent's
   carried ones are NOT NULL: the link holds only then. A record under a
   record under a record carries from its parent's carried column, and the
   cascade runs down the chain."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.sentences :as sentences]))

(defn- find-column-options [{:keys [columns]} column-key]
  (some (fn [[k _ options]] (when (= k column-key) (or options {}))) columns))

(defn find-parent-table
  "The table the :parent column references (its one-column primary key),
   or nil."
  [table tables-by-key]
  (let [target (get-in (find-column-options table (get-in table [:parent :column])) [:references :to])]
    (when target
      (some (fn [[_ other]] (when (= [target] (sentences/find-primary-key-columns other)) other)) tables-by-key))))

(defn- find-parent-reference
  "The parent column's own :references, or nil."
  [table-map]
  (:references (find-column-options table-map (get-in table-map [:parent :column]))))

(defn- find-carried-pairs
  "The :carries as [[child column, parent column] …], in a stable order."
  [{{:keys [carries]} :parent}]
  (vec (sort-by (comp str first) carries)))

(defn find-optional-pairs
  "The :carries-optional as [[child column, parent column] …], in a stable
   order; [] for none."
  [{{:keys [carries-optional]} :parent}]
  (vec (sort-by (comp str first) carries-optional)))

(defn find-problems-of-parent
  "A table's :parent, checked: [problem …]."
  [{:keys [table parent] :as table-map} tables-by-key]
  (when (contains? table-map :parent)
    (let [columns  (set (map first (:columns table-map)))
          optional #(:optional? (find-column-options %1 %2))]
      (if-not (and (map? parent) (contains? #{#{:column :carries} #{:column :carries :carries-optional}} (set (keys parent)))
                   (map? (:carries parent)) (seq (:carries parent))
                   (or (not (contains? parent :carries-optional))
                       (and (map? (:carries-optional parent)) (seq (:carries-optional parent)))))
        [(str table ": :parent is {:column <the column naming the parent> :carries {<its column> <the parent's column> …}}, one carried at least,"
              " and :carries-optional {<its column> <the parent's column> …} beside them for columns that may be empty")]
        (let [{:keys [column carries carries-optional]} parent
              parent-table (find-parent-table table-map tables-by-key)]
          (concat
           (when-not (contains? columns column)
             [(str table ": :parent's column " column " is no column of it")])
           (when (and (contains? columns column) (nil? parent-table))
             [(str table ": :parent's column " column " must reference another table's one-column primary key, the parent's id")])
           (when (and (contains? columns column) (optional table-map column))
             [(str table ": :parent's column " column " is optional; a record under a record has its parent (NOT NULL)")])
           (when (and (contains? columns column) (not (sentences/is-indexed? table-map [column])))
             [(str table " needs an index on [" column "], what a parent's change is carried down by")])
           (let [on-delete (:on-delete (find-parent-reference table-map))]
             (when (and parent-table (not (contains? #{:cascade :refuse :restrict} on-delete)))
               [(str table ": :parent's column " column " says :on-delete " (pr-str on-delete)
                     "; the link to the parent deletes as that column's reference says, and a NOT NULL column can't be set to null:"
                     " say :cascade (the children go with their parent), :refuse or :restrict (a parent with children stays)")]))
           (for [{own-columns :columns} (:references table-map)
                 :when (= (into #{column} (keys carries)) (set own-columns))]
             (str table ": its :references say the link to the parent over " (pr-str own-columns)
                  ", which groundwork-access makes from :parent, deleting as the parent column's reference says: leave it out"))
           (for [[mine theirs] carries-optional
                 problem (cond
                           (not (contains? columns mine))
                           [(str table ": :parent carries " mine " (optional), no column of it")]
                           (or (= mine column) (contains? carries mine))
                           [(str table ": :parent carries " mine " both ways: the parent column and :carries are never :carries-optional")]
                           (nil? parent-table)
                           []
                           (not (some #(= theirs (first %)) (:columns parent-table)))
                           [(str table ": :parent carries " mine " (optional) from " theirs ", no column of " (:table parent-table))]
                           (not= (sentences/find-column-type table-map mine) (sentences/find-column-type parent-table theirs))
                           [(str table ": " mine " and " theirs " are of different types")]
                           (and (optional parent-table theirs) (not (optional table-map mine)))
                           [(str table ": " mine " is NOT NULL, but " theirs " may be empty in " (:table parent-table)
                                 ": a child of a parent without one couldn't be written; make " mine " optional")]
                           :else [])]
             problem)
           (for [[mine theirs] carries
                 problem (cond
                           (not (contains? columns mine))
                           [(str table ": :parent carries " mine ", no column of it")]
                           (= mine column)
                           [(str table ": :parent carries its own parent column " mine)]
                           (optional table-map mine)
                           [(str table ": the carried " mine " is optional; it holds its parent's value, NOT NULL")]
                           (nil? parent-table)
                           []
                           (not (some #(= theirs (first %)) (:columns parent-table)))
                           [(str table ": :parent carries " mine " from " theirs ", no column of " (:table parent-table))]
                           (optional parent-table theirs)
                           [(str table ": " theirs " is optional in " (:table parent-table) "; a carried column is NOT NULL there too")]
                           (not= (sentences/find-column-type table-map mine) (sentences/find-column-type parent-table theirs))
                           [(str table ": " mine " and " theirs " are of different types")]
                           :else [])]
             problem)))))))

(defn find-fill-function-name
  "The function the fill trigger calls, named by a hash of what it fills."
  [{:keys [table parent]}]
  (str "groundwork_access.fill_" (format "%08x" (bit-and (hash [table parent]) 0xffffffff))))

(defn make-link
  "The reference over several columns that ties the carried columns to
   the parent's (groundwork-postgres's :references entry): a parent's
   change carried down (ON UPDATE CASCADE); its delete, and when the link
   is checked, as the parent column's own reference says."
  [{{:keys [column]} :parent :as table-map} tables-by-key]
  (let [pairs                          (find-carried-pairs table-map)
        {:keys [on-delete checked-at]} (find-parent-reference table-map)]
    {:columns    (into [column] (map first pairs))
     :to         (into (sentences/find-primary-key-columns (find-parent-table table-map tables-by-key)) (map second pairs))
     :on-delete  on-delete
     :on-update  :cascade
     :checked-at checked-at}))

(defn make-fill-trigger
  "The trigger that fills the carried columns (groundwork-postgres's :triggers entry)."
  [{{:keys [column]} :parent :as table-map}]
  {:function  (find-fill-function-name table-map)
   :arguments []
   :timing    :before
   :events    #{:insert :update}
   :update-of [column]
   :for-each  :row
   :when      nil})

(defn put-link-and-fill-on-table
  "A table map with a :parent given its link and its fill, beside the
   references and triggers it says itself; any other as it is."
  [table-map tables-by-key]
  (if (:parent table-map)
    (-> table-map
        (update :references #(conj (vec %) (make-link table-map tables-by-key)))
        (update :triggers #(conj (vec %) (make-fill-trigger table-map))))
    table-map))

(defn make-fill-function-statement
  "The statement that makes the fill function: the carried columns, the
   optional ones too, set from the parent's row, read past the gates; a
   row whose parent column is null, or names no row, is left as it is (the
   references refuse it)."
  [{{:keys [column]} :parent :as table-map} tables-by-key]
  (let [parent-table (find-parent-table table-map tables-by-key)
        pairs        (map-indexed (fn [i [mine theirs]] {:mine mine :theirs theirs :local (str "carried_" (inc i))})
                                  (into (find-carried-pairs table-map) (find-optional-pairs table-map)))
        parent-id    (first (sentences/find-primary-key-columns parent-table))
        own          (sentences/column-name column)]
    (str "CREATE OR REPLACE FUNCTION " (find-fill-function-name table-map) "()\n"
         "RETURNS trigger LANGUAGE plpgsql AS $$\n"
         "DECLARE before text DEFAULT coalesce(current_setting('groundwork_access.passing', true), '');\n"
         (str/join (for [{:keys [mine local]} pairs]
                     (str "        " local " " (postgres/turn-column-type-into-postgres-type (sentences/find-column-type table-map mine)) ";\n")))
         "BEGIN\n"
         "  IF NEW." own " IS NOT NULL THEN\n"
         "    PERFORM set_config('groundwork_access.passing', 'on', true);\n"
         "    SELECT " (str/join ", " (map #(str "p." (sentences/column-name (:theirs %))) pairs))
         " INTO " (str/join ", " (map :local pairs))
         " FROM " (postgres/turn-table-key-into-table-name (:table parent-table)) " p"
         " WHERE p." (sentences/column-name parent-id) " = NEW." own ";\n"
         "    IF FOUND THEN\n"
         (str/join (for [{:keys [mine local]} pairs]
                     (str "      NEW." (sentences/column-name mine) " := " local ";\n")))
         "    END IF;\n"
         "    PERFORM set_config('groundwork_access.passing', before, true);\n"
         "  END IF;\n"
         "  RETURN NEW;\n"
         "END $$")))

(defn find-follow-function-name
  "The function a parent's follow trigger calls, named by a hash of what it
   carries down."
  [{:keys [table parent]}]
  (str "groundwork_access.follow_" (format "%08x" (bit-and (hash [table parent :follow]) 0xffffffff))))

(defn make-follow-trigger
  "The trigger on the parent that carries a change of its optional carried
   columns down to the children (groundwork-postgres's :triggers entry)."
  [table-map]
  {:function  (find-follow-function-name table-map)
   :arguments []
   :timing    :after
   :events    #{:update}
   :update-of (vec (distinct (map second (find-optional-pairs table-map))))
   :for-each  :row
   :when      nil})

(defn put-follows-on-parents
  "The table maps, each parent of a record with :carries-optional given
   the trigger that carries a change down to it, beside its own triggers."
  [tables tables-by-key]
  (let [follows (group-by #(:table (find-parent-table % tables-by-key))
                          (filter #(seq (get-in % [:parent :carries-optional])) tables))]
    (mapv (fn [{:keys [table] :as table-map}]
            (if-let [children (seq (get follows table))]
              (update table-map :triggers #(into (vec %) (map make-follow-trigger) (sort-by (comp str :table) children)))
              table-map))
          tables)))

(defn make-follow-function-statement
  "The statement that makes the follow function: after a parent's change
   of a column its children carry where set, the children's columns set to
   the parent's new values, past the gates (read: groundwork_access.passing;
   write: groundwork_access.carrying, which only these children's write
   gate lets through), both back to what they were after."
  [{{:keys [column]} :parent :keys [table] :as table-map} tables-by-key]
  (let [parent-table (find-parent-table table-map tables-by-key)
        pairs        (find-optional-pairs table-map)
        parent-id    (sentences/column-name (first (sentences/find-primary-key-columns parent-table)))
        theirs       (distinct (map (comp sentences/column-name second) pairs))
        row-of       (fn [prefix cols] (str "(" (str/join ", " (map #(str prefix %) cols)) ")"))]
    (str "CREATE OR REPLACE FUNCTION " (find-follow-function-name table-map) "()\n"
         "RETURNS trigger LANGUAGE plpgsql AS $$\n"
         "DECLARE passing_before text DEFAULT coalesce(current_setting('groundwork_access.passing', true), '');\n"
         "        carrying_before text DEFAULT coalesce(current_setting('groundwork_access.carrying', true), '');\n"
         "BEGIN\n"
         "  IF ROW" (row-of "NEW." theirs) " IS DISTINCT FROM ROW" (row-of "OLD." theirs) " THEN\n"
         "    PERFORM set_config('groundwork_access.passing', 'on', true);\n"
         "    PERFORM set_config('groundwork_access.carrying', 'on', true);\n"
         "    UPDATE " (postgres/turn-table-key-into-table-name table)
         " SET " (str/join ", " (for [[mine theirs] pairs] (str (sentences/column-name mine) " = NEW." (sentences/column-name theirs))))
         " WHERE " (sentences/column-name column) " = NEW." parent-id
         " AND ROW" (row-of "" (map (comp sentences/column-name first) pairs))
         " IS DISTINCT FROM ROW" (row-of "NEW." (map (comp sentences/column-name second) pairs)) ";\n"
         "    PERFORM set_config('groundwork_access.carrying', carrying_before, true);\n"
         "    PERFORM set_config('groundwork_access.passing', passing_before, true);\n"
         "  END IF;\n"
         "  RETURN NULL;\n"
         "END $$")))
