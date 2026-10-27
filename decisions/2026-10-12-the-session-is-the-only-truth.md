# The session is the only truth

> **Changed in part** by [groundwork-access trusts outside issuers the app names](2026-10-27-groundwork-access-trusts-outside-issuers-the-app-names.md) (2026-10-27, v0.8.0): an outside issuer the app names (WorkOS AuthKit, for `/mcp`) is asked on every call by its token, checked by its keys; its subject is the person whose sign-in linked it, and signing out everywhere unlinks it.

> **Changed in part** by [doorman owns a person's tokens, and only sign-in names a person](2026-10-23-doorman-owns-tokens-and-only-sign-in-names-a-person.md) (2026-10-23, built on tidy-up, not released): a call proves who it is by doorman's session cookie or by a token doorman made for that person; only `sign-in!` names a person.

**Decision:** who is signed in is decided by Groundwork's session alone. An identity provider (a single sign-on service, a gate, a password check) is asked once, at sign-in; its tokens aren't kept to renew it in the background. Ending someone's access means deleting their sessions: sign-out, sign-out everywhere, a removed member.

**Why:** one place answers "who is this", so there is nothing to keep in sync. A background renewal would need a locked update of the session on every request, or parallel requests overwrite each other. Deleting rows ends access at once, on every server.

**The price:** when the provider disables an account, its sessions here live on until they end or are deleted; the app ends them when it learns of it (a webhook from the provider, a removed member).

**Instead of:** keeping the provider's tokens and renewing them silently, which keeps two sessions alive that must agree.
