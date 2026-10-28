# Design

## Context

See proposal.md (Why) for the measurement. What the code does today (`development`, Vert.x 4.5.35 per
`gradle.properties` `vertx_version`):

- `AiDial.java:start` creates one server, `vertx.createHttpServer(new HttpServerOptions(settings("server")))
  .requestHandler(proxy)`, and calls `listen()` on it from the main thread through `AiDial.java:open`.
- Vert.x `TCPServerBase.listen` adds the event loop of the listening context to the port's connection balancer
  (`ServerChannelLoadBalancer`). A second server on the same host and port joins as another loop, and the balancer
  hands new connections to the loops in turn. One server means one loop. Port 0 is not shared: each server binds its
  own random port. A negative port is one random port shared by all servers that use it.
- The listening context is the main thread's own context: `VertxImpl.getOrCreateContext` keeps one context per
  thread that is not a Vert.x thread. The timers `start()` registers run on that same context, so on the HTTP loop:
  `FileConfigStore` reload, `AccessTokenValidator.evictExpiredUserInfo`, `IdentityProvider.evictExpiredJwks`,
  `UpstreamRouteProvider.evictExpiredBalancers`, `HeartbeatService`, `ApplicationService`, `CodeInterpreterService`,
  `ResponseMappingService`, `BackgroundJobScheduler`.
- Upstream calls use the one `HttpClient` from `AiDial.java:start` (`vertx.createHttpClient(clientOptions)`). Vert.x
  keeps one connection pool per upstream host and port for the whole client (`SharedClientHttpStreamEndpoint`,
  `client.maxPoolSize` 4,096), reuses the most recently used free connection (LIFO, whatever its loop) and opens a
  new connection on the loop of the request that asked for it (`HttpClientBase`, `poolEventLoopSize` 0).
- Blocking work runs on virtual threads and resumes on the caller's context: `AsyncTaskExecutor.java:execute`
  captures `vertx.getOrCreateContext()` and completes the promise there, so a request resumes on its own loop.

## Goals / Non-Goals

**Goals:**
- Spread client connections over N event loops without changing any handler.
- One code path: N = 1 goes through the same startup and shutdown as N = 8.

**Non-Goals:**
- Moving the startup timers off the main thread's context.
- Pinning an upstream connection to the loop of the request that uses it.

## Decisions

### D1. A verticle with N instances
`AiDial.java:start` deploys a small verticle with `DeploymentOptions.setInstances(n)`. Each instance creates its
server from `new HttpServerOptions(settings("server"))` in `start`, sets `proxy` as the request handler and listens.
Vert.x gives each instance its own context on the next event loop. All instances share the one `Proxy`, as they
share every service today.

Rejected: calling `listen()` N times from contexts made with `VertxInternal.createEventLoopContext()`. It does the
same thing through an internal API.

As built: the verticle is an anonymous `AbstractVerticle` inside `AiDial.java:start`. `AiDial.java:open` now returns the
future's result (`AsyncOpener<R, T>`), because its only caller, which used to listen on one server, now needs the
deployment id for `stop()`. The `HttpServerOptions` are built once in `start()` and shared by the instances; the
verticle captures them and `proxy`. A named verticle class was considered in review and rejected: it would need a
callback for the port, for one use.

Why a verticle and not N `listen()` calls from `start()`: Vert.x 4.5.35 gives a thread that is not a Vert.x thread one
sticky event-loop context (`VertxImpl.getOrCreateContext` → `getStickyContext`, else `createContext`, which stores the
new context in the `stickyContext` thread-local), and `TCPServerBase.listen` takes `eventLoop = context.nettyEventLoop()`
from that context for the connection balancer. N `listen()` calls from the main thread therefore register the same
loop N times. `development` never ran Core itself as a verticle (no `AbstractVerticle` in its history before this change).
Only the server runs in one here; every service is still built in `start()` on the main thread, and `open` waits
for the deployment, so no request reaches a server before its instance has started.

### D2. `server.instances` inside the `server` block
The setting sits with the other server settings. `new HttpServerOptions(json)` ignores keys it does not know
(`HttpServerOptionsConverter.fromJson` switches on the known names, with no default branch). The env var
`aidial.server.instances` maps through `AiDial.java:envSettings`, which parses numbers. The code default is 1
(`settings("server").getInteger("instances", 1)`), because the test-resources `aidial.settings.json` shadows the
built-in one.

