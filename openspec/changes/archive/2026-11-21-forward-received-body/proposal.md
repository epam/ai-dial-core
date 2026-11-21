# Proposal

## Why

On every pass Core parses the client's JSON body into a tree, runs the enhancement chain over it, and serializes the
whole tree again for the origin, even when the chain changed nothing or only `model`. With large prompts that full
serialization is a large share of Core's CPU per request. A chain that Core serves in process (a tier alias, then a
router in decision mode, then an alias of the chosen model, then a model served by an embedded plugin) hands only the
tree from pass to pass, so its last pass serializes the whole body again for the plugin.

## What Changes

- Core sends the origin the client's body bytes as received while no pass changed the body, and those bytes with only
  the top-level `model` string replaced while the passes changed only `model` (an override name, an alias's target, a
  router's candidate). On any other change (defaults applied, the interceptor configuration removed, encrypted content
  unwrapped) Core serializes the changed tree, as today.
- Fail closed: Core keeps the bytes only when it and an origin read the same JSON from them: valid UTF-8, one object
  with nothing but whitespace around it, no repeated key at any level. Otherwise it serializes the tree it read (a
  repeated key: the last value wins, as today). The body is taken from the request on every pass, whatever the
  enhancement chain reports, so no change can leave the client's bytes in place.
- Every create interface: deployments-POST (chat completions, completions, embeddings), `/openai/v1/chat/completions`,
  Responses and Messages (with `count_tokens`), through interceptors too, and an embedded origin's `body`.
- In process: the target pass of an in-process alias call takes the alias pass's body whole (the tree, the bytes and
  the pending `model`), so a router's candidate behind an alias, and the model behind it, send the client's bytes. A
  call an origin plugin makes through its host has the tree the plugin passed and no bytes, and is serialized.
- **Behaviour change** for origins and analytics readers: the body keeps the client's layout (whitespace, key order,
  escapes) instead of Core's compact JSON; the JSON value is the same. The analytics row's `request.body`, which is
  the body Core sends, shows the same layout.
- **Behaviour change**: a chat completion that Core used to forward as received when the chain changed nothing, but
  whose bytes Core and an origin may read differently (a repeated key, content after the object, not UTF-8), is now
  sent serialized.

Out of scope:

- The decide request body and the fallback stand-in of a decision router served in process: the router flow still
  serializes the call's tree once for them (router-deployment "Decide request of an in-process call"). A separate PR.
- Inserting `model` into bytes that have none (deployments-POST through an alias or to a model with an override name):
  serialized, as today.
- Route requests (`routes`) and the MCP and feature requests, which do not go through the request classes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-origin`: ADDED "Body sent to the origin".

## Impact

- Code: `server` module. The request classes (`ReceivedBody`, `JsonBodyRequest` and its three subclasses), the
  controllers' parse sites and `BaseDeploymentPostController.setProcessedRequestBody`, the context's call body and
  plugin body, and the alias hop (`EmbeddedProxyRequest`, `VertxHostDelegate`). The enhancement functions report
  whether they changed the body. No config, settings or API change; the plugin API is unchanged.
- Cost per pass whose body is unchanged or changed only in `model`: one full serialization fewer, one UTF-8 check of
  the body more, and one copy of the body when `model` is replaced.
- Motivation: CPU profiles of Core under load (perf improvement initiative). An A/B on the direct path in October
  measured 26% less Core CPU per request. PR #2100; there is no GitHub issue.
- Supersedes: alias-in-process-hop D3, embedded-call D3 (the child pass gets the parent's body, not only its tree).
- Dependencies: none. The decide-body change named above is a separate PR; the two touch different code and merge
  independently.
