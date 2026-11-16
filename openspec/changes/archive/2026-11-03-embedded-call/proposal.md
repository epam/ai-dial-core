# Proposal: embedded-call

## Why

After `embedded-origin`, an origin plugin answers a request inside Core. A plugin that has to reach a model, such as a
routing plugin, still calls Core back over HTTP with its per-request key. That costs a whole second pass. A pass is
one request Core serves, with its own limit check, token-stats span, per-request key and analytics row.

Measured through a routing application at a 383 KB body, a pass costs about 25 ms of Core CPU. The second one
re-parses a body Core has already parsed, writes a second analytics row with the same bodies, and adds an HTTP round
trip into Core itself.

This change lets a plugin call another deployment in process. The child pass keeps every check, limit, token-stats
span and row field, and loses the HTTP request into Core, the second parse and the second copy of the bodies.

## What Changes

- **The host call.** An origin plugin gets a way to call a deployment while it answers a request.
  - It names the path: a deployments-POST action, `/openai/v1/chat/completions`, `/openai/v1/responses` or
    `/openai/responses` (not in background), or `/anthropic/v1/messages`.
  - It passes the body as a parsed tree.
  - Core serves the call as a child pass of the plugin's pass and hands back the answer: status and headers first,
    then the body as it arrives. No HTTP request goes into Core.
- **What the child pass keeps and loses.** It is checked and charged as a deployment calling Core back with its
  per-request key is today:
  - **Kept:** the role, consent and per-request-key access checks, the target's limits against the initiator's
    bucket, a token-stats span under the plugin's span, a fresh per-request key for the target's origin, and an
    analytics row with `parent_deployment` the plugin's deployment.
  - **Kept:** the target's HTTP origin calls keep their OTel client span and `traceparent` in the client's trace.
  - **Lost:** the HTTP request into Core, the key lookup in Redis and the body parse. The tree the plugin passes is
    the child's request, on all four interfaces and on an interceptor hop.
- **The child's analytics row carries no bodies** (`request.body`, `response.body`, `assembled_response`); every other
  field stays. The plugin's own pass logs the bodies once for the trace.
- **Calls end with the plugin's pass.** When it ends, Core cancels the calls still open and answers a later call
  `401`, as an HTTP child with a deleted key gets.
- **More context for the plugin.** It gets the caller's identity (user id, roles, project) and its request's body
  as a parsed tree, next to the request bytes it gets today. A routing plugin then needs no `/v1/user/info` call and
  no parse of its own.
- **Cancel.** When Core cancels the plugin's answer, or the plugin cancels a call's answer, the child pass stops as a
  pass whose client went away does.
- **Told before it answers.** When the client goes away while the plugin is still working, Core tells the plugin, so
  it can stop its upstream work. A plugin that then fails ends the request quietly: no retry on another upstream.
- **Core's Vert.x at create.** A plugin is created with Core's Vert.x instance, for the clients and timers it builds
  once.
- **A sample `forward` origin plugin** (new module `plugin-samples`). It forwards each request to one configured
  deployment through the host call. It is the measurement vehicle until a routing plugin exists, and a worked example
  for plugin authors.
  - The Docker image includes it only when built with `--build-arg SAMPLE_PLUGINS=true`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-origin`: new requirements "Calling a deployment from an origin plugin", "Calls end with the plugin's
  pass", "Caller in the plugin request", "Client gone before the plugin answers" and "Cancelling a call".
- `app-to-model-accounting`: new requirement "In-process child pass". It is limited, charged, spanned and recorded as
  a deployment calling Core back with its per-request key.
- `analytics-log`: "Bodies in the analytics row" gains the in-process child pass as an exception. New requirement:
  "Rows without bodies for an in-process child pass".

## Out of scope

- **A list of candidate deployments the plugin hands back for Core to try in order**, falling back to the next on a
  non-2xx answer. Here the plugin calls and decides itself; such a list can follow.
- **Calls from request and response filters**, and filters themselves (`embedded-filter`).
- **Item operations and background responses through the call.** The call is a `POST` to a create operation, and a
  Responses call with `background: true` is refused.
- **An OTel server span for the child pass.** Its analytics row and token-stats span carry the trace and span ids,
  and the target's HTTP origin calls keep their client spans.
- **The Java port of the router.** It lives in its own repository and uses this contract. Until then the CPU target of
  the path through a routing application is measured with the `forward` plugin.
- **Sharing the response processing between the child and the parent pass.** The answer's events are still parsed in
  the child pass and again in the plugin's pass. Removing that belongs with a way for a filter to say it left an event
  unchanged, in `embedded-filter`.

## Impact

Motivated by the internal design notes of the perf improvement initiative: the exploration of in-process plugins
(2026-10-28 to 2026-11-01) and the plan for embedded deployments (2026-10-19). There is no GitHub issue; the notes are
not linked. This change covers their child call, the cheaper child pass that the CPU target depends on, and the
measurement. It leaves the candidate list and the filters to later changes.

Code:
- `plugin-api`: `OriginHost` (`call`, `cancelled`), `OriginCall` and `OriginCaller`; `OriginPlugin.handle` gains the
  host; `OriginRequest` gains the caller and the body tree; `OriginPluginProvider.create` gains Core's `Vertx`.
- `server`:
  - an in-memory server request and response for the child pass;
  - the child driver beside `Proxy.processAuthorizationResult`;
  - `ProxyContext`: a shared body tree, a lazily serialised body, the child-pass flag;
  - the six body parse sites: `DeploymentPostController`, `ChatCompletionsController`, `ResponsesController`,
    `MessagesBaseController`, and the two interceptor controllers;
  - `AnalyticsLogContext` and `BaseChatCompletionController` (the row without bodies);
  - `GenAiTraceAttributes` (no span writes from a child pass);
  - `BaseDeploymentPostController.finalizeRequest`, which closes the calls;
  - `EmbeddedProxyRequest`, which watches the client while the plugin works, and `EmbeddedOrigins`, which passes Core's
    `Vertx` to `create`.
- New module `plugin-samples`, plus `settings.gradle` and `Dockerfile` (the opt-in build argument).
- Docs: `docs/dynamic-settings/plugins.md`, `docs/tracing.md`, and `CLAUDE.md` (module table).

Depends on `embedded-origin`, archived on this branch.
