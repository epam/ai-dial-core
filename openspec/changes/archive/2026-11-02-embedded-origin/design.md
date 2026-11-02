# Design: embedded-origin

## Context

See proposal.md for why. Paths are under `server/src/main/java/com/epam/aidial/core/server/` unless a module is
named. Line numbers are from this branch at `1f67f81e`, after `development` was merged in. The merge left the
request-path files unchanged.

**Where Core calls an origin today.** Every interface family reaches the HTTP call through two methods of
`controller/BaseDeploymentPostController.java`.

- `createProxyRequest(String absoluteUri)` (380-391) sets `ProxyContext.proxyRequestUri`, builds
  `RequestOptions` with `setAbsoluteURI`, and calls `proxy.getClient().request(options)`.
  `createProxyRequest(InterfaceType)` (393-403) builds the URI with `DeploymentEndpointUtil.resolveRequestUri`.
- `sendProxyRequest` (405-457) copies the client headers, adds `API-KEY`, `X-DIAL-DEPLOYMENT-FEATURES`, the
  `X-UPSTREAM-*` headers and the application headers, and calls `proxyRequest.send(body)`.
- deployments-POST and `/openai/v1/chat/completions` reach them through `BaseChatCompletionController.sendRequest`
  and `handleProxyRequest`. The Responses API goes through `ResponsesController.sendRequest`. Anthropic Messages
  and `count_tokens` go through `anthropic/MessagesBaseController.sendRequest` and `handleProxyRequest`.
- Interceptor hops use `createProxyRequest(String)` too: `BaseInterceptorController` extends this class (line 29)
  and calls it at line 114. `ChatCompletionInterceptorController` overrides `enrichProxyRequestHeaders`.
- Responses item operations (get, delete, cancel) are the one other path to an origin. They go through
  `ResponseItemController.forwardToUpstream` and `sendRequest` to `service/ResponsesApiClient.send`, which builds
  its own `RequestOptions` and calls its own `HttpClient.request`.

**What code after the send uses.** The members of Vert.x `HttpClientRequest` and `HttpClientResponse` read after
creation are the de-facto origin contract:

| Type | Members used |
|---|---|
| request | `headers()`, `putHeader`, `send(Buffer)`, `reset()`, `connection().remoteAddress()` in seven log lines, and `absoluteURI()` in `McpProxyController` (324) |
| response | `statusCode()`, `headers()`, `getHeader`, `body()`, and the `ReadStream<Buffer>` methods that `vertx/stream/BufferingReadStream.java` drives (`handler`, `endHandler`, `exceptionHandler`, `pause`, `fetch`) |

The consumers on the deployment paths:
- `UpstreamRoute.fail` and `succeed`;
- `ProxyUtil.copyResponse`, `handleChunkedResponse`, `contentLength` and `setOverrideNameHeader`;
- `BaseDeploymentPostController.createResponseStream` and `isEventStreamResponse`;
- the `handleProxyResponse` of `BaseChatCompletionController`, `ResponsesController` and `MessagesBaseController`;
- `MessagesController` and `MessagesCountTokensController`;
- `BaseInterceptorController`;
- `ResponseItemController.collectAndForward` and `collectAndForwardStreaming`;
- `ProxyContext.proxyRequest` and `proxyResponse` (lines 94 and 96).

Three HTTP-only controllers set the same `ProxyContext` fields and call the same helpers:
- `McpProxyController` (208, 286, 324, 537);
- `DeploymentFeatureController` (228, 283, 337);
- `controller/route/RouteRequestBodyHandler` (144, 191, 263).

These unit tests mock the types: `DeploymentPostControllerTest`, `ResponsesControllerTest`,
`ResponseItemControllerTest`, `ChatCompletionInterceptorControllerTest`, `GenAiTraceAttributesTest`.

**`RequestOptions` takes http and https only.** In vertx-core 4.5.35, `RequestOptions.setAbsoluteURI(String)`
parses the string with `java.net.URL`, so any other scheme throws `VertxException: Invalid url`.

**How the serving url is resolved.** `util/DeploymentEndpointUtil.java` resolves one string per interface, through
`resolveServingEndpoint`, `resolveRequestUri`, `resolveResponseItemUri` and `resolveBaseUrl`.

