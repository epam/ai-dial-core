# Design

## Context

See proposal.md (Why) for the measurement. What the code does today (`development`):

- `AiDial.java:start` builds one `GfLogStore` from `AnalyticsSettings.from(settings("analytics"))` and hands it to
  `Proxy` as the `LogStore`.
- `GfLogStore.java:save` returns at once unless the gflog `aidial.log` logger is at INFO and the pass is a `POST`.
  Otherwise it calls `executor.submit(() -> doSave(logContext))`. The executor is
  `Executors.newSingleThreadExecutor` (thread `gflog-store-0`), so its queue has no bound. The comment there says why
  there is one thread: gflog keeps a buffer per writing thread.
- The queued `AnalyticsLogContext` (built by `AnalyticsLogContext.java:from`) holds `requestBody` and `responseBody`
  (`Buffer`) and `assembledStreamingResponse` (`String`) until `doSave` has run.
- `GfLogStore.java:append` writes `assembled_response`, `request.body` and `response.body` only when
  `!logContext.isSecuredApiKey()`. `GfLogStore.java:appendBody` cuts a body at `MAX_BODY_SIZE_BYTES` (4 MiB), appends
  `>>` when cut, copies the body twice (`Buffer.getBytes`, `new String`) and escapes it one character at a time.
- Callers: `BaseChatCompletionController`, `BaseDeploymentPostController`, `DeploymentFeatureController`,
  `BaseRouteController`, `MessagesController`, `MessagesCountTokensController`, `ResponsesController`,
  `McpProxyController` and the background-job path (`AnalyticsLogContext.from(BackgroundJobRecord, ...)`). They run on
  event loops and virtual threads, so `save` must be thread-safe. It is today, because it only submits to the
  executor.
- Metrics register on `Metrics.globalRegistry`, as in `ComplexResourceSweepService` (counters) and
  `MergedConfigStore` (gauges).

## Goals / Non-Goals

**Goals:**
- Let an operator bound the memory the analytics queue holds for bodies, so that a writer slower than the ingest rate
  costs bodies in some rows, not the pod. It is opt-in (D2): by default rows are written as today.
- Keep every row, and keep the row's shape the one the analytics consumer already reads for secured keys.
- Show the backlog whether or not a limit is set.

**Non-Goals:**
- Making the writer faster, or giving it more threads.
- Bounding the number of rows waiting. A row without bodies is about a kilobyte.

## Decisions

### D1. Count pending body bytes, decide per row when it is queued
`GfLogStore` keeps an `AtomicLong pendingBodyBytes`. `save` passes the row through a package-private
`admit(logContext)`:

- `secured` key: queue a copy without bodies. It holds 0 bytes, and the written row is unchanged (D3).
- the limit is positive and `pendingBodyBytes` is at or above it: queue a copy without bodies and count it (D2, D5).
- otherwise: add the row's body bytes to `pendingBodyBytes` and queue the row as it is. With no limit (the default),
  every row that is not secured takes this branch, as today; only the count and the gauge are new.

The task then runs `doSave(queued)` and, in a `finally`, `release(queued)`, which subtracts the queued row's body
bytes. A row's body bytes are `requestBody.length()` + `responseBody.length()` + `assembledStreamingResponse.length()`,
missing parts counting 0. That is what the row keeps alive while it waits, and a body over 4 MiB is held whole even
though only 4 MiB is written.

The check compares the bytes already waiting, before this row is added. So with nothing waiting a row always keeps its
bodies, even one larger than the limit, and the overshoot is at most the rows admitted at the same moment by
different threads. Rejected: comparing "waiting + this row" with the limit. A single large body would then lose its
body on an idle writer.

Rejected: a bounded executor queue that rejects or blocks when full. Rejecting drops the whole row, usage and cost
included. Blocking stalls the event loop that calls `save`.

### D2. One setting, `analytics.maxPendingBodyHeapPercent`, default `-1` (no limit)
`AnalyticsSettings` gets a `maxPendingBodyHeapPercent` component, a `double`. `AnalyticsSettings.java:from` reads
`settings.getDouble("maxPendingBodyHeapPercent", -1.0)`. The default is in code, because the test-resources
`aidial.settings.json` shadows the built-in one. The `GfLogStore` constructor turns a positive percent into a byte
limit once, `(long) (maxHeapBytes * percent / 100)`, where `maxHeapBytes` is `Runtime.getRuntime().maxMemory()`, which
does not change while the process runs. The arithmetic is in `double` because `maxMemory()` returns `Long.MAX_VALUE`
when the JVM has no heap limit, and a `long` product would overflow. `admit` leaves bodies out only when the byte limit
is positive, so `-1`, 0 and any other value below 1 mean no limit, and no value fails startup. A value above 100 gives
a limit above the heap, so it never applies. `-1` for "no limit" follows the Vert.x options Core already exposes, such
as `client.maxWaitQueueSize`. 0 also means no limit rather than "never log a body", because a `secured` key already
does that.

A percent of the heap, not bytes, because the bodies fill the heap. The operator does not need to know `-Xmx` or
recompute the value when the pod size changes, and one value in a shared Helm chart suits every pod. The README row
suggests 10: about 1.1 GiB on the 11.2 GiB perf pods, which is over 800 waiting rows of 1.3 MB of bodies, and about
200 MB on a 2 GiB heap. The warning in D5 prints the resulting limit in bytes.

