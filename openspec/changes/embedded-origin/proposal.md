# Proposal: embedded-origin

## Why

Today every deployment Core serves is reached over HTTP. Core sends the request to the deployment's endpoint and
relays the answer. Some deployments do little work of their own: a routing decision, or signing a request for a
cloud API. For those, the HTTP hop and the separate service behind it cost more than the work itself. The
`plugins` entity (change `plugin-config-entity`) lets Core load Java plugin code at start. This change gives that
code its first place in the request path: an **origin plugin** answers a deployment's request inside Core, in
place of the HTTP call. It also moves the code that sends the origin request onto a small Core-owned type, so an
in-process origin and an HTTP origin share everything after the send: retry, relay, usage, limits and the
analytics row.

## What Changes

- A new interface mode. `interfaces.<type>` takes `"mode": "embedded"` and `"plugin": "<name>"`, where the name is a
  `plugins` entry. Core then serves that interface by calling the plugin in process and sends no HTTP request. It
  works for every interface type: chat completions (deployments-POST and `/openai/v1/chat/completions`),
  embeddings, the Responses API with its item operations, and Anthropic Messages with `count_tokens`.
- What the plugin gets: exactly what an HTTP origin would get. That is the method, the path and query Core would
  append to a base url, the same headers (the per-request key in `API-KEY`, `X-DIAL-DEPLOYMENT-FEATURES`,
  `X-UPSTREAM-*` for a model with upstreams) and the body. One difference: Core starts no HTTP client span, so the
  plugin gets no `traceparent`. Core treats the plugin's answer as the origin's answer:
  - a retriable status moves to the next upstream;
  - the stream is relayed as it arrives;
  - usage is parsed and charged;
  - one analytics row is written, with `response.upstream_uri: plugin://<name>` for a deployment without upstreams.
- The new mode is checked on config write:
  - Models and applications: `mode: embedded` without a `plugin`, a `plugin` without that mode, and a `plugin`
    together with a `base_url` are rejected.
  - Interceptors: `mode: embedded` is rejected; the plugin rules apply to them too.
  - Custom applications outside the `platform` bucket: `mode: embedded` is rejected on write and never served,
    because a plugin runs platform code.
  - Any deployment: `overridePaths` under an embedded entry is rejected.

  An embedded interface never falls back to `endpoint` or `responsesEndpoint`.
- A plugin that cannot serve, for these reasons, gets the existing connect-failure answer: `502`
  `Failed to connect to upstream server`, with no retry.
  - no `plugins` entry by that name;
  - its provider is not loaded;
  - the provider does not serve requests;
  - creating the plugin fails.

  A Responses item operation answers `500` in these cases, as it does today when its origin cannot be reached.
- A plugin's handler is created from its entry's settings on first use, and again on the first request after the
  entry changes. A settings edit takes effect with no restart.
- The `plugin-api` module gains the origin contract: `OriginPluginProvider`, `OriginPlugin`, `OriginRequest`,
  `OriginResponse`, in Vert.x types.
- No change for HTTP origins. Core's own code moves from Vert.x `HttpClientRequest` and `HttpClientResponse` to a
  Core-owned request and response type with an HTTP and an embedded implementation. What Core sends to an HTTP
  origin, what it relays, and what it records do not change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-interfaces-config`: the following requirements gain the embedded mode:
  - "Interface mode", "Legacy endpoint fields" and "Nothing serves the interface";
  - "Advertised interface types";
  - "Override path validation" and "Rejection outcome".

  New requirements: "Plugin decided by mode", "No embedded interceptors" and "Embedded mode is for platform
  deployments": a custom application in a user bucket cannot name a plugin.
- `deployment-origin`: new requirements "Embedded origin request", "Embedded origin answer", "Embedded origin
  failure" and "Client gone from an embedded origin".
- `responses-api`: new requirement "Item request to an embedded origin". Get, delete and cancel on a stored
  response reach the plugin that created it.
- `plugin-registry`: new requirement "Origin plugins". An entry whose provider serves requests can serve an
  embedded interface, and a settings change takes effect on the next request.

## Out of scope

- **The host `call`.** This is a plugin calling another deployment in process, as a child pass with no HTTP
  request back into Core, with a metadata-only child row and the parent's parsed body shared. It is the follow-up
  change `embedded-call`, which depends on this one and carries the perf measurement on the path through a routing application.
  - Until then, an origin plugin that needs a model calls Core back over HTTP with the per-request key it is
    handed, as an HTTP application does.
- **Request and response filters**, including embedded interceptors and translators: change `embedded-filter`.
- **More plugin context.** A curated request with the caller's identity and the parsed body tree is left out; this
  change hands the plugin the HTTP origin contract only. It comes with `embedded-call`, where a routing plugin needs it.
- **Plugin lifecycle and timeouts.** There is no close hook, health check or timeout for a plugin. A replaced
  handler is dropped, and a plugin that never answers holds its request until the client gives up.
- **Feature endpoints** (`tokenize`, `truncate_prompt`, `configuration`) stay HTTP. They are configured apart from
  `interfaces`.

## Impact

Motivated by two internal design notes of the perf improvement initiative: an exploration of in-process plugins
(2026-10-28 to 2026-11-01) and a plan for embedded deployments (2026-10-19). There is no GitHub issue; the notes
are not linked.

From those notes, this change covers:
- the origin seam: one place where Core decides between HTTP and in process;
- the embedded config shape;
- the origin plugin contract;
- the decision that the kind follows today's accounting: an origin is a pass with its own row and limits.

It leaves the in-process child call, its cheaper child pass and the CPU measurement to `embedded-call`, and the
request and response filters to `embedded-filter`.

Code:
- `config`: `InterfaceMode.EMBEDDED`, `DeploymentInterface.plugin`.
- `plugin-api`: the origin contract, plus a Vert.x dependency.
- `server`:
  - the Core-owned origin request and response types with their HTTP and embedded implementations;
  - the controllers that send to an origin (`BaseDeploymentPostController`, `BaseChatCompletionController`,
    `ResponsesController`, `MessagesBaseController` and subclasses, `BaseInterceptorController`,
    `ResponseItemController`), and the HTTP-only ones that share the proxy request type (`McpProxyController`,
    `DeploymentFeatureController`, `route/RouteRequestBodyHandler`);
  - `ResponsesApiClient`, `UpstreamRoute`, `ProxyUtil`, `ProxyContext`, `DeploymentEndpointUtil`,
    `ConfigPostProcessor`;
  - startup wiring in `AiDial`, and a factory there for the analytics log store so integration tests can read rows.
- Docs: `docs/dynamic-settings/models.md`, `applications.md`, `interceptors.md` and `plugins.md`; the `plugin-api`
  row in `CLAUDE.md`.

The unmerged builtin-translator branch edits the same methods in `BaseDeploymentPostController`,
`MessagesBaseController`, `MessagesController` and `DeploymentEndpointUtil`. Whichever lands second rebases.
