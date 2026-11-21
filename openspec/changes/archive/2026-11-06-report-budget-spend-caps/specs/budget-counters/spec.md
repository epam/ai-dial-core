# Spec Delta

## ADDED Requirements

### Requirement: Coding client routes charge the budget
When `spendingLimits.mode` is `shadow` or `enforce`, a JWT caller's call to a model through `POST /anthropic/v1/messages`, `POST /openai/v1/responses` or `POST /openai/responses` SHALL be charged in full to the counter of the budget that pays for it, as calls on the other model routes are. The role cost record SHALL still be charged too.

#### Scenario: Enforce mode
- **WHEN** a caller with one budget in their cost object sends a Messages request that costs $0.50
- **THEN** the budget's `day.used` and `month.used` in `GET /v1/user/usage` grow by $0.50

#### Scenario: Shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and the same caller sends a Responses request that costs $0.50
- **THEN** the budget's `day.used` grows by $0.50 and the role `dayCostStats.used` grows by $0.50 too

#### Scenario: Fall-through to the next budget
- **WHEN** the first budget that covers the model is exhausted and the second has room
- **THEN** the call's cost is charged to the second budget only

#### Scenario: Translated deployment
- **WHEN** a caller sends a Responses request to a translated deployment
- **THEN** the outer request charges no budget counter
