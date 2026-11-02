# Design: embedded-call

## Context

See proposal.md for why. Paths are under `server/src/main/java/com/epam/aidial/core/server/` unless a module is
named. Line numbers are from this branch at `3ee799b2`. A *pass* is one request Core serves, with its own limit check,
token-stats span, per-request key and analytics row; a *child pass* is one a deployment starts by calling Core back.

**How a pass starts today.** `Proxy.handleRequest` (254-321) reads the OTel server span's trace and span ids, pauses
the request and authenticates it. `processAuthorizationResult` (472-485) then:
1. builds `new ProxyContext(this, request, apiKeyData, extractedClaims, traceId, spanId, traceFlags)`;
2. calls `GenAiTraceAttributes.initialize(context)` and `ContextManager.setProxyContext(context)`, a Vert.x context
   local;
3. calls `ControllerSelector.select(request).build(this, context).handle()`.

`ControllerSelector.select` (684-696) routes any request by method and path. It caches the choice in the request's
context only when the request is Vert.x-internal.

**A child pass today.** An application calls Core back with the per-request key in `API-KEY`. Core runs:
- `ApiKeyStore.getApiKeyData`: one Redis GET and a Jackson parse of the key record, and `401 Unknown api key` when
  the record is gone (139-142);
- a new `ProxyContext`, which takes the trace id, parent span and source deployment from that record (124-146), and
  the OTel server span as its span id;
- the controller of the path. That means the role, consent and per-request-key access checks, the target's limits,
  `startSpan`, minting a key for the target's origin, the enhancement chain, the relay, usage, the row and finalize.

What the pass touches through Vert.x's `HttpServerRequest` and `HttpServerResponse`:

| Object | Members |
|---|---|
| request | method, path, uri, query, headers, `body()`, version |
| response | status, headers, chunked, `write`, `end`, `reset`, `ended`/`closed`, the `WriteStream` members a pipe drives |
| log layout | the method, the uri, and the response's status, `ended()` and `getStatusMessage()` (`log/layout/AutoEnrichedOtelJsonLayout`, 107-119) |

The error paths call `respond(...)` from `AsyncTaskExecutor` tasks, so on a virtual thread:
- `DeploymentPostController.handleRequestBody`;
- `BaseChatCompletionController.processChatCompletionsRequestBody`;
- `BaseDeploymentPostController.nextUpstream`;
- `ResponsesController.sendRequest`;
- `MessagesBaseController.sendRequest`.

These end in `ProxyContext.respond` (191) writing the response off the event loop.

**Pipes.** In vertx-core 4.5.35, `PipeImpl.to` sets only the source's handlers. It pauses the source and sets the
destination's `drainHandler` when `writeQueueFull()`, and fails only when the ack of a `write(item, handler)` fails.
The controllers set no close or exception handler on the response; `BaseChatCompletionController` (200-205) and
`ResponsesController` (425-430) only pipe.

**Contexts and tracing.** `ContextInternal.duplicate()` makes a context on the same event loop with empty locals.
`ContextImpl.emit` runs its handler inline only on the context's own thread, and queues it otherwise. The Vert.x
OTel tracer reads the active OTel context from the local `VertxContextStorageProvider.ACTIVE_CONTEXT`. Without that
local, a client request starts no client span and sends no `traceparent`. `tracing/DialVertxTracer` (47-52) already
copies the local for that reason. `GenAiTraceAttributes.set` (293-301) writes to `Span.current()`.
`vertx/stream/InputStreamReader` already uses Vert.x's `InboundBuffer`, which queues, pauses, fetches and drains on a
context.

**Where the body is parsed.** Six sites do it the same way, `ProxyUtil.parseObject(body)`, the first four after
`context.setRequestBodyWithTimestamp(body)`:
- `DeploymentPostController.handleRequestBody` (272-293);
- `ChatCompletionsController.parseBody` (86-93);
- `ResponsesController.parseBody` (280-296);
- `MessagesBaseController.parseBody` (117-125);
- `ChatCompletionInterceptorController.parseRequest` and `ResponsesInterceptorController.parseRequest`, called from
  `BaseInterceptorController.handleRequestBody` (91-98).

Each enhancement chain then re-serialises the body before the origin or interceptor request, because
`ApplyDefaultDeploymentSettingsFn` reports a change:
- `BaseChatCompletionController.processChatCompletionsRequestBody`;
- `ResponsesController.handleRequestBody`;
- `MessagesBaseController.prepareUpstreamRoute`;
- `BaseInterceptorController.handleRequestBody`.

