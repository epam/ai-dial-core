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

`ControllerSelector.select` (684-696) routes any request by method and path against the `RouteTemplate` patterns. It
caches the choice in the request's context only when the request is Vert.x-internal. The create operations are
`POST_DEPLOYMENT`, `LLM_CHAT_COMPLETIONS_API`, `LLM_RESPONSES_API`, `LLM_RESPONSES_API_UNVERSIONED` and
`LLM_MESSAGES_API` (333-356).

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

These end in `ProxyContext.respond` (191) writing the response off the event loop, and in `finalizeRequest` on the same
thread.

**When the relay fails.** `BaseDeploymentPostController.handleResponseError` (165-185) and
`BaseInterceptorController.handleResponseError` (206-211) run when the pipe to the client fails: the client went away,
or the origin's stream failed. Both `reset()` the client response, so a cut answer does not look complete. Then a
`Model` reads its origin to the end, writes its row and calls `finalizeRequest`; any other deployment resets its origin
and does not call `finalizeRequest`, so the pass's key is not deleted.

**Headers Core sets for an origin.** `sendProxyRequest` (418-466) copies the client's headers without the hop-by-hop
ones (`API-KEY` and `Content-Length` among them), the trace headers and `X-DIAL-APPLICATION-ID`/`-PROPERTIES`, and
replaces `X-DIAL-DEPLOYMENT-FEATURES`. It sets some headers only for some targets:
- `X-UPSTREAM-ENDPOINT`, `X-UPSTREAM-KEY`, `X-UPSTREAM-EXTRA-DATA`, `X-DIAL-CACHE-BREAKPOINT-PATH` and
  `X-DIAL-CACHE-EXTRA-METADATA` for a `Model` with upstreams;
- `X-DIAL-OVERRIDE-NAME` for a target with an `overrideName` (`ProxyUtil.setOverrideNameHeader`);
- `X-DIAL-DEPLOYMENT-ID` for a translator interface.

**Pipes.** In vertx-core 4.5.35, `PipeImpl.to` sets only the source's handlers. It pauses the source and sets the
destination's `drainHandler` when `writeQueueFull()`, and fails only when the ack of a `write(item, handler)` fails or
the source fails. The controllers set no close or exception handler on the response; `BaseChatCompletionController`
(200-205) and `ResponsesController` (425-430) only pipe.

**Contexts and tracing.** `ContextInternal.duplicate()` makes a context on the same event loop with empty locals.
`ContextImpl.emit` runs its handler inline, with that context current, when called on the context's thread, and queues
it otherwise. The Vert.x OTel tracer reads the active OTel context from the local
`VertxContextStorageProvider.ACTIVE_CONTEXT`. Without that local, a client request starts no client span and sends no
`traceparent`. `tracing/DialVertxTracer` (47-52) already copies the local for that reason. `GenAiTraceAttributes.set`
(293-301) writes to `Span.current()`.

**`InboundBuffer`.** `vertx/stream/InputStreamReader` already uses Vert.x's `InboundBuffer`. Its `write` hands an
element to the handler inline when there is demand and nothing is being emitted, and queues it otherwise; `fetch` and
`resume` drain the queue from a task on the buffer's context, and the drain handler fires from there. A new buffer
flows, and a `write` with no handler set drops the element, so `InputStreamReader` creates it paused (52) and marks the
end with an empty buffer. Its methods check the thread and are `synchronized`.

**Where the body is parsed.** Six sites do it the same way, `ProxyUtil.parseObject(body)`, the first four after
`context.setRequestBodyWithTimestamp(body)`:
- `DeploymentPostController.handleRequestBody` (272-293);
- `ChatCompletionsController.parseBody` (86-93);
- `ResponsesController.parseBody` (280-296);
- `MessagesBaseController.parseBody` (117-125);
- `ChatCompletionInterceptorController.parseRequest` and `ResponsesInterceptorController.parseRequest`, called from
  `BaseInterceptorController.handleRequestBody` (91-98).

`BaseInterceptorController.handle` (71-75) reads `context.getRequestBody()` and, when it is null, the request's body,
before its chain. `ChatCompletionsController` and `ResponsesController` parse before `dispatch` hands over to an
interceptor; `DeploymentPostController` hands over before it reads the body.

