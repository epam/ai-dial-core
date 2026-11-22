# Proposal

## Why

A tier alias calls its decision router in process, so every request through a tier is an in-process call to a
decision router. For such a call Core serializes the whole request tree before the first candidate, also when the
router does not get the body (`decision.body` unset). Those bytes are read only when a candidate fails and the next one
starts from them. In a load test of a tier routing chain at 2,000 users with request bodies of 650 KB on average,
that serialization cost 6.8 ms of Core CPU per request, about 9% of Core on the tier path, and nothing read the bytes.

## What Changes

- Without `decision.body`, a router pass reached in process keeps a copy of the request tree taken before the first
  candidate, instead of serializing it. The copy duplicates the objects and arrays and shares the string and number
  values, which never change.
- A fallback candidate (the next one after a failure or a refusal, or the first one of a new verdict) starts from a
  fresh copy of that tree, as the first candidate started from the call's tree. It no longer parses a serialization.
- With `decision.body: true` nothing changes: Core serializes the tree once, sends it as the decide body and serves
  fallback candidates from those bytes.
- Unchanged: what the router, each candidate and the analytics rows receive.

Out of scope: forwarding the client's bytes to the origin instead of re-serializing (a separate change and PR); the
deployments-POST path, which keeps serializing the call tree for a router.

## Capabilities

### Modified Capabilities

- `router-deployment`: MODIFIED "Decide request of an in-process call": stated by what the decide request and the
  fallback candidates receive, not by the serialization; a scenario for a fallback inside an alias call. MODIFIED
  "Body of an embedded decide request": an in-process call's body is named by what it equals, not as the serialization.

## Impact

- Code: `server` module, `RouterDecisionFlow`, `RouterDecision` and `ProxyUtil.parseRequestBody` (the body timestamp).
  No config, settings or API change.
- Per tier request: one full-body serialization fewer when the router does not get the body; one copy of the tree's
  objects and arrays instead.
- Motivation: a Core CPU profile of the tier routing chain under load (2026-11-21). This change covers the
  serialization on the router pass; the other per-request costs that profile names are left to other changes. There
  is no GitHub issue yet; one is needed before the PR leaves draft.
- Supersedes: embedded-decision D6 (for the body-addressed APIs without `decision.body`); alias-in-process-hop D3 (its
  sentence on a decision router serializing the tree once).