On a retry, `BaseChatCompletionController.handleProxyResponse` (160-164) resends `context.getRequestBody()`.

**The analytics row.** `AnalyticsLogContext.from` builds the row; `withoutBodies()` (from `development`'s
analytics-backlog change) copies it without the three body fields.
- `BaseChatCompletionController.completeProxyResponse` (288-291) builds the assembled streamed answer for every SSE
  pass.
- Responses builds its own in `ExtractTerminalResponseFn`.
- Messages builds none (`MessagesController`, 242).

**Embedded origins.** These come from `embedded-origin`.
- `EmbeddedProxyRequest.send` calls `OriginPlugin.handle(OriginRequest)` on the request's event loop.
- The pass keeps its minted per-request key record as `ProxyContext.proxyApiKeyData`: executionPath
  `[<deployment>]`, spanId the pass's span, and the trace id.
- `finalizeRequest` deletes that key when the pass ends (`BaseDeploymentPostController`, 131-138).

## Goals / Non-Goals

**Goals:**
- A plugin calls a create operation of any deployment in process. The child pass runs the same checks, limits,
  token-stats span, key and row fields as an HTTP child.
- The child skips the HTTP request into Core, the key lookup, the body parse and the body copies in its row.
- A routing plugin needs no parse and no identity lookup of its own.

**Non-Goals:**
- A list of candidate deployments for Core to try in order, falling back on a non-2xx answer. Calls from filters.
- Sharing the answer's event processing between the child and the parent pass.
- An OTel server span for the child pass. Its HTTP origin calls keep their client spans (D2).
- A guard against a plugin calling its own deployment in a loop. An HTTP application can do the same today.

## Decisions

### D1. The contract additions

The contract stays in `plugin-api` and in Vert.x types:

```java
public interface OriginPlugin {
    Future<OriginResponse> handle(OriginRequest request, OriginHost host);
}

public interface OriginHost {
    Future<OriginResponse> call(OriginCall call);       // a child pass of this request
}

public record OriginCall(String uri, MultiMap headers, ObjectNode body) {}

public record OriginRequest(HttpMethod method, String uri, MultiMap headers, Buffer body,
                            ObjectNode bodyTree, OriginCaller caller) {}

public record OriginCaller(String userId, List<String> roles, String project) {}  // userId null for an API key
```

- **`OriginCall.uri`** is the path and query of a create operation:
  - a deployments-POST action;
  - `/openai/v1/chat/completions`;
  - `/openai/v1/responses`, not with `background: true`;
  - `/anthropic/v1/messages`.

  For the three that name the deployment in the body, the body's `model` names the target, as for any client.
- **`OriginCall.headers`** are copied; Core does not keep the plugin's map. Core drops the headers it sets for an origin
  itself, so a plugin that forwards its own request's headers cannot pass them on to another deployment:
  - `API-KEY`;
  - `X-UPSTREAM-ENDPOINT`, `X-UPSTREAM-KEY` and `X-UPSTREAM-EXTRA-DATA`;
  - `X-DIAL-DEPLOYMENT-FEATURES`, `X-DIAL-APPLICATION-ID`, `X-DIAL-APPLICATION-PROPERTIES` and
    `X-DIAL-DEPLOYMENT-ID`;
  - `Content-Length`.

  Core adds `Content-Type`. The call carries the plugin pass's per-request key.
- **`OriginCall.body`** passes to Core: the child's chain changes it in place, so a plugin that calls twice passes a
  `deepCopy()`.
- **`OriginRequest.bodyTree`** is the tree Core serialised into `body`, after the pass's chain. The plugin may pass it
  to `call` as it is, which spares the copy. On a retry over upstreams Core re-parses `body` for the next attempt, so
  each attempt gets a tree no earlier call has changed.
- **`OriginCaller.roles`** is a `List.copyOf` of the pass's roles, which come from cached claims.
- **The `handle` signature changes.** The contract is not published yet, and the one caller is in this repository.

Rejected alternatives:
- **`call(deployment, body)` with the interface taken from the plugin's own request.** A routing plugin answering
  Messages may want to call a model's Responses interface, and the path already says which interface it is.
- **`OriginHost` handed over at `create`.** A call belongs to one request: its key, span and identity.

