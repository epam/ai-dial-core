# Proposal

## Why

Coding harnesses send a session id header on every request, and Core already reads it for spans and for the analytics
log. For upstream choice Core ignores it: each request gets the balancer's weighted random pick, unless one of three
pins applies. These are a client `X-UPSTREAM-ID`, the upstream stamped in echoed Responses reasoning items, and the
upstream cache entry that the adapter's `X-DIAL-CACHE-BREAKPOINT-PATH` answer creates. The adapter reports the cached
prefix at the request's last input item, so a later request matches that entry only when it extends the earlier
request. A request of the same session that sends the transcript with a different last item and no reasoning items
matches no pin. Harness side requests (a permission check, prompt suggestions, a summary) are such requests. They go
to a random upstream. A provider's prompt cache is local to one upstream (one region or one account), so on a model
with many upstreams these requests miss the cache that the session built.

Observed on a production deployment over 7 days: for one GPT model with about 20 upstreams, 60% of a harness's
main-thread calls that came less than 5 minutes apart switched upstream. Switched calls were 74% cached, calls that
stayed on their upstream 97%. Sub-agent conversations of the same model stayed on their upstream in 98.5% of calls,
and the conversations of another GPT model in 99.4%.

## What Changes

- New model field `sessionAffinity` in the dynamic config: a boolean, default `false`, hot-reloaded with the model.
- With `sessionAffinity: true`, the first attempt of a request that carries a session id goes to the upstream that a
  weighted rendezvous hash of the session id and the upstream ids picks. The hash chooses among the upstreams that the
  balancer would use: available, with a weight above 0, in the highest tier that has one. Each upstream gets a share
  of sessions in proportion to its `weight`. While an upstream cools down after a retriable answer, its sessions go to
  their next choice, and they come back when it is available again.
- The session id is the value Core already resolves from the static setting `tracing.conversationIdHeaders`.
- Precedence: `X-UPSTREAM-ID`, then a stamped reasoning item, then a matched upstream cache entry, then the session
  pick, then the weighted random pick. Core writes the upstream cache entry as today. Retries, the attempt bound and
  the error answers do not change.
- Without the field, or for a request without a session id, nothing changes.
- Docs: `docs/dynamic-settings/models.md` (the field), `docs/tracing.md` (the header list now also feeds routing).

Out of scope:

- Sending the session id to Azure or OpenAI as `prompt_cache_key` in the adapter. That is a separate change in the
  adapter.
- Any change to the upstream cache entry: its format, its lookup and its write.
- A global default for the field (in the static settings or in the dynamic global settings), session affinity for
  config `routes`, and support for the field in the admin UI.
- A hash on the user or the API key for requests without a session id.

## Capabilities

Modified: `upstream-cache-affinity` (six added requirements). `deployment-origin` ("Upstream choice and the attempt
bound") and `responses-api` ("Reasoning item affinity") do not change. Like the cache entry, the session pick only
replaces the balancer's pick for the first attempt. The attempt bound, the retries and the pins those capabilities
describe stay as they are.

## Impact

No issue. The change is motivated by the production measurement above and covers the toggle, the session pick, its
place in the precedence and the fallback when the session's upstream is unavailable. It leaves out the
`prompt_cache_key` change in the adapter. The two changes do not depend on each other.

Code: `config` (`Model`) and `server` (the `upstream` package and the three controllers that build an upstream route).
No new dependency, no stored record, no Redis or blob call.
