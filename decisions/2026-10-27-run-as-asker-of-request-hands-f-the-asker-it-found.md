# run-as-asker-of-request! hands f the asker it found

> Decided by the agent releasing the composer's switch changes, 2026-10-27, from the user's brief (the composer's page runs the find-session step and then `run-as-asker-of-request!`, which looks the session up a second time: make it once). Since v0.8.3. Changes in part [The session is looked up in the question's own transaction](2026-10-27-the-session-is-looked-up-in-the-questions-own-transaction.md) (`run-as-asker-of-request!`).

**Decision:** `run-as-asker-of-request!` calls `(f transaction asker)`: the asker it looked up, once, as the transaction's first statement, `{:person-id :visitor-id :session-flags :token-id :outside-issuer}` (nils and `#{}` for nobody). Nothing else changes: the lookup's rules, the refusal it throws, read-only made before `f`.

```clojure
(access/run-as-asker-of-request! pool request {:settings settings :read-only? true :scope-kinds scope-kinds :log log}
                                  (fn [transaction {:keys [person-id]}]
                                    (if person-id (postgres/find-rows transaction list-orders {}) :to-login)))
```

**Why:** a caller outside a message often needs both: who asks (a page that sends nobody to the login) and rows read as them. With `(f transaction)` it had to run the find-session step first, on the pool, and then this function looked the same session up again in its transaction: two lookups and two pool checkouts for one page. Handed the asker, one transaction does both.

**The price:** every `f` takes two arguments; a one-argument `f` (`#(… %)`) now throws an arity error.

**Instead of:**
- **a second function that hands the asker, beside the old one:** two names for one lookup
- **the asker read back from the transaction's setting:** Postgres's `groundwork_access.asking` is the gates' input, not an api
