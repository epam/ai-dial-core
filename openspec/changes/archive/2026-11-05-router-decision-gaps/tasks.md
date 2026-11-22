# Tasks

## 1. Fallback keeps the span's roll-up

- [x] 1.1 `TokenStatsTrackerTest.testFallbackKeepsTheFailedCandidatesRollUp`: start a pass span, a child reports cost
  under it, start the pass span again with another deployment; the pass keeps `aggCost` and the breakdown, and a
  later child credits the new deployment (scenario "Fallback keeps the failed candidate's roll-up").
- [x] 1.2 `TokenStatsTracker.TraceContext.addSpan`: keep an existing record, update its deployment name (D1).
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "com.epam.aidial.core.server.token.TokenStatsTrackerTest"`.

## 2. Decided background Responses job row

- [x] 2.1 `BackgroundJobRecord`: `executionPath`, `decision`; `from(context)` fills them and the router-aware parent (D2).
  `AnalyticsLogContext.decisionRow` public; `from(record, ...)` copies both fields.
- [x] 2.2 `GfLogStoreTest.testBackgroundJobRowCarriesTheDecision` replaces `testBackgroundJobRowHasNoDecision`: a
  record with path, parent and decision produces a row with `execution_path`, `parent_deployment` and `decision`.
- [x] 2.3 `RouterDecisionApiTest.backgroundResponsesJobDecidedAndRecorded`: `POST /openai/v1/responses` with
  `background: true` on `r3`, the origin accepts and answers the poll `completed`; the create is served by `gpt-4`
  and the job's row has the path, the router as parent and the decision (scenario "Row of a decided background job").
  The class overrides `additionalSettingsOverrides` with a short `backgroundJob.initialPollIntervalMs`.
- [x] 2.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "com.epam.aidial.core.server.log.GfLogStoreTest" --tests "com.epam.aidial.core.server.RouterDecisionApiTest"`.

## 3. Request-body timestamp

- [x] 3.1 `ProxyContextTest.testRequestBodyTimestampIsTheFirstRead`: two calls of `setRequestBodyWithTimestamp` keep
  the first stamp.
- [x] 3.2 `ProxyContext.setRequestBodyWithTimestamp`: stamp only when unset (D3).
- [x] 3.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "com.epam.aidial.core.server.ProxyContextTest"`.

## 4. Docs and close

- [x] 4.1 `docs/dynamic-settings/routers.md`: the decision record paragraph no longer excepts background Responses jobs,
  if it does today.
- [x] 4.2 `npx -y @fission-ai/openspec@1.14.3 validate router-decision-gaps --strict`; `review.md`; archive;
  `validate --specs --strict`; one commit with explicit paths.
