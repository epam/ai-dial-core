## MODIFIED Requirements

### Requirement: Decide request of an in-process call
When the request a decision router decides is served in process with a body tree and no bytes, as an origin
plugin's call or as the target pass of an alias call (deployment-aliases), the decision SHALL use that tree as it was
before the first candidate: with `decision.body: true` every decide request body, the re-decide included, SHALL be
JSON equal to it, and each fallback candidate SHALL be served from it, without an earlier candidate's changes.

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

#### Scenario: Fallback inside an alias call
- **WHEN** an alias with `presentAs: model` targets `r-tier`, a decision router over HTTP without `decision.body`
  answering `{"candidates": ["m-defaults", "m2"]}`, `m-defaults` declares `defaults: {"temperature": 0.5}` for
  Messages and answers `503`, and a caller sends a Messages request to the alias
- **THEN** `m-defaults` receives `temperature: 0.5`, and `m2` receives the caller's request with `model` set to `m2`
  and no `temperature`
