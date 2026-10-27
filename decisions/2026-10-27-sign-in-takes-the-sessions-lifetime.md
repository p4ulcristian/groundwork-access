# sign-in! takes the session's lifetime

> The user's decision, 2026-10-27 (the switch, settled: an impersonated session lasts one hour, sign-in! takes a lifetime). Since v0.8.0. Changes in part [sessions v1](2026-10-22-sessions-v1.md) ("a session lasts `:session-days`").

**Decision:** `sign-in!`'s options gain `:session-lasts`, required:

```clojure
(access/sign-in! pool request person-id {:settings settings :tables tables :session-flags #{}
                                          :session-lasts :session-days :outside-subjects {}})          ; as before
(access/sign-in! pool request person-id {:settings settings :tables tables :session-flags #{:impersonated}
                                          :session-lasts {:minutes 60} :outside-subjects {}})          ; an admin acting as someone
```

- **`:session-days`**: the settings' `:session-days` unseen, pushed out when seen again (at most once in `:renew-after-minutes`), as every session did.
- **`{:minutes n}`**: it ends n minutes after sign-in and is never pushed out; the cookie's `Max-Age` says the same.
- The session row says which, `:session/renews` (a boolean, `true` for every row made before v0.8.0, its default): a database of v0.7 is brought there by groundwork-postgres's plan alone.

**Why:** Iron Rainbow's impersonated session lasts one hour from its start, however much the admin works in it ("nothing about it should still be logged in tomorrow"). A sliding hour would last as long as the admin keeps clicking.

**The price:** one more key on every `sign-in!`; one more column on the sessions table.

**Instead of:**
- **a sliding lifetime per session** (`{:unseen-minutes 60}`): an impersonation that never ends while used
- **a setting for flagged sessions** (`:impersonated-session-minutes`): ties a lifetime to a flag's name, which the app chooses
- **days in place of minutes**: an hour is not a number of days
