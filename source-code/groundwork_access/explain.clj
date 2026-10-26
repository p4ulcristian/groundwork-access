(ns groundwork-access.explain
  "Why a message would be refused, as plain data, for a REPL: the action
   it needs, what the asker holds where the record is, each sentence that
   gives the action and which of its parts holds, and the denies.

   It reads in one read-only transaction that tells Postgres who is asking
   (as the check does) and reads past the gates (groundwork_access.passing), so it
   sees the record even when the asker can't; each part is the same SQL
   the check and the gates run."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.acting :as acting]
   [groundwork-access.asking :as asking]
   [groundwork-access.check :as check]
   [groundwork-access.conditions :as conditions]
   [groundwork-access.memberships :as memberships]
   [groundwork-access.rows-of :as rows-of]
   [groundwork-access.scope-kinds :as scope-kinds]
   [groundwork-access.sentences :as sentences]))

(defn- ask-one [transaction what sql params]
  (postgres/find-one-row transaction (postgres/make-query-from-sql-text what sql) params))

(defn- find-row [transaction table id]
  (let [pk (first (sentences/find-primary-key-columns table))]
    (ask-one transaction (str "the row of " (:table table))
             (str "SELECT * FROM " (postgres/turn-table-key-into-table-name (:table table))
                  " WHERE " (sentences/column-name pk) " = param('explain/id')")
             {:explain/id id})))

(defn- does-sql-hold? [transaction table id sql]
  (let [pk (first (sentences/find-primary-key-columns table))]
    (boolean (:explain/holds (ask-one transaction "whether a part holds"
                                      (str "SELECT (" sql ") IS TRUE AS explain__holds FROM "
                                           (postgres/turn-table-key-into-table-name (:table table))
                                           " WHERE " (sentences/column-name pk) " = param('explain/id')")
                                      {:explain/id id})))))

(defn- find-row-scopes
  "The scopes the row's columns name: [{:column :scope-kind :scope-id}],
   its own primary key on a scope kind's table among them (the row is the
   scope)."
  [table row {:keys [tables-by-key scope-kinds]}]
  (vec (for [[column] (:columns table)
             :let [kind (sentences/find-kind-of-member-column table column tables-by-key scope-kinds)]
             :when kind]
         {:column column :scope-kind kind :scope-id (get row column)})))

(defn- find-roles-giving
  "The roles the person holds in the scope, or in a scope above it, that
   give one of the actions there: [{:scope-kind :scope-id :role-id :name
   :gives action}], the scope's own first. A role above gives only the
   actions of the scope's kind and the kinds below it."
  [transaction scope-kinds person-id [kind scope-id] actions]
  (if (and person-id scope-id)
    (let [reaching (scope-kinds/find-actions-at-or-below scope-kinds kind)]
      (vec (for [{:keys [depth roles] :as membership} (memberships/list-memberships-above transaction scope-kinds [kind scope-id] person-id)
                 {:keys [role-id name] role-actions :actions} roles
                 action (sort-by str actions)
                 :when (and (contains? role-actions action) (or (zero? depth) (contains? reaching action)))]
             {:scope-kind (:scope-kind membership) :scope-id (:scope-id membership) :role-id role-id :name name :gives action})))
    []))

