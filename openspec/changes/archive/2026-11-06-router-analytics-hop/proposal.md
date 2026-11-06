# Proposal

## Why

A decided request writes one analytics row today, for the deployment that served. Its `parent_deployment` names the
router, but no row has the router as its `deployment`. Analytics treats the row with no `parent_deployment` as the
request's root, for example summing `total_price` over those rows. A decided request has no such row, so its cost is
missing from those sums. The router design asks for the router as a hop with the chosen deployment beneath it. That
hop records metadata only, and its cost is the sum of the deployments it calls.

## What Changes

- **Router row.** Every request the router answered with a verdict writes a router row. That includes a request whose
  candidates all failed, and a background Responses job, whose row is written when the job ends. The row has a span
  of its own, and the serving deployment's row becomes its child. It carries:
  - the caller's identity and the request line;
  - `parent_deployment` and `execution_path` as for a direct call to the router;
  - the client's response status;
  - no request or response body and no upstream.
- **Decision object moves.** The `decision` object is written on the router row only, not on the serving row. It is
  new in this PR and unreleased, so no reader depends on its old place. Carrying the decision through an interceptor
  chain's key is no longer needed and is removed.
- **Cost on the router row.** The router row shows the cost of everything below the request, as an application's row
  does: `price` and `usage_per_model`, which include the calls the router made while deciding. Token counts stay
  zero, because the router made no model call, so summing `total_tokens` over all rows counts each model call once.
- **Unchanged:**
  - the client's `statistics.usage_per_model`;
  - the token-stats spans;
  - limits and aggregated-cost records, because a router has none.
- **Docs.** `docs/dynamic-settings/routers.md` describes the two rows.

The decision is not carried in a body field. The router design allows no bodies on the hop, and a secured key or a
writer that is behind drops bodies. Analytics also reads those fields as chat payloads, for `response_id` and
`reasoning_tokens`.

Out of scope:
- A router span in token stats or OpenTelemetry. The router span exists only in the analytics rows.
- Mapping the `decision` object in the analytics service.
- The response `model` field.
- Any row for a decide request that fails before a verdict. None is written, as today.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `router-deployment`: "Decision record" and "Decision record across decisions" put the decision on a router row.
  "Router row cost" is added.
- `app-to-model-accounting`: "One analytics row per Core pass" admits the router row beside a decided pass's row.

## Impact

Motivated by the router design (documentation MR 211, `design/router_resource.md`, "Response, limits and analytics"
and "Limits, pricing and analytics"). It covers these points:
- the router shown as a hop above the chosen deployment;
- metadata only on that hop;
- the router's cost as the roll-up of what it calls.

The design's calls made while deciding "in the same call stack" already hold: their rows name the router as
`parent_deployment`. It leaves the response `model` field to a later decision. It ships in PR #2232 and builds on the
archived `router-decision-mode` and `router-redecide` changes.

Code:
- `RouterDecision`: the router hop (span id, parent span, parent deployment, path) and a written flag.
- `BaseDeploymentPostController`:
  - `decideIfRouter` records the hop;
  - `finalizeRequest` reads the span stats and saves the router row before `endSpan`.
- `AnalyticsLogContext`: the router row builder. The serving row drops `decision` and takes the router span as its
  parent span.
- Removed: `ApiKeyData.decision`, its set in `BaseInterceptorController`, and `decisionRow`'s carried branch.
- `BackgroundJobRecord` and `BackgroundJobService` for the job's router row.

Cost per decided request:
- one more analytics row, without bodies;
- no extra Redis round trip when the serving deployment reported usage, and one GET of the trace's token-stats
  record otherwise;
- one Redis update per re-decide, so calls the router makes while deciding again grow no candidate's aggregated cost.

A request that is not decided is unchanged.
