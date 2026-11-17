# Design

## Context

See proposal.md (Why) for the motivation. The code this change touches or relies on, on `development` (f61fff688):

- **The alias pass.** `AliasPasses.java:build` builds an `AliasApplicationPass` (or an `AliasToolSetPass` for a toolset
  alias) whose interfaces have Core's own address as base url and an override path to the target
  (`AliasPasses.java:overridePaths`). The feature endpoints (`AliasPasses.java:passFeatures`) and the MCP endpoint
  (`AliasPasses.java:build`) point there too. The address is `backgroundJob.localBaseUrl`, by default
  `http://localhost:<server port>` (`AiDial.java:start`, `AliasService.java:setLocalBaseUrl`).
- **How the alias pass sends.** The chat-completions controllers
  (`BaseChatCompletionController.java:processChatCompletionsRequestBody`, which `DeploymentPostController` and
  `ChatCompletionsController` extend), `MessagesBaseController.java:sendRequest` (also used by
  `MessagesCountTokensController`) and `ResponsesController.java:sendRequest` all call
  `BaseDeploymentPostController.java:createProxyRequest(InterfaceType)`. It resolves the URI from the deployment's
  interface (`DeploymentEndpointUtil.java:findRequestPathMapping`: `count_tokens` keeps its own path) and makes an
  HTTP request (`ProxyClient.java:http`), or a plugin request for a `dial-plugin://` url (`ProxyClient.java:embedded`).
- **The body.** `BaseDeploymentPostController.java:setProcessedRequestBody` serializes the request when the chain
  changed it or the pass has no bytes, and keeps the tree on the context when a plugin serves the interface. Messages
  and Responses pass `changed = true` always (`MessagesBaseController.java:190`, `ResponsesController.java:373`).
  `sendRequest` sets `Content-Length` from the pass's bytes (`BaseDeploymentPostController.java:531`).
- **The target pass today.** A new HTTP request into Core: `Proxy.java:handle` (with its server span and CORS and
  trace response headers, `Proxy.java:handleRequest`), a Redis GET of the alias pass's per-request key
  (`ApiKeyStore.java:getApiKeyData`), a parse of the body. The lend and the execution path are on the key record
  (`ApiKeyData.java:initFromContext`).
- **What the plugin work built.** `EmbeddedProxyRequest.java:send` takes the tree from the context, creates a
  `VertxHostDelegate` and registers it on the pass (`ProxyContext.java:addHostDelegate`), watches the client, and
  turns the answer into a `ProxyResponse` (`EmbeddedProxyResponse`). `VertxHostDelegate.java:call` serves a call as a
  child pass: only `CREATE_ROUTES`, no background Responses, `MAX_CALL_DEPTH` 32, an in-memory request and response
  (`EmbeddedServerRequest`, `EmbeddedServerResponse`), a `ProxyContext` built from the parent's proxy key record with
  no lookup, the tree as the call tree (`child.setCallTree`, which makes `ProxyContext.java:isChildPass` true), and
  `proxy.dispatch(child)`. `finalizeRequest` and a decision fallback (`RouterDecisionFlow.java:403`) call
  `ProxyContext.java:closeHostDelegates`, which cancels open child answers.
- **What a child pass does differently.** `isChildPass()` is read at six places. Two are about the missing bytes:
  `DeploymentPostController.java:285` (an empty body read as the tree) and `RouterDecisionFlow.java:108` (a decision
  router serializes the tree once as a stand-in for the client's bytes). Two are about bodies, which the plugin's own
  row logs (analytics-log, "Rows without bodies for an in-process child pass"): `AnalyticsLogContext.java:140` (row
  without bodies) and `BaseChatCompletionController.java:293` (no assembled response). Two are about the span, which is
  the plugin's: `GenAiTraceAttributes.java:set` (no span attributes) and `AutoEnrichedOtelJsonLayout.java:84` (no span
  enrichment of log lines).
