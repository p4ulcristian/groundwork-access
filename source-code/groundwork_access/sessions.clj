(ns groundwork-access.sessions
  "Who is asking: a session is a row in Postgres, the browser's cookie holds
   only random bytes, the table only their fingerprint. A person's session
   is made at sign-in, with its flags; a visitor's (someone not signed in)
   when the app first keeps something for them, with an id of its own
   that the visitor's rows name. At sign-in the visitor's rows become the
   person's and the old session ends, in one transaction. A person's
   session lasts the settings' :session-days unseen, pushed out when seen
   again, or a fixed number of minutes from sign-in (sign-in!'s
   :session-lasts), never pushed out."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.asking :as asking]
   [groundwork-access.cookie :as cookie]
   [groundwork-access.outside :as outside]
   [groundwork-access.settings :as settings]
   [groundwork-access.tokens :as tokens])
  (:import
   (java.time Duration Instant OffsetDateTime ZoneOffset)
   (java.util UUID)))

(defn- make-query [file-name]
  (postgres/make-query-from-sql-file (str "groundwork_access/" file-name)))

(def ^:private insert-session            (make-query "insert_session.sql"))
(def ^:private find-session              (make-query "find_session.sql"))
(def ^:private update-session-ends-at    (make-query "update_session_ends_at.sql"))
(def ^:private delete-session            (make-query "delete_session.sql"))
(def ^:private delete-sessions-of-person (make-query "delete_sessions_of_person.sql"))
(def ^:private delete-ended-sessions     (make-query "delete_ended_sessions.sql"))

(defn- find-days [{:keys [session-days visitor-session-days]} person-id]
  (if person-id session-days visitor-session-days))

(defn- find-ends-at [days]
  (OffsetDateTime/ofInstant (.plus (Instant/now) (Duration/ofDays days)) ZoneOffset/UTC))

(defn- find-lifetime
  "How long a new person's session lasts, from sign-in!'s :session-lasts:
   {:seconds n :renews? bool}."
  [session-lasts {:keys [session-days]}]
  (if (= :session-days session-lasts)
    {:seconds (* session-days 86400) :renews? true}
    {:seconds (* (:minutes session-lasts) 60) :renews? false}))

(defn- turn-flags-into-text [flags] (str/join " " (sort (map name flags))))

(defn- turn-text-into-flags [text] (set (map keyword (remove str/blank? (str/split (or text "") #" ")))))

(defn find-live-session
  "The request's session row, with its fingerprint, if its cookie (the
   one the settings name) has a live one."
  [pool-or-transaction request {:keys [cookie-name]}]
  (when-let [cookie-value (cookie/find-cookie-value request cookie-name)]
    (let [fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)]
      (some-> (postgres/find-one-row pool-or-transaction find-session {:session/fingerprint fingerprint})
              (assoc :session/fingerprint fingerprint)))))

(defn- make-move-query [{:keys [table] {:keys [column moves-to]} :visitor}]
  (let [v (postgres/turn-key-into-column-name column)
        p (postgres/turn-key-into-column-name moves-to)]
    (postgres/make-query-from-sql-text
     (str "move a visitor's rows of " table " to the person")
     (str "UPDATE " (postgres/turn-table-key-into-table-name table) " SET " p " = param('move/person-id'), " v " = NULL"
          " WHERE " v " = param('move/visitor-id')"))))

(defn- move-visitor-rows!
  "In every table with a :visitor column that :moves-to a person column:
   the visitor's rows become the person's, the visitor emptied. The gates
   let exactly this through (groundwork_access.find_moving_visitor)."
  [transaction tables visitor-id person-id]
  (asking/set-moving! transaction visitor-id person-id)
  (doseq [table tables :when (get-in table [:visitor :moves-to])]
    (postgres/run-statement! transaction (make-move-query table) {:move/person-id person-id :move/visitor-id visitor-id})))

(defn sign-in! [pool request person-id options]
  (let [{:keys [settings tables session-flags session-lasts outside-subjects]} (settings/check-sign-in-options options)
        {:keys [cookie-name secure-cookie?]} settings
        {:keys [seconds renews?]} (find-lifetime session-lasts settings)
        cookie-value (cookie/make-cookie-value)]
    (postgres/run-in-transaction! pool {:read-only? false :log nil}   ; groundwork-access's own: keeps no work
                                (fn [transaction]
                                  (when-let [{:session/keys [fingerprint visitor-id]} (find-live-session transaction request settings)]
                                    (when visitor-id
                                      (move-visitor-rows! transaction tables visitor-id person-id)))
        ;; the request's session ends, live or not: a key planted before the login is worthless after it
                                  (when-let [cookie-value (cookie/find-cookie-value request cookie-name)]
                                    (postgres/run-statement! transaction delete-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)}))
                                  (postgres/run-statement! transaction insert-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)
                                                                                     :session/person-id   person-id
                                                                                     :session/visitor-id  nil
                                                                                     :session/flags       (turn-flags-into-text session-flags)
                                                                                     :session/renews      renews?
                                                                                     :session/ends-at     (OffsetDateTime/ofInstant (.plusSeconds (Instant/now) seconds) ZoneOffset/UTC)})
                                  ;; the person's subject at each outside issuer the login proved: their tokens are theirs
                                  (outside/link-subjects! transaction person-id outside-subjects)))
    {:set-cookie (cookie/make-set-cookie-text cookie-name cookie-value seconds secure-cookie?)}))

