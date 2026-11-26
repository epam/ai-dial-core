# Spec Delta

## MODIFIED Requirements

### Requirement: Decision record
For a request the router answered with a verdict, a background job included, Core SHALL also write a router row:
`deployment` the router, `parent_deployment` and `execution_path` as for a direct call to it, the client's
`response.status`, no bodies or upstream, and a `decision` object with `router`, `api`, `candidates`, `deployment`
(the one that served), `decide_ms` and `attempts`, one per earlier try with Core's answer as `status` (see Decision
record across decisions).

#### Scenario: Row of a decided request
- **WHEN** the router `r1` answers `{"candidates": ["m1", "m2"]}`, `m1` is skipped for a `503` and `m2` serves
- **THEN** the `m2` row has `execution_path: ["r1", "m2"]`, `parent_deployment: "r1"` and no `decision`, and the router
  row has `deployment: "r1"`, `execution_path: ["r1"]`, no `parent_deployment`, no bodies, `decision.router: "r1"`,
  `decision.api: "openaiChatCompletions"`, `decision.candidates: ["m1", "m2"]`, `decision.deployment: "m2"`, a numeric
  `decision.decide_ms` and `decision.attempts: [{"deployment": "m1", "status": 502, "reason": "upstream"}]`

#### Scenario: Row of a decided background job
- **WHEN** a client sends `POST /openai/v1/responses` with `{"model": "r3", "background": true, "input": "hi"}`, the
  router `r3` answers `{"candidates": ["gpt-4"]}`, the origin accepts the job and later answers the poll with
  `status: completed`
- **THEN** the create is served by `gpt-4` with the dial id replaced as for any background create, and when the job
  ends Core writes the `gpt-4` row, with `execution_path: ["r3", "gpt-4"]` and `parent_deployment: "r3"`, and the
  router row, with `deployment: "r3"`, `decision.api: "openaiResponses"` and `decision.deployment: "gpt-4"`

#### Scenario: Router row of a candidate behind an interceptor
- **WHEN** the router `r1` answers `{"candidates": ["m-int"]}`, `m-int` has `interceptors: ["i2"]` and `i2` calls
  Core back
- **THEN** Core writes one router row, with `decision.deployment: "m-int"`, and one `m-int` row, with
  `execution_path: ["r1", "i2", "m-int"]`, `parent_deployment: "r1"` and no `decision`

#### Scenario: Router row after a global interceptor
- **WHEN** the config declares `globalInterceptors: ["i1"]`, a client calls `r1` and the router answers
  `{"candidates": ["gpt-4"]}`
- **THEN** the router row has `execution_path: ["i1", "r1"]` and no `parent_deployment`

#### Scenario: Router row of a secured key
- **WHEN** a client with a secured key calls `r1` and the router answers `{"candidates": ["gpt-4"]}`
- **THEN** Core writes both rows, and the router row carries the `decision` object

#### Scenario: Router row when every candidate fails
- **WHEN** `analytics.errorRows` is off, the router `r-two`, with `decision: {"maxDecisions": 2}`, answers `{"candidates": ["m1"]}`
  to both decide requests and `m1` answers `503` each time
- **THEN** the client receives `502` and Core writes only the router row, with `response.status: "502"`,
  `decision.decisions: 2`, `decision.deployment: "m1"` and `decision.attempts: [{"deployment": "m1", "status": 502,
  "reason": "upstream"}]`, the last try being the one `deployment` names

