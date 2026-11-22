# Design

## Context

See proposal.md. Behaviour on `feat/router-deployment-type` (head 5fc86b23, which has everything `development` has):

- `TokenStatsTracker.startSpan` runs `TraceContext.addSpan`, which does `spans.put(spanId, new TokenStats(...))`.
  Every controller that serves a candidate calls `startSpan` on the way in (`ChatCompletionsController.proceedToDeployment`,
  `ResponsesController.proceedToDeployment`, `MessagesBaseController.proceedToDeployment`,
  `DeploymentPostController` at the same step), so a fallback starts the same span again and replaces its record.
- A background Responses job's row is built by `BackgroundJobService.processResult` from the stored
  `BackgroundJobRecord` through `AnalyticsLogContext.from(BackgroundJobRecord, TerminalResult, usagePerModel)`. The
  record is built by `BackgroundJobRecord.from(ProxyContext, keyEncryptor)` when the create is answered
  (`ResponsesController`, the `saveJob` branch). It has `parentDeployment` from
  `AnalyticsLogContext.getParentDeployment(sourceDeployment, interceptors, executionPath)` and neither the execution
  path nor the decision; the live row (`AnalyticsLogContext.from(ProxyContext)`) has all three, with the router as
  parent when a decision is set and the decision row from `decisionRow(context)`.
- `ProxyContext.setRequestBodyWithTimestamp` stamps `requestBodyTimestamp = now` on every call. The three body
  parsers (`ChatCompletionsController.parseBody`, `ResponsesController.parseBody`, `MessagesBaseController.parseBody`)
  call it, and `reenterWithCandidate` in each calls `parseBody` again on the saved bytes. `GenAiTraceAttributes.phaseMs`
  and `MessagesController` read the stamp.

## Goals / Non-Goals

**Goals:** the three gaps, each at the one place all callers route through, with a test per spec scenario.

**Non-Goals:** any change to the candidate walk, the verdict, the decide request, or the row of a live request.

## Decisions

- **D1. Keep the span's record on a repeated `startSpan`.** `addSpan` keeps an existing `TokenStats` for the span and
  only sets its `deploymentName` to the context's deployment. Rejected: resetting the record and re-crediting from
  the analytics row (no source of the children's reports after the fact); a `restartSpan` entry point (every
  caller would need to know it is re-entering, and the tracker already has the fact in the map). A span id repeats
  only on re-entry, because each pass mints its own.
- **D2. Carry the decision in the job record, not in the key.** `BackgroundJobRecord` gains `executionPath` and
  `decision` (`RouterDecision.Row`), filled in `from(context)` from `context.getExecutionPath()` and
  `AnalyticsLogContext.decisionRow(context)` (made public), and `parentDeployment` uses the same rule as the live
  row (the router when a decision is set). `AnalyticsLogContext.from(record, ...)` copies both. Rejected: reading the
  per-request key in `processResult` for the path (the key may be gone; the record is the poller's source of truth
  for the row, as for every other row field).
- **D3. First-read semantics for the body stamp.** `setRequestBodyWithTimestamp` stamps only when the stamp is still
  `0`. Rejected: saving and restoring the stamp in each `reenterWithCandidate` (three places, and the pre-flight
  re-parse of the body-addressed APIs would stay wrong).

Differences from today's behaviour: the three fixes themselves. No reference implementation.

## Per-request cost

None on the request path: D1 is a map lookup in a step that already does one, D2 adds two fields to a record written
once per background create and read once per poll result, D3 is a comparison. Not measured.

## Risks / Trade-offs

- [D1 keeps stale `usagePerModel` entries of a candidate that failed] → intended: the spend happened and the
  aggregated cost already counted it; the row's breakdown shows what was called.
- [Old job records without the new fields] → `@JsonIgnoreProperties(ignoreUnknown = true)` is already on the record
  and missing fields read as null; the row then has no path and no decision, as today.

## Migration Plan

none