- That string is the 503 gate: `DeploymentPostController.handleDeployment`, `ChatCompletionsController.setupDeployment`,
  `ResponsesController.setupDeployment` and `MessagesBaseController.setupDeployment`.
- It is the synthetic upstream's endpoint and id for a deployment without `upstreams`
  (`upstream/UpstreamRouteProvider.getUpstreams`, 149-161).
- It is therefore the Responses mapping's `upstreamKey`, and the item call is pinned to that key.
- The analytics row's `response.upstream_uri` is the endpoint of the attempt's upstream
  (`log/AnalyticsLogContext.from`, 107-110): the synthetic one without `upstreams`, the configured one with them.
- `resolveMode` feeds `BaseDeploymentPostController.isSubjectToLimits` (244-246).

**Threading.** Blocking steps run in `vertx/AsyncTaskExecutor.execute`. It captures
`(ContextInternal) vertx.getOrCreateContext()` on the event loop and runs the task under `context.dispatch` on a
virtual thread. The first `createProxyRequest` runs there. The HTTP client's futures complete on the request's event
loop, so `handleProxyRequest`, `sendProxyRequest` and the relay run there; retries start on the event loop.

**Plugins today.** `config/PluginRegistry` (change `plugin-config-entity`) holds the providers loaded at start, by
type, and `Config.plugins` holds the declarations. A provider today only validates settings.

**Analytics rows in tests.** `AiDial.start` builds `new GfLogStore(...)` inline (245), and no integration test
asserts a row today. `GfLogStoreTest` checks rows at unit level only, against a mocked `LogEntry`.
`GfLogStore.admit` may queue a row without its bodies (`AnalyticsLogContext.withoutBodies`, spec `analytics-log`);
`upstream_uri` is written either way.

## Goals / Non-Goals

**Goals:**
- One place decides between an HTTP origin and an embedded one. Everything after the send stays shared: retry,
  relay, usage, limits, the analytics row and finalize.
- HTTP origins see no change in what Core sends, relays or records.
- An origin plugin gets the same request an HTTP origin would get, so an HTTP origin ports to a plugin one to one.

**Non-Goals:**
- Fewer passes or a cheaper child pass. A plugin that needs a model calls Core back over HTTP with its per-request
  key; `embedded-call` replaces that.
- Calling a plugin off the event loop, a timeout or a watchdog for plugin code, and a plugin lifecycle (close,
  health).
- Embedded interceptors and translators (`embedded-filter`).

## Decisions

### D1. A Core-owned origin request and response, mirroring what is used today

The change adds two interfaces in `upstream/`:
- `ProxyRequest`: `headers()`, `putHeader`, `send(Buffer) → Future<ProxyResponse>`, `reset()`, `remoteAddress()`,
  `absoluteURI()`.
- `ProxyResponse extends ReadStream<Buffer>`: `statusCode()`, `headers()`, `getHeader(CharSequence)`,
  `body() → Future<Buffer>`, and `discard()` (D6).

Each has an HTTP implementation that delegates to the Vert.x object (`HttpProxyRequest`, `HttpProxyResponse`) and
an embedded one (D6). `HttpProxyResponse.discard()` does nothing, which is what Core does today with an answer it
retries past.

The consumers listed in Context change type only. Their calls keep their names: `getHeader`, `body()`, `reset()`,
and `ReadStream` for `BufferingReadStream`. The log lines read `remoteAddress()` in place of
`connection().remoteAddress()`. The three HTTP-only controllers wrap the Vert.x objects they already create, so
they keep using the shared `ProxyContext` fields and helpers. Task group 1 lands this with the HTTP implementation
only and the existing suites green.

Rejected alternatives:
- **Implement Vert.x `HttpClientRequest` and `HttpClientResponse` for the embedded origin.** They are large
  interfaces, about 60 methods each, so this means dozens of throwing stubs that every Vert.x upgrade can break.
- **Use the plugin API's response type in Core's consumers.** That ties the published contract to what Core's
  relay happens to read, and moves `getHeader` and `body()` calls into helpers at every site.
- **Keep Vert.x overloads of the helpers for the HTTP-only controllers.** Two signatures for every helper, for
  callers that one constructor call per request serves equally well.

### D2. One switch, keyed by the interface's mode

A new `upstream/OriginClient`, held by `Proxy`, has two methods:
- `http(RequestOptions) → Future<ProxyRequest>` is today's `client.request(options)`, wrapped by D1.
- `embedded(ProxyContext, HttpMethod, String absoluteUri, String plugin) → Future<ProxyRequest>` builds the embedded
  request of D6.

