# spend-limit-reporting Specification Delta

## RENAMED Requirements

- FROM: `### Requirement: Only a single budget is reported`
- TO: `### Requirement: The payer is reported`

## MODIFIED Requirements

### Requirement: No Codex headers without a cost cap
Core SHALL NOT send any `x-codex-*` header when the caller's caps come from the role cost limit and the caller has no day, week or month cost limit, or only limits too large to enforce, or a limit of 0; when the caller's caps come from budgets and the budget reported under "The payer is reported" sets no window below 2^53, or there is none; or when the upstream answer is not 200.

#### Scenario: Uncapped caller
- **WHEN** a caller whose role sets no day, week or month cost limit sends a Responses request
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Upstream error
- **WHEN** a capped caller sends a Responses request and the upstream answers 400
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Several budgets
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller has two budgets in their cost object, and the one that
  pays sets only a day amount of 2^53, and the caller sends a Responses request that the upstream answers 200
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Budget too large to report
- **WHEN** `spendingLimits.mode` is `enforce` and a JWT caller whose one budget sets only a day amount of 2^53 sends
  a Responses request that the upstream answers 200
- **THEN** the answer carries no `x-codex-*` header

### Requirement: Budgets decide the caps in enforce mode
When `spendingLimits.mode` is `enforce`, for a JWT caller of a model on `POST /anthropic/v1/messages`, `POST /openai/v1/responses` and `POST /openai/responses`, and for a JWT caller of `GET /anthropic/api/oauth/usage`, Core SHALL take the spend caps from the caller's budget and SHALL NOT read or enforce the role cost limit; the cost object is the effective cost object of the budget-resolution capability. A JWT caller of anything else SHALL get no caps. API key callers and other modes keep the role limit.

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

### Requirement: The payer is reported
Under the requirement above, the answer to a call to a model SHALL report the budget that pays it, whichever the indication, the default budget or the walk resolved, never a blend. `GET /anthropic/api/oauth/usage` SHALL report a budget only when the caller has exactly one unexpired cost budget that matches the caller in their cost object, counted whatever models the budgets cover, and SHALL return `{"limits":[]}` otherwise.

#### Scenario: Two budgets in the cost object
- **WHEN** a caller whose token carries project `projA` matches two budgets in `projA`, each with room, the walk
  picks the first at 82% of its day amount, and the caller sends a Messages request that the upstream answers 200
- **THEN** the answer carries `anthropic-ratelimit-unified-overage-utilization: 0.82`, and the usage endpoint
  returns `{"limits":[]}`

#### Scenario: Indicated budget reported
- **WHEN** the same caller indicates the second budget, at 40% of its day amount, and sends a Responses request that
  the upstream answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 40`

#### Scenario: Default budget reported
- **WHEN** the caller's default is the second budget and they send a Responses request without an indication that
  the upstream answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 40`

#### Scenario: Second budget covers another model
- **WHEN** the caller matches a budget in `projA` for every model and another one in `projA` only for `claude-opus`
- **THEN** the usage endpoint returns `{"limits":[]}`

#### Scenario: Budget in another cost object
- **WHEN** a caller whose token carries project `projA` has one budget in `projA` and one in the default cost object
- **THEN** the usage endpoint reports the `projA` budget

#### Scenario: Expired budget not counted
- **WHEN** the caller matches two budgets in their cost object and the assignment of one has expired
- **THEN** the usage endpoint reports the other one

#### Scenario: No budget
- **WHEN** the caller matches no budget in their cost object
- **THEN** the usage endpoint returns `{"limits":[]}`

### Requirement: Exhausted budgets rejected in the client's format
Under the first requirement, for a deployment that is not translated, when the budget that would pay has no room in every window it sets (the indicated budget, the default budget in effect, or every budget the walk considers), Core SHALL reject the request before contacting the upstream with the route's spend-cap 429: `billing_error` on Messages, `usage_limit_reached` on Responses. It SHALL name the used-up window and reset of that budget, or of the walk's budget that can pay again first, the earlier budget in priority order on a tie, and SHALL win over a token or request limit.

#### Scenario: One budget, day exhausted, Messages
- **WHEN** a caller who has spent $10 of their one budget's $10 day amount sends a Messages request
- **THEN** Core answers 429 without calling the upstream, with `error.type` `billing_error`,
  `anthropic-ratelimit-unified-overage-period: daily`, and a `retry-after` that counts down to the next day reset

#### Scenario: One budget, day exhausted, Responses
- **WHEN** the same caller sends a Responses request
- **THEN** Core answers 429 without calling the upstream, with `error.type` `usage_limit_reached`,
  `error.limit_window_minutes` `1440`, `error.display_message` `You've exceeded your daily cost limit` and
  `x-codex-primary-used-percent: 100`

#### Scenario: One budget, month exhausted
- **WHEN** a caller who has spent their one budget's month amount sends a Responses request
- **THEN** Core answers the `usage_limit_reached` 429 with `error.limit_window_minutes` `43200` and
  `error.display_message` `You've exceeded your monthly cost limit`

#### Scenario: Several budgets, all exhausted
- **WHEN** on any day but the last of the month, a caller matches two budgets in their cost object, the first has
  used its month amount and the second its day amount, and sends a Responses request for a model both cover
- **THEN** Core answers the `usage_limit_reached` 429 with `error.limit_window_minutes` `1440` and `error.resets_at`
  equal to the next day reset

#### Scenario: Tie between budgets
- **WHEN** both budgets of the scenario above are used up and the caps that would free them reset at the same instant
- **THEN** the answer names the window of the first budget in priority order

#### Scenario: One of several budgets has room
- **WHEN** a caller matches two budgets that cover the model, the first is exhausted and the second, at 40% of its day
  amount, has room, and the upstream answers the Responses request 200
- **THEN** the request reaches the upstream and the answer carries `x-codex-primary-used-percent: 40`

#### Scenario: Indicated budget exhausted, another has room
- **WHEN** a caller indicates a budget that has used its month amount and holds another budget with room, and sends a
  Responses request
- **THEN** Core answers the `usage_limit_reached` 429 with `error.limit_window_minutes` `43200`

#### Scenario: Exhausted budget wins over a token limit
- **WHEN** a caller's budgets are exhausted and the model's per-minute token limit is reached too
- **THEN** Core answers with the spend-cap 429
