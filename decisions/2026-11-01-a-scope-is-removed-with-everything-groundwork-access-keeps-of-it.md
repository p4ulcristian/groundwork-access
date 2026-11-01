# A scope is removed with everything groundwork-access keeps of it

> Paul's decision, 2026-11-01 (the switch of Iron Rainbow onto composer v8: a deleted shop left its scope, its Owner role and that role's row behind). Accepted 2026-11-01. Since v0.8.7.

**Decision:** `remove-scope!` `(transaction [kind id])` removes a scope with its memberships and the roles they hold, its roles (Owner too) and their actions, and its row, in the caller's transaction: the one that deletes the app's own row. It answers whether there was such a scope. While scopes sit under it, it refuses (`:groundwork-access/scope-has-scopes-below`, `{:scope :below n}`): the app removes those first, as it deletes their rows first.

**Why:** the scopes, roles and memberships are groundwork-access's rows, and only its functions keep them right (a role's name unique in its scope, a member's roles the scope's own). An app that deletes a shop had no way to take its scope with it but deleting groundwork-access's tables by hand, or leaving the scope and its Owner role behind for ever, held by nobody and named by no row.

**Instead of:**
- **a cascade from the app's row:** the scopes have no reference to the app's tables (a scope is a kind and an id, of any table or of none), so Postgres can't follow it
- **removing the scopes under it too:** what goes with a scope below is the app's to say (a company's shops may move to another company); refusing keeps the removal of each one the app's own call
- **the app deleting the rows itself:** the tables are groundwork-access's, their shape its own to change