- **Attachment grants.** `CollectResponseAttachmentsFn.java:apply` grants the files of an answer to the caller's stored
  key and to the caller's key record; `processAttachedFile` checks access with the pass's own proxy record.

## Goals / Non-Goals

**Goals:**

- No HTTP request from Core to itself for the create operations of an alias.
- No second parse and no serialize of a body the alias pass only forwards.
- Bodies logged once per alias chain, in the target's row; whether a row carries bodies depends on the deployment
  (an alias) and the key, not on the transport.
- Reuse the in-process call machinery; no second copy of client watching, cancelling or answer conversion.

**Non-Goals:**

- Changing what the target pass checks, limits, charges or retries.
- Changing how origin plugin calls are served; only their rows change (D4).

## Decisions

### D1. The in-process hop is an embedded origin with a built-in forward plugin

`ProxyClient` gets a method that returns an `EmbeddedProxyRequest` whose plugin is a constant in Core:

```java
(request, host) -> host.call(new Call(request.uri(), request.headers(), request.bodyTree()))
```

The URI is already the target's (the alias pass's override path, or the body's `model` set by
`EnhanceDeploymentRequestFn` from the pass's `overrideName`), so the plugin only hands it on. Unlike
`ProxyClient.java:embedded` it does not check the platform rule: this is Core's own code, and a stored alias
(`aliases/public/<name>`) is not a platform application. The plugin API's `Request.body` is documented as the
serialized tree; here it is not (D3), which is harmless because this plugin reads only the tree. A code comment says
so.

Rejected:

- **A new `ProxyRequest` class that calls `VertxHostDelegate` directly.** It would repeat the tree hand-over, client
  watch, reset, host registration and answer conversion of `EmbeddedProxyRequest` with no difference in behaviour.
- **A `plugins` entry of type `forward` and a `dial-plugin://` base url on the alias pass.** The `forward` provider is
  a sample left out of the default image, `ProxyClient.java:embedded` refuses every stored alias, and Core's own
  behaviour would depend on admin config.
- **One pass, no alias pass.** A rewrite of the alias feature and its spec (proposal, out of scope).

### D2. One check decides the transport

A pass goes in process when all three hold: the deployment is an `AliasPass`; the pass is not a background job
(`ProxyContext.isBackgroundJob`, set by `ResponsesController.java:proceedToDeployment` before the send); and the
path of the resolved URI matches `VertxHostDelegate.CREATE_ROUTES` (Messages `count_tokens` does not). The same check
drives the send (`createProxyRequest(InterfaceType)`) and the body (D3). If they disagreed, an HTTP send would carry
the caller's bytes with `model` set to the alias, and Core would resolve the alias again and call itself in a loop
with no depth limit.

The URI is still resolved from the alias pass's interface, and the child pass gets its path and query
(`absoluteUri` minus `baseUrl`, as `EmbeddedProxyRequest` does for a plugin). The other alias requests keep their own
HTTP clients: features (`DeploymentFeatureController`), MCP (`McpProxyController`), stored-response operations
(`ResponseItemController`). The alias pass has no interceptors (`DeploymentService.java:getInterceptors`).

Rejected: **a new url scheme (`dial-core://`) in the alias pass's interfaces.** Background Responses and
`count_tokens` would then need the HTTP base url back, so the pass would carry two sets of urls.

### D3. In process, the body goes over as a tree and is not serialized

For a pass that goes in process, `setProcessedRequestBody` keeps the tree on the context, as for a plugin interface,
and does not serialize. The alias row has no bodies (D4), so nothing else reads the bytes. When the pass has no bytes
at all (it is itself a child pass), it sets an empty buffer, so that `Content-Length` (`:531`) has a value; the child
pass reads no body bytes. An alias pass that sends over HTTP serializes as today.

For a decision-router target the router pass serializes the tree once as a stand-in for the client's bytes
(`RouterDecisionFlow.java:108`; the router-deployment delta extends that requirement to alias hops). A decision
fallback restores the pass's bytes from `originalRequestBody`, as today.

