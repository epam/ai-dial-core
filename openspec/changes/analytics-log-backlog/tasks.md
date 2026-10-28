# Tasks

## 1. The settings

- [ ] 1.1 Add `AnalyticsSettingsTest#testMaxPendingBodyBytesDefault`, for the scenario "Setting absent":
  `AnalyticsSettings.from(new JsonObject()).maxPendingBodyBytes()` equals `Runtime.getRuntime().maxMemory() / 10`,
  and `skipBodiesWhenBehind()` is false. Add `AnalyticsSettingsTest#testMaxPendingBodyBytesBelowOne`, for the scenario
  "Value below 1": `from` with `maxPendingBodyBytes: 0` throws `IllegalArgumentException` whose message contains
  `analytics.maxPendingBodyBytes`. Also cover a set value of 1000 and `skipBodiesWhenBehind: true`. Write the tests
  before 1.2.
- [ ] 1.2 Add the `maxPendingBodyBytes` and `skipBodiesWhenBehind` components to `AnalyticsSettings`, read with their
  code defaults, the limit checked in `from` (design D2, D7). Verify 1.1 passes and the rest of `AnalyticsSettingsTest`
  is unchanged.
- [ ] 1.3 Add two rows to the settings table in `README.md`, after `analytics.headersAllowlist`:
  - `analytics.skipBodiesWhenBehind`, default `false`: when true, rows are written without `request.body`,
    `response.body` and `assembled_response` while the bodies waiting reach `analytics.maxPendingBodyBytes`. Recommend
    it where bodies are logged under high load or `server.instances` is above 1.
  - `analytics.maxPendingBodyBytes`, default a tenth of the JVM's maximum heap: the limit for the flag and for the
    backlog warning.

  Give the env vars (`aidial.analytics.skipBodiesWhenBehind`, `aidial.analytics.maxPendingBodyBytes`). Check that the
  rows have the table's four columns.
- [ ] 1.4 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.log.AnalyticsSettingsTest"`; both pass.

## 2. Rows without bodies while the writer is behind

- [ ] 2.1 Add `GfLogStoreTest#testBodiesWrittenForKeyNotSecured` (scenario "Key not secured") and
  `GfLogStoreTest#testSecuredKeyRowHasNoBodies` (scenario "Secured key"): `append` of a row with request and response
  bodies, and the same row with `securedApiKey` true. They pin today's behaviour before 2.4 rewrites the checks.
- [ ] 2.2 Add `GfLogStoreTest#testRowsWithoutBodiesWhileWriterBehind`, for the scenarios "Nothing waiting", "Writer
  behind" and "Writer caught up". Use a store with `skipBodiesWhenBehind` true, `maxPendingBodyBytes` 1,000 and rows
  built with `AnalyticsLogContext.builder()`:
  - `admit` of row A (2,000-byte request body) returns A with its body;
  - `admit` of row B returns a copy whose `append` JSON has no `request.body`, `response.body` or
    `assembled_response`, but has B's `deployment`, `token_usage`, `trace` and `response.status`;
  - after `release(A)`, `admit` of row C returns C with its bodies.

  With a `SimpleMeterRegistry` added to `Metrics.globalRegistry` for the test (and removed after it), assert that
  `dial_analytics_log_bodies_skipped_total` counted 1 and that `dial_analytics_log_pending_body_bytes` read 2,000
  while A was waiting. Add `GfLogStoreTest#testRowsKeepBodiesWhenFlagOff`, for the scenario "Flag absent": the same
  rows A and B with the flag at its default, and `admit` of B returns B with its bodies. The counter stays 0 and the
  gauge reads 4,000 with both waiting. Add `GfLogStoreTest#testSecuredRowQueuedWithoutBodies`: with the flag at its
  default, `admit` of a secured row returns a copy without bodies and adds 0 pending bytes (design D4).
- [ ] 2.3 Add `@Builder(toBuilder = true)`, the `bodiesOmitted` field and `withoutBodies()` to `AnalyticsLogContext`
  (design D3).
- [ ] 2.4 In `GfLogStore`: the `pendingBodyBytes` counter, `admit` and `release` (D1, D4), `save` queuing
  `admit(logContext)` and releasing in a `finally` after `doSave`, `append` using one `withBodies` local in place of
  its three `isSecuredApiKey()` checks (D3), the skip only when `skipBodiesWhenBehind` is true (D7), and the gauge,
  counter and per-episode log lines, worded for each mode (D5). Verify 2.1 and 2.2 pass and the rest of
  `GfLogStoreTest` is unchanged.
- [ ] 2.5 Add `dial_analytics_log_pending_body_bytes` (gauge) and `dial_analytics_log_bodies_skipped_total`
  (counter) to the table in `docs/tracing.md` "Wait metrics", each with one line on what it measures. Check that the
  rows have the table's three columns.
- [ ] 2.6 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.log.GfLogStoreTest" --tests "com.epam.aidial.core.server.log.AnalyticsSettingsTest"
  --tests "com.epam.aidial.core.server.DeploymentPostApiTest"`; all pass. `DeploymentPostApiTest` writes rows through
  the real store.

## 3. Measurement

- [ ] 3.1 Repeat the 2,000-user router-path perf run with bodies logged (keys not `secured`), `server.instances=4` and
  `analytics.skipBodiesWhenBehind=true` on an image with this change. Compare against the run that ran out of memory: failures, the live
  heap after GC over the steady phase, `dial_analytics_log_pending_body_bytes`, `dial_analytics_log_bodies_skipped_total`
  and Core CPU per user request. Record the numbers in design.md "Effect per request" (deferred: needs the perf env,
  run by the perf team).

## Workflow follow-up

- Write review.md in the change folder, archive with `npx -y @fission-ai/openspec@1.14.3 archive analytics-log-backlog -y`,
  then `validate --specs --strict`.
- The measurement needs an image that has both this change and `server.instances` (#2184): build it from a branch
  that merges both, or wait until one of them is in `development`.
