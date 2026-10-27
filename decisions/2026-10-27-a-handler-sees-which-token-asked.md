# A handler sees which token asked

> The user's decision, 2026-10-27 (the switch, settled: a handler sees which token asked). Since v0.8.0. Changes in part [doorman owns a person's tokens](2026-10-23-doorman-owns-tokens-and-only-sign-in-names-a-person.md) (what the find-session step puts in the context).

**Decision:** the find-session step puts the proof the asker came with in the step context, beside the asker:

- **`:groundwork-access/token-id`**: the id of the groundwork-access token that asked (the id `make-token!` returned and `list-tokens` lists), nil for a cookie, an outside issuer's token or nobody;
- **`:groundwork-access/outside-issuer`**: the `:name` of the outside issuer whose token asked (`:workos`), nil otherwise ([Outside issuers](2026-10-27-groundwork-access-trusts-outside-issuers-the-app-names.md)).

The message's log names them as before, `{:person-id … :token-id …}`, and `{:person-id … :outside-issuer "workos"}`. The REPL's `make-step-asking-as` puts both nil.

```clojure
{:name :history/write
 :enter (fn [{:groundwork-access/keys [person-id token-id outside-issuer] :as context}] …)}   ; "Anna, by the catalog script token"
```

**Why:** an app's history says what made a change: Anna in her browser, Anna's catalog script, or Anna through `/mcp`. The log said it already; a step couldn't.

**The price:** two more keys in every handler's context, nil for most.

**Instead of:**
- **the token's name**: one more read per question; the id finds the name when the history is shown
- **one key holding a kind and an id** (`{:by :token :id …}`): a step reading it has to take it apart
