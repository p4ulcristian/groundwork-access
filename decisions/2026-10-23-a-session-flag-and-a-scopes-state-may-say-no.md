# A session flag and a scope's state may say no, on the kind whose actions they deny

> **Added to** by [What an impersonator may not do needs nothing new](2026-10-27-impersonation-s-denies-need-nothing-new.md) (2026-10-27, checked on v0.8.0): a deny of a set of actions by `:impersonated` is what an impersonator may not do; the check, the gates, "what may I do?" and explain-refusal hold it.

> **Changed in part** by [A suspended scope's own row is frozen for its own kind's actions](2026-10-23-a-suspended-scopes-own-row-is-frozen-for-its-own-kinds-actions.md) (2026-10-23, built on tidy-up, not released): a state deny holds on the scope's own row too (the shop in `shops`), for the kind's own actions; the state is lifted by another kind's action or a named system actor.

> Since v0.7.0. Builds the deny of [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md) and its "Settled while building".

**Decision:** a kind of scope writes out `:denies`, `[]` for none. A deny is about the kind's own actions: `{:deny #{:money/move}}` (a set, or one action) or `{:deny :writes}` (every action of the kind that inserts, updates or deletes); naming another kind's action is refused at the start. It says no by one of two things, never a person or a row:

```clojure
:shop {…
       :denies [{:deny :writes :session :impersonated}                    ; a session flag
                {:deny :writes :when [:= :shop/status "suspended"]}]}     ; the scope's state
```

- **A session flag** is given at sign-in, `:session-flags #{:impersonated}` (required, `#{}` for none), kept on the session row (`:session/flags`, the names joined by spaces), put in the context as `:doorman/session-flags` and told to Postgres (`doorman.has_session_flag(flag)`).
- **A scope's state** lives in the app's own table, the kind's `:table` (a one-column `:uuid` primary key, the scope's id); the `:when` is a condition on its columns, checked at the start. It is read past the gates by a function doorman installs, `doorman.denied_…()`, the ids of the kind's scopes in that state.
- **Where it says no:** in each action's condition, so the check and the gates agree. A named action is denied in every operation; `:writes` only in the operations that write, so an action that reads and writes still reads. A state deny holds for a row whose columns naming that kind name a scope in the state, in any of them (a row of two scopes); a suspended shop keeps its reads and loses its writes, and a named reading action is hidden there. The scope's own row (the shop in `shops`) is no row of the scope, so the app lifts the state with an ordinary action.
- **The answers:** a record the asker may see but a deny holds for answers "Not allowed."; a handler without a `:record` whose action a session flag denies answers "Not allowed."; "what may I do?" leaves a flag's actions out everywhere and a state's out of that scope.

**Why:** the two denies decided, and only those, so a "no" is always one of two kinds and explain-refusal can name it. On the kind, because a deny is about actions and the scope kinds already list them, and every function that needs denies takes the scope kinds already. Row by row, because the gates know rows, not requests: a deny that held only through the asker's memberships would leave rows reached through `[:me …]` or a `:via` writable in a suspended shop.

**The price:** a flag deny wanted for every kind is written on each. A state deny reads the kind's table once per statement that asks; that table needs an index on what the `:when` reads when it is long. A suspended shop's rows are read-only for everyone who writes them through that kind's actions, the shop's own customers too.

**Instead of:**
- denies as a list of their own, beside the sentences: one more value for every function to take, for actions the kinds already hold
- a deny on each table's `:access`: a table forgotten is a write left open in a suspended shop
- the state as a column on doorman's scopes table: the reason (a trial ending, a bill) stays in the app, so two places would have to agree
- the state deny applied to the asker's memberships only: `[:me …]` and `:via` rows would still write
