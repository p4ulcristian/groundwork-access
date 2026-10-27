# The session is looked up in the question's own transaction

> **Changed in part** by [run-as-asker-of-request! hands f the asker it found](2026-10-27-run-as-asker-of-request-hands-f-the-asker-it-found.md) (2026-10-27): `run-as-asker-of-request!` calls `(f transaction asker)`.

> The user's decision, 2026-10-27 (the switch of Iron Rainbow onto groundwork-3, decision 3: one question per POST; the session lookup joins the question's own transaction). Since v0.8.0. Changes in part [access, piece 3: the check](2026-10-22-access-piece-3-the-check.md), ["What may I do?" is a handler for the browser](2026-10-23-what-may-i-do-is-a-handler-for-the-browser.md) and [doorman owns a person's tokens](2026-10-23-doorman-owns-tokens-and-only-sign-in-names-a-person.md) (`run-as-asker-of-request!`).

**What was wrong:** a handler's question went to Postgres twice: the find-session step looked the session (or token) up on the pool, a checkout of its own, and then the check's transaction took a second connection. Two pool checkouts per question, three when the session was pushed out (its own autocommit `UPDATE`).

**Decision:** the question's transaction is opened first, and the session is looked up in it.

- **`put-access-into-handlers`' and `make-my-actions-handler`'s** `:run-steps-in` open the question's transaction read-write on the pool, put it at `:groundwork-access/transaction` and run the find-session step in it: the session's or the token's lookup is the transaction's first statement, on the same connection; a session seen again is pushed out in it. Refused by the step (another Origin, a cookie and a token at once), the answer is that refusal and nothing else runs. Let through, the transaction is joined again with the message's log the step added the asker to, so a step's after-commit work that fails is still written with the request id and the person.
- **An action that only reads** runs read-only as before: the transaction is made read-only (`transaction_read_only`, local to it) in the same statement that tells Postgres who asks with every action (`set_asking_read_only.sql`), before the check and before any step. Once a query has run, Postgres refuses to make a transaction read-write again, so no step can undo it.
- **The find-session step** reads on `(:groundwork-access/transaction context)` when it is there, else on its pool, as groundwork-postgres's functions take a pool or a transaction: `make-find-session-step` still takes `[pool settings]`, and a step the composer runs on its own (a page, an upload, the browser's errors) still works.
- **Pushing a session out** in the question's transaction holds its row until the question ends; a second question of the same session due for it at the same time skips it (`FOR UPDATE SKIP LOCKED`) and leaves it to the first, so no question waits for another.
- **`run-as-asker-of-request!`** looks its asker up in its own transaction the same way (opened read-write, made read-only before `f` when `:read-only?` is true).
- The Origin check, bearer tokens, refusals and session flags are as they were.

**Measured** (`groundwork-access.question-transaction-test`, 2000 questions each, Postgres on the same machine): pool checkouts per question 2 before, 1 now, for a cookie or a token, an action that reads or writes; statements per question 6 before and 6 now; median time, reading by a cookie 1.12–1.26 ms before, 0.92–0.98 ms now; writing 2.70–3.17 ms before, 2.14–2.18 ms now.

**Why:** one connection per question, the decided shape; a pool sized for N questions at once serves N, not N/2. The lookup in the transaction reads what the question's statements read.

**The price:** a pushed-out session's row stays locked until its question ends, so a sign-out of that session (a `DELETE`) waits for the question, at most the statement timeout, once in `:renew-after-minutes`. A refused cross-site request now takes a pool connection (no statement runs on it). A transaction is opened read-write and made read-only one statement later, not by `BEGIN READ ONLY`.

**Instead of:**
- **the session lookup and the asking told in one SQL function**: fewer statements, but the session's rules (cookie, token, Origin, the push) would move into SQL, away from the step the REPL's "ask as" replaces
- **a read-only transaction and the push as after-commit work**: a second checkout whenever a session is due, and a push lost when the question fails
- **`make-find-session-step` without a pool, only inside a transaction**: every place the composer runs the step on its own would need a transaction of its own
