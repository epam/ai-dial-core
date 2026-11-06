# Design

## Context

See proposal.md, Why. Today, on this branch:
- A decided request is one pass with one OpenTelemetry server span. Its token-stats span starts in
  `BaseDeploymentPostController.java:decideIfRouter`, before the decide request. The calls the router makes while
  deciding roll up into that span, because the decide key from `mintDecideKey` takes its `spanId` from the context.
- The candidate's pass re-enters the span: `TokenStatsTracker.java:TraceContext.addSpan` keeps the roll-up. After the
  answer, the span's `aggCost` is the cost of everything below the request, and its `usagePerModel` lists every call
  below it except the deployment's own report: `TraceContext.updateStats`.
- `AnalyticsLogContext.java:from` writes the one row. It sets `parent_deployment` to the router and takes `decision`
  from `decisionRow`. That is the context's decision, or the one an interceptor chain carried in
  `ApiKeyData.decision`, set by `BaseInterceptorController.java:handle`.
- Interceptor hand-off passes write no row. When the candidate has interceptors, the router's pass writes nothing,
  and the row comes from the pass the last interceptor calls back.
- `BaseDeploymentPostController.java:finalizeRequest` is reached on every terminal path of the four LLM surfaces, as
  its comment states. That includes the interceptor hand-off, where `BaseInterceptorController` extends the base
  class, and failures. It runs `endSpan`.

## Goals / Non-Goals

Goals:
- the router row of Decision record;
- the router row's cost of Router row cost;
- the router row written exactly once per decided request.

Non-goals:
- A second Core pass for the router, as the alias design uses. Here the decision already runs in one pass with the
  candidate's limits, key and span, and a second pass would redo all of that.
- A router span in token stats or in OpenTelemetry.
- An aggregated-cost record for the router.

## Decisions

### 1. The router row is written by the pass that asked the router, in `finalizeRequest`

When `context.getDecision()` is set and the pass is not an accepted background job, `finalizeRequest` does three things
before `endSpan`:
1. reads the pass's span stats with `TokenStatsTracker.getUsageStats(context)`;
2. saves the router row;
3. ends the span.

A flag on `RouterDecision` keeps the row to one per request.

This one place covers every case:
- a candidate that answered;
- every candidate failed;
- the client gone;
- a Model candidate that answered non-200, where `collectTokenUsage` never tracks stats;
- the interceptor hand-off. Its pass ends after the chain's callback has finished and reported.

Rejected alternative: build the router row next to the serving row and carry the router hop in `ApiKeyData` to the
callback pass. It misses the cases where no serving row is written. An interceptor that calls the candidate twice
would also write the router row twice, counting its cost twice.

### 2. The router row's span is synthetic, and only the analytics rows see it

`decide` generates a span id when the router's verdict arrives, where `RouterDecision` is created. It uses `IdGenerator.random()`, as `BackgroundOperationTracer.java` does, and records
the router hop on `RouterDecision`:
- that span id;
- the context's parent span;
- `parent_deployment` from `AnalyticsLogContext.getParentDeployment`, with the router pass's source deployment,
  interceptors and path;
- the path up to the router: the incoming key's `executionPath` plus the router.

`context.getExecutionPath()` cannot give the path at decide time, because it reads `proxyApiKeyData`, which does not
exist yet (`ProxyContext.java:getExecutionPath`).

The serving row written by the same pass takes the router span as its `parentSpanId`. A serving row written by an
interceptor callback keeps its interceptor's span as parent, as any interceptor chain has today.

Rejected alternative: a router span in `TokenStatsTracker`, with the context span below it and the decide key on it.
- The calls made while deciding would leave the serving span. That breaks the "Decide request key" requirement: their
  usage counts in the served answer's `statistics.usage_per_model`.
- Re-entry would need to keep the router span's roll-up.
- The span adds a node and aggregated-cost writes for nothing the row needs. The context span's `aggCost` already
  totals everything below the request.

### 3. The router row's cost comes from the context span

The router row takes:
- `token_usage`: a fresh `TokenUsage` whose `aggCost` is the context span's `aggCost`;
- `usage_per_model`: the span's `usagePerModel`, plus one entry for the serving deployment's own usage when it
  reported any.

Token counts stay zero and there is no `deployment_price`. When the read fails or finds no record, the row has no
`token_usage`.

Deliberate difference: the router gets no `aggregated-costs` record. That record is a limit record in the initiator's
bucket, and "Router never limit-checked" says Core neither checks nor charges a router.

### 4. The decision moves to the router row; the interceptor carry goes

