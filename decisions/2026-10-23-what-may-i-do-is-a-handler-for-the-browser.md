> **Changed in part** by [The session is looked up in the question's own transaction](2026-10-27-the-session-is-looked-up-in-the-questions-own-transaction.md) (2026-10-27, v0.8.0): the find-session step runs in the handler's transaction, opened read-write; its step makes it read-only before it reads.

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

> **Changed in part** by [doorman owns the shape of "what may I do?"'s answer, on both sides](2026-10-23-doorman-owns-the-my-actions-answer-shape-on-both-sides.md) (2026-10-23, v0.6.0): the shape is written once, in `doorman.shapes` (`.cljc`), and the browser takes it from `doorman.api`'s `.cljs` half; parlor's copy is gone.

> **Added to** (2026-10-23, v0.7.0) by [Visitors get sessions of their own](2026-10-23-visitors-get-sessions-and-their-rows-move-at-sign-in.md), [[:member …] names a column or a kind of one scope](2026-10-23-a-member-sentence-names-a-column-or-a-kind-of-one-scope-and-what-is-held-there.md), [:via reads related rows through a function](2026-10-23-via-reads-related-rows-through-a-function-and-guards-what-they-match.md) and [A session flag and a scope's state may say no](2026-10-23-a-session-flag-and-a-scopes-state-may-say-no.md): a visitor's open actions, actions given where another is held, a via's scopes, and the denies taken out; it tells Postgres who is asking.

# "What may I do?" is a handler for the browser; open actions are listed for every scope

> Adds to [access, piece 1: who belongs where](2026-10-22-access-piece-1-who-belongs-where.md) and [access, piece 3: the check](2026-10-22-access-piece-3-the-check.md). Answers what parlor's ["Can I?" reads an answer the app names](https://github.com/p4ulcristian/parlor/blob/main/decisions/2026-10-22-can-i-reads-an-answer-the-app-names.md) left open. Since v0.4.0.

**Decision:** `(doorman/make-my-actions-handler options)` makes a mailroom handler, every key written out, that answers the asking person's actions for the browser. The app puts it in its handler table under a name of its choosing; house names it `:doorman/my-actions`. Its params are `{}`; its answer, `my-actions-answer-shape`, is parlor's `actions-answer-shape`:

```clojure
[{:scope-kind nil   :scope-id nil      :actions [:comments/write :posts/read :profile/edit]}  ; in every scope
 {:scope-kind :shop :scope-id #uuid "…" :actions [:orders/edit :orders/see]}]
```

- **A scope** the person is a member of gives one entry, with the actions their role there allows (`list-actions-of-person`; an Owner, every action of the kind).
- **The open actions** are one entry of nil kind and id, "in every scope": the actions of `:anyone` sentences for everyone, and, for someone signed in, those of `:signed-in` sentences and of `[:me column]` sentences too. Nobody signed in gets the `:anyone` ones only. An app with no open actions answers no such entry.
- **A `[:me column]` action** is had on the rows whose column names the person, which no scope lists. It is in the nil entry: "can I?" says yes in any scope, and the row decides, as the check does for a handler without a `:record` (a `[:me …]` sentence makes an action open to anyone signed in). A sentence's `:when` narrows rows the same way and is not looked at.
- **Order:** the nil entry first, then scopes by kind, then id; each list of actions sorted. The same rows give the same answer.
- **It runs as the asking person:** the app's find-session step first (the Origin checked; `:doorman/person-id`), then one read-only transaction at `:doorman/transaction`, in which its one step reads the memberships. Reading in the step, not around it, lets doorbell's notes name doorman's tables, so a screen asks again when a membership or role changes (when the app has those tables tell).
- **The options**, every key required, checked first: `:pool`, `:tables`, `:scope-kinds`, `:find-session-step` (as `put-access-into-handlers`'s), and `:rate-limit`, mailroom's, nil for none.
- It is ready as it is, not for `put-access-into-handlers`, which wants an `:action`: asking what one may do needs no permission.

**Why:** the browser hides what the person can't do (a hint; the check and the gates decide), and only doorman knows the actions, including the ones no membership gives: leaving them out, as `list-actions-of-person` does, says no to every open action. One entry for "everywhere" is what parlor reads already, and what v0.5.0's grants gave with no scope.

**The price:** a `[:me …]` or `:when`-narrowed action reads as yes in every scope, also where the person has no row it applies to: a button shows, and the server answers "Not found.". The answer's shape is written twice, here and in parlor (`actions-answer-shape`): doorman is the server's and parlor the browser's, and neither takes the other.

**Instead of:**
- a third kind of entry for `[:me …]` actions (`:scope-kind :me`): parlor's "can I?" would need a rule for it, and a screen asking about a scope still couldn't be told which rows
- leaving `[:me …]` actions out: a person couldn't see the button to edit their own profile
- the actions in the page shell: stale after a role changes, and nothing asks again
- a fixed message name inside doorman: the handler table is the app's; the name is where it says it