Rejected: a limit in bytes, `analytics.maxPendingBodyBytes`, which this change had first. It needs the heap size to
choose a value, and a value right for one pod size is wrong for another. Rejected: both settings, which is two knobs
for one limit. Rejected: a separate boolean flag, default false, next to a limit with a heap-relative default. That is
two settings for one switch, and the limit means nothing while the flag is off. Rejected: limiting by default (a tenth
of the heap).
Rows of every deployment under load would start losing their bodies on upgrade, and what analytics rows carry is for
each operator to choose.

### D3. A copy without bodies, shaped like a secured key's row
`AnalyticsLogContext` gets `@Builder(toBuilder = true)`, a `bodiesOmitted` field, and `withoutBodies()`. That method
returns `toBuilder().requestBody(null).responseBody(null).assembledStreamingResponse(null).bodiesOmitted(true)
.build()`, so the queued copy no longer references the body buffers. `GfLogStore.java:append` takes one local,
`boolean withBodies = !logContext.isSecuredApiKey() && !logContext.isBodiesOmitted()`, in place of its three
`!isSecuredApiKey()` checks. A row without bodies then has exactly the fields a secured key's row has today.

Rejected: building the copy with `securedApiKey(true)`. It gives the same row with no new field, but a non-secured row
would then claim to be secured, which misleads anyone reading `AnalyticsLogContext` later. Rejected: nulling the
bodies and leaving the check alone. `append` would then write `"body":""`, a shape the consumer does not see for
secured keys.

### D4. Secured rows drop their bodies when queued
A `secured` key's row never writes its bodies, but today the queued context still holds them. `admit` queues
`withoutBodies()` for it as well, so those buffers can be collected at once. The written row does not change, so this
holds with or without a limit.

### D5. Metrics and one log line per episode
- `dial_analytics_log_pending_body_bytes`: a gauge on `pendingBodyBytes`, with or without a limit, so an operator
  can see the backlog before setting one.
- `dial_analytics_log_bodies_skipped_total`: a counter of rows queued without bodies because the limit was reached.
  Secured rows are not counted. Without a limit it stays at 0.
- With a limit, an `AtomicBoolean behind`. The first skipped row of an episode logs a warning with the pending bytes
  and the limit. The first row admitted with bodies afterwards logs one line saying bodies are logged again. Operators
  without Prometheus thus still see it, and a busy pod does not log once per row. Without a limit there is no line to
  cross, so nothing is logged; the gauge is the signal.

Rejected: a field in the row marking bodies that were left out. It changes the schema the analytics consumer reads.

### D6. Test seam
`admit` and `release` are package-private and `@VisibleForTesting`, like `append` and `appendClaims` today. The tests
drive them with `AnalyticsLogContext.builder()` rows and read the JSON through `GfLogStoreTest`'s existing
`capturingEntry`. The public constructor passes `Runtime.getRuntime().maxMemory()` to a package-private one that takes
the heap size, so a test can use a 10,000-byte heap and a 10 percent setting to get a limit of 1,000 bytes. Rejected: a
constructor that takes the executor, so a test could hold the writer. Tests would then have to read rows from the
gflog logger, which they do not do today.

The metrics register on `Metrics.globalRegistry`, which ignores a second registration of the same gauge (Micrometer
logs "This Gauge has been already registered"). A later `GfLogStore` would then have no gauge on a test's
`SimpleMeterRegistry`, because the first store in the JVM owns it. `GfLogStoreTest` therefore removes any
`dial_analytics_log_pending_body_bytes` gauge before it creates a store. This touches tests only: Core creates one
store per process. The counter is not bound to a store, so it needs no such step.

## Effect per request

- Core passes, body copies and parses of a forwarded body, Redis and blob round trips: unchanged.
- Per POST pass with analytics at INFO: an `AtomicLong` read and add on save, and a subtract after the write. A secured
  or skipped row costs one builder copy of about 30 fields, with no body copy.
- With a limit and the writer behind, skipped rows save the writer its two body copies and the escaping, about 1.3 MB
  of work per user request on the router path. So the writer catches up faster. Without a limit, the writer does the
  same work as today.
- Measured by repeating the 2,000-user router-path perf run with bodies logged, `server.instances=4` and
  `analytics.maxPendingBodyHeapPercent=10` (about 1.2 GB on the 11.2 GiB pods): the setup that ran out of memory,
  plus the limit. Expected: no `OutOfMemoryError`, live heap after GC flat,
  `dial_analytics_log_pending_body_bytes` at or under about the limit, and `dial_analytics_log_bodies_skipped_total`
  above 0 only while the writer is behind.

## Risks / Trade-offs

- [There is no limit by default, so the queue stays unbounded unless an operator sets one] → The gauge shows the
  backlog either way. The README row says to set a limit where bodies are logged under high load, or where
  `server.instances` is above 1.
- [Rows lose bodies under load, with a limit set] → The metric and the warning say so. An operator who needs every
  body raises the limit and the heap, or reduces load per pod. The ceiling is the writer's speed, which is out of
  scope here.
- [Overshoot when many threads admit at once] → Bounded by the rows admitted at the same instant, a few times 1-4 MiB.
  It is small against a limit near a tenth of the heap.
- [An operator sets a percent above 100] → The limit is then above the heap and never applies, as with no limit. The
  README row gives 10 as the starting point.
- [String length counts characters, not bytes] → `assembledStreamingResponse` may be UTF-16 in memory, so it can
  hold up to twice its counted size. It is small next to the request body.

## Migration Plan

none. With the default `-1`, every row is written as today. Operators opt in by setting a positive
`aidial.analytics.maxPendingBodyHeapPercent`, and roll back by removing it. No stored data changes.
