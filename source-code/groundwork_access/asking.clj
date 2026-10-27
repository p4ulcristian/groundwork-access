(ns groundwork-access.asking
  "Who is asking, told to Postgres for one transaction: the setting
   groundwork_access.asking, as JSON, which groundwork-access's SQL functions read (see
   sql_functions/ and groundwork_access.api/function-files)."
  (:require
   [groundwork-postgres.api :as postgres]
   [groundwork-access.memberships :as memberships]
   [jsonista.core :as json]
   [malli.core :as malli]
   [malli.error :as malli-error]))

(def ^:private set-asking (postgres/make-query-from-sql-file "groundwork_access/set_asking.sql"))

(def ^:private set-asking-read-only (postgres/make-query-from-sql-file "groundwork_access/set_asking_read_only.sql"))

(def ^:private set-passing (postgres/make-query-from-sql-file "groundwork_access/set_passing.sql"))

(defn- turn-actions-into-holding
  "{\"shop orders/see\" [\"scope id\" …]} from {[:shop id] #{:orders/see …}}."
  [actions-by-scope]
  (reduce (fn [holding [[kind scope-id] actions]]
            (reduce #(update %1 (str (name kind) " " (subs (str %2) 1)) (fnil conj []) (str scope-id))
                    holding actions))
          {} actions-by-scope))

(def telling-shape
  "What Postgres is told besides who asks, every key required.
   :acting: the one action a handler's steps act as, or :every-action
   (run-as-asker-of-request!, \"what may I do?\", explain-refusal, the check itself):
   a person's sentences count only for it.
   :holding: {[kind scope-id] #{action …}}, the actions held in each scope
   that counts: for a handler's steps, only the scopes it acts in, and in
   them only what its action's sentences need held."
  [:map {:closed true}
   [:acting [:or [:= :every-action] :qualified-keyword]]
   [:holding [:map-of [:tuple :keyword :uuid] [:set :qualified-keyword]]]])

(def ^:private is-telling? (malli/validator telling-shape))

(defn turn-asker-into-text
  "The asker as groundwork_access.asking's JSON: the person (or, for nobody signed
   in, the visitor), the session's flags, the action acted as (or every
   action), and the scopes holding each action."
  [{:keys [person-id visitor-id session-flags]} {:keys [acting holding] :as telling}]
  (when-not (is-telling? telling)
    (throw (ex-info (str "What Postgres is told has problems: " (pr-str (malli-error/humanize (malli/explain telling-shape telling))))
                    {:type :groundwork-access/bad-telling :telling telling})))
  (json/write-value-as-string
   {:person       (some-> person-id str)
    :visitor      (when-not person-id (some-> visitor-id str))
    :flags        (vec (sort (map name session-flags)))
    :every_action (= :every-action acting)
    :acting       (when-not (= :every-action acting) (subs (str acting) 1))
    :holding      (turn-actions-into-holding holding)}))

(defn set-asking!
  "Tells Postgres, for this transaction, who is asking, and what counts
   (telling-shape): {:acting :orders/edit :holding {[:shop id] #{:orders/edit}}},
   or {:acting :every-action :holding (list-actions-of-person …)}. Told
   again in the same transaction, the later telling holds."
  [transaction asker telling]
  (postgres/find-one-row transaction set-asking {:asking (turn-asker-into-text asker telling)}))

(defn set-asking-with-every-action!
  "Lists the person's actions (none for a visitor or nobody) and tells
   Postgres, for this transaction, that the asker is asking with every
   action, holding them; read-only? true, in the same statement, that the
   transaction is read-only from here on (it was opened read-write, so
   the session step could push a session out in it). Returns the
   actions, by scope."
  [transaction scope-kinds {:keys [person-id] :as asker} read-only?]
  (let [actions (if person-id (memberships/list-actions-of-person transaction scope-kinds person-id) {})
        text    (turn-asker-into-text asker {:acting :every-action :holding actions})]
    (postgres/find-one-row transaction (if read-only? set-asking-read-only set-asking) {:asking text})
    actions))

(defn set-moving!
  "Tells Postgres, for this transaction, that sign-in moves a visitor's
   rows to the person; nobody else is asking."
  [transaction visitor-id person-id]
  (postgres/find-one-row transaction set-asking
                       {:asking (json/write-value-as-string {:moving {:visitor (str visitor-id) :person (str person-id)}})}))

(defn set-system!
  "Tells Postgres, for this transaction, that a named system actor is
   asking; no person, no visitor, no scope held; every action counts, so
   its :anyone rows are its too."
  [transaction system-name]
  ;; every action: the :anyone sentences, which count for anyone, count for it
  (postgres/find-one-row transaction set-asking {:asking (json/write-value-as-string {:system (name system-name) :every_action true})}))

(defn set-passing!
  "Reads past the gates for the rest of this transaction: for a read-only
   look (explain-refusal), never for a handler's steps."
  [transaction]
  (postgres/find-one-row transaction set-passing {}))
