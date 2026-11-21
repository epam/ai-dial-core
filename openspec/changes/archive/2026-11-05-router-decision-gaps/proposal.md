# Proposal

## Why

The router decision mode shipped with three gaps its review record (`openspec/changes/archive/2026-11-05-router-decision-mode/review.md`)
carried forward: a fallback to the next candidate drops the usage the failed candidate's children already rolled up,
a background Responses job decided by a router writes an analytics row that looks like a direct call, and a fallback
moves the request-body timestamp. The first is a money path, the second is the one case the design admitted was
untested, and all three are small enough for one change before any router strategy builds on the decision record.

## What Changes

- A pass re-entered with the next candidate keeps its span's roll-up (aggregated cost and per-model breakdown from the
  failed candidate's children); the span's deployment becomes the new candidate.
- A background Responses job served through a decision gets the same row as a live decided request: the router before
  the deployment in `execution_path`, `parent_deployment` the router, and the `decision` object. The job record carries
  what the poller needs for it.
- The request-body timestamp is the time the client's body was first read; a re-parse on a fallback or a pre-flight
  skip does not move it. No spec names this field, so it is an implementation fix with a unit test.

Out of scope: conversation binding and outcome feedback (settled 2026-11-05 as router-side and deferred, see the
perf improvement initiative's routing notes), the decision as a row of its own, body projection, a scoped key for the
decide call.

## Capabilities

### New Capabilities

none

### Modified Capabilities

- `router-deployment`: "Decision record" no longer excludes a background Responses job.
- `app-to-model-accounting`: "Usage roll-up to the ancestors" states that a re-entered pass keeps its roll-up.

## Impact

Depends on the archived changes `router-entity` and `router-decision-mode` (PR #2232, branch
`feat/router-deployment-type`); this change ships as its own PR on top of that branch, or on `development` once it
merges. Covers the three "known gaps" of the review record and nothing else from it. Code: the token-stats tracker's
span start, the background job record and its row mapping, the context's body setter. Stored job records written
before this change lack the new fields and read back with them null, as today.
