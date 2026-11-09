# [:member …] names a column or a kind of one scope, and may name the action held there

> **Changed in part** by [The check counts an action as held only by the person's roles](2026-11-09-the-check-counts-an-action-held-only-by-the-persons-roles.md) (2026-11-09, not released): a `[:member … action]` naming another action than the sentence's gives rows only: the check and "what may I do?" no longer count it.

> **Changed in part** by [A handler says where it acts, and Postgres hears only that](2026-10-23-a-handler-says-where-it-acts-and-postgres-hears-only-that.md) (2026-10-23, built on tidy-up, not released): on a scope kind's own table, `[:member <its primary key>]` names the row itself, the scope (`[:member :shop/id]` on `shops`); it was refused, "names no scope".

> Since v0.7.0. Builds "A kind of scope may have exactly one scope" of [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md), its "Settled while building" (`[:member <scope> <action>]`), and the platform's side of [A record between fixed sides carries them](2026-10-13-a-record-between-fixed-sides-carries-them.md).

**Decision:**

- **A kind of one scope** writes `:single? true` (every kind writes `:single?` out). `add-scope!` refuses a second scope of it, one transaction at a time (an advisory lock on the kind).
- **`[:member :platform]`** names such a kind instead of a column: a member of its one scope whose role holds the action, on every row the sentence is on. Naming a kind with more scopes, or no kind, is refused at the start. A fixed scope is named by its kind, never by its id.
- **`[:member column action]` and `[:member kind action]`**: the third element is the action the person must hold there; without it, the sentence's own action. It must be an action of the scope's kind or a kind below it, or nobody could hold it there: refused at the start, also when it is the sentence's own action.

```clojure
{:allow :orders/see  :to [:member :platform :platform/support]}   ; the back office reads every order
{:allow :orders/note :to [:member :order/shop-id :orders/edit]}    ; whoever may edit the shop's orders may note them
```

- "What may I do?" lists such a column sentence's action in each scope where the held action is held, and a kind-of-one-scope sentence's under the entry for every scope, for whoever holds the action there.

**Why:** the platform's staff are a side of shared records (a conversation between a customer, a shop and the back office); a kind of one scope gives them memberships and roles like every other scope, with no column on every row pointing at it. Naming the held action lets one role's action reach another action's rows without a role per combination.

**The price:** a kind of one scope's sentences reach every row of their table, which is what they are for and what they risk; the leak test can't tell a too-open one from a right one.

**Instead of:**
- a fixed scope's id in the sentence (`[:member #uuid "…"]`): ids differ per database, and the code would name data
- a platform column on every shared row: one more column to fill, always the same value
