(ns groundwork-access.views
  "The views that read past the gates. Postgres runs a view with its
   owner's rights unless it is security_invoker: the tables' owner (the
   role the deploy migrates as, BYPASSRLS on many hosts, or a superuser in a
   test) passes every gate, so whoever may read such a view reads every row
   of the tables under it, whatever the sentences say. A materialized view
   keeps its rows where no gate holds them at all. In the folders the app's
   tables are in, which the app's role is granted (groundwork-postgres's
   grant-tables-to-role!: every table and view there), none of either may
   stand: a view there is security_invoker, so it reads as whoever asks,
   through their gates. A view for an outside reader that must see past the
   gates (a report another role reads) lives in a folder of its own, which
   the app's role is never granted."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.api :as postgres]))

(def ^:private find-views-past-the-gates-query
  (postgres/make-query-from-sql-file "groundwork_access/find_views_past_the_gates.sql"))

(defn find-folders
  "The folders the tables are in, as Postgres names them: groundwork_access
   for :groundwork-access/roles, public for :orders."
  [tables]
  (set (map (fn [{:keys [table]}]
              (let [table-name (postgres/turn-table-key-into-table-name table)]
                (if (str/includes? table-name ".") (first (str/split table-name #"\.")) "public")))
            tables)))

(defn find-views-past-the-gates
  "[{:name :materialized?}]: the views in the tables' folders that read past
   the gates, by name as groundwork-postgres names a view (its folder before
   it, but public's), a view that isn't security_invoker and every
   materialized view; [] for none."
  [pool tables]
  (mapv (fn [{:view/keys [name materialized]}] {:name name :materialized? materialized})
        (postgres/find-rows pool find-views-past-the-gates-query {:folders (str/join "," (sort (find-folders tables)))})))

(defn- describe [{:keys [name materialized?]}]
  (if materialized?
    (str name " (a materialized view: its rows stand where no gate holds them)")
    (str name " (not security_invoker: it reads as its owner)")))

(defn check-views
  "Returns nil; throws :groundwork-access/views-past-the-gates with every
   view of find-views-past-the-gates at once."
  [pool tables]
  (let [views (find-views-past-the-gates pool tables)]
    (when (seq views)
      (throw (ex-info (str "These views read past the gates, in folders the app's role reads: "
                           (str/join ", " (map describe views))
                           ". A view there runs as whoever asks: CREATE VIEW … WITH (security_invoker = true) AS …;"
                           " one an outside reader needs past the gates goes in a folder of its own")
                      {:type :groundwork-access/views-past-the-gates :views views})))))
