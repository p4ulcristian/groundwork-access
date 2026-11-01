(ns groundwork-access.memberships
  "Who belongs where, as rows: the scopes (the app's own row ids, under a
   parent scope or none), each scope's roles (an id, a name unique in the
   scope, the actions it allows), and the memberships (a person in a
   scope, holding a set of the scope's roles there, none for no role yet).
   A person's actions in a scope are the union of their roles' there. A
   membership in a scope counts in every scope below it, for the actions
   of that scope's kind and the kinds below it."
  (:require
   [clojure.string]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.scope-kinds :as scope-kinds]))

(defn- make-query [file-name]
  (postgres/make-query-from-sql-file (str "groundwork_access/" file-name)))

(def ^:private insert-scope                  (make-query "insert_scope.sql"))
(def ^:private find-scope                    (make-query "find_scope.sql"))
(def ^:private lock-scope-kind               (make-query "lock_scope_kind.sql"))
(def ^:private count-scopes-of-kind          (make-query "count_scopes_of_kind.sql"))
(def ^:private insert-role                   (make-query "insert_role.sql"))
(def ^:private insert-role-action            (make-query "insert_role_action.sql"))
(def ^:private find-role                     (make-query "find_role.sql"))
(def ^:private find-role-by-name             (make-query "find_role_by_name.sql"))
(def ^:private lock-roles-of-scope           (make-query "lock_roles_of_scope.sql"))
(def ^:private update-role-name              (make-query "update_role_name.sql"))
(def ^:private delete-role-actions           (make-query "delete_role_actions.sql"))
(def ^:private delete-role                   (make-query "delete_role.sql"))
(def ^:private count-holders-of-role         (make-query "count_holders_of_role.sql"))
(def ^:private find-roles-of-scope           (make-query "find_roles_of_scope.sql"))
(def ^:private insert-membership             (make-query "insert_membership.sql"))
(def ^:private insert-membership-role        (make-query "insert_membership_role.sql"))
(def ^:private delete-membership-roles       (make-query "delete_membership_roles.sql"))
(def ^:private find-membership               (make-query "find_membership.sql"))
(def ^:private delete-membership             (make-query "delete_membership.sql"))
(def ^:private find-actions-of-person        (make-query "find_actions_of_person.sql"))
(def ^:private find-scopes-below-memberships (make-query "find_scopes_below_memberships.sql"))
(def ^:private find-memberships-above        (make-query "find_memberships_above.sql"))
(def ^:private count-scopes-below            (make-query "count_scopes_below.sql"))
(def ^:private delete-membership-roles-of-scope (make-query "delete_membership_roles_of_scope.sql"))
(def ^:private delete-memberships-of-scope   (make-query "delete_memberships_of_scope.sql"))
(def ^:private delete-role-actions-of-scope  (make-query "delete_role_actions_of_scope.sql"))
(def ^:private delete-roles-of-scope         (make-query "delete_roles_of_scope.sql"))
(def ^:private delete-scope                  (make-query "delete_scope.sql"))

(defn- turn-keyword-into-text [k] (subs (str k) 1))

