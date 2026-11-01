# A view the app's role reads runs as whoever asks, and the start refuses one that doesn't

> 2026-11-01, asked for by the switch of Iron Rainbow onto composer v8: of its 17 views only one was `security_invoker`, and on stage and prod the tables' owner, who made them, passes every gate. Since v0.8.6. Adds to [access, piece 4: the gates](2026-10-22-access-piece-4-the-gates.md) and [doorman reads past its gates only inside its own functions](2026-10-23-doorman-reads-past-its-gates-only-inside-its-own-functions.md).

**Decision:** in the folders the app's tables are in, every view is `security_invoker`, and there is no materialized view. `check-views pool tables` finds the others (`find-views-past-the-gates`, `[{:name :materialized?}]`) and throws `:groundwork-access/views-past-the-gates` with every one at once; groundwork (the composer) runs it at the start, after migrate, on the app's own pool.

- **Which views:** those in the folders of the tables the app passes (`groundwork_access` for `:groundwork-access/roles`, `public` for `:orders`), the folders groundwork-postgres's `grant-tables-to-role!` grants the app's role, every table and view in them. An extension's own views are left out, as groundwork-postgres leaves them.
- **What counts as invoker:** the view's `security_invoker` option, read from Postgres's catalog as Postgres parses it (`= true`, `= on`, `= 1`); `= false` or no option is a view run as its owner.
- **A view an outside reader needs past the gates** (a report another database role reads, which counts every shop) lives in a folder of its own: the app's role is never granted it, so it is no hole in the app, and the check doesn't look there.
- **Refused, not fixed:** groundwork-access doesn't rewrite a view. The app writes `WITH (security_invoker = true)` in its file, and the file stays what Postgres has.

**Why:** Postgres runs a view with its owner's rights unless it is `security_invoker`. The deploy migrates as the tables' owner, which on many hosts has BYPASSRLS (Neon's owner role), and in a test is a superuser: whoever may read such a view reads every row of the tables under it, whatever the sentences say, and the gates, built so that a query that forgot a rule still sees only its asker's rows, have a hole the size of every view. It is invisible where the app's role made the views itself (development, a preview), and in tests run as a superuser, who passes every gate anyway: only production shows it. A materialized view is worse, its rows copied where no gate holds them. Refusing at the start makes the safe view the only one that runs, wherever it runs, as groundwork-access already refuses a role that passes the gates.

**The price:** an app with a view its role reads must write the option, and every read through the view is held by the asker's gates on each table under it: a caller whose action reads no row of one of those tables gets none through the view either. That is the point, but an app that relied on its views reading past the gates finds the reads it must now give through sentences.

**Instead of:**
- **making every declared view invoker by default** (rewriting `CREATE VIEW` or setting the option after it is made): the file would no longer say what Postgres has, groundwork-postgres compares the two and would change it back on every plan, and a reader of the file would believe a view runs as its owner
- **checking only views whose owner passes the gates:** the owner changes with the host (a superuser in tests, the app's role in development, BYPASSRLS on Neon); a view safe on one is a hole on the next
- **a view an outside reader needs, beside the app's:** the app's role reads every view of its folders, so the outside reader's window would be the app's too
