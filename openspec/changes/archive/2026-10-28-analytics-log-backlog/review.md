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
  - D5: a row with no bodies is not counted as skipped (review round 4, `331cde20`).
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

Round 4, 2026-10-29: the maintainer's automated review, eight findings, answered in the PR threads. Fixed in
`331cde20`:

- A row with no bodies, queued while the writer is behind, was counted as skipped and could start the warning
  episode, although it lost nothing. It is still queued without bodies, so it has the fields of such a row, but it is
  no longer counted (D5; `testRowsWithoutBodiesWhileWriterBehind`).
- The comment on `behind` now says it only gates the two log lines; `admit` leaves bodies out by the limit alone.

Rejected, with the reason:

- `assembledStreamingResponse` is counted in chars, not bytes: the count is what the row holds in memory, exact for
  Latin-1 text and at most 2x low for other text (Risks). The `Buffer` copy at write time is one row at a time.
- A CAS loop to make the limit exact, the count leaking if `submit` throws, the gauge on `Metrics.globalRegistry`,
  default `-1`, and rows without bodies still waiting in an unbounded queue: as in rounds 1-3 above. In the perf runs
  with the limit the backlog peaked at the limit, 1.12 GiB per pod.

## Checks run

- `./gradlew checkstyleMain checkstyleTest`: clean.
- Passing test classes (Windows, local, after merging `development` at `ef98f196`): `GfLogStoreTest` (50),
  `AnalyticsSettingsTest` (9), `DeploymentPostApiTest` (12, writes rows through the real store).
- `testOneWarningWhileWriterHoversAtLimit` fails with the caught-up check of the first draft and passes with the
  half-limit gap.
- `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`: 12 passed.
- CI on `56bc59f5`: build, style checks, docker build and ORT pass; `code_checks` (the full suite) was pending when
  this was written.
- Round 4 (`331cde20`, local): checkstyle clean; `GfLogStoreTest` (50) and `AnalyticsSettingsTest` (9) pass.
  `validate --all --strict`: `analytics-log` passes; `resource-subscription` and `user-limits` fail on placeholder
  Purpose sections that came from `development` (`f92b9449`).

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
  `analytics.maxPendingBodyHeapPercent=10`, was first deferred to a `development` image after the merge, then run on
  the PR image (`73fed50`) on 2026-10-28: three runs with the limit, two without. The results are in two PR comments.
  They are not yet in design.md "Effect per request", and 3.1 is not ticked.
- Follow-up idea, not in this change: the controllers assemble a streamed response on the event loop
  (`BaseChatCompletionController`) only for `admit` to drop it for secured rows and for rows skipped while the writer
  is behind. Asking the store first would save that work when the pod is overloaded.
