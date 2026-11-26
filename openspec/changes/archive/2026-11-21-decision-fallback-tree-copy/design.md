# Design

## Context

A pass served in process (an origin plugin's call, or the target pass of an alias call) has the parsed request as a
tree and no body bytes. When that pass reaches a decision router, the decision flow needs the client's request twice
over: as the decide body when the router asks for it, and as the starting point of each fallback candidate, because
the failed candidate's chain changed the live tree (defaults, `model`, attachments). Today Core serializes the tree
once, before the first candidate, makes the bytes the pass's original body, and a fallback candidate parses them
again. A tier alias calls its router this way on every request, and its router does not ask for the body.

## Decisions

### D1. A fallback candidate starts from a copy of the tree, not from a serialization

Without `decision.body`, `prepare` keeps `tree.deepCopy()` on the `RouterDecision` and serializes nothing. Jackson's
value nodes (strings, numbers, booleans) are immutable, so the copy shares them and duplicates only the objects and
arrays: its cost grows with the number of JSON nodes, not with the bytes of the prompt. `prepare` runs on a virtual
thread before any candidate, so the copy is the client's request.

Every path to another candidate (a refusal, a failed call, a new verdict) goes through `resetCandidateOutcome`. There
the next candidate gets a fresh copy as its call tree, with no request body set, so the controller's parse takes the
call tree (`ProxyUtil.parseRequestBody`) exactly as it did for the first candidate. One field and one branch; the
re-entry methods of the controllers are unchanged.

- **Rejected: keep the serialization** (embedded-decision D6) - D6 assumed in-process calls to a decision router are
  rare. A tier alias makes every tier request one, at 6.8 ms of Core CPU per request for 650 KB bodies.
- **Rejected: serialize the copy lazily when a fallback starts** - the fallback starts on the event loop, so the
  serialization would block it or need an extra hop to a virtual thread before the re-entry; the fresh copy needs
  neither, and a re-entry then parses nothing.
- **Rejected: no copy, the next candidate takes the live tree** - it would carry the failed candidate's changes.

### D2. With `decision.body` the serialization stays

The router gets the client's request as bytes, so Core still serializes the tree once, sends it as the decide body,
and a re-decide and the fallback candidates use those bytes as today. The copy of D1 is not kept then.

- **Rejected: copy in both cases and serialize only for the decide body** - the bytes exist anyway, and the re-decide
  body would need a serialization of its own.

## Differences from today

None at the boundaries: the decide request, the request each candidate's origin receives and the analytics rows are
the same. Inside, the pass's original body stays empty for an in-process call without `decision.body`.

## Effect per request

Per request through a decision router reached in process, without `decision.body` (a tier alias):

| | Today | After |
|---|---|---|
| Full-body serializations on the router pass | 1 | 0 |
| Copy of the tree's objects and arrays | 0 | 1 |
| Per fallback candidate | 1 parse of the serialization | 1 copy of the tree's objects and arrays |

Measured today: 6.8 ms of Core CPU per tier request in `RouterDecisionFlow.prepare` (JFR, 2,000 users, 650 KB average
body). After, by the same load against the same entry point (tasks.md 1.5): 7.79 → 0.12 ms on the same nodes; the
copy is too cheap to show in the samples.

## Risks / Trade-offs

- Memory: the decision holds the copied objects and arrays for the life of the request, where today it held the
  serialized bytes. The copy shares the strings, so it is smaller than the bytes for a prompt-heavy body.
- A mutable value node (a `POJONode`, or a `BinaryNode` and its byte array) is shared between the copies. A parsed
  request never holds one. An origin plugin's call tree is built by the plugin and may hold one, but no Core chain
  function changes such a value in place.

## Migration Plan

None.

## Code map

- `RouterDecisionFlow.java:decideWith` - reads the body future only when the router gets the body.
- `RouterDecisionFlow.java:prepare` - serializes with `decision.body`, else keeps the copy (`Prepared.clientTree`).
- `RouterDecisionFlow.java:decide` - stores the copy on the decision.
- `RouterDecisionFlow.java:resetCandidateOutcome` - the one place every move to another candidate passes.
- `RouterDecisionFlow.java:redecide` - unchanged: sends the original bytes only with `decision.body`.
- `RouterDecision.java` - the `clientTree` field.
- `ProxyContext.java:getCallTree`, `ProxyContext.java:setCallTree` - the call tree is visible while no request body
  is set.
- `ProxyUtil.java:parseRequestBody` - takes the call tree in place of parsing, and keeps the first parse's body
  timestamp, so a fallback candidate's parse of a copy is not a second read (as for saved bytes).
- `MessagesBaseController.java:prepareUpstreamRoute` - no longer sets the body timestamp again after the chain.
- `MessagesBaseController.java:reenterWithCandidate`, `ChatCompletionsController.java:reenterWithCandidate`,
  `ResponsesController.java:reenterWithCandidate` - unchanged; their parse takes the copy.
- `DeploymentPostController.java:readRequestBody` - unchanged: deployments-POST still serializes the call tree for a
  router.
