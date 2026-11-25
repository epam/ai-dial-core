# Spec Delta

## ADDED Requirements

### Requirement: Cost object in the analytics row
Every analytics row SHALL carry a `cost_object` object with `id`, the cost object of the caller that started the
chain, and `source`: `claim` (the token's project), `key` (the API key's project) or `default`
(`spendingLimits.defaultCostObject`). It SHALL follow `spending_limits`, or `project` when the row has none. `project`
and `spending_limits.cost_object` SHALL stay as they are. A background Responses job's row SHALL carry the cost object
of the request that started the job.

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
