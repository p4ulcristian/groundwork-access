# access, piece 3: the check, put in front of every handler

> **Changed in part** by [The session is looked up in the question's own transaction](2026-10-27-the-session-is-looked-up-in-the-questions-own-transaction.md) (2026-10-27, v0.8.0): the transaction opens first, read-write, and the find-session step runs in it, its lookup the first statement; an action that only reads is made read-only before the check.

> **Changed in part** by [A refusal is a named reason, declared on its handler](https://github.com/p4ulcristian/bridge/blob/tidy-up/decisions/2026-10-23-a-refusal-is-a-named-reason-declared-on-its-handler.md) (bridge's, 2026-10-23, built on tidy-up, not released): the check refuses by bridge's `refuse` with doorman's reasons, `:doorman/not-found` and `:doorman/not-allowed`, and `:doorman/signed-out` in place of either when nobody is signed in, whatever the record; doorman adds the three to every handler's `:refusals`.

> **Changed in part** by [A handler says where it acts, and Postgres hears only that](2026-10-23-a-handler-says-where-it-acts-and-postgres-hears-only-that.md) (2026-10-23, built on tidy-up, not released): every handler writes `:record` or `:scope` (`[kind param]`, `:every-scope-holding-the-action`, `:no-scope`); with `:scope [kind param]` the check asks for the action in the named scope; once the check lets a message through, Postgres is told only the handler's action, in the scopes it acts in.

> **Added to** by [A session flag and a scope's state may say no](2026-10-23-a-session-flag-and-a-scopes-state-may-say-no.md) and [Visitors get sessions of their own](2026-10-23-visitors-get-sessions-and-their-rows-move-at-sign-in.md) (2026-10-23, v0.7.0): who is asking carries the visitor and the session's flags; a handler without a `:record` runs for an asker who can have its action by a way (open, a visitor's, an action held somewhere) and answers "Not allowed." when a session flag denies it.

> **Changed in part** by [A handler writes out mailroom's keys, and its :run-steps-in is nil](2026-10-22-a-handler-writes-out-mailrooms-keys.md) (2026-10-22, v0.1.1): a handler also writes out `:rate-limit` and `:run-steps-in`, and `:run-steps-in` must be nil.

> **Changed in part** by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) (2026-10-22): who is asking is the setting `doorman.asking`; the steps' transaction is at `:doorman/transaction`; `put-access-into-handlers`'s options are checked, every key required.

> **Added to** on 2026-10-22: a handler without a `:record` (a list, a new row, an email) runs only if the person can have its action at all: held in some scope, or open to them by an `:anyone`, `:signed-in` or `[:me …]` sentence; otherwise "Not found.". Which rows, the gates decide.

> Builds on [doorman ships permissions](2026-10-12-doorman-ships-permissions.md), Groundwork's "What every endpoint declares" and [Ring at the edge, steps inside](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-ring-at-the-edge-steps-inside.md).

**Decision:**

- **Every handler declares** its `:action`, `:params-shape` and `:answer-shape`, and, when the message names one row, its `:record` (`[:orders :order/id]`: the table, and the param holding the row's id). `put-access-into-handlers` refuses, with every problem at once, a handler missing one, or a `:record` whose table has no sentence allowing the action.
- **The app doesn't write the check.** Each handler gets it in front of its steps, and a `:run-steps-in` that, for each message: finds the session; opens one transaction; tells Postgres who is asking (`groundwork.asking`: the person, and the scopes holding each action, from `list-actions-of-person`); runs the steps with `:groundwork.server.access/transaction` in the context.
- **The check:** with a `:record`, one query asks whether the row exists and the action's condition (piece 2) holds for it; if not, the answer is "Not found.", the same for a row of another shop, a row that doesn't exist and nobody signed in, and no step of the handler runs.
- `install-access-functions!` installs the two functions the conditions read (`groundwork.find_asking_person()`, `groundwork.find_scopes_holding(kind, action)`).

**Why:** a check an app must remember to write is the leak the app this was learned from had; here a handler can't run without one, and the check and the gates (piece 4) read the same conditions and the same setting.

**The price:** a handler without `:record` (a list, a new row) isn't checked here; the gates (piece 4) filter lists and refuse a new row that breaks the rule. "Not allowed" for a row the person may see but not change isn't told apart from "Not found." yet. Reads don't run read-only yet. The handler's steps must use the transaction, not the pool; nothing stops them yet.

**Instead of:** a check step the app adds to each handler: one forgotten handler is a leak.