Rejected: a top-level key such as `serverInstances` (server settings in two places), or a key under `vertx` (not a
`VertxOptions` field, so it would be misleading there).

### D3. Default 1
With 1, every deployment serves as today until an operator sets the value. The perf A/B can then use one image for
the baseline and the test.

Rejected: a default equal to the event-loop count. It changes the threading of every deployment before the perf run
has shown the effect. That is a later change.

### D4. Reject values below 1 at startup
`instances < 1` throws `IllegalArgumentException("server.instances must be positive, but got: " + n)`, the same
pattern as `deploymentStats.ttlMs` in `AiDial.java:start`. Vert.x would refuse too (`DeploymentManager`: "Can't
specify < 1 instances to deploy"), but without naming the setting. No upper bound: more instances than event loops
share loops, because Vert.x assigns verticle contexts in turn, and nothing fails.

As built: the check runs first in `start()`, right after the settings are printed and before Vert.x, Redis or storage
are created, so a bad value fails at once and leaves nothing to close. From review: `server.port` 0 with
`server.instances` above 1 is rejected in the same place, with a message naming `server.port`, because port 0 binds
a port per instance (D5) and the port Core keeps would name one of them. When `server.instances` exceeds
`vertx.eventLoopPoolSize`, Core logs a warning once `VertxOptions` are built and starts; the instances share loops.
A non-integer value fails as every other numeric setting read in `start()` does (`JsonObject.getInteger`,
`ClassCastException`); a settings-wide type check is not this change.

### D5. Keep the port, not the server
`AiDial` keeps the `actualPort()` of the started servers in an `int` field (they share it). The field feeds
`backgroundJobService.setLocalBaseUrl` and the startup log, as `server.actualPort()` does today, and replaces the
`server` field. Its Lombok getter replaces `getServer()`, whose only user is `ResourceBaseTest.java:setUp`.

A test with more than one instance sets `server.port: -1`, the shared random port. With port 0 each instance would
bind its own port and `actualPort()` would name one of them; D4 rejects that combination at startup.

The field is `volatile`: every instance writes it from its own event loop, with the same value since the port is
fixed or shared, and the main thread reads it after `open` returns (the deployment future completes after every
instance's `start`, and `CompletableFuture.get` orders the read after it).

Rejected: mapping port 0 to -1 in `AiDial` when N > 1. Only tests use port 0, and a silent remap hides the setting.

### D6. Stop undeploys the verticle
`AiDial.java:stop` undeploys the verticle where it closed the one server today. Undeploying closes every instance's
server. The order is unchanged: servers first, then the HTTP client and the rest.

From review: `HttpServerInstancesApiTest.testStopReleasesTheSharedPort` stops Core with 4 instances and binds the
shared port again, which passes only if the undeploy closed every instance's server. `stop()` is not repeatable once
its Vert.x is closed, before or after this change: a second call fails at the first close it attempts on the closed
Vert.x (`RejectedExecutionException: event executor terminated`, seen at `HttpClient::close`), so the test hands the
stopped instance back as null and the teardown skips it.

### D7. One shared upstream client
`HttpClient` and `WebSocketClient` stay one per pod, created in `AiDial.java:start` as today. The 4,096 cap stays a
per-pod total, and the number of upstream connections still follows the number of requests in flight.

Rejected: one client per instance. `Proxy`, `AccessTokenValidator`, `ApplicationOperatorService`,
`ResponsesApiClient` and `BackgroundJobService` take the client at construction, so each would need a per-loop
lookup. The cap and the idle connections per upstream would grow N times. The cost of the shared pool is under
"Effect per request".

### D8. A digest per call in `IdentityProvider`
`IdentityProvider.java:extractUserHash` calls `MessageDigest.getInstance("SHA-256")` on each call instead of using
the `sha256Digest` field built in the constructor. The field goes away. `MessageDigest` is not thread-safe, and the
method runs on the request's loop. `IdentityProvider.java:extractClaims` ends in `verifyJwt(decodedJwt).map(this::from)`,
which runs `from` → `toExtractedClaims` → `extractUserHash` at once when `getJwk` (`FutureUtil.shareLookup` over
`cache`) is already complete. The user-info path completes on the request's context. With one loop the calls never
overlap. With N loops they can, and the hash that reaches the analytics row (`ProxyContext` takes
`ExtractedClaims.userHash()`, `GfLogStore` writes it as `user.id`) could be corrupted, or the call could throw.

Rejected: `synchronized`, which puts one lock that all loops share on the login path, and a `ThreadLocal`, which is
more code and gives one digest per virtual thread as well. The hex loop stays as it is.

### Thread-safety review
This is what request code running on N loops shares, as checked. It is a sweep for non-final fields, collections that
are not concurrent, `MessageDigest` / `Cipher` / format fields and static mutable state, over the server, storage,
credentials and config modules. It is not a proof.

- Built for concurrency: `MergedConfigStore` (volatile `config`, `AtomicLong pendingRebuildTimerId`, `rebuildLock`);
  `ApiKeyStore` (volatile `ConcurrentHashMap keys`, `mutationLock`); `UpstreamRouteProvider.java:get`
  (`balancers.compute`) and `TieredBalancer` (synchronized methods); `AccessTokenValidator.userInfoCache` and
  `IdentityProvider.cache` (`ConcurrentHashMap`); `GfLogStore` (writes on its own executor).
- Read-only after construction: `IdentityProvider.rolePaths`, `AccessTokenValidator.providers` (`setProviders` is
  test-only), `GetUserRoleFunctionFactory.roleFnMapping`, and the other plain lists and maps that constructors fill.
  They are built on the main thread before the verticle deploys, and the deployment hands `proxy` to the loops through
  Vert.x's task queues, which publishes them; concurrent reads of an unchanged `ArrayList` or `HashMap` are safe.
- One per request: controllers (`ControllerSelector` builds one per request), request functions,
  `BufferingReadStream`, `SseParser`, `CacheKeyBuilder` (new in `ChatCompletionRequest`, `MessagesApiRequest`,
  `ResponsesApiRequest`). Config objects are changed only in scratch copies (`ConfigManifestSupport`).
- Context: `AsyncTaskExecutor.java:execute` resumes on the caller's context. OpenTelemetry keeps its context in the
  Vert.x context (`VertxContextStorageProvider`, set in `AiDial.java:start`).
- Metrics: servers after the first reuse its metrics object (`TCPServerBase.listen`: `metrics =
  actualServer.metrics`), so the `vertx_http_server_*` meters stay one set. `NettyEventExecutorMetrics` reports per
  loop as today.
- Found: `IdentityProvider.sha256Digest` (D8). Also `CostRateLimit.CURRENCY_FORMAT`, a static `NumberFormat`, but it
  runs on virtual threads today (`RateLimiter.java:loadCostRateLimit` reads blob storage), so it is out of scope and
  reported.

### Deliberate differences from today
1. At N = 1 too, the server runs on the verticle's context instead of the main thread's, so the startup timers no
   longer share the HTTP loop. Request code and those timers can now run at the same time. The state they share is
   in the list above.
2. With N > 1, a request may reuse an upstream connection opened on another loop. Its response is read on that loop,
   and Vert.x hands each chunk to the request's loop.

## Effect per request

- Core passes, body copies and parses, Redis and blob round trips: unchanged.
- Event-loop tasks, N > 1 only: a reused upstream connection on another loop costs two hand-offs per response chunk.
  `Http1xClientConnection` `StreamImpl.receiveChunk` → `context.execute` moves the chunk to the request's loop, and
  `ackBytes` → `eventLoop.execute` moves the flow-control ack back. LIFO reuse ignores the loop, so at N = 8 about 7
  in 8 reused connections cross. Estimate, chunk count not measured: a few hundred chunks per response over 2 passes
  is 2-5 ms CPU per user request, against about 74 ms per user request in the 2026-10-27 run.
- JWT path: one `MessageDigest.getInstance` per validation. Measured on JDK 21 (2 M iterations, a 25-byte input):
  `getInstance` + digest 230-240 ns, a cloned prototype + digest 160-190 ns, a `ThreadLocal` digest 150 ns. The
  difference is under 0.1 µs per validated request. SHA-256 is one of the algorithms every Java platform must provide
  (`MessageDigest` javadoc), so the `NoSuchAlgorithmException` branch, now an `IllegalStateException` at validation
  time instead of a constructor failure, is unreachable on a compliant JVM.
- Expected, not measured: the 86-108 tasks queued on one loop spread over N loops, and the 3.9 s median overhead
  falls toward the 0.2 s of the 45-pod run, where each loop carried 330 sessions. At N = 8 a 1,000-user pod gives each
  loop about 125.
- Measured by the perf env A/B on one image, `aidial.server.instances` unset vs 8: Core CPU per user request
  (cAdvisor), client latency minus simulator latency (p50 / p95), `netty_eventexecutor_tasks_pending` by `name`,
  `vertx_http_client_active_connections`, `dial_async_task_start_delay_seconds`.

### Measured (perf env, 2026-10-27)

Setup: 2 Core pods (7.5 CPU, 16 GiB, heap 11.2 GiB), 2,000 users, the router path (client → Core → router → Core →
adapter → simulator), request bodies 665 KB on average, image of commit `40e73b21` with `aidial.server.instances=4`.
Baseline: the same load on an earlier `development` build with one server, so not the same image. There was no run
with one server on this image.

| | One server, bodies logged | N = 4, bodies logged | N = 4, bodies not logged |
|---|---|---|---|
| Failed requests | 0 | 2,923 of 220,715; one pod `OutOfMemoryError` | 0 of 47,859 |
| User requests per second | 101 | 124-128 | 128.7 |
| Overhead over the simulator, p50 / p95 | +3.89 / +4.12 s | about +0.2 s, mean | +0.171 / +0.152 s |
| Reading the client's body / the router's body | 1,024 / 655 ms | - | 26 / 33 ms |
| Core CPU per user request | 74 ms | about 98 ms | 82.5 ms |
| CPU throttled (CFS periods) | about 5% | 22-49% | about 7% |
| Share of time in GC | at most 3.5% | 7-15% | 3-5% |
| Live heap after GC | flat, about 7 GiB | 7.5 → 10.6 GiB in 4 min | flat, 4.0-6.8 GiB |

- The one-loop cap is gone. With N = 4 the backlog (`netty_eventexecutor_tasks_pending`) sits on loops 2-5 only (max
  over the steady 5 min: 152 / 38 / 124 / 45 on one pod, 390 / 437 / 377 / 385 on the other), and thread-1 and
  loops 6-15 are at 0. Reading bodies in 26-33 ms says the loops do not queue for long.
- "Bodies not logged" means every API key marked `secured`, so the analytics row carries no request or response body.
  The per-request key Core gives the router copies that flag, so the second pass is covered too.
- With bodies logged, N = 4 crashed for a reason outside this change. `GfLogStore` writes analytics rows on one thread
  behind an unbounded queue, and each queued row keeps its request and response bodies. That thread writes about
  100-120 MB/s per pod, about 70 user requests per second on the router path, which logs 2 bodies per user request. One
  server held the pods near 50, under that ceiling. N = 4 brought them to 63-70 while the pods were CPU-throttled,
  the queue grew about 14 MB/s, and the heap filled.
- Not yet split: 82.5 vs 74 ms CPU per user request. It mixes N = 4, bodies not logged (which should lower it) and JIT
  warm-up. The pods were 3-4 min old, and earlier captures put the C2 compiler at 0.4-0.75 cores per pod in the first
  minutes of load, about 6-12 ms per user request at this rate. The 2-5 ms hand-off estimate is not checked yet. A
  profiled repeat with per-thread CPU is to split it.

## Risks / Trade-offs

- [The sweep missed shared state that only one loop kept safe] → The concurrent streaming integration test, the
  review of the list above, and the 2,000-user perf run (0 failures today) before the default changes.
- [Hand-offs cost more CPU than estimated] → The A/B measures CPU per user request. If it grows noticeably, a
  follow-up change gives each loop its own client (D7's rejected option).
- [Port 0 with N > 1 binds N ports] → Only tests use port 0. The N > 1 test uses -1 (D5).
- [N above the event-loop count] → Loops are shared and nothing fails (D4).
- [With bodies logged, N > 1 lifts a pod past what the analytics writer can keep up with] → In the 2026-10-27 run
  this filled the heap (see "Measured"). Keep the default 1. Enable N > 1 under load only with `secured` keys, or once
  `GfLogStore` bounds what it holds; that is a separate change.

## Migration Plan

none. The default is 1. Operators enable the change with `aidial.server.instances` and roll it back by removing it.

## Open Questions

- The default once measured (event-loop count or CPU count). It is a later change and does not change this one.
