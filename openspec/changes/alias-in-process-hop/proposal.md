# Proposal

## Why

Core serves the target of an alias in a second pass that it reaches over HTTP to its own address
(`backgroundJob.localBaseUrl`). For every alias call Core writes the body as an HTTP client, reads it back as an HTTP
server, parses it again, and looks the per-request key up in Redis again. A chain such as tier alias, then a router in
decision mode, then an alias of the chosen model, makes two such calls per user request.

Core already runs a pass in process for an origin plugin's call (`embedded-call`). The A/B in that change's review
measured a Core-to-Core hop served in process at 16.6 ms less Core CPU per user request than the same hop over HTTP
(Anthropic Messages, 383 KB requests). The alias hop can reuse that machinery: the target pass stays a pass of its
own, only the HTTP request to Core's own address goes away.

## What Changes

- For chat completions, completions, embeddings, Responses (not in background) and Messages, the alias pass hands its
  request to the target pass in process: an in-memory request dispatched by Core's own controller selection,
  authenticated by the alias pass's per-request key record, with the parsed body handed over as a tree. No HTTP
  request reaches Core's own address for these calls.
- An alias pass that hands its body over as a tree no longer serializes it. Its analytics row then holds the request
  body as the alias pass received it; today it holds the body with the target's name in `model`.
- Everything else an alias forwards keeps going over HTTP to Core's own address, unchanged: MCP
  (`/v1/deployments/<alias>/mcp`, `/v1/toolset/<alias>/mcp`), the feature endpoints (`tokenize`, `truncate_prompt`,
  `configuration`, `rate`), Responses with `background: true`, and the stored-response operations.
- Everything the alias spec says about an alias call stays as it is: two passes, the execution path
  `[alias, target]`, one token-stats span and one analytics row per pass, limits and cost charged by the target pass
  only, the target's answer relayed, access granted to the target pass only.

Out of scope, each a later change if wanted:

- Serving MCP, the feature endpoints and background Responses of an alias in process.
- Keeping the alias pass's per-request key out of Redis. The alias pass still stores it (one write) and deletes it
  (one delete), because the target pass's attachment grants update the stored key.
- Serving an alias call in one pass (no alias pass at all).
- Changing what other in-process passes (origin plugin calls) serialize.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-aliases`: one ADDED requirement: the target pass of an alias call is served in process for the
  create operations, with no HTTP request to Core's own address, and the alias pass's analytics row holds the body
  it received. The existing requirements are unchanged.

## Impact

- Code: `server` module. The alias pass's request (`BaseDeploymentPostController`), the origin client
  (`ProxyClient`), and the embedded request classes it reuses (`EmbeddedProxyRequest`, `VertxHostDelegate`).
  No config, settings or API change. `backgroundJob.localBaseUrl` keeps its meaning for background polling and for the
  alias requests that stay on HTTP.
- Records: per alias call, one HTTP request into Core fewer, one Redis GET fewer (the target pass's key lookup), one
  JSON parse and one serialize of the body fewer. The alias row's `request.body` changes as described above.
- Observability: the target pass no longer has an HTTP server span of its own; it is traced under the alias pass's
  span, as an origin plugin's call is. Token-stats spans and analytics rows are unchanged.
- Motivation: the A/B in `openspec/changes/archive/2026-11-03-embedded-call/review.md` (task 7.1), which measured the
  in-process hop. It covers the create operations of an alias; MCP, features and background Responses are left out.
  There is no GitHub issue yet; one is needed before the PR leaves draft.
- Dependencies: none open. Builds on `embedded-call`, `embedded-decision` and `alias-router-target`, all merged.
