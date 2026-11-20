# Tasks

Spec scenario to test, all in `RouterDecisionApiTest`:

- "Row of a decided request": `failingFirstCandidateSkipped`.
- "Row of a decided background job": `backgroundResponsesJobDecidedAndRecorded`, and
  `streamedBackgroundJobWritesBothRowsLive` for a create whose stream ends the job live.
- "Serving row is the router row's child": `servingRowIsTheRouterRowsChild`.
- "Router row of a candidate behind an interceptor": `chosenDeploymentBehindAnInterceptorRecordsTheDecision`.
- "Router row after a global interceptor": `routerRowAfterGlobalInterceptor`.
- "Router row of a secured key": `routerRowOfSecuredKey`.
- "Router row when every candidate fails": `limitReached`.
- "Row of a re-decided request": `routerDecidesAgainWithTheHistory`.
- "Router row shows the cost under it": `routerRowShowsTheCostUnderIt`.
- "Call made while deciding counted on the router row": `routerAsksModelWhileDeciding`.
- app-to-model-accounting "One row for a decided request": `servingRowIsTheRouterRowsChild`.

`routerAsksModelWhileDeciding` also keeps checking that the client's `usage_per_model` is unchanged, and that the
priced call made while deciding writes no aggregated-cost record. `GfLogStoreTest`
`testBackgroundJobRouterRowCarriesTheDecision` covers the job's two rows at the log boundary.

## 1. Router hop and row

- [x] 1.1 `RouterDecision`: a `Hop` record (span id, parent span id, parent deployment, path up to the router) and a
  `routerRowWritten` flag. `decideIfRouter` builds the hop with `IdGenerator.random()`, `getParentDeployment` and the
  incoming key's path, as design D2 describes. `RouterDecisionTest` covers the flag.
- [x] 1.2 `AnalyticsLogContext`:
  - `router(context, stats)` builds the router row from the hop, the decision, the context and the stats (D3, D4);
  - `from(context, ...)` drops `decision`, and takes the hop's span as `parentSpanId` when the pass holds the
    decision.
- [x] 1.3 `finalizeRequest`: when the pass holds a decision, is not an accepted background job, and the row is not
  written yet, it reads `getUsageStats(context)`, saves the router row, then calls `endSpan`. A failed read saves the
  row without cost (D1).
- [x] 1.4 Remove `ApiKeyData.decision`, its set in `BaseInterceptorController`, and `decisionRow`'s carried branch
  (D4). Grep that nothing else reads them.
- [x] 1.5 `RouterDecisionApiTest`, tests first:
  - `awaitRows(rows, n)`, `routerRow` and `row(rows, deployment)` helpers in place of `singleRow`;
  - `failingFirstCandidateSkipped` asserts "Row of a decided request", with two rows;
  - `oneRowForDecidedRequest` becomes `servingRowIsTheRouterRowsChild`;
  - "Serving row is the router row's child";
  - `chosenDeploymentBehindAnInterceptorRecordsTheDecision` becomes "Router row of a candidate behind an interceptor";
  - "Router row after a global interceptor", "Router row of a secured key" and "Router row when every candidate
    fails";
  - the re-decide row test becomes "Row of a re-decided request";
  - every other test that reads `decision` from the served row reads it from the router row.
- [x] 1.6 `RouterDecisionApiTest`:
  - "Router row shows the cost under it", with `gpt-4` and `m1` priced in the fixture, asserting that `r1` has no
    aggregated-cost record;
  - "Call made while deciding counted on the router row", extending `routerAsksModelWhileDeciding`.
- [x] 1.7 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest" --tests "com.epam.aidial.core.server.data.RouterDecisionTest"`.

## 2. Background job

- [x] 2.1 `BackgroundJobRecord` carries the hop. `BackgroundJobService.processResult` takes the job span's stats from
  `updateDeploymentStats`'s result, or reads them with `getUsageStats` when there was no usage, before `endSpan`. It
  then saves the router row (D5).
- [x] 2.2 `RouterDecisionApiTest` "Row of a decided background job": both rows when the job ends, and no router row
  at create.
- [x] 2.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest"`.

## 3. Docs and checks

- [x] 3.1 `docs/dynamic-settings/routers.md`, kept brief:
  - the analytics paragraph near "A decided request is one pass": two rows, which row carries `decision`, and the
    router row's cost;
  - the background job sentence;
  - the note in the decide key bullet that calls made while deciding count in the served answer's `usage_per_model`
    stays true. Check it.
- [x] 3.2 Measurement: a `RouterDecisionApiTest` assertion that a decided request writes exactly two rows. The added
  GET is in the cost table of design.md.
- [x] 3.3 Full module suite `./gradlew :server:test`, checkstyle, and
  `npx -y @fission-ai/openspec@1.14.3 validate router-analytics-hop --strict`.

## Workflow follow-up

- `review.md`, archive, `validate --specs --strict`, one commit into PR #2232.