Every failure of the embedded branch comes back as a failed future, never as a throw, so each caller's existing
failure path answers it:
- the resolve failures of D5;
- the platform check of D9.

The two callers branch on the plugin:
- `createProxyRequest(InterfaceType)` passes `DeploymentEndpointUtil.resolvePlugin(deployment, type)` (non-null only
  in `embedded` mode) to `createProxyRequest(String absoluteUri, @Nullable String plugin)`.
  - With no plugin it builds `RequestOptions` exactly as today and calls `http`.
  - With a plugin it calls `embedded`.
  - `createProxyRequest(String)` keeps its signature and passes no plugin, so interceptor hops stay HTTP.
  - A failed future reaches `handleProxyConnectionError`, which answers the connect-failure `502`.
- `ResponsesApiClient.send` gains a `ProxyContext` and a `plugin` argument and branches the same way.
  - `ResponseItemController.forwardToUpstream` passes `resolvePlugin(deployment, OPENAI_RESPONSES)`.
  - A failed future reaches `ResponseItemController.handleError`, which answers `500`
    `Failed to process response operation`, as it does today when the origin cannot be reached.

`RequestOptions` is built only on the HTTP branch because it cannot hold a `plugin://` url (Context).

Rejected alternative: **dispatch on the URL scheme of the resolved uri.** A pass-through `base_url` written as
`plugin://x` would then be served by plugin `x` with no `mode: embedded` and none of its checks.

### D3. An embedded entry resolves to the base url `plugin://<name>`

`DeploymentEndpointUtil.resolveBaseUrl` answers `"plugin://" + plugin` for an `embedded` entry with a `plugin`, and
null without one. Every url-shaped role then works without a branch:
- the 503 gate;
- the synthetic upstream endpoint and id, so the analytics `response.upstream_uri`, the mapping's `upstreamKey` and
  the item call pinned to it all read `plugin://<name>`;
- `resolveRequestUri` and `resolveResponseItemUri`;
- `proxyRequestUri` and the logs.

Next to that, in `DeploymentEndpointUtil`:
- `resolveLegacyEndpoint` and `findOverridePath` treat `EMBEDDED` as they treat `TRANSLATOR` (no fallback, no
  overrides);
- `isInterfaceDeclared` advertises an embedded entry that has a `plugin`;
- the new `resolvePlugin` returns the entry's `plugin` in `embedded` mode. It is a pure read and never throws.

The embedded request strips the `plugin://<name>` prefix, so the plugin gets the path and query.

The resolver does not check that the `plugins` entry exists. That is a deliberate difference from a translator
name, which resolves to no url and is answered `503`.
- **Why:** the resolver takes the translators map at about 15 call sites, so checking plugin existence would
  change every one of them to take the whole `Config`.
- **Effect:** a missing entry is found when the request is built (D5) and answered with the connect-failure
  `502`.

Rejected alternative: **an `embedded` flag threaded through the callers of the resolver.** It needs about 15 more
branches for the same result.

### D4. The plugin contract: the HTTP origin contract, in Vert.x types

The contract goes into `plugin-api` (package `com.epam.aidial.core.plugin`), with `vertx-core` added at
`${vertx_version}`. The subprojects apply plain `java`, so the dependency is `implementation`, as Jackson's already
is. The `plugin-api` row of `CLAUDE.md` changes from "Jackson only" to Jackson and Vert.x.

```java
public interface OriginPluginProvider extends PluginProvider {
    OriginPlugin create(ObjectNode settings);              // once per declaration version (D5)
}

public interface OriginPlugin {
    Future<OriginResponse> handle(OriginRequest request);  // on the request's event loop (D6)
}

public record OriginRequest(HttpMethod method, String uri, MultiMap headers, Buffer body) {}

public interface OriginResponse {
    int statusCode();
    MultiMap headers();
    ReadStream<Buffer> body();
    void cancel();                                         // Core stops reading early
}
```

- `uri` is the path and query Core would append to a base url, for example
  `/openai/deployments/app/chat/completions?api-version=1`.
- `headers` is the map `sendProxyRequest` builds. It includes `API-KEY` with the per-request key and the
  `X-UPSTREAM-*` headers of the attempt's upstream, and no `traceparent`, since no client span is started.
