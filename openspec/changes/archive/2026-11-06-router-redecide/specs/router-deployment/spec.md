# Spec Delta

## ADDED Requirements

### Requirement: Decision limit
A decision-mode interface entry SHALL accept an optional `maxDecisions`, the number of decide requests one client
request may need, the first included, from 1 to 10; it defaults to `3`. Core SHALL reject a value outside that range
with `maxDecisions must be between 1 and 10`. Like `decisionTimeoutMs`, it is ignored on an entry not in decision mode.

#### Scenario: Limit out of range rejected
- **WHEN** the config declares `routers.r1` with `interfaces.openaiChatCompletions: {"mode": "decision", "base_url":
  "http://router/decide", "maxDecisions": 0}`, or the same with `"maxDecisions": 11`
- **THEN** the config is rejected with a message containing `maxDecisions must be between 1 and 10`

### Requirement: Re-decide after the candidates
When every candidate of a verdict has failed, the client is still connected and fewer than `maxDecisions` decide
requests were sent, Core SHALL send another decide request, built as the first one with a new key, plus
`X-DIAL-ROUTER-ATTEMPTS`, the JSON array of every attempt so far, and try the new verdict's candidates in the same
way, a candidate that already failed included. When the re-decide fails or no decision is left, Core SHALL answer
the last candidate's failure.

#### Scenario: Router decides again with the history
- **WHEN** `r1`'s first verdict is `{"candidates": ["m1"]}`, `m1`'s only upstream answers `503`, and its second verdict
  is `{"candidates": ["m2"]}`
- **THEN** the second decide request carries `X-DIAL-ROUTER-ATTEMPTS: [{"deployment":"m1","status":502,"reason":"upstream"}]`
  and an `API-KEY` that differs from the first decide request's and has `executionPath: ["r1"]`, `m2` serves the
  request and the client receives `200`

#### Scenario: Limit reached
- **WHEN** `maxDecisions` is `2` and both verdicts name only `m1`, whose only upstream answers `503`
- **THEN** the router receives two decide requests, `m1` is called twice, and the client receives `502`

#### Scenario: Re-decide fails
- **WHEN** the first candidate fails with `503` after its upstreams and the router answers the second decide request
  `500`
- **THEN** the client receives the first candidate's `502`, not the router's error

#### Scenario: Refusal in the second verdict skipped
- **WHEN** the second verdict is `{"candidates": ["private-model", "m2"]}` and the caller may not use `private-model`
- **THEN** `m2` serves the request and nothing is sent to `private-model`

#### Scenario: Body sent again
- **WHEN** the interface has `context: {"body": true}` and Core decides again
- **THEN** the second decide request's body is the client's request bytes, as the first one's was

### Requirement: Router headers set by Core only
Core SHALL drop a client-sent `X-DIAL-ROUTER-ATTEMPTS` or `X-DIAL-ROUTER-NOTICE` from every decide and notice request
and SHALL set them only as this capability states.

#### Scenario: Forged history dropped
- **WHEN** a client sends `X-DIAL-ROUTER-ATTEMPTS: [{"deployment":"x"}]` and `X-DIAL-ROUTER-NOTICE: true` to a
  decision-mode router
- **THEN** the first decide request carries neither header

### Requirement: Mid-stream notice
When a candidate's answer fails after bytes of it reached the client, outside an interceptor hand-off, Core SHALL end
the client's response as for any broken answer and SHALL send the decide `base_url`, without waiting, a notice request
with the decide request's client and default headers, `X-DIAL-ROUTER`, `X-DIAL-API`, `X-DIAL-ROUTER-NOTICE: true` and
`X-DIAL-ROUTER-ATTEMPTS`, no `API-KEY` and an empty body. Core SHALL ignore its answer; it is not a decision.

#### Scenario: Stream broken after the first chunk
- **WHEN** the verdict is `{"candidates": ["m1"]}`, the client streams `POST /openai/deployments/r1/chat/completions`,
  and `m1` sends one SSE chunk and then drops the connection
- **THEN** the client receives that chunk and a broken connection, and the router receives a notice request with no
  `API-KEY` and an empty body whose `X-DIAL-ROUTER-ATTEMPTS` ends with
  `{"deployment":"m1","status":200,"reason":"stream","received":true,"partial":true}`

#### Scenario: Client gone, no notice
- **WHEN** the client closes its connection in the middle of the streamed answer
- **THEN** the router receives no notice and no second decide request

### Requirement: Decision record across decisions
For a request that needed more than one decide request, the analytics `decision` object SHALL carry `decisions`, the
number of decide requests sent; `candidates` SHALL list every verdict's candidates in order and `decide_ms` SHALL be
their sum. An attempt SHALL carry `received: true` when the candidate's upstream had answered; the field is left out
when false, and `decisions` is left out for a request decided once.

