# Spec Delta

## MODIFIED Requirements

### Requirement: Key contents for a root request
For a request authenticated by a project API key or a JWT, Core SHALL write into the key it mints: the client's
project key as `originalKey` (none for a JWT), the JWT claims as `extractedClaims`, Core's trace id as `traceId`,
the server span of this pass as `spanId`, the deployment (or route) name as `sourceDeployment`, `executionPath`
`[<deployment>]`, preceded by the router's name when a router's decision chose the deployment (router-deployment),
and `httpHeaders` holding `X-CONVERSATION-ID` and `X-JOB-TITLE` when the client sent them.

#### Scenario: Root key record
- **WHEN** a user with a JWT whose `sub` is `u1` sends `X-CONVERSATION-ID: c1` to
  `POST /openai/deployments/app/chat/completions`, Core's span is `s1` in trace `t1`
- **THEN** the key record has `traceId: "t1"`, `spanId: "s1"`, `sourceDeployment: "app"`, `executionPath: ["app"]`,
  the `u1` claims and `httpHeaders: {"X-CONVERSATION-ID": "c1"}`

#### Scenario: Root key record after a decision
- **WHEN** a user calls router `r1` and its decision chooses `gpt-4`
- **THEN** the key Core sends to `gpt-4` has `sourceDeployment: "gpt-4"` and `executionPath: ["r1", "gpt-4"]`

### Requirement: One analytics row per Core pass
Core SHALL write one analytics row per pass that relays an origin's answer, a `Model` pass whose client disconnected
during the relay included, with `deployment`, `execution_path` from the key it minted, `parent_deployment` set to
the nearest preceding `execution_path` entry that is not one of the deployment's interceptors (absent for a root),
and `trace.trace_id`, `trace.core_span_id` and, for a child, `core_parent_span_id`.

#### Scenario: Parent deployment of a model called by an app
- **WHEN** root `app` calls `gpt-4`
- **THEN** two rows are written: `app` with `execution_path: ["app"]` and no `parent_deployment`, and `gpt-4` with
  `execution_path: ["app", "gpt-4"]` and `parent_deployment: "app"`

#### Scenario: Conversation id carried to the child row
- **WHEN** the client sent `X-CONVERSATION-ID: c1` to `app` and `app` calls `gpt-4` without that header
- **THEN** the `gpt-4` row carries conversation id `c1`

#### Scenario: One row for a decided request
- **WHEN** a user calls router `r1` in decision mode and its decision chooses `gpt-4`, which answers
- **THEN** one row is written, `gpt-4` with `execution_path: ["r1", "gpt-4"]` and `parent_deployment: "r1"`, and no
  row for `r1`