- `body` is the `Buffer` Core would send, passed by reference, and empty for an item operation. Core reuses it for
  a retry, the analytics row and the limits, so the Javadoc says a plugin must not modify it.
- `settings` handed to `create` is the live config tree. The Javadoc says it is read-only, as for
  `PluginProvider.validate`.
- `PluginRegistry` finds an origin provider through the same `ServiceLoader<PluginProvider>`, so the plugin kind
  follows from the provider's type. No `kind` field is needed.

Rejected alternative: **a curated request with the caller's identity, trace ids and the parsed body tree.** A
routing plugin needs it, and it arrives with `embedded-call`, which also lets a plugin call another deployment in
process. Handing over the HTTP contract keeps this change to one new behaviour. It also keeps today's way for a
plugin to reach a model: calling Core back with `API-KEY`, which app-to-model-accounting already covers.

### D5. One plugin instance per declaration version

A new `config/EmbeddedOrigins` is built in `AiDial` from the `PluginRegistry` and held by `Proxy`. Its
`resolve(Config, String name)` does this:

1. Reads `config.getPlugins().get(name)`.
2. Looks up the provider by `type`. It must be an `OriginPluginProvider`.
3. Returns the cached `OriginPlugin` when the cached declaration is the same object as the live one, or `equals`
   it. `Plugin` is `@Data`, so the comparison covers `type` and the `settings` tree.
4. Otherwise calls `create(settings)` outside the map, then `put`s the result. Two requests racing on a new
   declaration may each create one, and the last `put` wins; that is harmless.

`resolve` runs on the event loop on retries, so it takes no lock: `get`, compare, then `put`. The Javadoc of `create`
says it must be quick and must not block.

Any failure fails the request's future (D2):
- no entry by that name;
- the provider is not loaded, or does not serve requests;
- `create` throws.

A replaced instance is dropped with no close call. A deleted name's instance stays in the map, so the map holds at
most one instance per plugin name ever declared. Both shortcuts get a code comment naming the limit and the upgrade
path (a close hook and eviction, once a plugin holds threads or connections).

Rejected alternatives:
- **`ConcurrentHashMap.compute`.** It runs `create` under the map's bin lock on the event loop, against the
  event-loop rules of `docs/CODE_STYLE.md`.
- **Pass `settings` on every `handle`.** Every plugin would then cache its own parsed settings.
- **Create at config rebuild.** Every rebuild, for any entity, would recreate every plugin.

### D6. The embedded request and response

`upstream/EmbeddedProxyRequest` is built by `OriginClient.embedded`. It captures the request's context the way
`AsyncTaskExecutor.execute` does: `(ContextInternal) vertx.getOrCreateContext()`, which on a dispatched virtual
thread is the request's context.

The flow on that context:
1. It is returned as `ctx.succeededFuture(request)`. Listeners then run on the event loop, as with the HTTP client.
   A plain completed future would run them inline on the virtual thread.
2. `handleProxyRequest`, `sendProxyRequest` and `send` therefore run on the event loop.
3. `send(body)` calls `plugin.handle` there.
4. It completes a promise of `ctx`.

A synchronous throw from `handle` fails that promise. The send failure path then treats it as a failure to receive
the headers: `fail(BAD_GATEWAY)` and the next attempt.

`reset()` cancels the plugin's answer, or marks the request so the answer is cancelled when it arrives.

`upstream/EmbeddedProxyResponse` adapts the plugin's `OriginResponse`:
- `getHeader` reads `headers().get`;
- `body()` aggregates the stream into one `Buffer`;
- the `ReadStream` methods delegate to the plugin's body stream;
- `discard()` calls `cancel()`.

The three `handleProxyResponse` methods call `discard()` on an answer they retry past, so a plugin's stream is never
left waiting for a reader. For HTTP, `discard()` does nothing.

Rules for plugins, stated in the `OriginPlugin` Javadoc:
- complete the future and emit the stream on the context `handle` was called on, and never block it;
- emit nothing until Core sets a handler or calls `fetch` or `resume`. Core sets the handler only after the future
  completes, and chunks emitted earlier are lost; Vert.x's own streams behave the same way.

Core adds no hop to enforce the first rule. A code comment names the limit, with the upgrade path of a forwarding
adapter when a plugin needs its own threads. The SSE listener chain and the `ProxyContext` it writes are not
thread-safe.

