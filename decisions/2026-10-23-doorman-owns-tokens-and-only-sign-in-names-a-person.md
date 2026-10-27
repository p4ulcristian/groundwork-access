# doorman owns a person's tokens, and only sign-in names a person

> **Changed in part** by [The session is looked up in the question's own transaction](2026-10-27-the-session-is-looked-up-in-the-questions-own-transaction.md), [A handler sees which token asked](2026-10-27-a-handler-sees-which-token-asked.md) and [groundwork-access trusts outside issuers the app names](2026-10-27-groundwork-access-trusts-outside-issuers-the-app-names.md) (2026-10-27, v0.8.0): `run-as-asker-of-request!` finds its asker in its own transaction; the context names the token, `:groundwork-access/token-id`; `/mcp`'s WorkOS tokens are checked by groundwork-access as an outside issuer's, the person linked at sign-in, and make no token; `sign-out-everywhere!` unlinks them too.

> **Changed in part** by [A transaction doorman opens carries its caller's log](2026-10-23-a-transaction-doorman-opens-carries-its-callers-log.md) (2026-10-23, on tidy-up): `run-as-asker-of-request!` takes `{:settings :read-only? :scope-kinds :log}`, the caller's log (or nil) written out.

> The user's decision, 2026-10-23, from the tidy-up after Groundwork v1.1 (Iron Rainbow's personal tokens for the catalog and AI calls, its `labs_api_tokens`). Changes in part [The session is the only truth](2026-10-12-the-session-is-the-only-truth.md) and [Sessions for visitors, and none for servers](2026-10-12-sessions-for-visitors-and-none-for-servers.md). Built on tidy-up, not released.

**Decision:** one door names a person to doorman: `sign-in!`, called once the app's own login has proven who someone is. Every call after it carries a proof doorman made and reads itself:

- **a cookie**, from a browser: the session, as today;
- **a bearer token**, from a script or an AI acting as a person: `Authorization: Bearer …`. A signed-in person makes a token for themselves, through doorman, with a name and an end date, both required. doorman shows it once and keeps only its SHA-256, as it does a session's; the person lists their tokens and revokes them; a removed member's tokens end with their sessions.

A token's call is that person's: the same actions, scopes and gates. No Origin check (a bearer token isn't sent by a browser on its own).

`run-as-asker!` leaves doorman's public api: no code can tell doorman "this is p-17". The composer's routes get the asker from doorman's own step, from the cookie or the token. The REPL's "ask as this person" stays, in the REPL only, named for what it is.

**Why:** "who is this" stays one place's answer. A token is a session without a browser. If the app checks its own tokens and then names the person, any code holding the pool can name anyone, and nothing checks it.

**The price:** doorman gains a tokens table and its migration. Iron Rainbow's tokens move into it with their hashes, so nobody has to make a new one: IR keeps the SHA-256 of the whole token text (`ircat_` and 43 characters), and doorman hashes the whole text too. The composer's `answer-as` takes the request instead of an asker.

**Not decided here:** the token's text (IR's starts with a lead, `ircat_`, so a leaked one says what it is, and shows its first 12 characters in the list); a token that holds fewer actions than its person; the longest end date; `/mcp`'s WorkOS OAuth tokens (by "the session is the only truth", the app checks one once and doorman issues its own); which route kind token calls arrive on.

**Settled while building** (the user to review):

- **The settings** are three more required keys of `settings-shape`: `:token-lead` (`"ircat_"` for IR), `:token-shown-characters` (IR's 12) and `:token-longest-days`. The list may show at most the lead and half of the 43 random characters; more refuses the start.
- **The text** is the lead and 43 characters (32 random bytes, URL-safe base64 without padding). The table keeps the SHA-256 of the whole text as lowercase hex, as a session's cookie's, and the first `:token-shown-characters` at making (`:token/shown-start`), since the text itself is gone. IR's import has to bring its hashes in the same encoding.
- **The table**, `tokens-table` (`doorman.tokens`): `:token/id` (uuid, the key), `:token/fingerprint` (unique), `:token/person-id`, `:token/name`, `:token/shown-start`, `:token/created-at`, `:token/ends-at`. No last-used column: no write on every call. The app migrates it by cellar beside `sessions-table`.
- **`(make-token! pool request {:settings :name :ends-at})`** finds the person from the request, as every token function does; nothing takes a person id. Only a signed-in session makes one: nobody, a token's call (a token makes no token, so a leaked one can't outlive its revocation) and a session with flags (the token, having none, would shed the denies an impersonating admin is under) are refused, `:doorman/no-token-for-asker`. `:ends-at` is a `java.time.Instant`, after now and at most `:token-longest-days` away, else `:doorman/bad-token-end`. It returns `{:token-id :text :shown-start :ends-at}`; the text is never readable again.
- **`list-tokens` and `revoke-token!`** take the request too, by a cookie or a token: the person's tokens that haven't ended, newest first, without the text; revoking deletes the row, only the asker's own. `delete-ended-tokens!` forgets ended ones, a nightly job beside `delete-ended-sessions!`.
- **The step**: `Authorization: Bearer` (the scheme in any case; another scheme, like Basic, is no token) is read before the cookie. A request carrying both the app's cookie (live or not) and a bearer token is answered with an error. A wrong, ended or revoked token is nobody, as a stale cookie is. A token's asker has no session flags and no visitor. The message's log gets `{:person-id … :token-id …}`. `find-session-key-of-request` hashes the token first, so rate limits count per token.
- **A removed member** loses the scope on every call at once, by token or cookie, since memberships are read per call; `remove-membership!` ends nothing else. `sign-out-everywhere!` ends every session and token of the person in one transaction, and returns `{:sessions n :tokens m}` (a count before).
- **`run-as-asker!` became `run-as-asker-of-request!`** `(pool request {:settings :read-only? :scope-kinds} f)`: the asker found by the step's rules; a request the step would refuse throws `:doorman/refused-request`.
- **The REPL's "ask as this person"** is `doorman.repl/make-step-asking-as`, a step in place of the find-session step, outside `doorman.api`; it puts the asker in the context and the log with the same code as the step.
- **`explain-refusal` still takes an asker**: it is read-only and answers the check's reasons, not rows; it stays in `doorman.api`.

**Instead of:**
- **the app checks its tokens and names the person** (today's `run-as-asker!` and `answer-as`): any server code can act as anyone
- **token calls run as a named system actor**: an AI's call no longer sees what its person sees; every reach needs its own sentences
