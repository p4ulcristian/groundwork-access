(ns groundwork-access.rows-of
  "A param that says it is a row of a table, by a mark on its shape in a
   handler's :params-shape:

     :params-shape [:map
                    [:batch/id       :uuid]
                    [:workstation-id [:uuid {:groundwork-access/row-of :workstations}]]
                    [:part-ids       [:vector [:uuid {:groundwork-access/row-of :parts}]]]]

   Before any step, every marked id must be a row the handler's action may
   read in the scopes it acts in (the table's sentences for that action
   that allow :read, as the gate reads them), or the message is refused
   :groundwork-access/not-found. groundwork-access reads its mark through a :map (its keys), a
   :vector, :sequential or :set (each element), a :maybe and an :and (each
   of its shapes); a mark anywhere else stops the start, named, as does a
   marked table that isn't one of the tables, has no one-column primary
   key of the mark's type, or has no sentence letting the action read it.
   A marked param that is nil or missing is no row to check."
  (:require
   [groundwork-postgres.api :as postgres]
   [malli.core :as malli]
   [groundwork-access.scope-kinds :as scope-kinds]
   [groundwork-access.sentences :as sentences]))

(def mark :groundwork-access/row-of)

(defn- turn-into-form [shape]
  (if (malli/schema? shape) (malli/form shape) shape))

(defn- split-form
  "[type properties children] of a form: :uuid, [:uuid {…}], [:map {…} …]."
  [form]
  (if (vector? form)
    (let [[type & more] form]
      (if (map? (first more))
        [type (first more) (vec (rest more))]
        [type nil (vec more)]))
    [form nil []]))

(def ^:private reaching-types
  "The shapes groundwork-access reads its mark through."
  #{:map :vector :sequential :set :maybe :and})

(defn- split-entry
  "[key properties shape] of a :map entry."
  [entry]
  (if (map? (second entry))
    [(first entry) (second entry) (nth entry 2 nil)]
    [(first entry) nil (second entry)]))

(defn- find-reached-marks
  "Every mark the walk reaches: [{:path [step …] :table :type :form}], a
   step a map key or :groundwork-access/each (every element)."
  [form path]
  (let [[type properties children] (split-form (turn-into-form form))]
    (concat
     (when (contains? properties mark)
       [{:path path :table (get properties mark) :type type :form form}])
     (case type
       :map                     (mapcat (fn [entry]
                                          (let [[k _ shape] (split-entry entry)]
                                            (find-reached-marks shape (conj path k))))
                                        children)
       (:vector :sequential :set) (when-let [child (first children)] (find-reached-marks child (conj path :groundwork-access/each)))
       :maybe                   (when-let [child (first children)] (find-reached-marks child path))
       :and                     (mapcat #(find-reached-marks % path) children)
       nil))))

(defn- count-marks-anywhere [form]
  (count (filter #(and (map? %) (contains? % mark)) (tree-seq coll? seq (turn-into-form form)))))

(defn find-marks
  "Every mark of a params shape the walk reaches."
  [params-shape]
  (vec (find-reached-marks params-shape [])))

(defn find-problems-of-marks
  "A handler's marks, checked: each one reached, on an id's shape, of a
   table that is one of the tables, with a one-column primary key of the
   mark's type, and readable by the handler's action (reads, its
   condition of reading the table). [problem …]."
  [handler-name {:keys [action params-shape]} tables scope-kinds reads]
  (let [marks         (find-marks params-shape)
        tables-by-key (into {} (map (juxt :table identity)) tables)]
    (concat
     (when (< (count marks) (count-marks-anywhere params-shape))
       [(str handler-name ": a " mark " in :params-shape that groundwork-access can't reach; it reads one through "
             ":map, :vector, :sequential, :set, :maybe and :and, on an id's shape: [:uuid {" mark " :table}]")])
     (for [{:keys [path table type]} marks
           :let [where   (str handler-name ": the " mark " " (pr-str table) " at " (pr-str path))
                 table-map (get tables-by-key table)
                 pk      (when table-map (sentences/find-primary-key-columns table-map))
                 pk-type (when (= 1 (count pk)) (sentences/find-column-type table-map (first pk)))
                 problem (cond
                           (not (keyword? table))
                           (str where ": names a table by its key, like :workstations")
                           (contains? reaching-types type)
                           (str where ": sits on a " type "; put it on the id's shape, [:uuid {" mark " " table "}]")
                           (nil? table-map)
                           (str where ": " table " is none of the tables")
                           (not= 1 (count pk))
                           (str where ": " table " needs a one-column primary key, the row's id")
                           (not= pk-type type)
                           (str where ": the param is " (pr-str type) ", and " table "'s id is " (pr-str pk-type))
                           (not (contains? (scope-kinds/find-what-action-does scope-kinds action) :read))
                           (str where ": " action " doesn't read, so it can't read a row of " table)
                           (nil? (get reads table))
                           (str where ": no sentence on " table " lets " action " read it"))]
           :when problem]
       problem))))

(defn- find-at-path
  "[[concrete-path value] …] for a mark's path in the data: a map key
   taken where the map has it, every element of a list or set; nil
   values are none."
  [data path concrete]
  (cond
    (nil? data)        []
    (empty? path)      [[concrete data]]
    :else
    (let [[step & more] path]
      (if (= :groundwork-access/each step)
        (if (or (sequential? data) (set? data))
          (mapcat (fn [i x] (find-at-path x more (conj concrete i))) (range) data)
          [])
        (if (and (map? data) (contains? data step))
          (find-at-path (get data step) more (conj concrete step))
          [])))))

(defn find-marked-ids
  "Every id the marks name in the params: [{:path [:part-ids 0] :table :parts :id …}]."
  [marks params]
  (vec (for [{:keys [path table]} marks
             [concrete id] (find-at-path params path [])]
         {:path concrete :table table :id id})))

(defn make-readable-queries
  "{table query}: for each marked table, whether a row is one the
   action's read condition lets through, for the asker as told."
  [marks tables reads action]
  (into {}
        (for [table (distinct (map :table marks))
              :let [table-map (some #(when (= table (:table %)) %) tables)
                    pk        (sentences/column-name (first (sentences/find-primary-key-columns table-map)))]]
          [table (postgres/make-query-from-sql-text
                  (str "whether " action " reads a row of " table)
                  (str "SELECT EXISTS (SELECT 1 FROM " (postgres/turn-table-key-into-table-name table)
                       " WHERE " pk " = param('row/id') AND (" (get reads table) ")) AS row__readable"))])))

(defn find-readable
  "The marked ids, each with whether it is readable now:
   [{:path :table :id :readable? bool}]."
  [transaction queries marked-ids]
  (mapv (fn [{:keys [table id] :as marked}]
          (assoc marked :readable? (boolean (:row/readable (postgres/find-one-row transaction (get queries table) {:row/id id})))))
        marked-ids))
