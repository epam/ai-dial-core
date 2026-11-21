# Proposal

## Why

Core serves the target of an alias in a second pass that it reaches over HTTP to its own address
(`backgroundJob.localBaseUrl`). For every alias call Core serializes the body, writes it as an HTTP client, reads it
back as an HTTP server, parses it again, and looks the per-request key up in Redis again. A chain such as tier alias,
then a router in decision mode, then an alias of the chosen model, makes two such calls per user request. Every pass
of that chain also logs the same request and response bodies in its analytics row.

Core already runs a pass in process for an origin plugin's call (`embedded-call`). The review of that change
estimated such a child pass at about 11 to 17 ms less Core CPU than the HTTP pass it replaced, at 383 KB requests.
The alias hop can reuse that machinery: the target pass stays a pass of its own, only the HTTP request to Core's own
address goes away.

## What Changes

- For chat completions, completions, embeddings, Responses (`/openai/v1/responses` and `/openai/responses`, not in
  background) and Messages (not `count_tokens`), the alias pass hands its request to the target pass in process: an
  in-memory request dispatched by Core's own controller selection, authenticated by the alias pass's per-request key
  record, with the parsed body handed over as a tree. No HTTP request reaches Core's own address for these calls, and
  the alias pass no longer serializes the body.
- The analytics row of an alias pass has no `request.body`, `response.body` or `assembled_response`, whatever the
  transport. The target pass's row keeps its bodies, also when it runs in process, so a chain logs the bodies once, in
  the target's row. A target that refuses the call before its origin writes no row, as on a direct call, so such a
  call logs no bodies. **Behaviour change** for analytics consumers that read bodies from alias rows.
- Leaving the bodies out is an alias property only, not a property of serving a pass in process: the row of a pass an
  origin plugin calls in process carries its bodies again, as a pass called over HTTP does. **Behaviour change** for
  plugin calls: their bodies are logged in the plugin's row and in the target's row.
- The target pass of an in-process alias call has no HTTP server span. It writes its request, operation and usage
  span attributes and its log-line enrichment on the alias pass's server span, which then shows the target's model and
  usage (latency, attempts and the response id stay the alias's). Under an origin plugin's call it writes none, as the
  plugin's call does not.
- Deliberate differences, all toward what a direct call of the target does:
  - The target's origin no longer receives the alias pass's `X-DIAL-OVERRIDE-NAME`.
  - The caller's answer no longer carries the target pass's copies of `Access-Control-Allow-Origin`,
    `X-DIAL-TRACE-ID` and `X-DIAL-SPAN-ID` next to the alias pass's own.
  - A file the target pass shares with the alias pass from its answer is shared on with the alias's caller, as on a
    direct call. Over HTTP the alias pass never saw that grant.
  - A client gone before the answer cancels the target pass at once.
- Everything else an alias forwards keeps going over HTTP to Core's own address, unchanged: MCP, the feature
  endpoints (`tokenize`, `truncate_prompt`, `configuration`, `rate`), Messages `count_tokens`, Responses with
  `background: true`, and the stored-response operations.
- Unchanged: two passes, the execution path `[alias, target]`, one token-stats span and one analytics row per pass,
  limits and cost charged by the target pass only, the target's answer relayed, access granted to the target pass only.

Out of scope, each a later change if wanted:

- Serving MCP, the feature endpoints, `count_tokens` and background Responses of an alias in process.
- Keeping the alias pass's per-request key out of Redis. The alias pass still stores it (one write) and deletes it
  (one delete), because the target pass's attachment grants update the stored key.
- Serving an alias call in one pass (no alias pass at all).
- An OTel span of its own for an in-process target pass.
- Changing what origin plugin calls serialize.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-aliases`: ADDED requirements: the target pass served in process for the create operations; the
  headers of an in-process alias call; attachments shared on through an alias.
- `analytics-log`: ADDED "Rows without bodies for an alias pass"; MODIFIED "Bodies in the analytics row" (the alias
  exception, and in-process passes logged as others); REMOVED "Rows without bodies for an in-process child pass".
- `router-deployment`: MODIFIED "Decide request of an in-process call", which now also covers a decision router that
  is the target of an in-process alias call.

## Impact

- Code: `server` module. The alias pass's request and body (`BaseDeploymentPostController`), the origin client
  (`ProxyClient`), the embedded classes it reuses (`EmbeddedProxyRequest`, `VertxHostDelegate`), the analytics row
  and assembled response (keyed on the alias pass instead of the child pass), and the child-pass checks in span
  attributes and log enrichment. No config, settings or
  API change. `backgroundJob.localBaseUrl` keeps its meaning for background polling and for the alias requests that
  stay on HTTP.
- Records, per in-process alias call: one HTTP request into Core fewer and one Redis GET fewer (the target pass's key
  lookup); one parse fewer, and one serialize fewer when the target is not a decision router (a decision router serializes
  the tree once to stand in for the client's bytes). Analytics: the alias row carries no bodies; the row of a plugin's
  in-process call carries them again.
- Observability: one HTTP server span fewer per in-process alias hop; the target row's `span_id` has no OTel span of
  its own, as an origin plugin's call has. A dashboard that counts passes by server spans counts fewer.
- Motivation: the A/B and its estimate in `openspec/changes/archive/2026-11-03-embedded-call/review.md` (task 7.1).
  This change covers the create operations of an alias; MCP, features, `count_tokens` and background Responses are
  left out. There is no GitHub issue yet; one is needed before the PR leaves draft.
- Dependencies: none open. Builds on `embedded-call`, `embedded-decision` and `alias-router-target`, all merged.
