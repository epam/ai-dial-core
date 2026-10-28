# Review: analytics-log-backlog

## What shipped

- `GfLogStore` counts the bytes of request, response and assembled bodies held by rows waiting in its single-thread
  executor (`pendingBodyBytes`). `save` queues `admit(logContext)` and subtracts the queued row's bytes in a `finally`
  after `doSave`.
- `admit`: a `secured` key's row is queued as `withoutBodies()` and counts 0 bytes. With a positive limit and the
  waiting bodies at or above it, the row is queued as `withoutBodies()` and `dial_analytics_log_bodies_skipped_total`
  is incremented. Otherwise the row's bytes are added and it is queued as it is.
- `analytics.maxPendingBodyHeapPercent` (env `aidial.analytics.maxPendingBodyHeapPercent`), a `double`, code default
  `-1`. The constructor turns a positive percent of `Runtime.getRuntime().maxMemory()` into a byte limit once; a
  limit of 0 bytes or below means no limit.
- `AnalyticsLogContext` gets `@Builder(toBuilder = true)`, a `bodiesOmitted` field and package-private
  `withoutBodies()`. `append` writes the three body fields only when the key is not `secured` and the bodies were not
  omitted, so a row without bodies has exactly a secured row's fields.
- Gauge `dial_analytics_log_pending_body_bytes`, with or without a limit. With a limit, one warning when rows start
  losing their bodies and one info line once a row is admitted with bodies while the waiting bodies are under half the
  limit.
- Docs: a README settings row for the new setting, and the `server.instances` row from #2184 now points at it instead
  of "keep 1 until that writer is bounded". Two rows and an intro sentence in `docs/tracing.md` "Wait metrics".

## Drift from design.md

- design.md was edited in place, so D1-D6 describe the built code. What changed from the first draft:
  - The limit was first `analytics.maxPendingBodyBytes` in bytes, then put behind a flag, then made the switch on its
    own (commits `c3b0b269`, `cfd8dc4f`), and finally replaced by a percent of the heap (`0b96e827`, D2).
  - The "caught up" line waits for the waiting bodies to fall under half the limit (D5). Without that gap a writer at
    steady overload logged a warning and an info line for every row it wrote (review round 1, `105b40b9`; the
    comparison is `pending * 2 < limit` since round 2, `3d966d4d`).
  - D1 and Risks now bound the overshoot by one row's whole bodies per thread admitting at the same moment, since
    bodies are held whole, not cut at 4 MiB. Risks also notes that a `Buffer` may hold up to twice its length.
- The spec delta: "Bodies in the analytics row" names the writer-behind exception, only a body over 4 MiB is cut and
  ends in `>>`, and "No limit ... by default" covers keys that are not `secured`. The scenarios are as proposed.
- Task 3.1 is not done (see "Open").

## Review rounds

Three rounds of an independent code review on the PR, 2026-10-28 (`105b40b9`, `3d966d4d`, `56bc59f5`). Besides the
fixes above: the body lengths are summed as `long`, helper names and visibility follow `docs/CODE_STYLE.md`, and the
tests check the response and assembled terms of the count and that a secured row is not counted as skipped while the
writer is behind. Rejected, with the reason:

- Rows without bodies still hold headers and claims: bounding the row count or size is a non-goal.
- The count is the length, not the capacity, and an oversized body fills the budget: the limit bounds what the rows
  hold, and they hold those bytes (D1, Risks).
- The count leaks if `executor.submit` throws: the executor is never shut down, so only an `OutOfMemoryError` can.
- The gauge on `Metrics.globalRegistry` stays bound to the first store, and no test runs through `save()`: D6.
- `getDouble` fails on a string: env values arrive as numbers (`JsonObjectHelper.from` turns `10` into a
  `BigInteger`); a quoted value in a settings file fails, as for every typed setting.
- Default `-1`: D2, an operator choice.
- Threads admitting at the same moment pass the check together, and a stale read can log an extra pair: accepted in
  D1; the counter is exact.
- `append` checks both `isSecuredApiKey()` and `isBodiesOmitted()`: D3.
- A secured row costs a builder copy per pass: "Effect per request".

## Checks run

- `./gradlew checkstyleMain checkstyleTest`: clean.
- Passing test classes (Windows, local, after merging `development` at `ef98f196`): `GfLogStoreTest` (50),
  `AnalyticsSettingsTest` (9), `DeploymentPostApiTest` (12, writes rows through the real store).
- `testOneWarningWhileWriterHoversAtLimit` fails with the caught-up check of the first draft and passes with the
  half-limit gap.
- `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`: 12 passed.
- CI on `56bc59f5`: build, style checks, docker build and ORT pass; `code_checks` (the full suite) was pending when
  this was written.

## Spec-to-test map

| Scenario | Test |
|---|---|
| Key not secured | `GfLogStoreTest.testBodiesWrittenForKeyNotSecured` |
| Secured key | `GfLogStoreTest.testSecuredKeyRowHasNoBodies`, `testSecuredRowQueuedWithoutBodies` |
| Setting absent | `GfLogStoreTest.testRowsKeepBodiesWithoutLimit`, `AnalyticsSettingsTest.testMaxPendingBodyHeapPercent` |
| Value 0 | `GfLogStoreTest.testRowsKeepBodiesWithoutLimit` |
| Writer behind | `GfLogStoreTest.testRowsWithoutBodiesWhileWriterBehind` (row B) |
| Nothing waiting | `GfLogStoreTest.testRowsWithoutBodiesWhileWriterBehind` (row A) |
| Writer caught up | `GfLogStoreTest.testRowsWithoutBodiesWhileWriterBehind` (row C) |
| Larger heap | `GfLogStoreTest.testLimitFollowsTheHeap` |

## Open

- Task 3.1, the 2,000-user router-path perf run with bodies logged, `server.instances=4` and
  `analytics.maxPendingBodyHeapPercent=10`, is deferred to a `development` image after the merge. design.md "Effect
  per request" holds the expectation with no measured value beside it yet.
- Follow-up idea, not in this change: the controllers assemble a streamed response on the event loop
  (`BaseChatCompletionController`) only for `admit` to drop it for secured rows and for rows skipped while the writer
  is behind. Asking the store first would save that work when the pod is overloaded.
