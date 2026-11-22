# Spec Delta

## MODIFIED Requirements

### Requirement: Decision record
For a request served through a decision, a background Responses job included, Core SHALL write one analytics row,
for the deployment that served, with the router before it in `execution_path`, `parent_deployment` the router, and a
`decision` object: `router`, `api`, `candidates`, `deployment` (the one that served), `decide_ms` and `attempts`, one
`{deployment, status, reason}` per candidate tried before it, `status` being Core's answer for it.

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
