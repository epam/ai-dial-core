# Design

## Context

See proposal.md (Why) for the motivation. The code this change touches or relies on, on `development` (f61fff688):

- **The alias pass.** `AliasPasses.java:build` builds an `AliasApplicationPass` (or an `AliasToolSetPass` for a toolset
  alias) whose interfaces have Core's own address as base url and an override path to the target
  (`AliasPasses.java:overridePaths`); the feature endpoints and the MCP endpoint point there too
  (`AliasPasses.java:passFeatures`). The address is `backgroundJob.localBaseUrl`, by default
  `http://localhost:<server port>` (`AiDial.java:start`, `AliasService.java:setLocalBaseUrl`).
- **How the alias pass sends.** The chat-completions controllers (`BaseChatCompletionController.java:processChatCompletionsRequestBody`,
  which `DeploymentPostController` and `ChatCompletionsController` extend), `MessagesBaseController.java:sendRequest` and
  `ResponsesController.java:sendRequest` all call `BaseDeploymentPostController.java:createProxyRequest(InterfaceType)`.
  It resolves the URI from the deployment's interface and makes an HTTP request (`ProxyClient.java:http`), or a plugin
  request when the interface names a `dial-plugin://` url (`ProxyClient.java:embedded`).
- **The body.** `BaseDeploymentPostController.java:setProcessedRequestBody` serializes the request when the chain
  changed it or the pass has no bytes, and keeps the tree on the context when a plugin serves the interface. Messages
  and Responses pass `changed = true` always (`MessagesBaseController.java:190`, `ResponsesController.java:373`).
- **The target pass today.** A new HTTP request into Core: `Proxy.java:handle`, a Redis GET of the alias pass's
  per-request key (`ApiKeyStore.java:getApiKeyData`), a parse of the body. The lend that lets the target pass call the
  target is on the key record (`ApiKeyData.java:initFromContext`, `lentDeployment`), as is the execution path.
- **What the plugin work built.** `EmbeddedProxyRequest` is a `ProxyRequest` answered by a plugin in Core. On `send`
  it takes the tree from the context (`EmbeddedProxyRequest.java:send`), creates a `VertxHostDelegate` and registers
  it on the pass (`ProxyContext.java:addHostDelegate`), watches the client (`watchClient`), and turns the plugin's
  answer into a `ProxyResponse` (`EmbeddedProxyResponse`). `VertxHostDelegate.java:call` serves a call as a child pass:
  only the create operations (`CREATE_ROUTES`), no background Responses, the depth limit (`MAX_CALL_DEPTH`), an
  in-memory request and response (`EmbeddedServerRequest`, `EmbeddedServerResponse`), a `ProxyContext` built from the
  parent's proxy key record with no lookup, the tree as the call tree (`child.setCallTree`), and
  `proxy.dispatch(child)`. The pass's end (`BaseDeploymentPostController.java:finalizeRequest`) and a decision fallback
  (`RouterDecisionFlow.java:403`) call `ProxyContext.java:closeHostDelegates`, which cancels the open child answers.
  `CollectResponseAttachmentsFn.java:apply` updates both the stored key and the shared record, so a grant the child
  pass makes is seen by the parent.
- **The sample.** `ForwardOriginPluginProvider` (module `plugin-samples`) is a plugin whose whole job is to call one
  deployment through `HostDelegate.call`. The alias pass needs the same, with the target already named by the URI.

## Goals / Non-Goals

**Goals:**

