# Tasks

## 1. User hash under concurrent validation

- [ ] 1.1 Add `IdentityProviderTest#testUserHashUnderConcurrentValidation` for the scenario "Many users validated at
  once": 1,000 JWTs signed with the test key pair, each with its own `email` claim, validated by 8 threads at once
  through `extractClaimsFromJwt`, with the JWK lookup stubbed as in the existing tests. Assert each
  `ExtractedClaims.userHash()` equals the hex SHA-256 of `"salt" + email`. Write it before 1.2. With the shared digest
  it is expected to fail on most runs; it is a race, so record what you saw.
- [ ] 1.2 In `IdentityProvider.extractUserHash`, get a new `MessageDigest.getInstance("SHA-256")` per call and remove
  the `sha256Digest` field and its constructor block (design D8). Verify 1.1 passes and the rest of
  `IdentityProviderTest` is unchanged.
- [ ] 1.3 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.security.IdentityProviderTest"`; both pass.

## 2. Server instances

- [ ] 2.1 Add `HttpServerInstancesApiTest extends ResourceBaseTest` with `additionalSettingsOverrides()` returning
  `server.instances: 4` and `server.port: -1` (design D5). Test `testConcurrentStreamedRequests` covers the scenario
  "Concurrent streamed requests over four instances": a `TestWebServer(4848)` origin answers an SSE stream (as in
  `DeploymentPostApiTest`), 32 clients send the streamed chat completion at once, each on its own connection. Assert
  every answer is `200` with the origin's events in order, ending with `data: [DONE]`, and that the origin got 32
  requests. In the same test, re-stub `validator.extractClaims` to record `Thread.currentThread().getName()` and
  assert that more than one `vert.x-eventloop-thread-*` name was recorded. That is the design-level check that
  connections spread.
- [ ] 2.2 Add test `testStartFailsBelowOneInstance` (in a plain JUnit class, `HttpServerInstancesTest`) for the
  scenario "Value below 1": `AiDial` with the test settings plus `server.instances: 0` throws from `start()`, with a
  message containing `server.instances`, and `stop()` leaves no server listening.
- [ ] 2.3 Implement design D1, D2, D4, D5 and D6 in `AiDial`: read `settings("server").getInteger("instances", 1)`,
  reject values below 1, deploy the server verticle with `DeploymentOptions.setInstances(n)` through `open`, keep the
  shared `actualPort()` in an `int` field for `backgroundJobService.setLocalBaseUrl` and the startup log, and
  undeploy the verticle in `stop()` where the server was closed. Verify 2.1 and 2.2 pass.
- [ ] 2.4 Switch `ResourceBaseTest.setUp` from `dial.getServer().actualPort()` to the new port getter. Verify that
  `ResourceApiTest` and `DeploymentPostApiTest` pass with the default one instance; together they cover the scenario
  "Setting absent".
- [ ] 2.5 Add a `server.instances` row to the settings table in `README.md`: default 1, number of HTTP servers
  sharing `server.port`, one event loop each, env `aidial.server.instances`. Check the row renders with the table's
  column count.
- [ ] 2.6 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.HttpServerInstancesApiTest" --tests "com.epam.aidial.core.server.HttpServerInstancesTest"
  --tests "com.epam.aidial.core.server.ResourceApiTest" --tests "com.epam.aidial.core.server.DeploymentPostApiTest"
  --tests "com.epam.aidial.core.server.AiDialMetricsLifecycleTest"`; all pass.

## 3. Measurement

- [ ] 3.1 Local sanity: run Core (`./gradlew :server:run`) with `aidial.server.instances=4`, send 50 concurrent
  requests on separate connections, and confirm in the log that they ran on several `vert.x-eventloop-thread-*`.
  Record the thread names in review.md.
- [ ] 3.2 Perf env A/B on one image, `aidial.server.instances` unset vs `8`, 2,000 users on 2 Core pods: compare
  client latency minus simulator latency (p50 / p95), Core CPU per user request, `netty_eventexecutor_tasks_pending`
  by `name`, `vertx_http_client_active_connections` and failures. Record the numbers and the estimate in design.md
  "Effect per request" next to each other (deferred: needs the perf env, run by the perf-analysis session).

## Workflow follow-up

- Write review.md in the change folder, archive with `npx -y @fission-ai/openspec@1.14.3 archive http-server-instances -y`,
  then `validate --specs --strict`.
- Branch from `development` before the commit; the change folder is untracked and moves with the checkout.
- Open the PR against `development`, then send the perf-analysis session the PR, the image reference, the env var
  (`aidial.server.instances=8`) and the risks from design.md.