(defn turn-text-into-keyword
  "\"orders/see\" -> :orders/see (with its namespace, unlike (keyword s))."
  [text]
  (apply keyword (clojure.string/split text #"/" 2)))

;; -----------------------------------------------------------------------------
;; Roles

(defn- refuse [message data]
  (throw (ex-info message data)))

(defn- check-role-name! [role-name]
  (when-not (and (string? role-name) (not (clojure.string/blank? role-name)))
    (refuse (str "A role's name is text, not " (pr-str role-name)) {:type :groundwork-access/bad-role-name :name role-name}))
  (when (= scope-kinds/owner-role role-name)
    (refuse "\"Owner\" is groundwork-access's own role, holding every action; a role of the app's has another name"
            {:type :groundwork-access/owner-role :name role-name})))

(defn- check-actions! [scope-kinds kind actions]
  (when-not (and (set? actions) (every? qualified-keyword? actions))
    (refuse (str "A role's actions are a set of actions, not " (pr-str actions)) {:type :groundwork-access/bad-actions :actions actions}))
  (let [allowed (scope-kinds/find-actions-at-or-below scope-kinds kind)]
    (doseq [action actions
            :when (not (contains? allowed action))]
      (refuse (str kind " has no action " action ", nor a kind below it") {:type :groundwork-access/unknown-action :action action}))))

(defn- lock-roles! [transaction [kind scope-id]]
  ;; one change to a scope's roles at a time: a name stays unique in the scope
  (postgres/find-one-row transaction lock-roles-of-scope {:role/lock-name (str "groundwork_access.roles/" (name kind) " " scope-id)}))

(defn- check-name-free! [transaction [kind scope-id] role-name]
  (when (postgres/find-one-row transaction find-role-by-name {:role/scope-kind (name kind) :role/scope-id scope-id :role/name role-name})
    (refuse (str "The scope has a role named " role-name " already") {:type :groundwork-access/role-name-taken :name role-name})))

(defn- find-role-of-scope!
  "The role, {:role/id :role/name}, if it is the scope's; throws otherwise."
  [transaction [kind scope-id] role-id]
  (or (and (uuid? role-id)
           (postgres/find-one-row transaction find-role {:role/id role-id :role/scope-kind (name kind) :role/scope-id scope-id}))
      (refuse (str "The scope has no role " role-id) {:type :groundwork-access/unknown-role :role-id role-id})))

(defn- refuse-owner! [{:role/keys [id name]} what]
  (when (= scope-kinds/owner-role name)
    (refuse (str "\"Owner\" is groundwork-access's own role, holding every action: it can't be " what)
            {:type :groundwork-access/owner-role :role-id id})))

(defn- insert-role! [transaction [kind scope-id] role-name actions]
  (let [role-id (random-uuid)]
    (postgres/run-statement! transaction insert-role {:role/id role-id :role/scope-kind (name kind) :role/scope-id scope-id :role/name role-name})
    (doseq [action actions]
      (postgres/run-statement! transaction insert-role-action {:role-action/role-id role-id :role-action/action (turn-keyword-into-text action)}))
    role-id))

(defn add-role! [transaction scope-kinds [kind :as scope] role-name actions]
  (check-role-name! role-name)
  (check-actions! scope-kinds kind actions)
  (lock-roles! transaction scope)
  (check-name-free! transaction scope role-name)
  (insert-role! transaction scope role-name actions))

(defn rename-role! [transaction scope role-id role-name]
  (check-role-name! role-name)
  (lock-roles! transaction scope)
  (let [role (find-role-of-scope! transaction scope role-id)]
    (refuse-owner! role "renamed")
    (when-not (= role-name (:role/name role))
      (check-name-free! transaction scope role-name)
      (postgres/run-statement! transaction update-role-name {:role/id role-id :role/name role-name}))
    nil))

(defn set-role-actions! [transaction scope-kinds [kind :as scope] role-id actions]
  (check-actions! scope-kinds kind actions)
  (refuse-owner! (find-role-of-scope! transaction scope role-id) "given other actions")
  (postgres/run-statement! transaction delete-role-actions {:role-action/role-id role-id})
  (doseq [action actions]
    (postgres/run-statement! transaction insert-role-action {:role-action/role-id role-id :role-action/action (turn-keyword-into-text action)}))
  nil)

(defn remove-role! [transaction scope role-id]
  (lock-roles! transaction scope)
  (let [role    (find-role-of-scope! transaction scope role-id)
        held-by (:role/held-by (postgres/find-one-row transaction count-holders-of-role {:role/id role-id}))]
    (refuse-owner! role "removed")
    (when (pos? held-by)
      (refuse (str "The role " (:role/name role) " is held by " held-by " member" (when (< 1 held-by) "s")
                   "; take it from them first (set-membership-roles!)")
              {:type :groundwork-access/role-held :role-id role-id :name (:role/name role) :held-by held-by}))
    (postgres/run-statement! transaction delete-role-actions {:role-action/role-id role-id})
    (postgres/run-statement! transaction delete-role {:role/id role-id})
    nil))

(defn list-roles [transaction scope-kinds [kind scope-id]]
  (->> (postgres/find-rows transaction find-roles-of-scope {:role/scope-kind (name kind) :role/scope-id scope-id})
       (partition-by :role/id)
       (mapv (fn [[{:role/keys [id name]} :as rows]]
               {:role-id id
                :name    name
                :actions (scope-kinds/find-actions-of-role scope-kinds kind name
                                                           (set (keep #(some-> (:role-action/action %) turn-text-into-keyword) rows)))}))))

;; -----------------------------------------------------------------------------
;; Memberships

(defn- check-roles-of-scope! [transaction scope role-ids]
  (when-not (and (set? role-ids) (every? uuid? role-ids))
    (refuse (str "A member's roles are a set of role ids, #{} for none yet, not " (pr-str role-ids))
            {:type :groundwork-access/bad-roles :role-ids role-ids}))
  (doseq [role-id role-ids]
    (find-role-of-scope! transaction scope role-id)))

(defn- insert-membership-roles! [transaction [kind scope-id] person-id role-ids]
  (doseq [role-id role-ids]
    (postgres/run-statement! transaction insert-membership-role {:membership-role/person-id person-id :membership-role/scope-kind (name kind)
                                                               :membership-role/scope-id scope-id :membership-role/role-id role-id})))

(defn add-membership! [transaction [kind scope-id :as scope] person-id role-ids]
  (check-roles-of-scope! transaction scope role-ids)
  (postgres/run-statement! transaction insert-membership {:membership/person-id person-id :membership/scope-kind (name kind)
                                                        :membership/scope-id scope-id})
  (insert-membership-roles! transaction scope person-id role-ids)
  nil)

(defn set-membership-roles! [transaction [kind scope-id :as scope] person-id role-ids]
  (check-roles-of-scope! transaction scope role-ids)
  (if (postgres/find-one-row transaction find-membership {:membership/person-id person-id :membership/scope-kind (name kind)
                                                        :membership/scope-id scope-id})
    (do (postgres/run-statement! transaction delete-membership-roles {:membership-role/person-id person-id :membership-role/scope-kind (name kind)
                                                                    :membership-role/scope-id scope-id})
        (insert-membership-roles! transaction scope person-id role-ids)
        true)
    false))

(defn- check-parent! [transaction scope-kinds kind parent-id]
  (when parent-id
    (let [parent-kind (get-in scope-kinds [kind :parent])]
      (when-not parent-kind
        (throw (ex-info (str kind " has no :parent kind, so a scope of it has no parent")
                        {:type :groundwork-access/bad-parent :kind kind :parent-id parent-id})))
      (when-not (postgres/find-one-row transaction find-scope {:scope/kind (name parent-kind) :scope/id parent-id})
        (throw (ex-info (str "There is no " parent-kind " " parent-id " to be the parent")
                        {:type :groundwork-access/bad-parent :kind kind :parent-id parent-id}))))))

(defn- check-single! [transaction scope-kinds kind]
  (when (get-in scope-kinds [kind :single?])
    ;; one at a time, so two transactions can't both make the first
    (postgres/find-one-row transaction lock-scope-kind {:scope/lock-name (str "groundwork_access.single/" (name kind))})
    (when (pos? (:scopes/count (postgres/find-one-row transaction count-scopes-of-kind {:scope/kind (name kind)})))
      (throw (ex-info (str kind " has exactly one scope, and it exists")
                      {:type :groundwork-access/single-scope :kind kind})))))

(defn add-scope! [transaction scope-kinds kind scope-id founder-id parent-id]
  (when-not (contains? scope-kinds kind)
    (throw (ex-info (str kind " is no scope kind") {:type :groundwork-access/unknown-kind :kind kind})))
  (check-parent! transaction scope-kinds kind parent-id)
  (check-single! transaction scope-kinds kind)
  (postgres/run-statement! transaction insert-scope {:scope/kind        (name kind)
                                                   :scope/id          scope-id
                                                   :scope/parent-kind (when parent-id (name (get-in scope-kinds [kind :parent])))
                                                   :scope/parent-id   parent-id})
  (let [scope    [kind scope-id]
        owner-id (insert-role! transaction scope scope-kinds/owner-role #{})
        role-ids (into {scope-kinds/owner-role owner-id}
                       (for [[role-name actions] (get-in scope-kinds [kind :roles])]
                         [role-name (insert-role! transaction scope role-name actions)]))]
    (add-membership! transaction scope founder-id #{owner-id})
    role-ids))

(defn remove-scope! [transaction [kind scope-id :as scope]]
  (lock-roles! transaction scope)
  (let [below (:scope/below (postgres/find-one-row transaction count-scopes-below {:scope/kind (name kind) :scope/id scope-id}))]
    (when (pos? below)
      (refuse (str "The scope has " below " scope" (when (< 1 below) "s") " under it; remove those first")
              {:type :groundwork-access/scope-has-scopes-below :scope scope :below below})))
  (postgres/run-statement! transaction delete-membership-roles-of-scope {:membership-role/scope-kind (name kind) :membership-role/scope-id scope-id})
  (postgres/run-statement! transaction delete-memberships-of-scope {:membership/scope-kind (name kind) :membership/scope-id scope-id})
  (postgres/run-statement! transaction delete-role-actions-of-scope {:role/scope-kind (name kind) :role/scope-id scope-id})
  (postgres/run-statement! transaction delete-roles-of-scope {:role/scope-kind (name kind) :role/scope-id scope-id})
  (pos? (postgres/run-statement! transaction delete-scope {:scope/kind (name kind) :scope/id scope-id})))

(defn remove-membership! [transaction [kind scope-id] person-id]
  (postgres/run-statement! transaction delete-membership-roles {:membership-role/person-id person-id :membership-role/scope-kind (name kind)
                                                              :membership-role/scope-id scope-id})
  (pos? (postgres/run-statement! transaction delete-membership {:membership/person-id person-id :membership/scope-kind (name kind)
                                                              :membership/scope-id scope-id})))

(defn- add-row-to-actions
  "One row of a person's memberships (a role they hold in a scope and one
   of its actions, or none) added to their actions there: the union of
   their roles'."
  [scope-kinds actions {:membership/keys [scope-kind scope-id] :role/keys [name] :role-action/keys [action]}]
  (let [kind  (keyword scope-kind)
        scope [kind scope-id]]
    (if (= scope-kinds/owner-role name)
      (update actions scope (fnil into #{}) (scope-kinds/find-actions-of-role scope-kinds kind name #{}))
      (update actions scope (fnil conj #{}) (some-> action turn-text-into-keyword)))))

(defn- add-scope-below [scope-kinds direct actions {:scope/keys [kind id] :membership/keys [scope-kind scope-id]}]
  (let [kind     (keyword kind)
        inherits (filter (scope-kinds/find-actions-at-or-below scope-kinds kind)
                         (get direct [(keyword scope-kind) scope-id]))]
    (update actions [kind id] (fnil into #{}) inherits)))

(defn list-actions-of-person [transaction scope-kinds person-id]
  (let [direct (-> (reduce #(add-row-to-actions scope-kinds %1 %2) {}
                           (postgres/find-rows transaction find-actions-of-person {:membership/person-id person-id}))
                   (update-vals #(disj % nil)))]
    (reduce #(add-scope-below scope-kinds direct %1 %2)
            direct
            (postgres/find-rows transaction find-scopes-below-memberships {:membership/person-id person-id}))))

(defn list-memberships-above
  "A scope and every scope above it, nearest first, whether the person is
   a member there, and the roles they hold in each with the actions each
   allows (Owner's: every action of its kind and below):
   [{:scope-kind :scope-id :depth :member? :roles [{:role-id :name :actions #{…}}]}],
   :roles [] where they aren't a member, or hold no role yet."
  [transaction scope-kinds [kind scope-id] person-id]
  (->> (postgres/find-rows transaction find-memberships-above {:scope/kind (name kind) :scope/id scope-id
                                                            :membership/person-id person-id})
       (partition-by (juxt :scope/kind :scope/id))
       (mapv (fn [[{:scope/keys [kind id depth] :membership/keys [found]} :as rows]]
               (let [kind (keyword kind)]
                 {:scope-kind kind :scope-id id :depth depth :member? (boolean found)
                  :roles      (vec (for [[{:role/keys [id name]} :as role-rows] (partition-by :role/id (filter :role/id rows))]
                                     {:role-id id :name name
                                      :actions (scope-kinds/find-actions-of-role
                                                scope-kinds kind name
                                                (set (keep #(some-> (:role-action/action %) turn-text-into-keyword) role-rows)))}))})))))