Rejected: **serializing as today.** One full serialize per hop for bytes nobody reads.

### D4. Bodies: an alias pass's row has none; every other row has them, in process or not

Leaving the bodies out becomes a property of the alias pass, not of the transport. `AnalyticsLogContext.java:140`
writes the row with `withoutBodies()`, the path a `secured` key uses, when the deployment is an `AliasPass`, instead
of when the pass is a child pass. `BaseChatCompletionController.java:293` skips the assembled response on the same
condition. So the target pass of an alias call keeps its bodies whatever the transport, and so does a pass an origin
plugin calls in process: the analytics-log requirement "Rows without bodies for an in-process child pass" is removed.

Rejected:

- **Keep the plugin child-pass rule and exempt the alias target pass from it.** Two rules for one question ("does this
  row carry bodies"), one keyed on the transport and one on the deployment, and a marker to tell them apart.
- **The plugin child-pass rule for the alias target row too (no bodies).** The target row is the one with the model,
  its usage and cost; it would lose prompt and completion for every aliased call.
- **Bodies in the alias row, as today.** The tier chain logs the same bodies three times, and the log writer holds
  them on the heap until they are written.

Cost for plugin calls: a plugin's call now writes the bodies in the child row too (log writer copies and heap until
written). The embedded-call A/B was measured without them.

### D5. The target pass of an alias hop is marked, and the two span rules skip it

The two child-pass rules about spans (`GenAiTraceAttributes.java:set`, `AutoEnrichedOtelJsonLayout.java:84`) stay
for an origin plugin's call: its child pass must not write over its plugin's span. The host the alias hop creates
marks the child passes it serves as alias target passes, and these two rules skip a marked pass (D6). The two rules
about the missing bytes (`DeploymentPostController.java:285`, `RouterDecisionFlow.java:108`) apply to both, since the
target pass has no bytes either.

Rejected: **deriving the mark from the key record's `lentDeployment`.** The record of a lent application's pass also
carries it, so a plugin's call under such a pass would be taken for an alias target.

### D6. Tracing

The target pass runs on a duplicate of the alias pass's Vert.x context with the alias pass's OTel context
(`VertxHostDelegate.call`). It has no server span; it writes its span attributes and log-line enrichment on the
current span, the alias pass's server span, where attributes both passes set end with the target's values. Its HTTP
origin calls keep their client spans under that span. Its row and token-stats span have a span id of their own with
the alias pass's span as parent, as an origin plugin's call does; that id has no OTel span.

Rejected:

- **The plugin child-pass rule (no attributes).** The model's `gen_ai.*` attributes, usage and cost would be on no span
  for any aliased call.
- **An OTel span of its own per target pass.** More code, and not needed to keep the attributes; a later change if
  per-pass spans are wanted.

### D7. The key record goes over; the stored key stays; grants reach the alias's caller

The child pass is authenticated by the alias pass's proxy key record (`VertxHostDelegate.call`), which holds the lend
and the execution path the HTTP target pass read from Redis. The alias pass still stores the key and deletes it in
`finalizeRequest`, because `CollectResponseAttachmentsFn` updates the stored key and
`ApiKeyStore.java:updatePerRequestApiKey` throws when it is missing.

Because the record is shared, a grant the target pass makes lands on the record the alias pass checks in
`processAttachedFile`, and the alias pass shares the file on with its own caller, as a direct call of the target
would. Over HTTP the alias pass's record never saw the grant. This is a deliberate difference (requirement
"Attachments shared on through an alias"); the plugin path behaves the same way.

Rejected: **a copy of the record for the child pass.** It would keep today's narrower result through an alias and add
a copy per call.

### D8. Ending, cancelling, depth