Rejected alternatives:
- **Call `handle` on a virtual thread.** It would allow blocking, but every chunk would then cross threads.
- **A `runOnContext` adapter in front of every stream.** It costs a hop per chunk to enforce a rule that
  first-party plugins can keep.

### D7. Limits and accounting follow the pass-through shape

`InterfaceMode.EMBEDDED("embedded", true)` is subject to limits, so the pass is checked and charged exactly as a
pass-through one. The plugin serves the completion itself, as an adapter does. That matches the design note's rule
that an origin is a pass with its own row.

`processChatCompletionsRequestBody`, `ResponsesController.handleRequestBody` and
`MessagesBaseController.prepareUpstreamRoute` still mint the per-request key, and `finalizeRequest` still deletes
it. The plugin can use the key to call Core back, and the key's fields (`spanId`, `executionPath`,
`sourceDeployment`) keep a callback accounted under this pass.

Rejected alternative: **exempt the pass like `translator` mode.** A translator's callback serves the completion. An
origin plugin may answer by itself, and then nothing would be charged.

### D8. Validation

- **Models.** `ConfigPostProcessor.validateDeploymentInterfaces` gets an `EMBEDDED` branch before the pass-through
  url rule: no `plugin`, or a `base_url` next to the `plugin`. Then a `plugin` outside `embedded` mode is checked
  after the translator check. The messages are in the spec.
- **Applications and interceptors.** `validateOverridePaths(Deployment, warnings)` is the per-entry entry point for
  applications and interceptors on every write surface:
  - `ConfigPostProcessor` (349, 368, 855, 940);
  - `ConfigResourceController` (2069);
  - `ApplicationService.requireRenderableOverridePaths`;
  - `ConfigApplyService` (232);
  - `ConfigValidationService` (107, 150).

  It is renamed `validateInterfaces` and gains the same plugin rules, plus `Mode 'embedded' is not supported for an
  interceptor`. One rename puts the new rules on all of those surfaces.
- **Override paths.** `validateOverridePaths(... DeploymentInterface ...)` rejects `overridePaths` under an
  `EMBEDDED` entry, as it does under `TRANSLATOR`.
- **Unresolved names.** A `plugin` naming no entry is not a config error, as for a translator name (D3).

Rejected alternative: **the plugin rules for models only, as the translator rules are.** Applications are the first
users of the embedded mode. Unchecked, a typo in an application's entry would be stored and answer `502` on every
request.

### D9. Embedded mode only for platform deployments

A plugin runs platform code, and it may use credentials from the pod's environment or workload identity, as a
plugin that signs requests for a cloud API would. So a user's custom application must not be able to name one.

- **On write.** `ApplicationService.prepareApplication` already knows `platformBucket`. Outside it, any
  `embedded` entry is refused with `422` `Mode 'embedded' is reserved for platform deployments`.
  - Both application write paths run it: `putApplication`, and `putApplicationByUnitRole` for a write an org-unit
    role allows.
  - Org-unit roles grant access only under org units, never to the `platform` bucket
    (`AccessService.getUnitRoleAccess`).
- **On request.** `OriginClient.embedded` refuses an `Application` whose name is not a key of
  `config.getApplications()`, and the D2 failure paths answer it.
  - Config applications are keyed by their name: `MergedConfigStore.isShortNameKeyed`, and
    `ConfigPostProcessor.processApplications` sets the name from the key.
  - A custom application's name is its resource URL, which never equals a key, because slash-keyed file entries are
    dropped.
  - Models come only from the config, and interceptors cannot be embedded (D8).
- **Why a name check, not an identity check.** Comparing with the object `config.selectDeployment` returns would
  refuse platform deployments in two cases:
  - a request that straddles a config reload, since a rebuild makes new deployment objects;
  - every schema-typed application, which `ApplicationSchemaService` replaces by a copy.
- **Why both checks.** The write check gives the user a clear answer. The request check also covers blobs that
  reach a user bucket by another path: an import, a blob written by an older Core, or a publication copy. Org-unit
  approvers can now approve publications (`PublicationService`), so a publication need not pass an admin.

### D10. Tests