#### Scenario: Row of a re-decided request
- **WHEN** the first verdict `{"candidates": ["m1"]}` fails on `m1`'s `503` and the second verdict `{"candidates":
  ["m2"]}` serves
- **THEN** the row has `decision.decisions: 2`, `decision.candidates: ["m1", "m2"]`, `decision.deployment: "m2"` and
  `decision.attempts: [{"deployment": "m1", "status": 502, "reason": "upstream"}]`

## MODIFIED Requirements

### Requirement: Candidate walk
Core SHALL try the candidates in order: one the caller may not use, whose limits are exhausted, that does not serve
the interface or does not exist is skipped before any call; one that fails to connect, answers a retriable status
after its upstream retries, or whose `200` answer breaks (not for a background job) is skipped while no byte reached
the client. Then Core decides again (Re-decide after the candidates). No fallback after a non-retriable answer or an
interceptor hand-off.

#### Scenario: Forbidden first candidate skipped
- **WHEN** the router answers `{"candidates": ["private-model", "gpt-4"]}` and the caller's roles do not admit
  `private-model`
- **THEN** `gpt-4` serves the request and nothing is sent to `private-model`

#### Scenario: Failing first candidate skipped
- **WHEN** the router answers `{"candidates": ["m1", "m2"]}` and `m1`'s only upstream answers `503`
- **THEN** `m2` serves the request, the client receives `200`, and one analytics row is written, for `m2`

#### Scenario: Every candidate forbidden
- **WHEN** the router answers `{"candidates": ["private-a", "private-b"]}` every time and the caller may use neither
- **THEN** the router receives three decide requests and Core answers `403`

#### Scenario: Non-retriable answer relayed
- **WHEN** the router answers `{"candidates": ["m1", "m2"]}` and `m1` answers `400` with a JSON error
- **THEN** the client receives that `400` and nothing is sent to `m2`

#### Scenario: Broken body before the client got a byte
- **WHEN** the router answers `{"candidates": ["m1", "m2"]}` for a non-streamed chat completion, and `m1` sends `200`
  headers and drops the connection in the middle of its body
- **THEN** `m2` serves the request, the client receives `200`, and the row's `decision.attempts` holds
  `{"deployment": "m1", "status": 502, "reason": "body", "received": true}`

#### Scenario: Broken stream before its first chunk
- **WHEN** the router answers `{"candidates": ["m1", "m2"]}` for a streamed chat completion, and `m1` sends `200`
  `text/event-stream` headers and drops the connection before any event
- **THEN** `m2`'s stream reaches the client with one `Content-Type` header

### Requirement: Decision failures
For the first decide request of a request, Core SHALL answer `504` when it exceeds `decisionTimeoutMs` (connecting included), `502` when the
router is unreachable, answers `5xx`, or names a candidate that does not exist or is a router, or when the router is
re-entered through a deployment it chose, and SHALL relay a router `4xx` status and body to the client. In every
case Core SHALL send nothing to any candidate and SHALL write no analytics row for the request.

#### Scenario: Slow router
- **WHEN** `decisionTimeoutMs: 200` and the router takes 2 s to answer
- **THEN** Core answers `504` within the second

#### Scenario: Router rejects the request
- **WHEN** the router answers `400` with `{"error": {"message": "state either tier or target"}}`
- **THEN** the client receives `400` with that body

#### Scenario: Router down
- **WHEN** nothing listens on the decide URL
- **THEN** Core answers `502`

#### Scenario: Unknown last candidate
- **WHEN** the router answers `{"candidates": ["private-a", "no-such-model"]}` and the caller may not use `private-a`
- **THEN** Core answers `502`

#### Scenario: Router re-entered through an application
- **WHEN** the router answers `{"candidates": ["app"]}` and `app` calls `POST /openai/deployments/r1/chat/completions`
  back with its per-request key
- **THEN** that call is answered `502` without a decide request of its own, and the router's next decision sees
  `app` failed with `502`

### Requirement: Decision record
For a request served through a decision, a background Responses job included, Core SHALL write one analytics row
for the request, for the deployment that served, with the router before it in `execution_path`, `parent_deployment` the router, and a
`decision` object: `router`, `api`, `candidates`, `deployment` (the one that served), `decide_ms` and `attempts`, one
attempt per try before it, `status` being Core's answer for it (see Decision record across decisions).

#### Scenario: Row of a decided request
- **WHEN** the router `r1` answers `{"candidates": ["m1", "m2"]}`, `m1` is skipped for a `503` and `m2` serves
- **THEN** the row has `deployment: "m2"`, `execution_path: ["r1", "m2"]`, `parent_deployment: "r1"` and
  `decision.router: "r1"`, `decision.api: "openaiChatCompletions"`, `decision.candidates: ["m1", "m2"]`,
  `decision.deployment: "m2"`, a numeric `decision.decide_ms` and
  `decision.attempts: [{"deployment": "m1", "status": 502, "reason": "upstream"}]`

#### Scenario: Row of a decided background job
- **WHEN** a client sends `POST /openai/v1/responses` with `{"model": "r3", "background": true, "input": "hi"}`, the
  router `r3` answers `{"candidates": ["gpt-4"]}`, the origin accepts the job and later answers the poll with
  `status: completed`
- **THEN** the create is served by `gpt-4` with the dial id replaced as for any background create, and the one row
  written when the job ends has `deployment: "gpt-4"`, `execution_path: ["r3", "gpt-4"]`, `parent_deployment: "r3"`
  and `decision.router: "r3"`, `decision.api: "openaiResponses"`, `decision.deployment: "gpt-4"`