### D2. The child driver: an in-memory request routed by Core's own selector

A new `ChildPass` (server package) implements `OriginHost` for one embedded request. `call` does this:

1. Refuses a call it cannot serve. The call's future fails, and the plugin decides what to answer:
   - one whose path `ControllerSelector` does not resolve to one of the four create routes;
   - a Responses call with `background: true`.
2. Answers `401` `Unknown api key`, as an HTTP child with a deleted key would get, when the plugin's pass has ended.
3. Builds a `ChildServerRequest`: method `POST`, the call's path and query, its headers (D1) plus
   `Content-Type: application/json`, an empty `body()`, and `response()` a `ChildServerResponse`.
4. Builds the child's `ProxyContext`:
   - `new ProxyContext(proxy, request, parent.getProxyApiKeyData(), null, null, spanId, parent.getTraceFlags())`.
     `spanId` is 16 random hex digits. The parent's in-memory key record is the child's `apiKeyData`, so
     `ProxyContext` takes the trace, the parent span and the source deployment from it, exactly as for an HTTP child,
     and nothing is read from Redis.
   - It marks the context as a child pass and gives it the call's body tree (D3).
5. Runs `Proxy.dispatch(context)` on a duplicate of the parent's Vert.x context. `dispatch` is extracted from
   `processAuthorizationResult`: initialize, `setProxyContext`, select, build, handle.
   - The duplicate gets the parent's `ACTIVE_CONTEXT` local, as `DialVertxTracer` does, so the child's HTTP origin
     calls start client spans under the parent's server span and send `traceparent`.
   - `GenAiTraceAttributes` skips its span writes for a child pass, so they do not land on the parent's span.
   - The duplicate's other locals are its own: the child's `ContextManager` entry, and its blocking tasks
     (`AsyncTaskExecutor.execute` captures the current context), never touch the parent's.
   - A dispatch that fails is answered through `Proxy.handleError` on the child request, as for HTTP, so the plugin
     gets a status.
6. Returns a future that completes with the answer once the child response's head is written. A controller that
   answers `404`, `403` or `429` gives an answer, not a failure.

**When `ChildPass` closes.** It closes when the plugin's pass ends: `finalizeRequest` closes it, and so does
`EmbeddedProxyRequest.reset`.
- It cancels the answers it handed out that are still open.
- It cancels a head that arrives after it closed.
- It answers any later call `401`.

So a plugin that forgets a call cannot leave a child running past its own pass.

Rejected alternatives:
- **A request facade in `ProxyContext`** in place of `HttpServerRequest`. Every controller reads
  `context.getRequest()`, and it would touch them all for one caller.
- **A dedicated child driver that calls services directly.** It would copy the relay, retry, usage and row logic of
  three controllers.
- **Loopback HTTP with a flag in the key.** It keeps the HTTP request into Core and the parse, and the user chose the
  in-process pass.

### D3. The body tree, shared instead of parsed

`ProxyContext` gains `parseRequestBody(Buffer body)`, which all six parse sites call in place of
`ProxyUtil.parseObject`:
- For a child with a tree, it returns the tree once and sets only the body timestamp.
- Otherwise it parses as today. The four controller sites also set the body.

`ProxyContext.requestTree` keeps the tree only when the interface is `embedded`; `EmbeddedProxyRequest.send` takes it
and clears the field. Other passes keep no tree on the context, so their heap does not grow.

`getRequestBody()` serialises the shared tree when the body has not been set. Only a reader before a chain needs that,
such as a request whose chain fails early: every chain path, the interceptor hop included, sets the body itself.

Rejected alternative: **serialise the call's tree when the call is made.** That costs one serialisation per call,
and every chain path throws the result away.

### D4. The in-memory request and response

`ChildServerRequest implements HttpServerRequest` and `ChildServerResponse implements HttpServerResponse` sit in
`server/.../upstream/`.
- **Members.** They implement the members of the table in Context, plus `isEnded`, `scheme`, `host`, `absoluteURI`,
  `params`, `getParam`, `ended`, `closed` and `getStatusMessage`. Every other member throws
  `UnsupportedOperationException` naming the member and the in-process call. The tests (D8) cover each interface and
  the interceptor hop, so a member missed on a tested path fails a test.
- **Threads.** Every mutator (`setStatusCode`, `putHeader`, `write`, `end`, `reset`) runs on the child's context. A
  call from a virtual thread, as the error paths make, moves there first.
