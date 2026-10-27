# What an impersonator may not do needs nothing new: a deny of a set of actions by its session flag

> The user's decision, 2026-10-27 (the switch, decision 6: what an impersonator may not do are actions of their own, denied by the scope kind to an `:impersonated` session). Checked on v0.8.0. Adds to [A session flag and a scope's state may say no](2026-10-23-a-session-flag-and-a-scopes-state-may-say-no.md).

**Decision:** nothing is built. A scope kind's deny already names a set of its own actions and a session flag:

```clojure
:shop {…
       :actions {:messages/send #{:read :insert} :payments/take #{:read :update} …}
       :denies  [{:deny #{:messages/send :messages/remove :payments/take :payouts/start
                          :invoices/issue :invoices/send :quotes/accept} :session :impersonated}]}
```

`groundwork-access.impersonation-test` proves it on a session signed in with `:session-flags #{:impersonated} :session-lasts {:minutes 60}`:

- the check answers `:groundwork-access/not-allowed` for a denied action, whether its handler acts in a `:record`, a `:scope [kind param]` or `:every-scope-holding-the-action`; every other action of the scope stays the person's;
- the gates refuse the denied action's write too, where every action counts (`run-as-asker-of-request!`), and from another action's step;
- "what may I do?" leaves the denied actions out;
- `explain-refusal` answers `:groundwork-access/not-allowed` and names the deny under `:denies`, `{:scope-kind :shop :deny {…} :says-no? true}`, beside the sentence that holds.

**Why:** the deny of a set of actions by a flag was built with the flag ([A session flag and a scope's state may say no](2026-10-23-a-session-flag-and-a-scopes-state-may-say-no.md)); Iron Rainbow's sixteen endpoints become those actions, and the scope kind says no to them.

**The price:** the deny is written on each kind whose actions it names, as that decision says.

**Instead of:** a step on each of the sixteen handlers, which a new handler can forget.
