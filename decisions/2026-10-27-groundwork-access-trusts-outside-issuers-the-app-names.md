# groundwork-access trusts outside issuers the app names

> **Changed in part** by [An outside issuer's token is checked by nimbus-jose-jwt; the policy stays groundwork-access's](2026-10-27-an-outside-issuers-token-is-checked-by-nimbus-jose-jwt.md) (2026-10-27, v0.8.1): the JWT is read, its keys fetched, kept and matched, and its signature checked by nimbus-jose-jwt; the checks' order, their named problems and the policy stay as below.

> The user's decision, 2026-10-27 (the switch, decision 7: groundwork-access trusts an outside issuer the app names, WorkOS AuthKit for Iron Rainbow's `/mcp`: its keys, issuer, audience; the subject is linked to a person, who then asks like anyone). Since v0.8.0. Changes in part [The session is the only truth](2026-10-12-the-session-is-the-only-truth.md) and [doorman owns a person's tokens](2026-10-23-doorman-owns-tokens-and-only-sign-in-names-a-person.md) (its "not decided here: `/mcp`'s WorkOS OAuth tokens").

**Decision:** the settings name outside issuers, `:outside-issuers`, required, `[]` for none; each writes out every key:

```clojure
{:name :workos                                        ; sign-in!'s :outside-subjects, the context and the log name it
 :issuer "https://auth.shop.example"                  ; a token's iss, exactly
 :keys-at [:discovery "https://auth.shop.example/.well-known/oauth-authorization-server"]   ; or [:jwks address]
 :audience "https://shop.example/mcp"                 ; a token's aud holds it
 :algorithms #{"RS256"}                               ; of RS256 RS384 RS512 ES256 ES384 ES512
 :subject-claim "sub"
 :clock-skew-seconds 30                               ; at most 300
 :connect-timeout-ms 2000 :answer-timeout-ms 5000
 :keys-kept-seconds 3600
 :fetch-at-most-every-seconds 30}
```

- **Which token is whose:** a bearer token starting with `:token-lead` is groundwork-access's own, as before; with outside issuers named, any other is an outside issuer's, a JWT. A lead an outside token could start with (`eyJ`, a JWT's header) refuses the start. With none named, every bearer token is groundwork-access's own, as before.
- **The check**, in this order, the first failing one naming the refusal: three base64url parts, a JSON header and claims (`:malformed`); no `crit` (`:unsupported-header`); its `iss` one issuer's `:issuer` (`:unknown-issuer`); its `alg` one of that issuer's `:algorithms` (`:algorithm-not-allowed`), which the settings' shape keeps to RSA and ECDSA: never `none`, never HMAC, so the issuer's public key is never a secret; a `kid` (`:no-key-id`); the key of that `kid` (`:keys-unavailable`, `:unknown-key`), of the alg's kind, an RSA key of 2048 bits or more, an EC key on the alg's curve, its own `alg` and `use` agreeing (`:key-does-not-fit`); the signature (`:bad-signature`); `exp` present and not passed, `nbf` not ahead, each by more than `:clock-skew-seconds` (`:expired`, `:not-yet-valid`); `aud` holding `:audience` (`:wrong-audience`); the `:subject-claim` a text (`:no-subject`); a person linked to it (`:subject-not-linked`).
- **Keys:** fetched from `[:jwks address]`, or from the `jwks_uri` of `[:discovery address]` (OpenID's or OAuth's metadata; its `issuer` must be `:issuer`); https, or http on localhost and 127.0.0.1 only; never following a redirect; kept `:keys-kept-seconds`; fetched again when a token names a `kid` they lack, and at most every `:fetch-at-most-every-seconds`, a failed fetch counted; one fetch at a time per issuer. Stale keys aren't used when a fetch fails. They are kept in the process, by the issuer's settings, so every pool and every request shares one fetch.
- **The subject's person** is linked at sign-in: `sign-in!`'s `:outside-subjects`, required, `{}` for none, `{:workos "user_01…"}`, the subject the app's login proved (its WorkOS login), written to `outside-subjects-table` (`groundwork_access.outside_subjects`), replacing an earlier person of that subject. `sign-out-everywhere!` unlinks the person's subjects, so their outside tokens are refused at once, until they sign in again.
- **The asker** is that person with no session flags and no visitor, as a token's; no Origin check. They ask like anyone: the check, the gates, the sentences, the actions of their memberships. The context names the issuer, `:groundwork-access/outside-issuer`; the log `{:person-id … :outside-issuer "workos"}`. An outside token makes no token (`make-token!` refuses, `:outside-token`).
- **A failed check is never nobody:** the find-session step refuses `:groundwork-access/token-refused`, a fourth reason groundwork-access adds to every handler (and `put-refusals-into-shapes` in the browser), and writes the problem to the log (`:groundwork-access/request-refused {:because … :problem … :outside-issuer …}`); `run-as-asker-of-request!` throws `:groundwork-access/refused-request` with `:reason`, `:problem` and `:issuer-name`.

**Why:** Iron Rainbow's `/mcp` is reached by an AI client that signs in through WorkOS AuthKit and carries AuthKit's access token on every call. Checked here, by the same step that reads cookies and tokens, the call is a person's and goes through the same check and gates; nothing else names the person. A failure named, not "nobody", lets the client tell an expired token (get a new one) from a call it may not make.

**The price:** a JWT checker in groundwork-access, on the JDK's own RSA and ECDSA and jsonista, no library: every rule above is its own code. A person who never signed in since the switch has no link, so their `/mcp` calls are refused `:subject-not-linked` until their first sign-in. Rate limits by `find-session-key-of-request` count per token text, so an outside token's limit starts again with each new token the issuer hands out. A person disabled at the issuer keeps asking by a token until it expires (its `exp`, the issuer's minutes), unless the app signs them out everywhere. One more table.

**Instead of:**
- **`/mcp` asking "as this person id" itself** (the decision's "instead of"): any code holding the pool could name anyone
- **Iron Rainbow's own OAuth server**, issuing groundwork-access tokens: a second login flow to build and keep
- **a JWT library** (nimbus-jose-jwt, buddy-sign): defaults of its own (cache times, retries, accepted algorithms) under every setting, and rules nobody here has read
- **the subject's person read from an app table by a setting** (`users.workos_id`): a read past the gates before anyone asks, or a function per app
- **the subject is the person's id** (an issuer's custom claim): the issuer would name people to the app
