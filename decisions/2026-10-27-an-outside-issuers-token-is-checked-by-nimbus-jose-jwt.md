# An outside issuer's token is checked by nimbus-jose-jwt; the policy stays groundwork-access's

> The user's decision, 2026-10-27 (keep groundwork minimal, but what a vetted library solves is solved with that library: crypto, tokens, request signing, protocols, parsers, formats; hand-written code that only saves a dependency is wrong). Since v0.8.1. Changes in part [groundwork-access trusts outside issuers the app names](2026-10-27-groundwork-access-trusts-outside-issuers-the-app-names.md): its price ("a JWT checker in groundwork-access, on the JDK's own RSA and ECDSA and jsonista, no library") and its "instead of: a JWT library".

**Decision:** nimbus-jose-jwt (`com.nimbusds/nimbus-jose-jwt` 10.10, no dependencies of its own) does what v0.8.0 wrote by hand:

- **reading the token:** `JWTParser` (a signed JWT, or an unsigned one, refused by its alg), its header and its claims (`JWTClaimsSet`: `iss`, `aud` a text or a list, `exp`, `nbf`);
- **the keys:** `JWKSet` parsing of the JWKS document (`URLBasedJWKSetSource`), kept by nimbus's `CachingJWKSetSource` for `:keys-kept-seconds` (one refresh at a time; a request waits for another's refresh at most as long as the fetch may take: `:connect-timeout-ms` plus `:answer-timeout-ms`, twice with a discovery document), fetched again when a token's `kid` matches none (`JWKSetBasedJWKSource`); no refresh ahead, no retries, no outage tolerance, so stale keys are never used;
- **choosing the key:** `JWKMatcher.forJWSHeader` (the alg's key type, its `kid`, `use` `sig` or unsaid, `alg` the token's or unsaid), with the alg's curve for EC (`Curve.forJWSAlgorithm`) and `minKeySize` 2048 for RSA;
- **the signature:** `RSASSAVerifier` and `ECDSAVerifier` (which refuses an ECDSA signature of the wrong length or format, and r or s of zero);
- **the times:** `DateUtils.isAfter` and `isBefore` with `:clock-skew-seconds`.

groundwork-access keeps, on top: the order of the checks and their named problems, the algorithm allowlist (RS256/384/512, ES256/384/512, from the issuer's `:algorithms`, checked before any key is looked up), `kid` required, `crit` refused, https key addresses (http only on localhost and 127.0.0.1), the discovery document's `issuer` and `jwks_uri` checked, the audience, the subject claim, the linked person. Two pieces stay its own code, as nimbus has no setting for them:

- **The fetch** is nimbus's `ResourceRetriever` on the JDK's HttpClient: `:connect-timeout-ms` to connect, `:answer-timeout-ms` for the whole answer, 200 only, never a redirect. nimbus's `DefaultResourceRetriever` (HttpURLConnection) follows redirects and times each read, not the answer.
- **The fetch rate** is one fetch per `:fetch-at-most-every-seconds`, a failed fetch counted, refused between with nimbus's `RateLimitReachedException`. nimbus's `RateLimitedJWKSetSource` lets two fetches through each period.

**Why:** a JWT check is crypto and a format: base64url, JSON, JWK key encodings, ECDSA signature transcoding, each with its own known attacks (alg none, HMAC with a public key, CVE-2022-21449's zero signatures). nimbus-jose-jwt is the JWT library most Java servers use (Spring Security's among them), read and fixed by many; the settings already say every value the library needs, so none of its defaults is used.

**The price:** one dependency, an 843 KB jar. A JWKS document nimbus can't parse in full (a key whose numbers aren't valid, an EC point off its curve) is no keys at all, `:keys-unavailable`, where v0.8.0 left that one key out; a key type nimbus doesn't know is still left out. Claims nimbus can't read (an `exp` that isn't a number) are `:malformed`, where v0.8.0 said `:expired`. A key under 2048 bits or for encryption is `:key-does-not-fit`, where v0.8.0 left it out of the kept keys (`:unknown-key`); v0.8.0's decision already named it so.

**Instead of:**
- **v0.8.0's own checker:** every rule its own code, read by nobody else
- **nimbus's `DefaultJWTProcessor` and `DefaultJWTClaimsVerifier` whole:** one exception for any failure, its message the only way to tell an expired token from a wrong audience; the named problems would be read from message texts
- **nimbus's `JWKSourceBuilder` defaults:** five minutes' cache, two fetches per thirty seconds, a 50 KiB limit, HttpURLConnection's redirects: values nobody here set
- **buddy-sign:** HMAC and RSA, but no JWK set fetching or caching
