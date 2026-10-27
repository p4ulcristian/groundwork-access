# A sentence's text in SQL is quoted by groundwork-postgres, PgJDBC's quoting

> The user's decision, 2026-10-27 (keep groundwork minimal; take a library only where the problem is one people get subtly wrong, escaping among them, and its cost is small where it lands). Since v0.8.2.

**Decision:** every text literal groundwork-access writes into the SQL it makes from the sentences and denies (a `:when`'s values, an action's name, a scope kind's, a system actor's, a session flag's) is quoted by groundwork-postgres's `quote-text`, which is PgJDBC's `Utils/escapeLiteral`. groundwork-access keeps no quoting of its own.

**Why:** the copy in `groundwork-access.sentences` was the fourth of the same line, `(str "'" (replace s "'" "''") "'")`; a gate's condition is the one place a bad quote is a hole in access. One quoting, the driver's, in the library that already talks to Postgres for groundwork-access, costs nothing: groundwork-postgres is already its dependency.

**The price:** a value with a zero byte (`\u0000`) in a sentence throws `:groundwork-postgres/not-quotable` when the conditions are made, where the copy wrote SQL Postgres refused at migrate. The SQL is otherwise the same. Like groundwork-postgres's own literals, it is quoted for `standard_conforming_strings` on, Postgres's default, which an app must not turn off ([groundwork-postgres's decision](https://github.com/p4ulcristian/groundwork-postgres/blob/main/decisions/2026-10-27-text-and-names-in-made-sql-are-quoted-by-pgjdbc.md)).

**Instead of:**
- **its own copy**: the same rule in four places, a fix in one missing from the others
- **PgJDBC's `Utils` called from groundwork-access directly**: two libraries each choosing the standard_conforming_strings flag
