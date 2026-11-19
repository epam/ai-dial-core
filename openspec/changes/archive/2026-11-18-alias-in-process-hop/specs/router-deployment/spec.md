# Spec Delta

## MODIFIED Requirements

### Requirement: Decide request of an in-process call
When the request a decision router decides is served in process with a body tree and no bytes, as an origin
plugin's call or as the target pass of an alias call (deployment-aliases), Core SHALL serialize that tree once,
before the first candidate, and SHALL use the serialization wherever the decision uses the client's bytes: as the
body of every decide request when `decision.body` is `true`, the re-decide included, and as the request each fallback
candidate is served from.

#### Scenario: Plugin calls a decision router that wants the body
- **WHEN** application `app` is served by plugin `p2`, a client sends a chat completion to `app` with
  `X-Test-Call: /openai/v1/chat/completions` and `X-Test-Call-Model: r2`, and `r2` is a decision router over HTTP with
  `decision: {"body": true}` answering `{"candidates": ["m1"]}`
- **THEN** the router's decide request body is JSON equal to the body `p2` passed, and `m1` serves the call

#### Scenario: Fallback inside an in-process call
- **WHEN** `p2` calls `/openai/v1/chat/completions` with `model: rf`, a decision router over HTTP without
  `decision.body` answering `{"candidates": ["m1", "m2"]}`, and `m1` declares `defaults: {"temperature": 0.5}` and
  answers `503` after its upstream retries
- **THEN** `m2` receives the `messages` `p2` passed, with `model` set to `m2` and no `temperature`

#### Scenario: Skip inside an in-process call
- **WHEN** `p2` calls `/openai/v1/chat/completions` with `model: rf`, and `rf` answers
  `{"candidates": ["missing", "m2"]}`, where no deployment `missing` exists
- **THEN** `m2` receives the `messages` `p2` passed, with `model` set to `m2`

#### Scenario: In-process call on deployments-POST
- **WHEN** `p2` calls `/openai/deployments/r2/chat/completions`
- **THEN** `m1` serves the call with the `messages` `p2` passed

#### Scenario: Alias over a decision router that wants the body
- **WHEN** an alias with `presentAs: model` targets `r2`, a decision router over HTTP with `decision: {"body": true}`
  answering `{"candidates": ["m1"]}`, and a caller sends a chat completion to the alias
- **THEN** the router's decide request body carries the caller's `messages`, and `m1` serves the call