**The test plugin.** `TestOriginPluginProvider` (type `test-origin`, in `server/src/test/.../config/`, registered
next to `TestEchoPluginProvider` in `META-INF/services`). It records in static fields the `OriginRequest` of every
call and the number of `cancel` calls. It answers by the path:
- chat completions: a `chat.completion` JSON, or SSE when the body has `stream: true`;
- embeddings: an embedding list;
- `/openai/v1/responses`: a `response` with id `plugin-resp-1`, then the item get and cancel;
- `/anthropic/v1/messages`: a message, and `count_tokens`.

Every answer carries `usage` and the setting `greeting` as its text. Headers from the client steer the failure
cases:
- `X-Test-Status` sets the status;
- `X-Test-Status-For-Endpoint` sets it for one `X-UPSTREAM-ENDPOINT`;
- `X-Test-Fail: call` fails the future;
- `X-Test-Hold` sends the first event and waits for `cancel`.

Core copies client headers to the origin request, so these reach the plugin as they would reach an HTTP origin.

**Reading analytics rows.** No test reads a row at API level today (Context). `AiDial` gets a protected
`createLogStore(AnalyticsSettings)` that returns the `GfLogStore` it builds today. `ResourceBaseTest` overrides it
with a store that records each `AnalyticsLogContext` and forwards it to `GfLogStore`, and exposes the recorded rows.
It records the context before `GfLogStore.admit` decides on bodies. `embedded-call` reuses this for its child-row
scenarios.

**The test classes.**
- `EmbeddedOriginApiTest` (new, server) covers the deployment-origin, responses-api, plugin-registry and
  request-side deployment-interfaces-config scenarios. Each test creates its `plugins` entry and deployments
  through the Configuration API, so the shared test config, and the listing counts other tests assert, stay
  unchanged. The retry scenario declares `maxRetryAttempts: 2` and two upstream tiers, because the default is one
  attempt and a tier is picked at random.
- The config rules go into `ConfigPostProcessorTest`, plus the `422` write scenarios in `EmbeddedOriginApiTest`.
- The custom-application write is in `EmbeddedOriginApiTest`. The stored-blob request scenario writes the blob
  through `ResourceService` as `ConfigPluginApiTest.putBlob` does.

### D11. Effect per request

**HTTP origins.**
- One small wrapper object for the request and one for the response per attempt, and one interface call more per
  stream callback.
- No extra body copy or parse, and no extra Redis or blob round trip.
- Nothing measurable is expected. A deferred A/B checks that: the perf scenario in which clients call a model
  directly, `development` against this change.

**Embedded origins.**
- Core takes no pooled connection, writes no request headers to a socket and parses no response headers.
- The body `Buffer` is handed over by reference.
- `EmbeddedOrigins.resolve` costs one map read and one comparison of the declaration. That comparison is by
  identity until a config rebuild, then by `equals` over the settings tree.
- Redis and blob round trips are those of a pass-through pass: the key SET NX and DEL, the limit records and the
  span stay.
- The pass count does not change, because a plugin that calls a model still makes an HTTP child pass. This change
  claims no Core CPU saving on the path through a routing application; `embedded-call` makes and measures that
  claim.

## Risks / Trade-offs

- **[A plugin blocks or answers slowly on the event loop]** → every request on that loop stalls. There is no
  timeout or watchdog in v1. Mitigation: the Javadoc rules (D6) and first-party trust. A plugin that never
  answers holds its request until the client goes, as a hung HTTP origin without an idle timeout would.
- **[A plugin emits from another thread, or before Core sets a handler]** → the SSE listener chain is not
  thread-safe, and early chunks are lost. The rules are in the Javadoc. The upgrade path is the forwarding adapter
  of D6.
- **[Older pods during a rollout]** → a Core without `EMBEDDED` cannot bind `mode: embedded` or `plugin`.
  - **Bucket entity:** the older pod records it as invalid and skips it in both `abort` and `skip` modes
    (`MergedConfigStore` blob scan). That deployment is missing on that pod, so it answers `404` there, while the
    rest of the config loads.
  - **Config file:** an older pod cannot start with it.

  Mitigation: roll the image out before writing embedded config (Migration Plan).
- **[A plugin sees `X-UPSTREAM-KEY` and the per-request key]** → it sees what an HTTP origin sees today, no more.
  Plugins are first-party jars only. The caller's own key is never sent
  (`API-KEY` is a hop-by-hop header in `ProxyUtil`).
