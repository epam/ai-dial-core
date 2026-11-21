# Review: http-server-instances

## What shipped

- `AiDial.start` reads `server.instances` (env `aidial.server.instances`, code default 1) and deploys a small
  anonymous `AbstractVerticle` with `DeploymentOptions.setInstances(n)`. Each instance creates its HTTP server from
  the shared `HttpServerOptions`, sets the one `Proxy` as the request handler and listens on `server.port`. Vert.x puts
  each instance on its own event loop, so client connections spread over N loops.
- `AiDial.open` now returns the future's result (`AsyncOpener<R, T>`), because `stop()` needs the deployment id.
  `stop()` undeploys the verticle where it closed the one server, so every instance's server closes. The order is
  unchanged: servers first, then the HTTP client and the rest.
- `AiDial` keeps the shared `actualPort()` in a `volatile int` for `backgroundJobService.setLocalBaseUrl` and the
  startup log. `getServer()` is gone; `ResourceBaseTest.setUp` uses the port getter.
- Startup refuses `server.instances` below 1 (message names `server.instances`) and `server.port` 0 with
  `server.instances` above 1 (message names `server.port`). It warns once when `server.instances` exceeds
  `vertx.eventLoopPoolSize`.
- `IdentityProvider.extractUserHash` takes a new `MessageDigest.getInstance("SHA-256")` per call. The shared
  `sha256Digest` field is removed. This is the one thread-safety fix the sweep in design.md found for request code.
- README: a `server.instances` row in the settings table, with the analytics-writer risk for N > 1 where bodies are
  logged.

## Drift from design.md

- design.md was edited in place as the work went on, so its D1, D4, D5 and D6 describe the built code. What changed
  from the first draft:
  - The `HttpServerOptions` are built once in `start()` and captured by the verticle.
  - The port field is `volatile`.
  - The port 0 rejection, the loop-count warning and `testStopReleasesTheSharedPort` came from the PR review round of
    2026-10-28 (commit `423eb27d`).
  - `HttpServerOptions.setReusePort(true)` was considered and rejected (commit `714b8bac`, D1).
- Task 3.1 (a local Core run with 50 connections) was not run as a separate local Core.
  `HttpServerInstancesApiTest` is the same check at 32 connections: 8 requests on each of `vert.x-eventloop-thread-2`
  to `-5`.
- Task 3.2 is not done (see "Open").
- The spec delta gained the "Port 0 with several instances" scenario and the port 0 clause in the review round. The
  other scenarios are as proposed.

## Measurement

Perf env, 2026-10-27, 2 Core pods, 2,000 users, router path, image of commit `40e73b21`, `server.instances=4`. The
baseline is an earlier `development` build with one server, not the same image. Numbers are in design.md "Measured";
the ones that matter for the decision:

| | One server, bodies logged | N = 4, bodies logged | N = 4, bodies not logged |
|---|---|---|---|
| Failed requests | 0 | 2,923 of 220,715; one pod `OutOfMemoryError` | 0 of 47,859 |
| Overhead over the simulator, p50 | +3.89 s | about +0.2 s, mean | +0.171 s |
| Core CPU per user request | 74 ms | about 98 ms | 82.5 ms |

- The one-loop backlog is gone: with N = 4, `netty_eventexecutor_tasks_pending` sits on loops 2-5 only.
- With bodies logged, N = 4 crashed in `GfLogStore` (one writer thread, unbounded queue of rows that keep their
  bodies). That is outside this change; design.md "Risks" says to keep the default 1 and to use N > 1 under load only
  with `secured` keys until the writer is bounded.
- The 82.5 vs 74 ms CPU difference is not split between the hand-offs, JIT warm-up and bodies not being logged.

## Checks run

- `./gradlew checkstyleMain checkstyleTest`: clean (tasks 1.3, 2.6, 4.6).
- Passing test classes (Windows, local): `IdentityProviderTest`, `HttpServerInstancesApiTest` (2 tests),
  `HttpServerInstancesTest` (2 tests), `DeploymentPostApiTest` (12), `AiDialMetricsLifecycleTest`.
- `ResourceApiTest.testWorkflow` fails on Windows only (the file-system blob store lists `folder\` for `folder/`). It
  passed in CI run 40683644747 of 2026-10-28 (`code_checks`, `style_checks`).
- `IdentityProviderTest.testUserHashUnderConcurrentValidation` written first failed on the shared digest with
  `ArrayIndexOutOfBoundsException: arraycopy: length -12 is negative`, so the validation itself failed, not only the
  hash. It passes after the per-call digest.
- Stop with several instances: `stop()` is not repeatable once its Vert.x is closed, before or after this change (a
  second call fails with `RejectedExecutionException: event executor terminated` at `HttpClient::close`). The test
  hands the stopped instance back as null.

## Spec-to-test map

| Scenario | Test |
|---|---|
| Setting absent | `ResourceApiTest`, `DeploymentPostApiTest` (default one instance) |
| Value below 1 | `HttpServerInstancesTest.testStartFailsBelowOneInstance` |
| Port 0 with several instances | `HttpServerInstancesTest.testStartFailsWithPortZeroAndSeveralInstances` |
| Stop with four instances | `HttpServerInstancesApiTest.testStopReleasesTheSharedPort` |
| Concurrent streamed requests over four instances | `HttpServerInstancesApiTest.testConcurrentStreamedRequests` |
| Many users validated at once | `IdentityProviderTest.testUserHashUnderConcurrentValidation` |

## Open

- Task 3.2, the perf env A/B on one image (`aidial.server.instances` unset vs 8, 2,000 users on 2 Core pods), is
  deferred to the perf team. design.md "Effect per request" holds the estimate (2-5 ms CPU per user request for the
  hand-offs) with no measured value beside it yet.
- The maintainer asked in the PR review to move the `IdentityProvider` digest fix to its own PR. The reply offers to
  split it, to merge first. It is still in this change. If it is split, the requirement "Analytics user hash under
  concurrent requests" moves with it to that change.
- The maintainer noted earlier trouble with running Core as a verticle. Git has no record of it; only the server runs
  inside the verticle here. The full suite in CI is the check. The PR workflow run for the branch head `20116f61`
  (run 40696865502) was still queued when this was written.
- Before N > 1 is recommended where bodies are logged: a separate change that bounds what `GfLogStore` holds.
- The default value (event-loop count or CPU count) is a later change, after the A/B.