`AnalyticsLogContext.from(context, ...)` no longer sets `decision`. With it, three things that exist only to put the
decision on the callback pass's row are deleted:
- `decisionRow`'s carried branch;
- `ApiKeyData.decision`;
- its set in `BaseInterceptorController`.

The callback row's `parent_deployment` already comes from `getParentDeployment`: the path `[r1, i2, m-int]` with
interceptors `[i2]` gives `r1`. A new `AnalyticsLogContext.router(context, stats)` builds the router row, and
`LogStore.save` takes it like any row. Tests that capture rows therefore see two rows.

The router row has no bodies, and its request line is the client's. It takes from the context:
- identity, headers and time;
- duration: `calculateOperationDurationMs`;
- the client's response status.

### 5. A background job's router row is written by the poller

For an accepted job, `BackgroundJobRecord` carries the hop as it carries `decision`. `BackgroundJobService.java`
`:processResult` then:
1. reads the job span's stats, from `updateDeploymentStats`'s result when the job reported usage, or with
   `getUsageStats` otherwise;
2. writes the router row;
3. runs `endSpan`.

A job started by a pass that is not the root may find the record deleted. Its router row then has no cost, as its own
row's roll-up does today.

### 6. The decision is not carried in a body field

The router design allows no bodies on the hop, and `GfLogStore.admit` drops bodies for a secured key and while the
writer is behind. The analytics service derives `response_id` and `reasoning_tokens` from `assembled_response` and
`response.body`, so a decision there would read as a broken chat answer.

### 7. Drift found while implementing

- The hop is built in `decide`, not in `decideIfRouter`, because `RouterDecision` only exists once the verdict arrives.
- When no candidate and no decision is left, the walk stays on the last candidate (`retryWithNextCandidate`).
  `decision.deployment` then names that last try, and it is not in `attempts`, which lists tries before the one
  `deployment` names. The "Router row when every candidate fails" scenario states this.
- A background create hands the router row to its poller once `BackgroundJobService.saveJobRecord` has saved the
  record: that takes the row's once-only flag. A streamed create saves the record too, and when its stream ends the
  job live (`ResponsesController.handleStreamingResponse` deletes the record), it gives the flag back and writes the
  row itself.
- The span was named after the router while it decided, so a priced call made while deciding grew
  `r1/aggregated-costs`, and during a re-decide the failed candidate's record. `TraceContext.addSpan` now names no
  span for a `Router`, and `redecide` takes the name off with `TokenStatsTracker.unnameSpan` (one more Redis update,
  on the re-decide path only).
- An Application candidate whose answer breaks after its head (`handleResponseError`, the non-`Model` branch) never
  reaches `finalizeRequest`, and "Client gone from an application" keeps it that way. That branch writes the router
  row itself, without `endSpan` or the key's invalidation.
- When the router pass is a child, its root can end and delete the trace record before `finalizeRequest` reads it.
  `trackDeploymentStats` therefore keeps the `UsageStats` its report returned on the context, and the router row uses
  them; the GET runs only when no report came back (every candidate failed, the interceptor hand-off, a non-200
  Model answer). A fallback clears them.

## Per-request cost

These figures are for a decided request. A request that is not decided is unchanged.

| What | Change |
|---|---|
| Core passes | none |
| Body copies or parses | none |
| Redis round trips | none when the serving deployment reported usage: the router row reuses the stats that report returned. Otherwise one GET of the trace's token-stats record in `finalizeRequest` or in the job's poller. |
| Redis writes | none; one update per re-decide, to take the failed candidate's name off the span |
| Analytics rows | one more, without bodies |

A `RouterDecisionApiTest` case counts the rows. The GET runs through `BlockingCallTracer` as `cost_stats.get`, the
name `TokenStatsTracker.getUsageStats` already uses. No perf env run is needed for this change.

## Risks / Trade-offs

- [A reader that took the serving row's `core_parent_span_id` as the client's span] → After this change it is the
  router span. Rows that are not decided are unchanged, and the router row has the old parent.
- [The router span is not in the OpenTelemetry trace] → The router row's `core_span_id` matches no exported span.
  This is out of scope, as the proposal says.
- [A decide request fails after the router made priced calls] → No router row is written, as Decision failures
  requires, so those calls' cost still has no root row. This is rare, because a decide failure is a router fault, and
  it is unchanged from today.
- [Behind an interceptor, the serving row's parent span is the interceptor's span, which has no row] → This is the
  same as any interceptor chain today. `parent_deployment` still names the router.

## Migration Plan

None. The `decision` object and the router row are unreleased. A rollback returns to one row per decided request.