Each enhancement chain then re-serialises the body before the origin or interceptor request, because
`ApplyDefaultDeploymentSettingsFn` reports a change:
- `BaseChatCompletionController.processChatCompletionsRequestBody`;
- `ResponsesController.handleRequestBody`;
- `MessagesBaseController.prepareUpstreamRoute`;
- `BaseInterceptorController.handleRequestBody`.

On a retry, `BaseChatCompletionController.handleProxyResponse` (160-164) resends `context.getRequestBody()`. After the
chain, the readers of the body are the origin request, the limits (`increaseLimits`, only after a `200`) and the row.

**The analytics row.** `AnalyticsLogContext.from` builds the row; `withoutBodies()` (from `development`'s
analytics-backlog change) copies it without the three body fields.
- `BaseChatCompletionController.completeProxyResponse` (288-291) builds the assembled streamed answer for every SSE
  pass, then saves the row and calls `finalizeRequest` in the same task (296-312).
- Responses builds its own in `ExtractTerminalResponseFn`.
- Messages builds none (`MessagesController`, 242).

**Embedded origins.** These come from `embedded-origin`.
- `OriginClient.embedded` makes an `EmbeddedProxyRequest` per attempt; it holds no `ProxyContext` (36).
- `EmbeddedProxyRequest.send` calls `OriginPlugin.handle(OriginRequest)` on the request's event loop. `reset` and
  `EmbeddedProxyResponse.discard` end the attempt; `discard` calls the answer's `cancel`.
- `OriginPlugin` asks the plugin to complete its future and emit its answer on the context `handle` was called on.
- The pass keeps its minted per-request key record as `ProxyContext.proxyApiKeyData`: executionPath
  `[<deployment>]`, spanId the pass's span, and the trace id.
- `finalizeRequest` deletes that key when the pass ends (`BaseDeploymentPostController`, 127-139).

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
    Future<Void> cancelled();                           // Core stopped waiting for the answer (D10)
}

public interface OriginPluginProvider extends PluginProvider {
    OriginPlugin create(ObjectNode settings, Vertx vertx);   // Core's Vert.x (D11)
}

public record OriginCall(String uri, MultiMap headers, ObjectNode body) {}

public record OriginRequest(HttpMethod method, String uri, MultiMap headers, Buffer body,
                            ObjectNode bodyTree, OriginCaller caller) {}

