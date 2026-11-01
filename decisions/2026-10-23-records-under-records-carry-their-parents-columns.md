# Records under records carry their parent's columns, by cellar's link and a fill

> **Changed in part** by [A child's link to its parent deletes as its parent column says](2026-11-01-a-childs-link-deletes-as-its-parent-column-says.md) (2026-11-01, v0.8.4): the link deletes, and is checked, as the parent column's own reference says; its price below no longer holds.

> **Changed in part** by [A column a child carries where set is filled, and follows its parent past the gates](2026-11-01-a-column-a-child-carries-where-set-is-filled-and-follows-its-parent-past-the-gates.md) (2026-11-01, v0.8.5): a column that may be empty is carried apart (`:carries-optional`), filled, and followed by a trigger on the parent that runs past the gates.

> Since v0.7.5. Replaces [Records under records wait for cellar's matching links](2026-10-23-records-under-records-wait-for-cellar.md): cellar has them since v0.4.5 ([A table map says references over several columns, and the triggers a library declares](https://github.com/p4ulcristian/cellar/blob/main/decisions/2026-10-23-a-table-map-says-references-over-several-columns-and-a-librarys-triggers.md)). Builds [Every scoped table names its scope](2026-10-12-every-scoped-table-names-its-scope.md), [A record of two scopes may have a parent and children](2026-10-13-a-record-of-two-scopes-may-have-a-parent-and-children.md) and the carrying half of [A record between fixed sides carries them](2026-10-13-a-record-between-fixed-sides-carries-them.md).

**Decision:** a table map says its parent in doorman's words, `:parent {:column :job/order-id :carries {:job/shop-id :order/shop-id}}`: the column naming the parent (a reference to the parent's one-column primary key), and each carried column with the parent's column it equals. doorman keeps them equal, with cellar:

- **the link:** `put-gates-on-tables` adds to the child's `:references` the parent column and the carried ones pointing at the parent's id and columns, `ON UPDATE CASCADE`: a parent's change is carried to its children by Postgres in the same statement, as its referential action (it runs as the tables' owner, the gates not forced on it, so the children's gates don't stop it), down every level; a child can hold no other value. The parent gets the unique key the link needs from cellar.
- **the fill:** it adds to the child's `:triggers` one before each insert and each change of the parent column, calling `doorman.fill_…()` (installed by `install-access-functions!`), which sets the carried columns from the parent's row, read past the gates; the app's SQL never names them, and a value it names is overwritten. The child's insert gate then checks the filled scope.
- **checked at the start:** the parent column references a table's one-column primary key, is NOT NULL and indexed (what a parent's change is carried down by); each carried column exists on both sides, of one type, NOT NULL on both.

A carried column may be a scope's (both of a two-scope parent's) or a person's; a child may name a second scope of its own beside them. The child's sentences name its own carried column (`[:member :job/shop-id]`), so the check and the gates read the same condition, and the leak test covers the child like any table.

**Why:** every row one direct comparison away from its scope, at every depth, without the app keeping copies equal. cellar makes the link and the trigger from the map, so migrate keeps them; Postgres's cascade is the only way a parent's change reaches children the asker may not write, without a role that passes the gates.

**The price:** a parent with children can't be deleted (the link refuses it, as a column's reference does); a moved parent moves its whole tree in one statement. doorman doesn't find tables that should say `:parent` and don't: a reference alone (an order naming its customer) isn't one.

**Instead of:**
- a check in the write gates that a child's columns equal its parent's: a parent could never change them while it had children, and the fill stays undone
- a trigger on the parent that updates the children: it runs as the asker, held by the children's gates
- reaching the scope through the parent on every read: slows with depth and with long lists
