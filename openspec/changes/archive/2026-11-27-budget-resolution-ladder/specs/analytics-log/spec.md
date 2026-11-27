# analytics-log Specification Delta

## MODIFIED Requirements

### Requirement: Spending limits in the analytics row
When Core checks the limits of a JWT caller's call to a model, it SHALL write, in that model's row, right after
`project`, a `spending_limits` object with `mode` (`spendingLimits.mode`: `off`, `shadow` or `enforce`). In
`shadow` and `enforce` the object SHALL also carry `cost_object` (the effective cost object the call is charged to)
and `outcome`: `paid` when a budget pays, `no_budget` when the budget check refuses with a 403 code (no budget
covers the caller and the model, the indicated budget is not held or does not cover the call, the default budget
does not cover it, or the walk is off), or `exhausted` when the budget that would pay is used up.

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

#### Scenario: Indicated budget not covering in shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and a caller indicates a budget that does not cover the model they call
- **THEN** the call succeeds and its row has `spending_limits` with `outcome` `no_budget`

#### Scenario: Every budget used up in shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and the caller's only budget is used up
- **THEN** the call succeeds and its row has `spending_limits` with `outcome` `exhausted`

#### Scenario: Every budget used up in enforce mode
- **WHEN** `spendingLimits.mode` is `enforce` and the caller's only budget is used up
- **THEN** Core refuses the call with 429 and writes no row for it

### Requirement: Cost object in the analytics row
Every analytics row SHALL carry a `cost_object` object with `id`, the cost object of the caller that started the
chain, and `source`: `claim` (the token's project), `key` (the API key's project), `header` (the root request's
`X-DIAL-COST-OBJECT`, or the cost object of the budget it indicates, where the provider sets no `costObjectPath`) or
`default` (`spendingLimits.defaultCostObject`). It SHALL follow `spending_limits`, or `project` when the row has none.
`project` and `spending_limits.cost_object` SHALL stay as they are. A background Responses job's row SHALL carry the
cost object of the request that started the job.

#### Scenario: API-key caller
- **WHEN** a client with API key `proxyKey1` (project `EPM-RTC-GPT`) sends a chat completion
- **THEN** the row has `project.id` `EPM-RTC-GPT` and `cost_object` with `id` `EPM-RTC-GPT` and `source` `key`

#### Scenario: Token with a project
- **WHEN** `spendingLimits.mode` is `enforce` and a caller whose token carries project `projA` has one budget and sends
  a Messages request to a model
- **THEN** the model's row has `spending_limits` right after `project`, then `cost_object` with `id` `projA` and
  `source` `claim`

#### Scenario: Token without a project
- **WHEN** a caller whose token has no project claim sends a request
- **THEN** the row has `cost_object` with `id` `ORG` and `source` `default`, while `project.id` is empty

#### Scenario: Cost object from the header
- **WHEN** the provider sets no `costObjectPath` and a caller sends `X-DIAL-COST-OBJECT: IMF` with a chat completion
  to a model
- **THEN** the row has `cost_object` with `id` `IMF` and `source` `header`, and `spending_limits.cost_object` `IMF`