public record OriginCaller(String userId, List<String> roles, String project) {}  // userId null for an API key
```

- **`OriginCall.uri`** is the path and query of a create operation:
  - a deployments-POST action;
  - `/openai/v1/chat/completions`;
  - `/openai/v1/responses` or its unversioned alias `/openai/responses`, not with `background: true`;
  - `/anthropic/v1/messages`.

  For the three that name the deployment in the body, the body's `model` names the target, as for any client.
- **`OriginCall.headers`** are copied; Core does not keep the plugin's map. Core drops from them the headers of Context
  that `sendProxyRequest` sets only for some targets: `X-UPSTREAM-ENDPOINT`, `X-UPSTREAM-KEY`,
  `X-UPSTREAM-EXTRA-DATA`, `X-DIAL-CACHE-BREAKPOINT-PATH`, `X-DIAL-CACHE-EXTRA-METADATA`, `X-DIAL-OVERRIDE-NAME` and
  `X-DIAL-DEPLOYMENT-ID`. A plugin that forwards its own request's headers would otherwise pass its origin's
  upstream key, or its override name, on to a target Core does not set them for. It also drops
  `X-DIAL-APPLICATION-ID`, `X-DIAL-APPLICATION-PROPERTIES` and `X-DIAL-DEPLOYMENT-FEATURES`: `sendProxyRequest`
  drops or replaces them, but the interceptor hop copies every header on. Core adds `Content-Type`. The call carries
  the plugin pass's per-request key.
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

A new `ChildPass` (server package) implements `OriginHost` for one attempt of an embedded request. `call` does this:

1. Refuses a call it cannot serve. The call's future fails, and the plugin decides what to answer:
   - one whose path matches none of the five create routes of Context;
   - a Responses call with `background: true`.
2. Answers `401` `Unknown api key`, as an HTTP child with a deleted key would get, when the attempt has ended.
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
6. Returns a future that completes with the answer once the child response's head is written (D4). A controller that
   answers `404`, `403` or `429` gives an answer, not a failure.

**Who holds it.** `OriginClient.embedded` passes the pass's `ProxyContext` to `EmbeddedProxyRequest`. `send` makes the
attempt's `ChildPass`, hands it to the plugin as the host, and registers it on the context.

**When it closes.** `finalizeRequest` closes every `ChildPass` registered on the context; `EmbeddedProxyRequest.reset`
and `EmbeddedProxyResponse.discard` close the attempt's own. `reset` covers the client gone from an application, whose
pass never reaches `finalizeRequest`. Closing runs on the parent's Vert.x context, as `finalizeRequest` can run on a
virtual thread.
- It cancels the answers it handed out that are still open.
- It cancels a head that arrives after it closed.
- It answers any later call `401`.

So a plugin that forgets a call cannot leave a child relaying past its own pass. A child serving a `Model` still reads
its origin to the end and writes its row, as after any client gone.

Rejected alternatives:
- **A request facade in `ProxyContext`** in place of `HttpServerRequest`. Every controller reads
  `context.getRequest()`, and it would touch them all for one caller.
- **A dedicated child driver that calls services directly.** It would copy the relay, retry, usage and row logic of
  three controllers.
- **Loopback HTTP with a flag in the key.** It keeps the HTTP request into Core and the parse, and the user chose the
  in-process pass.

### D3. The body tree, shared instead of parsed

**The child's tree.** A child's `ProxyContext` carries the call's tree. `getCallTree()` returns it while no body is set,
and null after that and on every other pass. The six parse sites use it in place of `ProxyUtil.parseObject` when it is
not null:
- the four controller sites then set only the body timestamp, not the body;
- `BaseInterceptorController.handle` reads the request's body only when neither the body nor the call tree is set.

So the tree reaches the chain of the controller that serves the call, through an interceptor hop too:
`ChatCompletionsController.parseBody` and `ResponsesController.parseBody` wrap it before `dispatch` hands over, and the
interceptor's `parseRequest` wraps the same tree. The chain serialises it, as today, and that sets the body. Before
the chain there is no body, as for a request answered `415` today; on the create paths only the row reads it then, and
a child's row drops it. A mocked `ProxyContext` returns null, so the controller tests parse as today.

**The plugin's tree.** The four serialise points of Context set the body through one `BaseDeploymentPostController`
helper. It also keeps the tree on the context when `DeploymentEndpointUtil.resolveMode` gives `embedded` for the
interface. `EmbeddedProxyRequest.send` hands it to the plugin as `bodyTree` and clears it; when it finds none, on a
retried attempt, it parses `body`. Other passes keep no tree on the context, so their heap does not grow.

Rejected alternatives:
- **Serialise the call's tree when the call is made.** That costs one serialisation per call, and every chain path
  throws the result away.
- **A lazily serialised `getRequestBody()`.** `BaseInterceptorController.handle` reads it before its chain, so every
  interceptor hop would serialise the tree and parse it again.

### D4. The in-memory request and response

`ChildServerRequest implements HttpServerRequest` and `ChildServerResponse implements HttpServerResponse` sit in
`server/.../upstream/`.
- **Members.** They implement the members of the table in Context, plus `isEnded`, `scheme`, `host`, `absoluteURI`,
  `params`, `getParam`, `ended`, `closed` and `getStatusMessage`. Every other member throws
  `UnsupportedOperationException` naming the member and the in-process call. The tests (D8) cover each interface and
  the interceptor hop, so a member missed on a tested path fails a test.
- **Threads.** Every mutator (`setStatusCode`, `putHeader`, `write`, `end`, `reset`) runs on the child's context. A
  call from a virtual thread, as the error paths make, moves there first. The state the log layout reads from other
  threads (status, `ended`, `closed`) is `volatile`.
- **Head.** The first `write` or `end` freezes the status and headers into the answer. The call's future completes with
  it through `parentContext.emit`.
- **Body.** The answer's `ReadStream` is an `InboundBuffer` on the parent's context, created paused and resumed when
  the plugin sets a handler, so nothing written before is dropped.
  - `write` and `end` hand their chunk, or an end marker, to the buffer through `parentContext.emit`. When the plugin
    has demand the chunk reaches its handler inline, on the same event loop, with the parent's context current, as
    `OriginPlugin` asks.
  - The plugin's `pause`, `fetch` and `resume` work as on any Vert.x stream.
  - `writeQueueFull` is the buffer's answer to the last write. The buffer's drain fires the response's `drainHandler`
    through `childContext.emit`, so a pipe from the child's `BufferingReadStream` resumes its origin on the child's
    context, as with an HTTP client.
- **Reset.** `reset()` is how the child cuts its answer: `handleResponseError` calls it when the child's origin fails
  mid-stream.
  - Before the head, it fails the call's future, as an HTTP client sees a connection dropped before the headers.
  - After the head, it drops the queued chunks and fails the answer's body through its exception handler.
- **Cancel.** `cancel` on the answer acts as a client connection closing. It runs on the child's context.
  - The response turns `closed()`.
  - It fails the ack of every pending and later `write` and `end`.
  - It fires a pending `drainHandler`, so a paused pipe resumes and fails on its next write.
  - It fails the call's future, when the head has not come yet, and an answer body the plugin has not read to its
    end, so neither waits forever.

  The child's pipe then fails into `handleResponseError`: a `Model` reads its origin to the end and writes its row,
  and any other deployment resets its origin.

`InboundBuffer`'s methods are `synchronized`. Its monitor is taken by one thread only, so it never blocks the event
loop; `InputStreamReader` already uses it there. This is a recorded exception to the CODE_STYLE rule on locks.

Rejected alternatives:
- **`java.lang.reflect.Proxy` instances for the two interfaces.** Both ways fail at run time on a member nobody
  implemented. A dynamic proxy also hides which members exist behind a dispatch on method names, needs
  `InvocationHandler.invokeDefault` for the interfaces' default methods, and cannot be read or stepped through like
  two plain classes.
- **A hand-written chunk queue** in place of `InboundBuffer`. It would repeat demand, pause and drain logic Core
  already reuses from Vert.x.
- **Delivering each chunk from a task of its own.** One task per chunk costs more than the inline hand-over, and
  `emit` already makes the parent's context current.

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
- **Chat completions, Responses and Messages:** the same path as the request, with `model` set in the tree.
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
  - `X-Test-Call: <path>` calls that path with the request's own `bodyTree` and relays the answer. The relay wraps the
    call's answer, so a cancel of the relayed answer is counted in `CANCELLED` and passed on;
  - `X-Test-Call-Model: <name>` sets `model` in the tree first;
  - `X-Test-Call-Cancel` cancels the call after its first event and answers by itself;
  - `X-Test-Await-Cancel` answers nothing until `cancelled()` completes, then counts it and fails.

  The call passes on every header but `X-Test-Call*`, so a `p2` of the same type does not recurse, while `X-Test-Hold`
  reaches it. The plugin records the `OriginCaller` of each request, and keeps its `OriginHost` and Vert.x context.
- **`EmbeddedCallApiTest`** (new) covers the scenarios of the deltas. Targets are a second plugin `p2`, or an HTTP
  origin on `TestWebServer` for the interceptor, HTTP-origin and mid-stream failure scenarios. Rows are read through the
  recording log store of `ResourceBaseTest`.
  - "Plugin cancels a call" holds `p2`'s answer open with `X-Test-Hold`.
  - "Call after the plugin's pass ended" awaits `app`'s row, which Core saves in the same task as `finalizeRequest`,
    then calls through `p1`'s kept host on its kept context and awaits the status. That order is fixed, so the test
    does not race the pass's end.
- **Unit tests:**
  - `ChildServerResponseTest`: the head on the first write; nothing dropped before the plugin sets a handler; chunks
    reaching the plugin with the parent's context current; the backpressure and the drain on the child's context; a
    write from a virtual thread; `reset` before and after the head; cancel failing the pipe;
  - `ChildServerRequestTest`: path, query and the headers dropped;
  - `ChildPassTest`: refused routes, a background call, a call after close, a late head;
  - `ProxyContextTest`: `getCallTree` until the body is set;
  - `plugin-samples`: `validate` and the path mapping.
- **Controller tests.** The tests that mock `ProxyContext` get null from `getCallTree()` and need no change.

### D9. Effect per request

These are estimates. The measured base is the perf env run of 2026-10-20: the path through a routing application at
a 383 KB body, 53.5 ms of Core CPU per user request. That is two passes, about 25 ms each, and about 3 ms for the
router's `/v1/user/info` call.

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
- one `emit` per chunk handed to the plugin, and one task for a write from a virtual thread;
- the tree kept on the context of an embedded pass until its plugin is called.

Expected:
- the child pass at about 10-14 ms;
- a user request through a routing plugin at about 35-39 ms (the plugin's pass and the child pass), against 53.5 ms
  with the HTTP router. About 3 ms of that difference is the `/v1/user/info` call the HTTP router makes and the
  `forward` plugin does not; the A/B reports it apart.

It does not reach 30 ms: the doubled event processing and the parent pass's own costs remain.

Blob round trips: none added.

### D10. Telling the plugin its client went away before it answered

Today nothing watches the client while Core waits for an origin's head: the relay notices a client gone on its first
write, and a reset happens only then. A plugin whose answer takes time (a non-streamed generation, several upstream
calls, a credential fetch) keeps working for a client that has left.

- **The signal.** `OriginHost.cancelled()` is a future that completes on the plugin's context when Core stops waiting
  for the attempt's answer. `ChildPass.cancel` completes it and closes the attempt; `close` alone leaves it pending.
  - `EmbeddedProxyRequest.reset` (the client gone during the relay) and `EmbeddedProxyResponse.discard` (a retry past
    the answer) cancel.
  - `finalizeRequest` closes: a request that ends normally never completes the future.
- **The watch.** While the plugin's future is pending, `EmbeddedProxyRequest.send` holds the close handler of the
  pass's client response. A close then cancels the attempt. For a pass that itself serves a call, the client response
  is the in-memory one, whose close handler fires when its caller cancels, so the signal passes down a chain of calls.
- **After the signal.** A plugin answer that fails ends the attempt quietly: the send's future never completes, so
  Core neither marks the upstream failed nor tries the next one, and writes no row. That is how a client gone from an
  application ends today. An answer the plugin still gives is relayed and fails on its first write, as an HTTP
  origin's does after its client went away: a `Model` reads it to the end and charges it.

Rejected alternatives:
- **Reset or fail the send on a client close.** A failed send marks the upstream failed and calls the next one, for a
  client that has gone.
- **Stop retries for every origin once the client is gone**, in the shared controllers. It would save HTTP work too,
  but it changes the HTTP path, which this change leaves as it is.
- **A future in `OriginRequest`.** A request record should not carry a lifecycle signal.

### D11. Core's `Vertx` at create

`OriginPluginProvider.create(ObjectNode settings, Vertx vertx)` hands a plugin Core's Vert.x instance, so a plugin
builds its HTTP clients and timers once, when it is created, rather than from `Vertx.currentContext()` on its first
request. `EmbeddedOrigins` gets it from `AiDial`. The provider contract is not published yet, so the signature changes
now rather than through a second method later.

Rejected alternative: **`OriginHost.vertx()`.** It breaks nothing, but every plugin would still build its clients
lazily on a request, with the races that brings.

## Open Questions

Raised by a Java port of the model provider, which would run as an origin plugin serving the openai, vertexai and
bedrock adapters. Two of the three it raised are decided here: telling a plugin its request was cancelled before it
answers (D10) and Core's `Vertx` at create (D11). The third is left to a follow-up change:

- **Closing a replaced instance.** `EmbeddedOrigins` replaces a plugin instance when its declaration changes, and
  drops the old one. A plugin that holds HTTP client pools, token caches or timers leaks them on every settings
  change, and they are never closed at shutdown either. Proposal: `OriginPlugin` gains `default void close() {}`,
  which Core calls once the replaced instance has no attempt in flight (counted from `send` to the attempt's
  `ChildPass` closing) and at shutdown. A default method breaks no plugin, so it can come later.

`OriginRequest.uri` (the path and query after the interface's base URL) is enough for the port: its plugin maps it
onto its own routes from its settings.

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
- **[A call after the client of an application went away]** → `401`, while an HTTP child would still succeed until
  the key expires, as Core does not delete the key on that path. This is a deliberate difference: nobody reads the
  answer.
- **[Child passes are not HTTP requests]** → they do not appear in the Vert.x HTTP server metrics. Their rows and
  token-stats spans do.
- **[A plugin calls its own deployment]** → an endless chain of child passes, as with an HTTP application calling
  itself today, without the HTTP connection pool to slow it. There is no depth guard in v1.
- **[No OTel server span for the child]** → traces show the plugin's pass, and under it the client spans of the child's
  HTTP origin calls. The analytics row and the token-stats span carry the child's ids.
- **[A plugin stops on `cancelled()` and fails]** → no row and no charge for the request, while an HTTP model origin
  whose client went away keeps generating and is charged in full. This is a deliberate difference: the plugin's
  upstream work is cut short, and a plugin that answers what it has is charged for what it reports.
- **[A plugin keeps `bodyTree` after passing it to `call`]** → the child's chain changes it under the plugin. The
  Javadoc says the call takes it.

## Migration Plan

None for config. A plugin built against `embedded-origin`'s `handle(OriginRequest)` and `create(ObjectNode)` is
rebuilt against the new signatures. None is published yet.

To measure:
1. Build a Core image with `--build-arg SAMPLE_PLUGINS=true`.
2. Declare `plugins.forward-perf` of type `forward` and an application whose `openaiChatCompletions` is embedded with
   it.
3. Run the routing-application perf scenario against that application.

## Apply notes

Where the implementation differs from the decisions above, or settles what they left open:

- **D4, threads.** The status, the headers and the response's state (`ended`, `closed`, the frozen head) change in
  the thread that calls, as on Vert.x's own response, not after a hop to the child's context. A pass writing from a
  virtual thread then reads back the status it set (`collectTokenUsage` reads it). Only the hand-over to the plugin
  moves: it runs on the plugin's context through `emit`, and the write acks and the drain handler on the child's.
- **D4, head and reset.** The call's future completes, or fails on a reset before the head, from a task of its own on
  the plugin's context (`runOnContext`), so the plugin's code never runs inside the pass's first write. A reset after
  the head reaches the body's exception handler the same way, and a body cut before the plugin set that handler
  reports it once the handler is set.
- **D3.** The four controller sites call `ProxyUtil.parseRequestBody(context, body)`, and the interceptor controllers
  a `parseObject` helper of `BaseInterceptorController`; both take the call tree when there is one. The serialise
  helper `setProcessedRequestBody` also sets the body when the pass has none yet, whatever the chain reported, so an
  in-process pass never reaches its origin without one. `RequestObject` gains `getTree()`; on `ResponsesApiRequest`
  it is `@JsonIgnore`, as the OpenAPI schema is built from that class.
- **D2.** A call with no `uri` or no body fails like a refused path. `close` cancels every response of the attempt,
  one before its head included, so the late-head cancel is a guard for a head already on its way. After close a call
  is answered `401` with the plain-text body `Unknown api key`, as `Proxy.handleError` writes it for an HTTP child.
  `EmbeddedProxyResponse` closes the attempt's `ChildPass` through a `Runnable` it is given.
- **D4, members.** `ChildServerRequest.host()` is null and `absoluteURI()` is the path and query; `authority()`
  throws. `writeHead()` freezes the head.
- **D10 and D11** were added during apply, from the open questions of a Java model-provider port; task group 8
  implements them.
- **After the implementation review:**
  - D10: the watch also covers a client gone before `send`, as a close handler set on a closed connection never
    fires; the attempt is then cancelled at once.
  - D2: `ResponseItemController` has its own `finalizeRequest`, which closes its child passes too.
  - D2: a child pass's log lines write no attributes onto the plugin's span: the log layout skips its span enrichment
    for one, as `GenAiTraceAttributes` does.
- **Not run here.** Task 6.2 was checked by reading the `Dockerfile`; no image was built. Task 7.1, the perf A/B, ran
  after the archive: review.md "Perf A/B". `ResourceApiTest.testWorkflow` fails locally on Windows only, as before this change.

