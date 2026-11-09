# Review: router-decision-gaps

Implemented on `fix/router-decision-gaps`, branched from `feat/router-deployment-type` at 5fc86b23 (PR #2232).

## What changed

- `TokenStatsTracker.TraceContext.addSpan`: a span started again keeps its record and takes the new deployment name
  (D1). Test: `TokenStatsTrackerTest.testFallbackKeepsTheFailedCandidatesRollUp`.
- `BackgroundJobRecord`: `executionPath` and `decision`, filled from the context with the router-aware parent;
  `AnalyticsLogContext.decisionRow` is public and `from(record, ...)` copies both (D2). Tests:
  `GfLogStoreTest.testBackgroundJobRowCarriesTheDecision` (and `testBackgroundJobRowWithoutDecision` for an old
  record), `RouterDecisionApiTest.backgroundResponsesJobDecidedAndRecorded`, which runs the whole chain: the decided
  create, the job record, the poll through Core at a 300 ms `initialPollIntervalMs` set by
  `additionalSettingsOverrides`, and the row the job service writes, captured through its own `logStore` field.
- `ProxyContext.setRequestBodyWithTimestamp`: the stamp is taken on the first call only (D3). Test:
  `ProxyContextTest.testRequestBodyTimestampIsTheFirstRead`.
- `docs/dynamic-settings/routers.md`: the decision record paragraph covers background jobs.

## Verification

`./gradlew checkstyleMain checkstyleTest` clean. `:server:test` for `TokenStatsTrackerTest` (14),
`GfLogStoreTest` (53), `ProxyContextTest` (8), `RouterDecisionApiTest` (36), `ResponsesApiTest` (8): all pass.
`validate router-decision-gaps --strict` valid; after the archive `validate --specs --strict` reports only the two
pre-existing placeholder-purpose warnings (`resource-subscription`, `user-limits`).

## Notes

- The background job's live pass writes no row of its own (`ResponsesController.handleNonStreamingResponse`, the
  `saveJob` branch ends the response without `completeProxyResponse`), so the job's row is the one row, as the spec
  now says.
- Capturing rows in the integration test replaces the log store on both `Proxy` and `BackgroundJobService`; the
  service holds its own reference from construction.
- Not done, by design: a stale `usagePerModel` entry of a failed candidate stays on the span (the spend happened).