(defn- explain-to
  "What a :to word says of the row, and whether it holds; a [:member …]
   names the roles that give what it needs (:given-by)."
  [to action table row actions-by-scope {:keys [tables-by-key scope-kinds transaction person-id]} holds?]
  (cond
    (#{:anyone :signed-in} to) {:says to :holds? holds?}
    (= :system (first to)) {:says to :holds? holds? :system (second to)}
    (= :me (first to))
    {:says to :holds? holds? :column (second to) :value (get row (second to))
     :asker-is (if (= (second to) (sentences/find-visitor-column table)) :visitor :person)}
    (= :member (first to))
    (let [[_ x] to
          needs (sentences/find-held-action to action)]
      (if (sentences/is-column-word? x)
        (let [kind  (sentences/find-kind-of-member-column table x tables-by-key scope-kinds)
              scope [kind (get row x)]]
          {:says to :holds? holds? :scope-kind kind :scope-id (get row x) :needs needs
           :held-there? (contains? (get actions-by-scope scope) needs)
           :given-by (find-roles-giving transaction scope-kinds person-id scope #{needs})})
        {:says to :holds? holds? :scope-kind x :needs needs
         :held-there? (boolean (some (fn [[[kind] actions]] (and (= kind x) (contains? actions needs))) actions-by-scope))
         :given-by (vec (mapcat #(find-roles-giving transaction scope-kinds person-id % #{needs})
                                (filter #(= x (first %)) (sort-by str (keys actions-by-scope)))))}))))

(defn- explain-sentence [transaction table id row sentence action actions-by-scope place]
  (let [place     (assoc place :table table)
        {:keys [to via] condition :when} sentence
        part-sql  (if via (sentences/turn-via-into-sql via place) (sentences/turn-who-into-sql to action table place))]
    (cond-> {:table    (:table table)
             :sentence sentence
             :holds?   (does-sql-hold? transaction table id (sentences/turn-sentence-into-sql sentence action place))}
      to        (assoc :to (explain-to to action table row actions-by-scope place (does-sql-hold? transaction table id part-sql)))
      via       (assoc :via {:says via :holds? (does-sql-hold? transaction table id part-sql)})
      condition (assoc :when {:says condition :holds? (does-sql-hold? transaction table id (sentences/turn-when-into-sql condition))}))))

(defn- find-denied-ids [transaction deny]
  (set (map :scope/id (postgres/find-rows transaction (postgres/make-query-from-sql-text
                                                     "the scopes in a denied state"
                                                     (str "SELECT " (conditions/find-denied-function-name deny) "() AS scope__id"))
                                        {}))))

(defn- explain-denies
  "Each deny of the action: whether it says no here (a session flag the
   asker's session has; a scope of the row in the denied state)."
  [transaction scope-kinds action session-flags row-scopes]
  (vec (for [{:keys [kind deny session] :as read-deny} (scope-kinds/find-denies scope-kinds)
             :when (scope-kinds/does-deny-apply? read-deny action nil)]
         (if session
           {:scope-kind kind :deny deny :says-no? (contains? session-flags session)}
           (let [denied (find-denied-ids transaction read-deny)
                 hits   (filter #(and (= kind (:scope-kind %)) (contains? denied (:scope-id %))) row-scopes)]
             {:scope-kind kind :deny deny :says-no? (boolean (seq hits)) :scope-ids (mapv :scope-id hits)})))))

(defn- explain-memberships
  "The asker's memberships of each scope the row names and of those above
   it, each with the roles held there and their actions ([] for none
   yet)."
  [transaction scope-kinds person-id row-scopes]
  (when person-id
    (vec (distinct (for [{:keys [scope-kind scope-id]} row-scopes :when scope-id
                         {:keys [member? roles] :as membership} (memberships/list-memberships-above transaction scope-kinds [scope-kind scope-id] person-id)
                         :when member?]
                     {:scope-kind (:scope-kind membership) :scope-id (:scope-id membership)
                      :roles      (mapv #(update % :actions (fn [actions] (vec (sort-by str actions)))) roles)})))))

(defn- find-answer [transaction table id action conditions scope-kinds]
  (let [action-sql (get-in conditions [(:table table) :actions action])
        reads      (for [[other sql] (get-in conditions [(:table table) :actions])
                         :when (contains? (scope-kinds/find-what-action-does scope-kinds other) :read)]
                     sql)]
    (cond (does-sql-hold? transaction table id action-sql) :allowed
          (and (seq reads) (does-sql-hold? transaction table id (str/join " OR " (map #(str "(" % ")") reads)))) :groundwork-access/not-allowed
          :else :groundwork-access/not-found)))

(defn- explain-record [transaction {:keys [tables scope-kinds]} conditions asker action [table-key id-key] params actions-by-scope]
  (let [place      {:tables-by-key (into {} (map (juxt :table identity)) tables) :scope-kinds scope-kinds
                    :transaction transaction :person-id (:person-id asker)}
        table      (get-in place [:tables-by-key table-key])
        id         (get params id-key)
        row        (when id (find-row transaction table id))
        row-scopes (when row (find-row-scopes table row place))]
    {:record            {:table table-key :id id :exists? (some? row) :scopes (or row-scopes [])}
     :answer            (if row (find-answer transaction table id action conditions scope-kinds) :groundwork-access/not-found)
     :memberships       (explain-memberships transaction scope-kinds (:person-id asker) row-scopes)
     :actions-in-scopes (vec (for [{:keys [scope-kind scope-id]} row-scopes :when scope-id]
                               {:scope-kind scope-kind :scope-id scope-id
                                :actions (vec (sort-by str (get actions-by-scope [scope-kind scope-id])))}))
     :sentences         (if row
                          (mapv #(explain-sentence transaction table id row % action actions-by-scope place)
                                (sentences/find-sentences-of-action table action))
                          [])
     :denies            (explain-denies transaction scope-kinds action (:session-flags asker)
                                        (or row-scopes []))}))

(defn- explain-named-scope
  "For a :scope [kind param] handler: the scope the param names, whether
   the action is held there, the roles that give it (or what one of its
   [:member …] sentences needs held there), and the check's answer."
  [transaction scope-kinds needs asker action [kind param] params actions-by-scope]
  (let [scope-id (get params param)
        held?    (and (uuid? scope-id) (acting/is-held-there? needs action [kind scope-id] actions-by-scope))
        state    (when held? (check/find-state-deny transaction scope-kinds action [kind scope-id]))
        giving   (into #{action} (for [{need-kind :kind :keys [held]} (get needs action) :when (= kind need-kind)] held))]
    {:scope  {:says [kind param] :scope-kind kind :scope-id scope-id :held-there? held?
              :given-by (if (uuid? scope-id) (find-roles-giving transaction scope-kinds (:person-id asker) [kind scope-id] giving) [])}
     :answer (cond (not held?)                                                 :groundwork-access/not-found
                   (check/find-flag-deny scope-kinds action (:session-flags asker)) :groundwork-access/not-allowed
                   state                                                       :groundwork-access/not-allowed
                   :else                                                       :allowed)}))

(defn- explain-no-record [transaction {:keys [tables scope-kinds]} asker {:keys [action scope]} params actions-by-scope]
  (let [tables-by-key (into {} (map (juxt :table identity)) tables)
        context       {:groundwork-access/person-id (:person-id asker) :groundwork-access/visitor-id (:visitor-id asker)
                       :groundwork-access/actions actions-by-scope}
        explained     (vec (for [table tables
                                 sentence (sentences/find-sentences-of-action table action)
                                 :let [way (sentences/find-way-of-sentence sentence action table tables-by-key)]]
                             {:table (:table table) :sentence sentence :way way
                              :holds? (and (contains? (check/find-counted-ways #{way} scope) way)
                                           (check/does-way-hold? way context))}))
        flag-deny     (check/find-flag-deny scope-kinds action (:session-flags asker))
        named         (when (vector? scope)
                        (explain-named-scope transaction scope-kinds (acting/find-needs tables scope-kinds)
                                             asker action scope params actions-by-scope))]
    {:record            nil
     :scope             (if named (:scope named) {:says scope})
     :answer            (cond named                       (:answer named)
                              (not-any? :holds? explained) :groundwork-access/not-found
                              flag-deny                   :groundwork-access/not-allowed
                              :else                       :allowed)
     :memberships       (when (and named (uuid? (:scope-id (:scope named))))
                          (explain-memberships transaction scope-kinds (:person-id asker)
                                               [{:scope-kind (first scope) :scope-id (:scope-id (:scope named))}]))
     :actions-in-scopes (vec (for [[[kind id] actions] (sort-by (comp str key) actions-by-scope)]
                               {:scope-kind kind :scope-id id :actions (vec (sort-by str actions))}))
     :sentences         explained
     :denies            (explain-denies transaction scope-kinds action (:session-flags asker)
                                        (if (and named (:held-there? (:scope named)))
                                          [{:column nil :scope-kind (first scope) :scope-id (:scope-id (:scope named))}]
                                          []))}))

(defn- find-scopes-acted-in
  "The scopes the handler's steps would act in, once the check let it
   through: as the check finds them (check.clj)."
  [{:keys [record scope]} explained actions-by-scope sideways]
  (cond record          (concat (for [{:keys [scope-kind scope-id]} (get-in explained [:record :scopes]) :when scope-id]
                                  [scope-kind scope-id])
                                (filter #(contains? sideways (first %)) (keys actions-by-scope)))
        (vector? scope) [[(first scope) (get-in explained [:scope :scope-id])]]
        (= :no-scope scope) []
        :else           (keys actions-by-scope)))

(defn- explain-rows-of
  "The marked params ([:uuid {:groundwork-access/row-of …}]), each with whether the
   action reads its row in the scopes the handler acts in, Postgres told
   as the check tells it; and the answer, :groundwork-access/not-found when one
   isn't. [] for a handler with no mark; nil when the check refuses
   before the marks."
  [transaction {:keys [tables scope-kinds]} conditions asker {:keys [action params-shape] :as handler} params actions-by-scope explained]
  (let [marks (rows-of/find-marks params-shape)]
    (if-not (and (seq marks) (= :allowed (:answer explained)))
      (assoc explained :rows-of (if (seq marks) nil []))
      (let [needs    (acting/find-needs tables scope-kinds)
            scopes   (find-scopes-acted-in handler explained actions-by-scope (acting/find-sideways-kinds needs action))
            queries  (rows-of/make-readable-queries marks tables (check/find-reads-of-action conditions action) action)
            _        (asking/set-asking! transaction asker (acting/make-telling needs action scopes actions-by-scope))
            readable (rows-of/find-readable transaction queries (rows-of/find-marked-ids marks params))]
        (asking/set-asking! transaction asker {:acting :every-action :holding actions-by-scope})
        (cond-> (assoc explained :rows-of readable)
          (not-every? :readable? readable) (assoc :answer :groundwork-access/not-found))))))

(defn- name-answer
  "The check's answer: :allowed, or the reason it refuses with, the same
   as the check's (check/find-refusal-reason): :groundwork-access/signed-out when
   nobody is signed in."
  [answer person-id]
  (if (= :allowed answer) answer (check/find-refusal-reason answer person-id)))

(defn explain-refusal
  "The explanation, plain data; the options and the asker checked already.
   Its :answer is :allowed or the check's reason, :groundwork-access/not-found,
   :groundwork-access/not-allowed or :groundwork-access/signed-out; a marked param's row the
   action doesn't read makes it :groundwork-access/not-found (:rows-of)."
  [{:keys [pool handlers tables scope-kinds] :as options} asker handler-name params]
  (let [{:keys [action record] :as handler} (get handlers handler-name)]
    (if-not handler
      {:handler handler-name :answer :no-such-handler}
      (let [conditions (conditions/make-sql-conditions tables scope-kinds)]
        ;; groundwork-access's own, read-only: keeps no work
        (postgres/run-in-transaction! pool {:read-only? true :log nil}
                                    (fn [transaction]
                                      (let [actions-by-scope (asking/set-asking-with-every-action! transaction scope-kinds asker)]
                                        (asking/set-passing! transaction)
                                        (as-> (merge {:handler handler-name
                                                      :action  action
                                                      :writes? (scope-kinds/does-action-write? scope-kinds action)
                                                      :asker   asker}
                                                     (if record
                                                       (assoc (explain-record transaction options conditions asker action record params actions-by-scope)
                                                              :scope nil)
                                                       (explain-no-record transaction options asker handler params actions-by-scope)))
                                              explained
                                          (explain-rows-of transaction options conditions asker handler params actions-by-scope explained)
                                          (update explained :answer name-answer (:person-id asker))))))))))
