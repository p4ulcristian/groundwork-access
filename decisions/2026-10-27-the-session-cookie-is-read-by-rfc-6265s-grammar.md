# The session cookie is read by RFC 6265's grammar

> The user's decision, 2026-10-27 (keep groundwork minimal; replace the Cookie header read by a regex with ring-core's parsing where ring is on the library's path, otherwise one correct RFC 6265 parse with tests). Since v0.8.2. Changes in part [sessions v1](2026-10-22-sessions-v1.md): how the step finds the cookie.

**Decision:** the Cookie header is read by RFC 6265's grammar (4.2.1), in `groundwork-access.cookie`:

- the header is pairs split by `;`, the spaces and tabs around each taken off;
- a pair is `name=value`, split at its first `=`: the name a token (RFC 9110's `tchar`), the value cookie-octets (ASCII, no space, `"`, `,`, `;` or `\`), or cookie-octets in double quotes, read without them;
- a pair that isn't one (no `=`, a name with a space, a value with a space, a comma, a backslash, an unclosed quote or a character outside ASCII) is left out, and the pairs around it are still read;
- a name twice (a cookie for `/` and one for `/app`, or one a subdomain set): the first with a value, the browser's cookie for the longest path (RFC 6265, 5.4); an empty value is none.

The session step, `sign-out!`, `sign-in!`'s ending of the request's session and `find-session-key-of-request` all read the session cookie through it.

**Why:** the regex took everything after `name=` up to the next `;`: a quoted value kept its quotes (no session found), a value with a trailing space kept it, an empty `app_session=` was no cookie by luck. A Cookie header's grammar is short and fixed; ring-core's parsing isn't on groundwork-access's path (it needs no Ring), and its parser decodes values as form text by default, which this cookie never is.

**The price:** a cookie value outside RFC 6265's characters, which the regex took, is now none: `make-cookie-value`'s URL-safe base64 is inside them, so only a cookie groundwork-access didn't make is affected. A request whose header holds a bad pair before the session cookie still finds it, where the regex did too. groundwork-languages reads its language cookie by the same rules, in its own copy: the two libraries share no dependency to keep one in.

**Instead of:**
- **the regex**: quotes, spaces and bad pairs each a case nobody wrote down
- **ring-core's `ring.middleware.cookies`**: a dependency for one header, and form decoding of the value
- **the last of a repeated name**: the browser lists the most specific cookie first; the last is the least specific
