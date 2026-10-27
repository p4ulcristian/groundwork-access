# groundwork-access

Who is asking, and what may they do: the door of a Clojure app on
Postgres. **Who is asking:** a session is a row in Postgres; the browser's
cookie holds only random bytes, the table only their fingerprint. The app's
own login proves who someone is and calls `sign-in!`, the one door that
names a person; signing out deletes rows, so access ends at once on every
server. A signed-in person makes tokens for themselves, for a script or an
AI acting as them: a call with `Authorization: Bearer <token>` is theirs,
with the same actions, scopes and gates. An outside issuer the app names
in its settings (WorkOS AuthKit, for an app's `/mcp`) signs bearer tokens
too: groundwork-access checks each by the issuer's keys, issuer, audience, times
and algorithm, and its subject is the person whose sign-in linked it,
who then asks like anyone. A visitor (not signed in)
gets a session of their own when the app first keeps something for them;
their rows become the person's at sign-in. **What may they do:** the
app's kinds of scope (a shop under a company, a back office of one scope)
with their actions, roles and denies; scopes, roles and memberships as
rows; the `:access` sentences on table maps turned into one SQL condition
per action; the check in front of every handler; the gates (Postgres's
row rules) from the same conditions; the leak test; and why a message
would be refused, as data.

```clojure
io.github.p4ulcristian/groundwork-access {:git/tag "v0.7.15" :git/sha "e70edfe"}
```

Needs [groundwork-postgres](https://github.com/p4ulcristian/groundwork-postgres) (Postgres, tables as
maps, their gates), [groundwork-steps](https://github.com/p4ulcristian/groundwork-steps) (a
handler's steps, their context's keys, the refusal's shape), [groundwork-logs](https://github.com/p4ulcristian/groundwork-logs) (the
message's log), malli with test.check (the settings checked, the leak
test's params) and jsonista. One public namespace, `groundwork-access.api`; its parts
are `groundwork-access.*`. One more namespace is a REPL's only, never an app's code:
`groundwork-access.repl`'s `make-step-asking-as`, a step in place of the find-session
step that asks as a named asker (a composer's "ask as this person" tool). groundwork-access
requires no message runner: its steps run in whichever the app plugs its handlers into
(groundwork's, groundwork-bridge), and the leak test takes that runner under
`:answer-message` ([why](decisions/2026-10-24-groundwork-access-requires-groundwork-steps-not-groundwork-bridge.md)).
The find-session step adds the person to the
message's log (`:groundwork-steps/log`, `{:person-id …}`), so every later
step's lines say who asked
([why](decisions/2026-10-22-the-session-step-adds-the-person-to-the-log.md)).

## The api

| half | name | does |
|---|---|---|
| who | `settings-shape` | the settings as a malli shape, every key required |
| who | `sessions-table` `tokens-table` `outside-subjects-table` | its tables, `groundwork_access.sessions`, `groundwork_access.tokens` and `groundwork_access.outside_subjects` (which person each subject at an outside issuer is), as maps for groundwork-postgres |
| who | `sign-in!` `sign-out!` `sign-out-everywhere!` | a session made for a proven person, with its flags and its lifetime (`:session-lasts`: `:session-days`, pushed out when seen again, or `{:minutes 60}` from now, never pushed out; [why](decisions/2026-10-27-sign-in-takes-the-sessions-lifetime.md)), the person's subject at each outside issuer linked (`:outside-subjects`), the Set-Cookie text back, a visitor's rows moved to them and the request's old session ended, in one transaction; ended at once; everywhere: every session, token and outside subject of the person |
| who | `make-token!` `list-tokens` `revoke-token!` | a token for the request's signed-in person (a session without flags; not a token): its text shown once, only its SHA-256 kept, a name and an end date required; their live tokens listed (never the text); one ended at once ([why](decisions/2026-10-23-doorman-owns-tokens-and-only-sign-in-names-a-person.md)) |
| who | `delete-ended-tokens!` | forgets ended tokens; for a nightly background job |
| who | `start-visitor-session!` | a session for a visitor, on the app's own route: its id, which the rows kept for them name, and the Set-Cookie text |
| who | `delete-ended-sessions!` | forgets ended sessions; for a nightly background job |
| who | `make-find-session-step` | the step first in a handler: who asks, by a bearer token (no Origin check; no session flags), an outside issuer's token (checked by its keys; a failed check `:groundwork-access/token-refused`) or the session cookie (the Origin checked), a cookie and a token at once refused; `:groundwork-access/person-id`, `:groundwork-access/visitor-id`, `:groundwork-access/session-flags`, `:groundwork-access/token-id` and `:groundwork-access/outside-issuer` in the context ([why](decisions/2026-10-27-a-handler-sees-which-token-asked.md)), the person (and token or issuer) or visitor in `:groundwork-steps/log`; in a handler, looked up in the question's own transaction, its first statement ([why](decisions/2026-10-27-the-session-is-looked-up-in-the-questions-own-transaction.md)) |
| who | `run-as-asker-of-request!` | `(f transaction)` as the asker groundwork-access finds in a request, by the step's rules, outside a message (the app's own route, a page), looked up in that transaction: held by the gates as a message's steps are |
| who | `find-session-key-of-request` | a hash of the request's token, else its session cookie, for rate limits |
| what | `access-tables` | its tables, `groundwork_access.scopes` `groundwork_access.roles` `groundwork_access.role_actions` `groundwork-access.memberships` `groundwork_access.membership_roles` |
| what | `access-move-from-v0-7` | a database of v0.7 (a role was its name, a member held one) brought to `access-tables` with its rows, by two `postgres/migrate!` runs: `:old-tables` and `:renames`, then `:copy-and-drops` ([why](decisions/2026-10-23-roles-have-ids-and-a-member-holds-a-set-of-roles.md)) |
| what | `check-scope-kinds` | the app's kinds of scope, checked, every key written out |
| what | `does-action-only-read?` | whether an action only reads (its handlers run read-only); every other one's may write |
| what | `add-scope!` `add-membership!` `set-membership-roles!` `remove-membership!` `list-actions-of-person` | who belongs where; a scope under its parent scope (its roles' ids back, by name); a scope's founder is its Owner, holding every action of its kind and below; a member holds a set of the scope's roles by id (`#{}` for none yet), their actions there the union of their roles'; a membership counts in the scopes below |
| what | `add-role!` `rename-role!` `set-role-actions!` `remove-role!` `list-roles` | a scope's roles: an id, a name unique in the scope, actions; renamed in one row; a role nobody holds removed (`:groundwork-access/role-held` while held); Owner never changed |
| what | `make-my-actions-handler` `my-actions-answer-shape` (the shape on both sides: `groundwork-access.api`'s `.cljs` half has it too) | "what may I do?" for the browser: the asker's actions by scope, and the open ones under nil kind and id, every scope; the denies taken out; read-only, as the asker |
| what | `make-sql-conditions` | the sentences, checked, as SQL: one condition per action and per operation, the denies in them |
| what | `function-files` | the `.sql` files of the SQL functions the check and the gates read who is asking from, for groundwork-postgres's `:functions` beside the app's own |
| what | `make-access-functions` | the SQL functions made from the app's sentences and denies (a `:via`'s, a scope state's, the guard's, a record under a record's fill, `doorman."may <action> on <table>"()`), as groundwork-postgres's made functions, `{:name :sql}`: the app passes them to `migrate!` with `function-files` and its own, and groundwork-postgres makes them before the gates, compares them and drops one whose sentence is gone ([why](https://github.com/p4ulcristian/groundwork-postgres/blob/tidy-up/decisions/2026-10-23-functions-may-be-sql-text-a-library-makes.md)) |
| what | `put-access-into-handlers` | every handler says where it acts (`:record`, or `:scope` `[kind param]`, `:every-scope-holding-the-action`, `:no-scope`); the check in front of its steps, which run in a transaction where Postgres hears only the handler's action, in those scopes ([why](decisions/2026-10-23-a-handler-says-where-it-acts-and-postgres-hears-only-that.md)); a param marked `[:uuid {:groundwork-access/row-of :workstations}]` must be a row the action reads there, else `:groundwork-access/not-found`, before any step ([why](decisions/2026-10-23-a-param-may-say-it-is-a-row-of-a-table.md)) |
| what | `put-gates-on-tables` | the table maps with their gates, `:gates nil` on the ungated ones, and a record under a record (`:parent`) given its link to the parent and the fill of its carried columns, for groundwork-postgres |
| what | `find-leaks` | the leak test, for the app's own tests, each message answered by the app's runner (`:answer-message`, `(fn [handlers request message])`, groundwork-bridge's `answer-message` in groundwork); a record or scope it must not reach must be refused `:groundwork-access/not-found` (`:groundwork-access/signed-out` for an asker nobody signed in); a `:scope [kind param]` handler asked in each foreign scope (`:scope-kinds`) |
| what | `refusals` (both sides; the browser's half has `put-refusals-into-shapes` too) | groundwork-access's reasons, `:groundwork-access/signed-out` `:groundwork-access/not-found` `:groundwork-access/not-allowed` `:groundwork-access/token-refused`, added to every handler it wraps |
| what | `run-as-system!` | `(f transaction)` as a named system actor (a webhook's function): the rows the sentences give it by `[:system name]`, the open ones and the ungated tables, nothing else; the app's own role, no role that passes the gates |
| what | `find-system-leaks` | the leak test of a system actor: the rows it must not reach, read, changed and deleted as it, undone |
| what | `explain-refusal` | why a message would be refused, as data: the check's answer, the record's scopes, the asker's roles and actions there, each sentence's parts (a `[:member …]`'s roles that give what it needs, `:given-by`) and the denies; read-only, for a REPL |

Nothing has a default: every setting and option is written out; a missing
one refuses, named, with every other problem at once. A handler writes out
every key its runner reads (groundwork-bridge's: `:steps :params-shape :answer-shape :refusals
:rate-limit :run-steps-in`); groundwork-access wants both shapes real and `:run-steps-in` nil:
[the decision](decisions/2026-10-22-a-handler-writes-out-mailrooms-keys.md).

The check refuses with groundwork-access's reasons, `access/refusals` (groundwork-steps's
`refuse`, a named reason; [why](https://github.com/p4ulcristian/groundwork-bridge/blob/tidy-up/decisions/2026-10-23-a-refusal-is-a-named-reason-declared-on-its-handler.md)):
`:groundwork-access/not-found` (the asker may not reach the record or scope, nor see
it), `:groundwork-access/not-allowed` (they may see it, but not do this; a session
flag or a scope's state says no; a request from another Origin, or with a
cookie and a token at once), and `:groundwork-access/signed-out` in place of either
when nobody is signed in (nobody, a visitor, a wrong, ended or revoked
token), whatever the record, so it tells nothing of whether one exists;
and `:groundwork-access/token-refused`, an outside issuer's token that failed its
check, never nobody, so a client knows to get a new one.
groundwork-access adds the four to every handler it wraps; the app never writes
them. In the browser, `access/put-refusals-into-shapes` adds them to the
app's shapes by name. Each needs its text in the app's catalogs.

```clojure
(require '[groundwork-access.api :as access]
         '[groundwork-postgres.api :as postgres])

(def settings
  {:cookie-name          "app_session"              ; one per app: cookies don't tell ports apart
   :session-days         30                         ; a person's session unseen this long ends
   :visitor-session-days 7                          ; a visitor's too; then their rows are nobody's
   :renew-after-minutes  60                         ; a session seen again is pushed out at most this often
   :allowed-origin       "https://shop.example"     ; a request that changes something must come from here
   :secure-cookie?       true                       ; false only over plain http, in development
   :token-lead           "shop_"                    ; a token's text starts with it, then 43 random characters
   :token-shown-characters 11                       ; the list shows a token's first 11: the lead and 6 more
   :token-longest-days   365                        ; a token ends at most this far from when it is made
   :outside-issuers      [{:name     :workos       ; [] for none
                           :issuer   "https://auth.shop.example"                    ; a token's iss, exactly
                           :keys-at  [:discovery "https://auth.shop.example/.well-known/oauth-authorization-server"]   ; or [:jwks address]
                           :audience "https://shop.example/mcp"                     ; a token's aud holds it
                           :algorithms #{"RS256"}                                    ; RS256/384/512, ES256/384/512: never none, never HMAC
                           :subject-claim "sub"                                      ; linked to a person at sign-in
                           :clock-skew-seconds 30                                    ; exp and nbf, at most 300
                           :connect-timeout-ms 2000 :answer-timeout-ms 5000          ; fetching its keys
                           :keys-kept-seconds 3600                                   ; then fetched again
                           :fetch-at-most-every-seconds 30}]})                       ; an unknown kid fetches again, at most this often

(def scope-kinds
  (access/check-scope-kinds
   {:company  {:table :companies :parent nil :single? false
               :actions {:company/report #{:read}}
               :roles   {"Director" #{:company/report :orders/see}}      ; a kind below's action: in each shop
               :denies  []}
    :shop     {:table :shops :parent :company :single? false
               :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
               :roles   {"Staff" #{:orders/see :orders/edit} "Bookkeeper" #{:orders/see}}
               :denies  [{:deny :writes :session :impersonated}                  ; an admin acting as someone; or only some
                         ;; of the kind's own actions: {:deny #{:messages/send :payments/take} :session :impersonated}
                         {:deny :writes :when [:= :shop/status "suspended"]}]}  ; keeps its reads
    :platform {:table nil :parent nil :single? true                              ; exactly one scope
               :actions {:platform/support #{:read}} :roles {"Support" #{:platform/support}} :denies []}}))

(def orders                                         ; :gates comes from put-gates-on-tables
  {:table     :orders
   :columns   [[:order/id :uuid {:primary-key? true}]
               [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
               [:order/customer-id :uuid {:optional? true}]
               [:order/visitor-id :uuid {:optional? true}]
               [:order/status :string]]
   :indexes   [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :on-change nil
   :visitor   {:column :order/visitor-id :moves-to :order/customer-id}   ; moved at sign-in
   :access    [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}
               {:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:me :order/customer-id]}
               {:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:me :order/visitor-id]}   ; the visitor asking
               {:allow :orders/see :only :all-of-the-action :to [:member :platform :platform/support]}
               {:allow :orders/see :only :all-of-the-action
                :via {:table :agreements                                                            ; a related row exists
                      :match {:order/shop-id :agreement/shop-id}
                      :to    [:member :agreement/partner-id :agreements/see]
                      :when  [:= :agreement/status "active"]}}]})

;; on workstations: editing an order reads them, and only reads
;;   {:allow :orders/edit :only #{:read} :to [:member :workstation/shop-id]}
;; on shops, the scope's own row:
;;   {:allow :shops/edit :only :all-of-the-action :to [:member :shop/id]}

;; the deploy, as the role that owns the tables
(let [tables (into (access/put-gates-on-tables app-tables scope-kinds)
                   (conj access/access-tables access/sessions-table access/tokens-table access/outside-subjects-table))]
  (postgres/migrate! owner-pool {:tables tables :extensions []
                               :functions (into access/function-files       ; groundwork-postgres makes these before the gates:
                                                (access/make-access-functions app-tables scope-kinds))   ; they call them
                               :views []}
                   changes migrate-options)   ; groundwork-postgres's time limits for each part
  (postgres/grant-tables-to-role! owner-pool tables "app"))   ; the folder groundwork-access too

;; the app, as its own role, which owns nothing
;; who belongs where: a member holds a set of the scope's roles, by id
(postgres/run-in-transaction! app-pool {:read-only? false :log log}
  (fn [transaction]
    (let [roles (access/add-scope! transaction scope-kinds :shop shop-id paul-id company-id)   ;=> {"Owner" #uuid "…" "Staff" … "Bookkeeper" …}
          qc    (access/add-role! transaction scope-kinds [:shop shop-id] "QC approver" #{:orders/edit})]
      (access/add-membership! transaction [:shop shop-id] anna-id #{(get roles "Bookkeeper") qc})   ; :orders/see and :orders/edit
      (access/add-membership! transaction [:shop shop-id] tom-id #{}))))                          ; no role yet

(def ready
  (access/put-access-into-handlers
   {:orders/one   {:action       :orders/see
                   :record       [:orders :order/id]               ; one row: it acts in that row's shop
                   :params-shape [:map [:order/id :uuid]]
                   :answer-shape [:map [:order/id :uuid]]
                   :refusals     {:order/archived nil}             ; the runner's: the steps' own reasons; groundwork-access adds its three
                   :rate-limit   nil                               ; the runner's (groundwork-bridge), nil for none
                   :run-steps-in nil                               ; nil: groundwork-access's transaction runs them
                   :steps        [find-order add-order-answer]}    ; they use (:groundwork-access/transaction context)
    :orders/place {:action :orders/edit
                   :scope  [:shop :order/shop-id]                  ; the shop the param names: held there, or :groundwork-access/not-found
                   :params-shape [:map [:order/shop-id :uuid]
                                  [:workstation-id [:uuid {:groundwork-access/row-of :workstations}]]]   ; a row :orders/edit reads in that shop
                   …}
    :orders/mine  {:action :orders/see
                   :scope  :every-scope-holding-the-action         ; or :no-scope: :anyone, :signed-in, [:me …] only
                   …}}
   {:pool app-pool :tables app-tables :scope-kinds scope-kinds
    :find-session-step (access/make-find-session-step app-pool settings)}))

;; "what may I do?", for the browser (groundwork-screens's "can I?"), as the asker, read-only
(def handlers-with-my-actions
  (assoc ready :groundwork-access/my-actions
         (access/make-my-actions-handler {:pool app-pool :tables app-tables :scope-kinds scope-kinds
                                           :find-session-step (access/make-find-session-step app-pool settings)
                                           :rate-limit {:max 60 :per-ms 60000}})))   ; or nil
;=> [{:scope-kind nil :scope-id nil :actions […]} {:scope-kind :shop :scope-id #uuid "…" :actions [:orders/edit :orders/see]}]

;; a visitor, on the app's own route, the first time it keeps something for them
(let [{:keys [visitor-id set-cookie]} (access/start-visitor-session! app-pool request settings)]
  …)   ; the cart's row names visitor-id; set-cookie is nil when the request had a session already

;; at the end of the app's own login: the visitor's rows become the person's; their
;; subject at WorkOS is linked, so WorkOS AuthKit's tokens with it are theirs (/mcp)
(let [{:keys [set-cookie]} (access/sign-in! app-pool request person-id
                                             {:settings settings :tables app-tables :session-flags #{}
                                              :session-lasts :session-days :outside-subjects {:workos workos-user-id}})]
  {:status 302 :headers {"location" "/" "set-cookie" set-cookie}})
;; an admin acting as someone: flagged, for one hour, never pushed out
(access/sign-in! app-pool request person-id {:settings settings :tables app-tables :session-flags #{:impersonated}
                                              :session-lasts {:minutes 60} :outside-subjects {}})

;; a signed-in person makes a token, in a message's step or on the app's own route;
;; the text is in the answer this once
(access/make-token! app-pool request {:settings settings :name "catalog script"
                                       :ends-at (.plus (Instant/now) (Duration/ofDays 90))})
;=> {:token-id #uuid "…" :text "shop_q3V…" :shown-start "shop_q3V9xK" :ends-at #inst "…"}
;; a script's call with it:  Authorization: Bearer shop_q3V…   is Anna's, with no session flags;
;; its handlers' steps see (:groundwork-access/token-id context), for the app's history
(access/list-tokens app-pool request settings)              ;=> [{:token-id … :name "catalog script" :shown-start "shop_q3V9xK" …}]
(access/revoke-token! app-pool request settings token-id)   ;=> true

;; a page reading rows as whoever asks (its cookie, or a token), held by the gates
(access/run-as-asker-of-request! app-pool request {:settings settings :read-only? true :scope-kinds scope-kinds :log log}
                                  (fn [transaction] (postgres/find-rows transaction list-orders {})))

;; in the REPL: why would Anna's message be refused?
(access/explain-refusal {:pool app-pool :handlers handlers :tables app-tables :scope-kinds scope-kinds}
                         {:person-id anna :visitor-id nil :session-flags #{}}
                         :orders/one {:order/id order-in-another-shop})
;=> {:answer :groundwork-access/not-found :record {…} :memberships [{… :roles [{:role-id … :name "Staff" :actions […]}]}]
;    :sentences [{… :to {:holds? false :needs :orders/see :given-by [] …}}] :denies […]}

;; in the app's tests, each message answered by the app's runner, as over http
;; (in groundwork: groundwork-bridge's answer-message with the app's shaper, log and meter)
(is (= [] (access/find-leaks ready anna-request {:orders [order-in-another-shop] :shops [another-shop]}
                              {:answer-message #(bridge/answer-message %1 :no-rate-limits %2 %3 answer-options)
                               :scope-kinds    scope-kinds :seed 7 :size 30})))
```

The words of a sentence: `:to` is `:anyone`, `:signed-in`, `[:member
column]` or `[:member <kind of one scope>]` (either with the action held
there as a third element), `[:me column]` (the person, or the visitor for
the table's `:visitor` column), `[:system name]` (a named system actor,
`run-as-system!`, never a person;
[why](decisions/2026-10-23-a-named-system-actor-is-allowed-what-its-sentences-give-it.md)),
or `:via {…}` instead of `:to`; `:when` is
`[:= col v]`, `[:not= col v]`, `[:in col [vs]]`, `[:none col]` (not set),
`[:some col]` (set; [why](decisions/2026-10-23-a-when-may-say-a-column-is-set.md)), `[:and …]`,
`[:or …]`; `:only`, on every sentence, is `:all-of-the-action` (every
operation its kind declares) or the operations it allows on this table,
`#{:read}`. A `[:member column]` names the column's scope, or, on a scope
kind's own table, its primary key names the row itself, the scope. A
handler's steps act as its action alone: Postgres counts no other
action's sentences, and only the scopes the handler acts in. A list narrowed to the rows the asker may do an action on:
`WHERE order__id IN (SELECT * FROM groundwork_access."may orders/edit on orders"())`.
A record under a record carries its parent's columns: the table map says
`:parent {:column :job/order-id :carries {:job/shop-id :order/shop-id}}`,
and `put-gates-on-tables` gives it groundwork-postgres's link to the parent (`ON UPDATE
CASCADE`: a parent's change is carried down by Postgres, past the gates)
and a fill of the carried columns on insert, so the app's SQL never names
them and its sentences compare the child's own column
([why](decisions/2026-10-23-records-under-records-carry-their-parents-columns.md)).

## Outside issuers

A bearer token that doesn't start with `:token-lead`, with outside issuers
named, is an outside issuer's: a JWT. Its `iss` picks the issuer; then its
`alg` (one of the issuer's `:algorithms`), its `kid` (a key of the issuer's
JWKS document, or of the one its discovery document names, whose issuer
must be `:issuer`), its signature, `exp` and `nbf` (with
`:clock-skew-seconds`), `aud` (holding `:audience`) and its subject (the
`:subject-claim`, linked to a person by their sign-in's
`:outside-subjects`). The first that fails refuses the message
`:groundwork-access/token-refused`, and the log line
(`:groundwork-access/request-refused`) names it: `:malformed`,
`:unsupported-header`, `:unknown-issuer`, `:algorithm-not-allowed`,
`:no-key-id`, `:keys-unavailable`, `:unknown-key`, `:key-does-not-fit`,
`:bad-signature`, `:expired`, `:not-yet-valid`, `:wrong-audience`,
`:no-subject`, `:subject-not-linked`. The keys are kept in the process
`:keys-kept-seconds`, fetched again for an unknown `kid`, and fetched at
most every `:fetch-at-most-every-seconds`; never through a redirect.
[Why](decisions/2026-10-27-groundwork-access-trusts-outside-issuers-the-app-names.md).

## Tests

```sh
clojure -X:test     # the Postgres tests are skipped without a database

# with a throwaway Postgres:
initdb -D /tmp/access-pg -A trust -U "$USER"
pg_ctl -D /tmp/access-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/access-pg.log start
ACCESS_TEST_PORT=5499 ACCESS_TEST_USER=$USER clojure -X:test
pg_ctl -D /tmp/access-pg stop
```

A gate refusing a row and a write in a read-only transaction log their
errors during the tests; that is expected. The outside issuers' test
starts a stand-in issuer on 127.0.0.1, with keys of its own; the session
lookup's test prints, per question, the pool checkouts, the statements
and the time. The tests need a superuser
for `ACCESS_TEST_USER` (it makes the role `groundwork_access_app`, which owns
nothing, and `groundwork_access_owner`, not a superuser, which owns the tables of
the system actor's and the records-under-records tests, as in production;
and it writes the test rows past the gates).

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