- **[A client leaves during a streamed item GET]** → `ResponseItemController` resets only the client
  connection, so the plugin's stream is not cancelled. HTTP origins have the same gap today. A later change can
  call `discard()` there.
- **[Merge conflicts with the unmerged builtin-translator branch]** → that branch edits these methods:
  - `BaseDeploymentPostController.sendProxyRequest` and `isSubjectToLimits`;
  - `MessagesBaseController.setupDeployment`, `sendRequest` and `enrichProxyRequestHeaders`;
  - `MessagesController.processResponse`;
  - `DeploymentEndpointUtil` next to `resolveMode`.

  Mitigation: the refactor of D1 changes types and not logic, so either rebase is mechanical. Whichever lands
  second rebases.
- **[A missing plugin answers `502`, a missing translator `503`]** → recorded in D3. An operator reads the cause in
  the log line of the failed request.

## Apply notes

- **D1, two more members.**
  - `ProxyRequest` has `send()` as well as `send(Buffer)`: item operations send no body. The HTTP implementation
    keeps `HttpClientRequest.send()` for them, so what an HTTP origin receives does not change.
  - The URI accessor is `absoluteUri()`: checkstyle refuses `absoluteURI`.
- **D2, where the pieces sit.**
  - `OriginClient` holds the `EmbeddedOrigins`, built in `AiDial`, so `Proxy` gained only the `OriginClient`.
  - `OriginClient.embedded` reads the deployment from the context: both callers set it first.
  - `ResponsesApiClient.send` takes the context and the plugin as trailing arguments.
  - The two-argument `createProxyRequest(String, String)` arrived with task 3.5, when there was a plugin to pass.
- **D8, interceptors.** An interceptor with a `plugin` and no `embedded` mode gets `A plugin requires mode 'embedded'`,
  as a model or an application does.
- **D9, the request check** is in `OriginClient.embedded`: the application's name is a key of
  `config.getApplications()`, or starts with `applications/platform/`. The second case is a platform-bucket
  application called by its url, which `DeploymentService.findDeployment` loads from the bucket under that url.
- **D9, copies.** `ApplicationService.copyApplication` runs the same check as a write, against the destination
  bucket, so a copy, a move or a publication of an embedded application out of the platform bucket is refused.
- **D6, plugin errors.** The calls into plugin code (`handle` and `create`) catch `LinkageError` as well as
  `RuntimeException`: a jar missing a class throws `NoClassDefFoundError` on first use, which would otherwise
  escape a listener and leave the request unanswered.
- **D6, the body stream.** `EmbeddedProxyResponse.body()` resumes the stream after setting its handlers, and the
  `OriginResponse` Javadoc says a plugin's body emits only from a task of its own, never from inside a call Core
  makes to it: `BufferingReadStream` sets its handlers one after another.
- **D10, the hold stream.** The test plugin's `X-Test-Hold` stream adds an event every 100 ms until it is cancelled.
  Core notices a client that has gone only when it next writes to it, so a stream that never wrote again would never
  be cancelled; an HTTP origin is in the same position.
- **Connect-failure body.** The baseline "Connect failure" requirement of `deployment-origin` quotes `"code":"502"`,
  but `BaseDeploymentPostController.handleProxyConnectionError` writes `String.valueOf(HttpStatus.BAD_GATEWAY)`,
  which is `"BAD_GATEWAY"`. The "Missing plugin entry" scenario of this change now asserts the status and the
  messages only. The baseline text is a separate fix.
- **A Windows-only failure.** `CustomApplicationApiTest.testApplicationListing` fails locally because the filesystem
  blob store lists a folder as `gpt\` on Windows, as `ResourceApiTest.testWorkflow` does. The change does not touch
  folder listing; CI runs on Linux.

## Migration Plan

1. Deploy the image. With no `mode: embedded` in any config nothing changes, and HTTP origins go through the D1
   wrappers only.
2. Then write the `plugins` entry and switch a deployment's interface to `{"mode": "embedded", "plugin": ...}`.
3. Roll back by switching the interface back to its `base_url`. A config flip, no restart.

Rolling the image back while embedded config is live hits the older-pod risk above, so flip the config back first.

Responses stored while the interface was embedded keep `upstreamKey: plugin://<name>`. After a flip back, their item
operations answer `400` `Unknown upstream id plugin://<name>`, and their background jobs end without a result. The
same happens today when an upstream id is removed.
