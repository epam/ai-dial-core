# Proposal

## Why

A router in decision mode is asked over HTTP which deployment serves a request, and Core then serves that deployment
in the same pass: one pass, one key, one analytics row. That removes the second Core pass of a proxy router. Measured
on 2026-11-04, per user request over the direct path:

| Router | Core CPU | Router CPU |
|---|---|---|
| Decision mode | +2.4 to +7.1 ms | 1.1 ms |
| Proxy mode | +43 ms | 15.5 ms |

What is left:
- the router service itself, and a network hop per request;
- with `decision.body: true`, the client's whole body crossing that hop. Core parses it a second time to collect the
  attachments for the decide key, and writes it to the socket. The router reads it and checks it: that check took
  41% of the Java router's CPU at 383 KB in the 2026-11-05 run.

The plugin system on this branch can answer a request in process in place of an HTTP origin, and the Java router
already selects candidates from the caller in its plugin. But Core rejects an embedded interface on a decision router
(`A router in mode 'decision' serves no interface through a plugin`), because the decide call only knows HTTP.

## What Changes

- **Decision in process.** A router in decision mode MAY declare an interface in `embedded` mode. Core then hands the
  decide request, and the mid-stream notice request, to the plugin the interface names, in process. The plugin gets
  the method, the path (`/decide`), the headers and the body Core would send to the decide URL. Core reads the verdict
  from the plugin's answer exactly as from an HTTP router: `{"candidates": [...]}`, the same `decision.timeoutMs`, the
  same `502`/`504`/`4xx` mapping. The rule that rejects an embedded interface on a decision router is lifted; a
  translated interface stays rejected.
- **The plugin's calls.** A plugin deciding in process MAY call Core's create operations through its host, with the
  decide request key, as an HTTP router calls Core with `API-KEY`. Its calls end with the decide call. A notice
  carries no key.
- **The body.** With `decision.body: true`:
  - the plugin gets the client's bytes (the buffer Core already holds, not a copy) and the parsed request as its
    `bodyTree`, a copy of its own, so it reads fields without a parse;
  - on the body-addressed APIs, the decide key's attachment grants come from the parsed request Core already holds,
    in HTTP mode too, instead of from a second parse.
- **An in-process call to a decision router.** An origin plugin's call has a parsed tree and no bytes. Core serializes
  the tree once, before the first candidate, and the decision uses that serialization where it uses a client's bytes.
  On every create operation, deployments-POST included:
  - with `decision.body: true` the decide request carries it (today the decide fails with `500`);
  - each fallback candidate is served from it, so not from the tree the failed candidate changed (today a fallback
    fails, and on deployments-POST the first candidate already answers `400`).
- **No change** for routers in proxy mode, for the plugin API types, or for re-entry after a failed candidate in a
  pass that has bytes: Core keeps parsing those again, which happens only on fallback.

Out of scope:
- Embedded interceptors in front of a router, and FILTER plugins. The design keeps the decision input tree-first, so
  they can be added without reworking it, and records the open question they raise.
- A typed "decider" plugin kind: the decide protocol stays the one HTTP routers speak.
- The router side (the Java router plugin answering `POST /decide`) lives in the router's repository. The tests here
  use Core's test origin plugin.
- Lifting the per-request work of a proxy router's call back into Core (second serialize, second limit bookkeeping).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `router-deployment`:
  - Modified: Decision mode (an embedded interface is decided by its plugin in process); Router identity through user
    info (an embedded router gets the caller with the decide request).
  - Decision settings is replaced by Decision router settings, which accepts embedded interfaces on a decision router.
    OpenSpec cannot drop one scenario of a modified requirement, so the old block is removed and re-added under the
    new name.
  - Added: Decision by an embedded plugin, Embedded decision failures, Body of an embedded decide request, Calls of an
    embedded router while it decides, Decide request of an in-process call.
- `deployment-origin`: Calling a deployment from an origin plugin (calls carry the key of the request the plugin
  answers); Calls end with the plugin's pass (or with the decide call); Client gone before the plugin answers (not for
  a router's decide and notice requests).

## Impact

- Code:
  - `RouterDecisionClient`: decide and notice go through `OriginClient`;
  - `RouterDecisionFlow`: attachment grants, the in-process call's serialization;
  - the four controllers that call `decideIfRouter`;
  - `OriginClient` / `EmbeddedProxyRequest` / `ChildPass`: a router entry with its own call key and end;
  - `ConfigPostProcessor.validateDecision`;
  - the test origin plugin and integration tests.
- Docs: `docs/dynamic-settings/routers.md`, `docs/dynamic-settings/plugins.md`, and the javadoc of `OriginHost` and
  `OriginRequest`.
- Depends on PR #2216 (origin plugins and in-process calls) and on the router deployment type with decision mode from
  `development`; both are on this branch. Ships with #2216 or as a PR stacked on it.
- Motivation: the router A/B of 2026-11-04 (decision over HTTP) and the measurements posted on PR #2216
  (https://github.com/epam/ai-dial-core/pull/2216#issuecomment-6743084964).
  - Covered: the decide hop, and the parse and body transfer of `decision.body: true`.
  - Left out: the proxy router's call back into Core, and embedded interceptors.
- Plugin API: unchanged types. The decide request is one more request an embedded interface receives.