Taken from the plugin path: the alias pass's end and a decision fallback close the host, which cancels an open target
pass; a reset discards the answer and cancels the call. Two differences from the HTTP hop: a client gone before the
answer cancels the target pass at once (`EmbeddedProxyRequest.java:onClientGone`), where over HTTP it ran until the
alias first wrote to the client; and the depth limit of 32 applies, which an alias chain cannot reach (an alias
cannot target an alias, so a chain adds at most alias, router, alias).

### D9. Headers

The child request drops the headers Core sets for an origin (`EmbeddedServerRequest.DROPPED_HEADERS`) and sets
`Content-Type: application/json`, which every in-process route uses anyway. Differences from the HTTP hop:

- The alias pass's `X-DIAL-OVERRIDE-NAME` (the target's name) no longer reaches the target's origin. Over HTTP
  `copyHeaders` passed it on unless the target set its own (`ProxyUtil.java:setOverrideNameHeader`). A direct call
  sends none either.
- The target pass's response no longer adds a second `Access-Control-Allow-Origin`, `X-DIAL-TRACE-ID` and
  `X-DIAL-SPAN-ID` to the caller's answer: those come from `Proxy.java:handleRequest`, which an in-process pass does not
  go through.

The client's headers and the alias's `defaultHeaders` (set where the client sent none) pass, as for a plugin's call.

### Effect per request

Per alias call on a create operation:

| | Over HTTP (today) | In process |
|---|---|---|
| Core passes | 2 | 2 |
| HTTP requests into Core | 2 | 1 |
| Serializes | alias pass 1; target pass 1 for its origin | target pass 1 for its origin (a decision router: 1 stand-in) |
| Body copies over the wire | alias pass writes, target pass reads | none |
| Parses | caller's bytes 1, target pass 1 | caller's bytes 1 |
| Redis | alias key SET and DEL, target key GET, spans and limits of both passes | the same minus the GET |
| Blob operations | unchanged | unchanged |
| Rows with bodies | 2 | 1 (the target's) |

The tier chain (alias, decision router, alias, model) saves two HTTP requests into Core, two Redis GETs, two parses,
one serialize and two rows' bodies. The embedded-call review estimated a child pass at about 11 to 17 ms less Core CPU
than the HTTP pass it replaced, at 383 KB; that run's child row had no bodies, while here the target row keeps them
and the alias row drops them. It is an expectation, not a measurement. Task 4.1 measures it.

## Risks / Trade-offs

- [The target pass reaches controller code no plugin call has used, and an `EmbeddedServerRequest` member that throws
  is hit] → D2 sends only `CREATE_ROUTES` in process; the existing alias, router and decision API tests now run the
  target pass in process.
- [Analytics consumers read bodies from alias rows] → stated as a behaviour change in the proposal and in
  `docs/dynamic-settings/aliases.md`; the target row has them.
- [Plugin calls log their bodies twice, in the plugin's row and in the target's row] → as an HTTP application's call
  to a model does; stated in `docs/dynamic-settings/plugins.md`. The log writer's heap cap
  (`analytics.maxPendingBodyHeapPercent`) still bounds the backlog.
- [Traces lose one server span per alias hop; the target row's `span_id` matches no OTel span] → documented in
  `docs/tracing.md`, as for plugin calls.
- [No idle timeout on the hop: over HTTP the client's idle timeout bounded it] → the target pass's own origin timeouts
  still apply, as for plugin calls.
- [The hop no longer moves to another event loop] → with several HTTP server instances the loopback connection could
  land the target pass on another loop; in process it runs on the alias pass's loop. No difference with the default
  single instance.
- [The handed-over tree is shared] → `EmbeddedProxyRequest.send` clears the context's tree when it hands it over.

## Migration Plan

None. No config or settings change. `backgroundJob.localBaseUrl` is still used by background polling and by the alias
requests that stay on HTTP. Analytics consumers that read bodies from alias rows read the target's row instead; rows
of plugin calls carry bodies from this version on. Rollback is a revert.