- No HTTP request from Core to itself for the create operations of an alias (the requirement "Target pass served in
  process").
- No second parse and no serialize of a body the alias pass only forwards.
- Reuse the in-process call machinery as it is; no second copy of client watching, cancelling or answer conversion.

**Non-Goals:**

- Changing what the target pass does: its checks, limits, interceptors, translator, retries, span and row are the
  same as over HTTP.
- Changing the in-process calls of origin plugins.

## Decisions

### D1. The in-process hop is an embedded origin with a built-in forward plugin

`ProxyClient` gets a method that returns an `EmbeddedProxyRequest` whose plugin is a constant in Core:

```java
(request, host) -> host.call(new Call(request.uri(), request.headers(), request.bodyTree()))
```

The URI is already the target's (the alias pass's override path, or the body's `model` set by
`EnhanceDeploymentRequestFn` from the pass's `overrideName`), so the plugin only hands it on. Unlike
`ProxyClient.java:embedded` it does not check the platform rule: this is Core's own code, and a stored alias
(`aliases/public/<name>`) is not a platform application.

Rejected:

- **A new `ProxyRequest` class that calls `VertxHostDelegate` directly.** It would repeat what `EmbeddedProxyRequest`
  does (tree hand-over, client watch, reset, host registration, answer conversion) with no difference in behaviour.
- **A `plugins` entry of type `forward` and a `dial-plugin://` base url on the alias pass.** The `forward` provider is
  a sample left out of the default image, `ProxyClient.java:embedded` refuses an application that is not of the
  platform config (every stored alias), and Core's own behaviour would depend on admin config.
- **One pass, no alias pass.** A rewrite of the alias feature and its spec (proposal, out of scope).

### D2. Which requests go in process

`BaseDeploymentPostController.java:createProxyRequest(InterfaceType)` sends in process when the deployment is an
`AliasPass` and the pass is not a background job (`ProxyContext.isBackgroundJob`, set by
`ResponsesController.java:handle` before the send). The URI is still resolved from the alias pass's interface, and the
child pass gets its path and query, as `EmbeddedProxyRequest` does for a plugin (`absoluteUri` minus `baseUrl`). Every
route this produces is in `VertxHostDelegate.CREATE_ROUTES`.

The other alias requests keep their own HTTP clients and do not pass through this method: features
(`DeploymentFeatureController`), MCP (`McpProxyController`), stored-response operations (`ResponseItemController`).
The alias pass has no interceptors (`DeploymentService.java:getInterceptors`), so `BaseInterceptorController` is not
reached.

Rejected: **a new url scheme (`dial-core://`) in the alias pass's interfaces.** Background Responses would then need
the HTTP base url back, so the pass would carry two sets of urls. Keeping `localBaseUrl` leaves `AliasPasses.build`
unchanged.

Background Responses stay on HTTP because `VertxHostDelegate.call` refuses them: a background job's target pass may
outlive the alias pass, and a child pass is cancelled when the pass that made it ends.

### D3. The body goes over as a tree and is not serialized

`setProcessedRequestBody` keeps the tree on the context when the pass goes in process, as it does for a plugin
interface, and skips the serialize when the pass already has the bytes its caller sent. The target pass gets the tree
as its call tree and parses nothing. The alias pass's analytics row then holds the bytes the alias pass received
(requirement "Request body in the alias pass's row"); today it holds the serialized body with the target's name in
`model`.

When the alias pass has no bytes, because it is itself a child pass (an origin plugin's call to an alias), it
serializes as today, so its row still has a body.

`Content-Length` on the in-process request is the length of the bytes the alias pass holds. The child pass reads no
body bytes, so the header has no effect. A decision fallback restores the pass's bytes from `originalRequestBody`
(`RouterDecisionFlow.java:403` onward), as today.

Rejected: **serializing as today.** One full serialize of the body per hop for bytes nobody reads, except the alias row.

### D4. The key record goes over; the stored key stays

The child pass is authenticated by the alias pass's proxy key record (`VertxHostDelegate.call`: `parent.getProxyApiKeyData()`),
which holds the lend and the execution path the HTTP target pass read from Redis. The alias pass still stores the key
(`assignPerRequestApiKey`) and deletes it in `finalizeRequest`, because `CollectResponseAttachmentsFn` updates the
stored key, and `ApiKeyStore.java:updatePerRequestApiKey` throws when it is missing. Keeping the key out of Redis is a
later change.

### D5. Ending, cancelling and depth

Taken from the plugin path with no change: the alias pass's end and a decision fallback close the host, which cancels
an open target pass; a client gone before the answer cancels it (`EmbeddedProxyRequest.java:onClientGone`); a reset
discards the answer and cancels the call. The depth limit of 32 applies; an alias cannot target an alias, so an alias
chain adds at most two names (alias, router, alias, target).

### D6. Headers

The child request drops the headers Core sets for an origin (`EmbeddedServerRequest.DROPPED_HEADERS`: deployment id,
deployment features, override name, upstream and application headers). Over HTTP the target pass received them as
client headers and replaced or dropped them for its own origin. The client's headers and the alias's `defaultHeaders`
(set where the client sent none) pass, as for a plugin's call. A test compares the headers the model's origin
receives through the alias with what the HTTP hop gave (task 2.4).

### D7. Tracing

The child pass runs on a duplicate of the alias pass's Vert.x context with the alias pass's OTel context
(`VertxHostDelegate.call`), so the hop has no HTTP client and server span pair, and the target's origin calls are
traced under the alias pass's server span. Token-stats spans are unchanged: the target pass's span still has the alias
pass's span as parent (the key record's `spanId`).

### Effect per request

Per alias call on a create operation:

| | Over HTTP (today) | In process |
|---|---|---|
| Core passes | 2 | 2 |
| HTTP requests into Core | 2 | 1 |
| Serializes of the body | alias pass 1, target pass 1 for its origin | target pass 1 for its origin |
| Body copies over the wire | alias pass writes, target pass reads | none |
| Parses of the body | client bytes 1, target pass 1 | client bytes 1 |
| Redis | alias key SET and DEL, target key GET, the spans and limits of both passes | the same minus the GET |
| Blob operations | unchanged | unchanged |

The tier chain (alias, router in decision mode, alias, model) has two such hops. The embedded-call A/B
(`openspec/changes/archive/2026-11-03-embedded-call/review.md`, task 7.1) measured a child pass at about 11 to 17 ms
less Core CPU than the HTTP pass it replaced, at 383 KB requests. That is an expectation for the alias hop, not a
measurement of it. Task 4.1 measures it: Core CPU per user request and Valkey commands per request, through the alias
chain against direct, on the image before and after this change.

## Risks / Trade-offs

- [The target pass reaches controller code that no plugin call has used, and an `EmbeddedServerRequest` member that
  throws is hit] → the alias pass reaches only `CREATE_ROUTES`, which plugin calls cover; the existing alias, router
  and decision API tests now run the target pass in process and fail on such a gap.
- [A header the target's origin got over HTTP is now missing] → the header comparison test (task 2.4).
- [Traces lose one server span per alias hop; a dashboard that counts passes by server spans counts fewer] → stated in
  `docs/dynamic-settings/aliases.md`.
- [No idle timeout on the hop: over HTTP the client's idle timeout bounded it] → the target pass's own origin timeouts
  still apply, as for plugin calls.
- [The hop no longer moves to another event loop] → with several HTTP server instances, the loopback connection could
  land the target pass on another loop; in process it runs on the alias pass's loop. With the default single instance
  there is no difference.
- [The handed-over tree is shared] → `EmbeddedProxyRequest.send` clears the context's tree when it hands it over, and the
  alias row reads bytes, not the tree.

## Migration Plan

None. No config or settings change. `backgroundJob.localBaseUrl` is still used by background polling and by the alias
requests that stay on HTTP. Rollback is a revert.
