## ADDED Requirements

### Requirement: Session affinity picks the first upstream
For a model with `sessionAffinity: true` (default `false`), Core SHALL send the first attempt of a request with a
session id to the upstream that the session id maps to, unless a pin in "Pins that win over session affinity" applies.
The mapping SHALL depend only on the session id, the ids, weights and tiers of the model's `upstreams`, and which of
them are available. An upstream's id is its `id`, or its `endpoint` when it has no `id`.

#### Scenario: Requests of one session with different last messages
- **WHEN** model `m` has `sessionAffinity: true` and four upstreams `up-a` to `up-d` in tier 0 with weight 1, and a
  client sends ten `POST /openai/deployments/m/chat/completions` requests with `x-claude-code-session-id: s-1`, each
  ending with a different user message, and no `X-UPSTREAM-ID`
- **THEN** all ten origin requests carry the `X-UPSTREAM-KEY` of the same upstream

#### Scenario: Same pick on a new balancer
- **WHEN** session `s-1` maps to upstream `up-c` of `m`, and Core builds the balancer of `m` again with the same
  upstreams, as it does after a restart or on another Core instance
- **THEN** the next request of `s-1` goes to `up-c`

### Requirement: The session id comes from the conversation id headers
Core SHALL take the session id from the static setting `tracing.conversationIdHeaders`: the trimmed value of the first
listed header that is present, not blank and at most 256 characters long. With an empty list, no request has a
session id.

#### Scenario: The value decides, not the header name
- **WHEN** model `m` has `sessionAffinity: true` and four upstreams, and gets requests with `thread-id: s-2` and
  requests with `x-session-id: s-2`, none of them with `x-claude-code-session-id`
- **THEN** all of them reach the same upstream

### Requirement: Session shares follow upstream weights and tiers
With session affinity, Core SHALL choose among the same upstreams as the weighted balancer: the available upstreams
with a weight above 0 in the highest tier (the lowest `tier` value) that has one. Each of them SHALL receive a share of
the sessions in proportion to its `weight`. When no upstream of any tier is available, Core SHALL choose as the
balancer does today.

#### Scenario: Shares follow weights
- **WHEN** model `m` has `sessionAffinity: true`, `up-a` with weight 1 and `up-b` with weight 3 in tier 0, and
  requests carry the 10,000 session ids `s-0` to `s-9999`
- **THEN** between 23% and 27% of the sessions go to `up-a`, and the others to `up-b`

#### Scenario: Lower tier only when the higher one is unavailable
- **WHEN** model `m` has `sessionAffinity: true`, `up-a` in tier 0, `up-b` and `up-c` in tier 1, and
  `maxRetryAttempts: 1`
- **THEN** every session goes to `up-a`, and after `up-a` answers `503`, the requests of one session all go to the
  same one of `up-b` and `up-c` until `up-a` is available again

### Requirement: Sessions move only from an upstream that leaves
With session affinity, an upstream that cools down after a retriable answer SHALL be left out until it is available
again. Only the sessions that mapped to it SHALL move: each to the same other upstream on every request, spread over
the others in proportion to their weights, and back when it is available. When the config adds, removes or reweights
one upstream of the model, only the sessions that map to that upstream or away from it SHALL move.

#### Scenario: Upstream cooling down
- **WHEN** model `m` has `sessionAffinity: true` and upstreams `up-a`, `up-b` and `up-c` in tier 0 with weight 1,
  session `s-1` maps to `up-a`, and `up-a` answers `429` with `Retry-After: 60`
- **THEN** the next ten requests of `s-1` all go to the same one of `up-b` and `up-c`, and the sessions that map to
  `up-b` or `up-c` keep their upstream

#### Scenario: Added upstream takes only its share
- **WHEN** model `m` has `sessionAffinity: true` and upstreams `up-a`, `up-b` and `up-c` in tier 0 with weight 1, the
  sessions `s-0` to `s-9999` are mapped, and the config then adds `up-d` with weight 1
- **THEN** every session whose upstream changed now goes to `up-d`, and between 22% and 28% of the sessions moved

### Requirement: Pins that win over session affinity
Core SHALL use session affinity only when no other pin chooses the upstream. In this order, these pins SHALL choose
instead: a client `X-UPSTREAM-ID` header; a `reasoning` item in the `input` of `POST /openai/v1/responses` stamped by
one of the model's upstreams (see responses-api, "Reasoning item affinity"); an upstream cache entry that matches the
request. Core SHALL write upstream cache entries as it does without session affinity.

#### Scenario: X-UPSTREAM-ID wins
- **WHEN** model `m` has `sessionAffinity: true` and upstreams `up-a` and `up-b`, session `s-1` maps to `up-a`, and a
  request of `s-1` carries `X-UPSTREAM-ID: up-b`
- **THEN** the request goes to `up-b`

#### Scenario: Stamped reasoning item wins
- **WHEN** the same model serves Responses, and a `POST /openai/v1/responses` request of `s-1` has a `reasoning` item
  stamped by `up-b` in `input`
- **THEN** the request goes to `up-b`

#### Scenario: Cache entry wins
- **WHEN** model `m` has `sessionAffinity: true`, `autoCachingSupported: true` and upstreams `up-a` and `up-b`, session
  `s-1` maps to `up-a`, a first `POST /anthropic/v1/messages` request of `s-1` with `X-UPSTREAM-ID: up-b` gets `200`
  with `X-DIAL-CACHE-BREAKPOINT-PATH`, and a second request of `s-1` without `X-UPSTREAM-ID` extends that prefix
- **THEN** the second request goes to `up-b`

#### Scenario: Cache entry written for the session's upstream
- **WHEN** model `m` has `sessionAffinity: true` and `autoCachingSupported: true`, a request of `s-1` that matches no
  cache entry goes to `up-a` by the session pick, and `up-a` answers `200` with `X-DIAL-CACHE-BREAKPOINT-PATH`
- **THEN** Core stores a cache entry that names `up-a` for that prefix

### Requirement: Retries and requests without a session id are balanced as today
With session affinity, Core SHALL choose the second and later attempts of a request through the weighted balancer, as
without it, within the same attempt bound (see deployment-origin, "Upstream choice and the attempt bound"). For a model
without `sessionAffinity: true`, and for a request without a session id, Core SHALL choose every attempt as it does
today.

#### Scenario: Retry after the session's upstream fails
- **WHEN** model `m` has `sessionAffinity: true`, upstreams `up-a` and `up-b` and `maxRetryAttempts: 2`, session `s-1`
  maps to `up-a`, and `up-a` answers `503`
- **THEN** the second attempt goes to `up-b`, and the client gets the answer of `up-b` with `X-UPSTREAM-ATTEMPTS: 2`

#### Scenario: No session id
- **WHEN** model `m` has `sessionAffinity: true` and a request carries none of the headers in
  `tracing.conversationIdHeaders`
- **THEN** Core chooses the upstream with the weighted random balancer, as today

#### Scenario: Field not set
- **WHEN** model `m` does not set `sessionAffinity`, and its requests carry `x-claude-code-session-id: s-1`
- **THEN** Core chooses the first attempt of each request with the weighted random balancer, as for a request without
  the header