- **Head.** The first `write` or `end` freezes the status and headers into the answer that `call` completes with.
- **Body.** `write` and `end` feed an `InboundBuffer` on the parent's context. The answer's `ReadStream` is that
  buffer, so its chunks and its end are emitted from tasks of their own, as the `OriginResponse` contract asks, and
  the plugin's `pause`, `fetch` and `resume` work as on any Vert.x stream. `writeQueueFull` is the buffer's answer to
  the last write. `drainHandler` fires when the buffer drains, so a pipe from the child's `BufferingReadStream` pauses
  the child's origin as an HTTP client would.
- **Cancel.** `cancel` on the answer acts as a client connection closing.
  - The response turns `closed()`.
  - It fails the ack of every pending and later `write` and `end`.
  - It fires a pending `drainHandler`, so a paused pipe resumes and fails on its next write.

  The child's pipe then fails into `handleResponseError`: a `Model` reads its origin to the end and writes its row,
  and any other deployment resets its origin.

Rejected alternatives:
- **`java.lang.reflect.Proxy` instances for the two interfaces.** Both ways fail at run time on a member nobody
  implemented. A dynamic proxy also hides which members exist behind a dispatch on method names, needs
  `InvocationHandler.invokeDefault` for the interfaces' default methods, and cannot be read or stepped through like
  two plain classes.
- **A hand-written chunk queue** in place of `InboundBuffer`. It would repeat demand, pause and drain logic Core
  already reuses from Vert.x.

### D5. The child's row carries no bodies

- `AnalyticsLogContext.from` returns `withoutBodies()` for a child pass.
- `BaseChatCompletionController.completeProxyResponse` skips building the assembled streamed answer for one.
- Responses builds its assembled answer inside `ExtractTerminalResponseFn`, and that stays; Messages builds none.

