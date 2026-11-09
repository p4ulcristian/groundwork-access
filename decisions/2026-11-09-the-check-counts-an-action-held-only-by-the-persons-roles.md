# The check counts an action as held only by the person's roles; a sentence naming another action held gives rows, never the action

> The user's decision, 2026-11-09 (Iron Rainbow's review: a Viewer passed the check of 54 of its 81 shop actions, `:orders/edit` among them, because an open sentence gave every member rows of related tables as those actions). Not released. Changes in part [[:member …] names a column or a kind of one scope, and may name the action held there](2026-10-23-a-member-sentence-names-a-column-or-a-kind-of-one-scope-and-what-is-held-there.md), [access, piece 3: the check](2026-10-22-access-piece-3-the-check.md) and ["What may I do?" is a handler for the browser](2026-10-23-what-may-i-do-is-a-handler-for-the-browser.md).

**Decision:** the check in front of a handler, and "what may I do?", count an action as held only by the person's roles (a role of the scope, or above it; a small role of one action is a role):

- **A `[:member …]` sentence that names another action held there** (`[:member :order/shop-id :orders/edit]` allowing `:orders/note`, `[:member :platform :platform/support]` allowing `:orders/see`, the same in a `:via`'s `:to`) **gives rows, never the action.** The gates and `"may <action> on <table>"()` keep it: the person's rows of that action while a handler acts as it. The check and "what may I do?" don't count it.
- **`:scope [kind param]`**: the person's roles hold the action in that scope (`is-held-by-roles?`), else `:groundwork-access/not-found`.
- **`:record [table param]`**: the record is allowed by the action's sentences that give the action itself (`sentences/gives-the-action-itself?`; `make-sql-conditions`' new `:check` condition). One the asker can see and not act on is `:groundwork-access/not-allowed`, as before.
- **`:every-scope-holding-the-action`**: an open way (`:anyone`, `:signed-in`, `:visitor`) or `[:holds <the action>]` held by a role somewhere; `[:holds <another action>]` doesn't count.
- **"What may I do?"** lists no action a sentence gives where another action is held, and a `:via`'s scopes only when its `[:member …]` needs the action itself.
- What Postgres is told once the check lets a handler through is unchanged: the scopes its sentences reach (`is-held-there?`), so the rows stay what the sentences say.

An app that relied on such a sentence for an endpoint gives the action to a role (or names the action itself in the sentence and gives it to the role that held the other).

**Why:** "may this person do this?" was answered by "may this person see rows of this?". A sentence written so that every member reads the users named on the shop's orders, as whichever action reads users, made every member hold every such action at the check: a Viewer passed `:orders/edit`, and only the gates stopped the write, with a 500 or a quiet nothing. Who holds an action is what roles are for; sentences say which rows.

**Instead of:**
- the app rewriting each sentence as a split action per table: every app would have to know the check counts sentences, and one missed sentence opens an action to every member again
- a setting choosing the old counting: the old counting is the hole; no app should be able to turn it back on
- leaving `:record` handlers on every sentence: a related-row sentence (a partner's agreement) would still let a member act on a record as an action their roles don't hold
