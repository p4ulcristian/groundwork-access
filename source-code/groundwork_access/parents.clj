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

(defn find-problems-of-parent
  "A table's :parent, checked: [problem …]."
  [{:keys [table parent] :as table-map} tables-by-key]
  (when (contains? table-map :parent)
    (let [columns  (set (map first (:columns table-map)))
          optional #(:optional? (find-column-options %1 %2))]
      (if-not (and (map? parent) (= #{:column :carries} (set (keys parent)))
                   (map? (:carries parent)) (seq (:carries parent)))
        [(str table ": :parent is {:column <the column naming the parent> :carries {<its column> <the parent's column> …}}, one carried at least")]
        (let [{:keys [column carries]} parent
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
  "The statement that makes the fill function: the carried columns set
   from the parent's row, read past the gates; a row whose parent column
   is null, or names no row, is left as it is (the references refuse it)."
  [{{:keys [column]} :parent :as table-map} tables-by-key]
  (let [parent-table (find-parent-table table-map tables-by-key)
        pairs        (map-indexed (fn [i [mine theirs]] {:mine mine :theirs theirs :local (str "carried_" (inc i))})
                                  (find-carried-pairs table-map))
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
