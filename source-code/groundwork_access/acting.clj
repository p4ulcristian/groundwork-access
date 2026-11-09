(ns groundwork-access.acting
  "Where a handler acts, and what Postgres is told for its steps: the
   handler's action alone (groundwork_access.is_acting), held only in the scopes it
   acts in (groundwork_access.find_scopes_holding).

     :scope [:shop :shop/id]                 the scope the param names
     :record [:orders :order/id]             the scopes the record's columns name (its own row, on a
                                             scope kind's table), and, for its action's [:member <kind
                                             of one scope>] and :via sentences, the scopes of those kinds
     :scope :every-scope-holding-the-action  every scope holding it
     :scope :no-scope                        none: only :anyone, :signed-in and [:me …] sentences

   A scope counts only where the action is held (is-held-there?), and in it
   only the actions the action's [:member …] sentences need held there."
  (:require
   [groundwork-access.sentences :as sentences]))

(def scope-words
  "The :scope values that name no param."
  #{:every-scope-holding-the-action :no-scope})

(defn find-needs
  "{action #{{:kind k :held action-held :through :column|:single|:via}}}:
   what each action's [:member …] sentences (its own, or its :via's on the
   related row) need held, in which kind of scope, from every table's
   sentences."
  [tables scope-kinds]
  (let [tables-by-key (into {} (map (juxt :table identity)) tables)]
    (reduce (fn [needs [table {:keys [to via]} action]]
              (let [[who-table who] (if via [(get tables-by-key (:table via)) (:to via)] [table to])]
                (if (and (vector? who) (= :member (first who)))
                  (let [x (second who)]
                    (update needs action (fnil conj #{})
                            {:kind    (if (sentences/is-column-word? x)
                                        (sentences/find-kind-of-member-column who-table x tables-by-key scope-kinds)
                                        x)
                             :held    (sentences/find-held-action who action)
                             :through (cond via :via (sentences/is-column-word? x) :column :else :single)}))
                  needs)))
            {}
            (for [{:keys [access] :as table} tables :when (sequential? access)
                  sentence access
                  action (sentences/find-allowed-actions sentence)]
              [table sentence action]))))

(defn is-held-by-roles?
  "Whether the person's roles hold the action in the scope (there, or above
   it, as list-actions-of-person folds them in): what the check asks of a
   :scope [kind param] handler. A [:member … other-action] sentence gives
   rows, never the action
   (decisions/2026-11-09-the-check-counts-an-action-held-only-by-the-persons-roles.md)."
  [action scope actions-by-scope]
  (contains? (get actions-by-scope scope #{}) action))

(defn is-held-there?
  "Whether the scope counts for the action's rows, once the check has let
   the handler through: the action held by a role there (or above it, as
   list-actions-of-person folds them in), or the action one of its
   [:member …] sentences on that kind needs held. What Postgres is told
   (make-telling), never the check's answer (is-held-by-roles?)."
  [needs action [kind :as scope] actions-by-scope]
  (let [held (get actions-by-scope scope #{})]
    (or (contains? held action)
        (boolean (some #(and (= kind (:kind %)) (contains? held (:held %))) (get needs action))))))

(defn find-sideways-kinds
  "The kinds an action reaches rows through without the row naming the
   scope: a [:member <kind of one scope> …]'s, and a :via's."
  [needs action]
  (set (for [{:keys [kind through]} (get needs action) :when (#{:single :via} through)] kind)))

(defn make-telling
  "What Postgres is told for the handler's steps (asking/telling-shape):
   the action, and of the candidate scopes those holding it, each with only
   the actions the action's sentences need held in its kind."
  [needs action scopes actions-by-scope]
  (let [needed (fn [kind] (set (for [{k :kind :keys [held]} (get needs action) :when (= k kind)] held)))]
    {:acting  action
     :holding (into {} (for [[kind :as scope] (distinct scopes)
                             :when (is-held-there? needs action scope actions-by-scope)
                             :let [told (set (filter (needed kind) (get actions-by-scope scope)))]
                             :when (seq told)]
                         [scope told]))}))