The row keeps every other field: `deployment`, `execution_path` (the child's minted key: `[<parent>, <target>]`),
`parent_deployment`, `trace`, `token_usage` and `usage_per_model`.

Deliberate difference: the bodies are logged once only when the plugin passes its request's body on unchanged. A
plugin that rewrites the body sends the target a request whose body no row carries. The plugin's own row carries what
the client sent.

Rejected alternative: **marking the key `secured` for the child.** That would also change what the target's origin
is told about the caller.

### D6. The caller

`EmbeddedProxyRequest` builds `OriginCaller` from the pass's `ProxyContext`: `getUserId()` (null for an API key),
`List.copyOf(getUserRoles())` and `getProject()`. That is the identity a routing plugin would otherwise fetch with a
`/v1/user/info` call.

Rejected alternative: **hand the plugin `ProxyContext` or the key record.** That puts Core's internal types into the
published contract, and with them every field of the caller's key.

### D7. The `forward` sample plugin

A new module `plugin-samples` holds `ForwardOriginPluginProvider`, type `forward`, settings
`{"deployment": "<name>"}` (a required string).
- **deployments-POST actions:** each request goes to `/openai/deployments/<name>/<action>` through `host.call`, with the
  request's `bodyTree`.
- **`/openai/v1/chat/completions`, Responses and Messages:** the same path as the request, with `model` set in the tree.
- **The answer** is the call's answer, relayed as is. Cancel goes to the call's answer.

The module depends on `plugin-api`, Vert.x and Jackson as `compileOnly`, because Core's class loader provides them,
and as `testImplementation` for its tests. Its jar holds the provider's classes and the `META-INF/services` entry.

The `Dockerfile` builder stage builds every module already. An `ARG SAMPLE_PLUGINS=false` copies the sample jar into
`/build/plugins`, and so into `/app/plugins` (the default `plugins.dir`), only when it is `true`. The default image does
not change.

Rejected alternatives:
- **The forward provider in the server module's test fixtures.** A perf image cannot load test classes.
- **It in Core's main jar.** Every Core would then carry it.

### D8. Tests

- **The test plugin.** `TestOriginPluginProvider` gains a call mode:
  - `X-Test-Call: <path>` calls that path with the request's own `bodyTree` and relays the answer;
  - `X-Test-Call-Model: <name>` sets `model` in the tree first;
  - `X-Test-Call-Cancel` cancels the call after its first event and answers by itself;
  - `X-Test-Call-Late` makes a second call after it has answered, and records that call's status.

  The call passes on every header but `X-Test-Call*`, so a `p2` of the same type does not recurse, while `X-Test-Hold`
  reaches it. The plugin records the `OriginCaller` of each request.
- **`EmbeddedCallApiTest`** (new) covers the scenarios of the deltas. Targets are a second plugin `p2`, or an HTTP
  origin on `TestWebServer` for the interceptor and HTTP-origin scenarios. Rows are read through the recording log
  store of `ResourceBaseTest`.
- **Unit tests:**
  - `ChildServerResponseTest`: the head on the first write; the backpressure; tasks of their own on the parent's
    context; a write from a virtual thread; cancel failing the pipe;
  - `ChildServerRequestTest`: path, query and the headers dropped;
  - `ChildPassTest`: refused routes, a background call, a call after close, a late head;
  - the `ProxyContext` test: `parseRequestBody` and the lazy body;
  - `plugin-samples`: `validate` and the path mapping.
- **Controller tests.** `DeploymentPostControllerTest`, `ChatCompletionsControllerTest` and `ResponsesControllerTest`
  mock `ProxyContext`, so they need `parseRequestBody` stubbed.

### D9. Effect per request

These are estimates. The measured base is the perf env run of 2026-10-20: the path through a routing application at
a 383 KB body, 53.5 ms of Core CPU per user request, two passes of about 27 ms each plus about 3 ms for the router's
`/v1/user/info` call.

**What the child pass loses:**
- the HTTP client and server request into Core (connection, header writing and parsing, body aggregation), a few ms;
- the key lookup: 1 Redis GET and a parse of the key record;
- the request body parse, the larger part of the Jackson cost of the request, on every path, the interceptor hop
  included;
- the three body copies and the per-character escape in its row, and the assembled answer: most of the 6-9 ms of
  analytics work.

**What it keeps:**
- the chain and its one serialisation for the target's origin;
- the limit, span and key Redis calls (about 48 per child pass);
- the per-event SSE parse and re-serialise;
- the usage parse.

**What it adds:**
- one `InboundBuffer` task per chunk delivered to the plugin, and one task for a write from a virtual thread;
- the tree kept on the context of an embedded pass until its plugin is called.

Expected:
- the child pass at about 10-14 ms;
- a user request through a routing plugin at about 37-41 ms, against 53.5 ms with the HTTP router. About 3 ms of that
  difference is the `/v1/user/info` call the HTTP router makes and the `forward` plugin does not; the A/B reports it
  apart.

It does not reach 30 ms: the doubled event processing and the parent pass's own costs remain.

Blob round trips: none added.

## Risks / Trade-offs

- **[A Vert.x upgrade adds members to `HttpServerRequest` or `HttpServerResponse`]** → a compile error in the two
  in-memory classes, fixed by adding a stub.
- **[A controller path calls a member the in-memory classes do not support]** → `UnsupportedOperationException`, a
  `500` from the child, with the member named in the log. Mitigation: the tests cover each interface and the
  interceptor hop.
- **[The parent's in-memory key record and its Redis copy drift]** → this is a deliberate difference from an HTTP
  child.
  - The Redis copy receives auto-shared attachments (`CollectResponseAttachmentsFn`) and per-request grants after the
    parent minted it.
  - The child copies `perRequestReceivers` from the in-memory record (`ApiKeyData.initFromContext`), so a later call
    in the same pass misses those writes.
  - The effect is an attachment or a share refused where an HTTP child would succeed. Nothing is granted wrongly.
- **[A plugin calls its own deployment]** → an endless chain of child passes, as with an HTTP application calling
  itself today, without the HTTP connection pool to slow it. There is no depth guard in v1.
- **[No OTel server span for the child]** → traces show the plugin's pass, and under it the client spans of the child's
  HTTP origin calls. The analytics row and the token-stats span carry the child's ids.
- **[A plugin keeps `bodyTree` after passing it to `call`]** → the child's chain changes it under the plugin. The
  Javadoc says the call takes it.

## Migration Plan

None for config. A plugin built against `embedded-origin`'s `handle(OriginRequest)` is rebuilt against the new
signature. None is published yet.

To measure:
1. Build a Core image with `--build-arg SAMPLE_PLUGINS=true`.
2. Declare `plugins.forward-perf` of type `forward` and an application whose `openaiChatCompletions` is embedded with
   it.
3. Run the routing-application perf scenario against that application.