(defn start-visitor-session! [pool request settings]
  (let [{:keys [cookie-name visitor-session-days secure-cookie?] :as settings} (settings/check-settings settings)]
    (if-let [{:session/keys [person-id visitor-id]} (find-live-session pool request settings)]
      {:visitor-id (when-not person-id visitor-id) :set-cookie nil}
      (let [cookie-value (cookie/make-cookie-value)
            visitor-id   (UUID/randomUUID)]
        (postgres/run-statement! pool insert-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)
                                                    :session/person-id   nil
                                                    :session/visitor-id  visitor-id
                                                    :session/flags       ""
                                                    :session/renews      true
                                                    :session/ends-at     (find-ends-at visitor-session-days)})
        {:visitor-id visitor-id
         :set-cookie (cookie/make-set-cookie-text cookie-name cookie-value (* visitor-session-days 86400) secure-cookie?)}))))

(defn sign-out! [pool request settings]
  (let [{:keys [cookie-name secure-cookie?]} (settings/check-settings settings)]
    (when-let [cookie-value (cookie/find-cookie-value request cookie-name)]
      (postgres/run-statement! pool delete-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)}))
    {:set-cookie (cookie/make-set-cookie-text cookie-name "" 0 secure-cookie?)}))

(defn sign-out-everywhere! [pool person-id]
  (postgres/run-in-transaction! pool {:read-only? false :log nil}   ; groundwork-access's own: keeps no work
                              (fn [transaction]
                                {:sessions         (postgres/run-statement! transaction delete-sessions-of-person {:session/person-id person-id})
                                 :tokens           (tokens/delete-tokens-of-person! transaction person-id)
                                 :outside-subjects (outside/delete-subjects-of-person! transaction person-id)})))

(defn delete-ended-sessions! [pool]
  (postgres/run-statement! pool delete-ended-sessions {}))

(defn- is-due-for-longer-life?
  "A session seen again is pushed out, at most once in :renew-after-minutes."
  [ends-at days {:keys [renew-after-minutes]}]
  (.isBefore (.toInstant ^OffsetDateTime ends-at)
             (.minus (.plus (Instant/now) (Duration/ofDays days)) (Duration/ofMinutes renew-after-minutes))))

(defn find-session-asker!
  "{:person-id :visitor-id :session-flags} of the request's session (its
   cookie, the one the settings name); nils and #{} without a live one. A
   session seen again is pushed out, at most once in :renew-after-minutes,
   unless it lasts a fixed time. In a transaction (the question's own),
   the push is its row's, in that transaction: a session another question
   is pushing out right now is left to that one (SKIP LOCKED), so no
   question waits for another's."
  [pool-or-transaction request settings]
  (if-let [{:session/keys [fingerprint person-id visitor-id flags ends-at renews]} (find-live-session pool-or-transaction request settings)]
    (let [days (find-days settings person-id)]
      (when (and renews (is-due-for-longer-life? ends-at days settings))
        (postgres/run-statement! pool-or-transaction update-session-ends-at {:session/fingerprint fingerprint
                                                                           :session/ends-at     (find-ends-at days)}))
      {:person-id person-id :visitor-id (when-not person-id visitor-id) :session-flags (turn-text-into-flags flags)})
    {:person-id nil :visitor-id nil :session-flags #{}}))
