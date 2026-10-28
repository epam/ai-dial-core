# Tasks

## 1. The setting

- [x] 1.1 Rename `AnalyticsSettingsTest#testMaxPendingBodyBytes` to `testMaxPendingBodyHeapPercent`:
  `AnalyticsSettings.from(new JsonObject()).maxPendingBodyHeapPercent()` is `-1`, and a set value of 10 reads back as
  10 and of 2.5 as 2.5. Write the test before 1.2.
- [x] 1.2 Replace the `maxPendingBodyBytes` component of `AnalyticsSettings` with `maxPendingBodyHeapPercent`, a
  `double`, read in `from` with `getDouble` and the code default `-1` (design D2). Verify 1.1 passes and the rest of
  `AnalyticsSettingsTest` is unchanged.
- [x] 1.3 Replace the `analytics.maxPendingBodyBytes` row in the settings table in `README.md` with an
  `analytics.maxPendingBodyHeapPercent` row, after `analytics.headersAllowlist`. It says:
  - the default `-1` (and any value of 0 or below) means no limit;
  - a positive value is the limit as a percent of the JVM's maximum heap; while the bodies of rows waiting to be
    written reach it, new rows are written without `request.body`, `response.body` and `assembled_response`;
  - 10 is a starting point, and setting it is recommended where bodies are logged under high load or
    `server.instances` is above 1;
  - the env var is `aidial.analytics.maxPendingBodyHeapPercent`.

  Check that the row has the table's four columns.
- [x] 1.4 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.log.AnalyticsSettingsTest"`; both pass.

## 2. Rows without bodies while the writer is behind

- [x] 2.1 Add `GfLogStoreTest#testBodiesWrittenForKeyNotSecured` (scenario "Key not secured") and
  `GfLogStoreTest#testSecuredKeyRowHasNoBodies` (scenario "Secured key"): `append` of a row with request and response
  bodies, and the same row with `securedApiKey` true. They pin today's behaviour before 2.4 rewrites the checks.
- [x] 2.2 Change `GfLogStoreTest#testRowsWithoutBodiesWhileWriterBehind`, for the scenarios "Nothing waiting", "Writer
  behind" and "Writer caught up". Use a store with `maxPendingBodyHeapPercent` 10 on a 10,000-byte heap (a limit of
  1,000 bytes) and rows built with `AnalyticsLogContext.builder()`:
  - `admit` of row A (2,000-byte request body) returns A with its body;
  - `admit` of row B returns a copy whose `append` JSON has no `request.body`, `response.body` or
    `assembled_response`, but has B's `deployment`, `token_usage`, `trace` and `response.status`;
  - after `release(A)`, `admit` of row C returns C with its bodies.

  With a `SimpleMeterRegistry` added to `Metrics.globalRegistry` for the test (and removed after it), assert that
  `dial_analytics_log_bodies_skipped_total` counted 1 and that `dial_analytics_log_pending_body_bytes` read 2,000
  while A was waiting.

  Add `GfLogStoreTest#testLimitFollowsTheHeap`, for the scenario "Larger heap": with `maxPendingBodyHeapPercent` 10 and
  a 40,000-byte heap, `admit` of row A (2,000 bytes) and then row B returns B with its bodies; with 2.5 on the same
  heap (a limit of 1,000 bytes), `admit` of B returns a copy without bodies.

  Add `GfLogStoreTest#testOneWarningWhileWriterHoversAtLimit` (D5): with the waiting bodies going over the limit,
  under it but over half of it, over it again and then to 0, Core logs one warning and then one line, not a pair per
  crossing.
- [x] 2.3 Change `GfLogStoreTest#testRowsKeepBodiesWithoutLimit`, for the scenarios "Setting absent" and "Value 0": with
  `maxPendingBodyHeapPercent` `-1`, and again with 0, `admit` of rows A and B (2,000-byte request bodies each) returns
  both with their bodies. The counter stays 0 and the gauge reads 4,000 with both waiting. Keep
  `GfLogStoreTest#testSecuredRowQueuedWithoutBodies`: with no limit, `admit` of a secured row returns a copy without
  bodies and adds 0 pending bytes (design D4).
- [x] 2.4 Add `@Builder(toBuilder = true)`, the `bodiesOmitted` field and `withoutBodies()` to `AnalyticsLogContext`
  (design D3).
- [x] 2.5 In `GfLogStore`: the `pendingBodyBytes` counter, `admit` and `release` (D1, D4), `save` queuing
  `admit(logContext)` and releasing in a `finally` after `doSave`, `append` using one `withBodies` local in place of
  its three `isSecuredApiKey()` checks (D3), the byte limit resolved once in the constructor from
  `maxPendingBodyHeapPercent` and the heap size, the public constructor passing `Runtime.getRuntime().maxMemory()` to
  a package-private one that takes the heap size, the skip only when the byte limit is positive (D2, D6), and the
  gauge, counter and per-episode log lines (D5). Verify 2.1-2.3 pass and the rest of `GfLogStoreTest` is unchanged.
- [x] 2.6 Check that the rows for `dial_analytics_log_pending_body_bytes` (gauge) and
  `dial_analytics_log_bodies_skipped_total` (counter) in the table in `docs/tracing.md` "Wait metrics" name
  `analytics.maxPendingBodyHeapPercent`, each with one line on what it measures. Check that the rows have the table's
  three columns.
- [x] 2.7 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.log.GfLogStoreTest" --tests "com.epam.aidial.core.server.log.AnalyticsSettingsTest"
  --tests "com.epam.aidial.core.server.DeploymentPostApiTest"`; all pass. `DeploymentPostApiTest` writes rows through
  the real store.

## 3. Measurement

- [ ] 3.1 Repeat the 2,000-user router-path perf run with bodies logged (keys not `secured`), `server.instances=4` and
  `analytics.maxPendingBodyHeapPercent=10` (about 1.2 GB on the 11.2 GiB heap) on an image with this change.
  Compare against the run that ran out of memory: failures, the live heap after GC over the steady phase,
  `dial_analytics_log_pending_body_bytes`, `dial_analytics_log_bodies_skipped_total` and Core CPU per user request.
  Record the numbers in design.md "Effect per request" (deferred: needs the perf env, run by the perf team).

## Workflow follow-up

- Write review.md in the change folder, archive with `npx -y @fission-ai/openspec@1.14.3 archive analytics-log-backlog -y`,
  then `validate --specs --strict`.
- The measurement needs an image that has both this change and `server.instances` (#2184): build it from a branch
  that merges both, or wait until one of them is in `development`.
