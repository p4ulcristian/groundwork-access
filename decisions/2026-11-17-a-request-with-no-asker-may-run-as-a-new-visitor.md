# A request with no asker may run one transaction as a new visitor, whose id groundwork-access makes

> Decided by the user, 2026-11-17, from Iron Rainbow's frame uploads (a shop's site embeds the customizer in an iframe; every photo upload there was refused by the gates). Since v0.8.10.

**Decision:** `run-as-new-visitor!` runs `(f transaction visitor-id)` in one read-write transaction, as a visitor made for it: groundwork-access makes a fresh id (`UUID/randomUUID`, as `start-visitor-session!` does), tells Postgres that visitor is asking, and hands the id to `f`. No session row is made. It runs only for a request groundwork-access finds no asker in, by `run-as-asker-of-request!`'s rules: a person, a visitor session or a token throws `:groundwork-access/request-has-asker`; a request the step refuses throws `:groundwork-access/refused-request`. Options, every key required: `{:settings :log}`.

```clojure
(access/run-as-new-visitor! pool request {:settings settings :log log}
                            (fn [transaction visitor-id]
                              (postgres/run-statement! transaction insert-upload {:upload/visitor-id visitor-id})
                              visitor-id))
```

**Why:** a page in another site's frame keeps no cookie (a third-party frame's cookies are blocked or SameSite=Lax), so no visitor session can start there, and its request has no asker. Its writes of a visitor's rows (an upload) were refused, since every visitor sentence is `= find_asking_visitor()`, null for nobody. The app carries the new id on itself (Iron Rainbow signs it into a claim) to the tab that takes the rows over. The id is made by groundwork-access, never taken from the caller, so no code can act as a visitor someone else already is; and a request that has an asker is refused, so a signed-in person or a visitor session never poses as another visitor. The gates stay as they are: the rows `f` writes must name the new visitor.

**The price:** a visitor with no session: nothing finds them again but the id the app carries, and nothing ends them (their rows stay until the app's own clean-up forgets them). A read-only run has no use (a new visitor has no rows), so there is no `:read-only?`.

**Instead of:**
- **the app sets `groundwork_access.asking` itself:** the setting is the gates' input, not an api; an app that writes it can claim any asker, and breaks silently when its shape changes
- **the caller passes the visitor id:** a caller could act as another visitor, one with a session and rows
- **a visitor session started and its cookie put on the request:** a session row per upload that no browser keeps, and a request faked to carry a cookie it never had
- **a named system actor allowed to insert visitors' rows:** a looser gate, for rows that are a visitor's
- **a partitioned (CHIPS) cookie for the frame:** changes every session cookie's policy, and not every browser an app supports keeps one
