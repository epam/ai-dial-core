# Spec Delta

## MODIFIED Requirements

### Requirement: Rows without bodies for an alias pass
Core SHALL write the row of an alias pass without `request.body`, `response.body` and `assembled_response`, and with
every other field, whatever the key, the interface and whether the target pass runs in process. The row of the
alias's target pass SHALL carry its bodies as "Bodies in the analytics row" says; a target pass that refuses the call
before its origin writes no row unless `analytics.errorRows` is on, as on a direct call.

#### Scenario: Messages through an alias
- **WHEN** a caller with a key that is not `secured` sends `/anthropic/v1/messages` with `model: multi-alias`, and
  both passes write their rows
- **THEN** the alias's row has no `request.body`, `response.body` or `assembled_response` and has its `deployment`
  and `trace`, and the target's row has `request.body` with `model` set to the target name and `response.body`

#### Scenario: Streamed chat completion through an alias
- **WHEN** a caller with a key that is not `secured` sends a streamed chat completion to `assistant`
- **THEN** the alias's row has no `assembled_response`, and the row of `gpt-4-internal` has its `assembled_response`

### Requirement: Spending limits in the analytics row
When Core checks the limits of a JWT caller's call to a model, it SHALL write, in that model's row, right after
`project`, a `spending_limits` object with `mode` (`spendingLimits.mode`: `off`, `shadow` or `enforce`). In
`shadow` and `enforce` the object SHALL also carry `cost_object` (the cost object the call is charged to) and
`outcome`: `paid` when a budget pays, `no_budget` when no budget covers the caller and the model, or `exhausted`
when every such budget is used up.

#### Scenario: Enforce mode
- **WHEN** `spendingLimits.mode` is `enforce`, a caller whose token carries project `projA` has one budget, policy
  `projA-a1` assigned at `projA/a1`, and sends a Messages request to model `claude-spend`
- **THEN** the model's row has `spending_limits` with `mode` `enforce`, `cost_object` `projA` and `outcome` `paid`

#### Scenario: Shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and the same caller sends the same request
- **THEN** the model's row has `spending_limits` with `mode` `shadow`, `cost_object` `projA` and `outcome` `paid`

#### Scenario: Spending limits off
- **WHEN** `spendingLimits.mode` is `off` and a caller with a budget assignment calls the model
- **THEN** the row has `spending_limits` with `mode` `off` and no other key

#### Scenario: No budget in shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and a caller whose token has no project claim has no budget
- **THEN** the call succeeds and its row has `spending_limits` with `cost_object` `ORG` and `outcome` `no_budget`,
  while `project.id` is empty

#### Scenario: Every budget used up in shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and the caller's only budget is used up
- **THEN** the call succeeds and its row has `spending_limits` with `outcome` `exhausted`

#### Scenario: Every budget used up in enforce mode
- **WHEN** `spendingLimits.mode` is `enforce`, `analytics.errorRows` is off and the caller's only budget is used up
- **THEN** Core refuses the call with 429 and writes no row for it

## ADDED Requirements

### Requirement: Error rows are on by default
Core SHALL write the rows of "Error rows for answers Core gives itself" and "Error rows for a relay that breaks" unless
`analytics.errorRows` (env `aidial.analytics.errorRows`) is `false`. Absent, it SHALL be `true`. With it off, every row SHALL be
what Core writes without this setting.

#### Scenario: Setting absent
- **WHEN** Core runs without `analytics.errorRows` and a model's only upstream answers `429` with `maxRetryAttempts: 1`
- **THEN** the client gets `503` and one row is written, with `response.status: "503"`

#### Scenario: Setting off
- **WHEN** `analytics.errorRows` is `false` and a model's only upstream answers `429` with `maxRetryAttempts: 1`
- **THEN** the client gets `503` and no row is written

#### Scenario: Setting off, a successful pass
- **WHEN** `analytics.errorRows` is `false` and a model answers `200`
- **THEN** the row is the row Core writes without the setting

### Requirement: Error rows use the fields of any row
A row of "Error rows for answers Core gives itself" or "Error rows for a relay that breaks" SHALL have the fields every row has and
no field that other rows lack. When Core gave the answer itself, its `response.body` SHALL be empty and it SHALL have no
`token_usage`.

#### Scenario: Refusal
- **WHEN** `analytics.errorRows` is on and a key that is not `secured` is refused with `403` on
  `/openai/deployments/{id}/chat/completions`
- **THEN** the row has the `request.body` field (empty, as that surface reads the body after the refusal), `response.status: "403"` and `trace`, `response.body` is the empty string, and the row
  has no `token_usage` and no `error` member

#### Scenario: Successful pass with the setting on
- **WHEN** `analytics.errorRows` is on and a model answers `200`
- **THEN** the row is the row written with the setting off
