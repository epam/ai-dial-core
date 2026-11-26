# Spec Delta

## MODIFIED Requirements

### Requirement: Budgets decide the caps in enforce mode
When `spendingLimits.mode` is `enforce`, for a JWT caller of a model on `POST /anthropic/v1/messages`, `POST /openai/v1/responses` and `POST /openai/responses`, and for a JWT caller of `GET /anthropic/api/oauth/usage`, Core SHALL take the spend caps from the caller's budget and SHALL NOT read or enforce the role cost limit; the cost object is the token's project claim or `defaultCostObject`. A JWT caller of anything else SHALL get no caps. API key callers and other modes keep the role limit.

#### Scenario: Project budget reported on Messages
- **WHEN** a caller whose token carries project `projA` has one budget in `projA` with a day amount of $10 and $8.20
  spent, and a role day cost limit of $500 with nothing spent, sends a Messages request and the upstream answers 200
- **THEN** the answer carries `anthropic-ratelimit-unified-status: allowed_warning` and
  `anthropic-ratelimit-unified-overage-utilization: 0.82`

#### Scenario: Project budget reported on Responses
- **WHEN** the same caller sends a Responses request and the upstream answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 82` and `x-codex-primary-window-minutes: 1440`

#### Scenario: Role cost limit reached, budget has room
- **WHEN** a caller has spent their whole role day cost limit and has one budget with room left
- **THEN** the request reaches the upstream and the answer reports the budget

#### Scenario: Token without a project
- **WHEN** a caller whose token carries no project has one budget in the default cost object at 40% of its month
  amount
- **THEN** the usage endpoint returns one `monthly` row with `percent` 40

#### Scenario: API key caller
- **WHEN** an API key at 82% of its role day cost limit sends a Responses request that the upstream answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 82`

#### Scenario: Call to an application
- **WHEN** a caller at 30% of their role day cost limit, with one budget at 82%, sends a Messages request to an
  application that answers 200
- **THEN** the answer carries no `anthropic-ratelimit-unified-*` header, while the usage endpoint reports the budget

#### Scenario: Messages call to an application with the role cost limit reached
- **WHEN** a caller who has spent their whole role day cost limit, with one budget with room, sends a Messages request
  to an application that answers 200
- **THEN** Core answers 200
