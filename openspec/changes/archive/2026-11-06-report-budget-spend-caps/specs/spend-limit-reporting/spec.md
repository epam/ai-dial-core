# Spec Delta

## ADDED Requirements

### Requirement: Budgets decide the caps in enforce mode
When `spendingLimits.mode` is `enforce`, for a JWT caller of a model on `POST /anthropic/v1/messages`, `POST /openai/v1/responses` and `POST /openai/responses`, and for a JWT caller of `GET /anthropic/api/oauth/usage`, Core SHALL take the spend caps from the caller's budget and SHALL NOT read or enforce the role cost limit. The cost object is the token's project claim, or `defaultCostObject` when it has none. Other callers, deployments and modes keep the role cost limit.

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
- **THEN** the answer carries `anthropic-ratelimit-unified-overage-utilization: 0.30`, while the usage endpoint
  reports the budget

### Requirement: Only a single budget is reported
Under the requirement above, Core SHALL report a budget only when the caller has exactly one unexpired cost budget that matches the caller in their cost object, counted whatever models the budgets cover. Otherwise Core SHALL send no `anthropic-ratelimit-unified-*` and no `x-codex-*` header on a 200 answer, and `GET /anthropic/api/oauth/usage` SHALL return `{"limits":[]}`.

#### Scenario: Two budgets in the cost object
- **WHEN** a caller whose token carries project `projA` matches two budgets in `projA`, each with room, and sends a
  Messages request that the upstream answers 200
- **THEN** the answer carries no `anthropic-ratelimit-unified-*` header, and the usage endpoint returns
  `{"limits":[]}`

#### Scenario: Second budget covers another model
- **WHEN** the caller matches a budget in `projA` for every model and another one in `projA` only for `claude-opus`,
  and sends a Responses request for another model that the upstream answers 200
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Budget in another cost object
- **WHEN** a caller whose token carries project `projA` has one budget in `projA` and one in the default cost object
- **THEN** the usage endpoint reports the `projA` budget

#### Scenario: Expired budget not counted
- **WHEN** the caller matches two budgets in their cost object and the assignment of one has expired
- **THEN** Core reports the other one

#### Scenario: No budget
- **WHEN** the caller matches no budget in their cost object
- **THEN** the usage endpoint returns `{"limits":[]}`

### Requirement: Budget windows as spend caps
The reported budget's `day` and `month` amounts SHALL be the caps. A window the budget does not set, or sets at 2^53 or more, SHALL NOT be reported; a budget amount is always above 0. A window's spend SHALL be the caller's spend in it across all models of the budget, before this request, and its reset the next period start of the rate-limit schedule.

#### Scenario: Day and month amounts
- **WHEN** the caller's one budget has a day amount of $10 and a month amount of $20.50, the caller has spent $8.20
  today, and a Responses request is answered 200
- **THEN** the answer carries `x-codex-primary-used-percent: 82` with window 1440 and
  `x-codex-secondary-used-percent: 40` with window 43200

#### Scenario: Month amount only
- **WHEN** the caller's one budget sets only a month amount, at 30%
- **THEN** a Responses answer carries the `x-codex-primary-*` headers with `x-codex-primary-window-minutes: 43200`
  and no `x-codex-secondary-*` header, and the usage endpoint returns one `monthly` row

#### Scenario: Spend of every model of the budget
- **WHEN** the caller spent $3 on one model and $5 on another, both in the budget, against a day amount of $10
- **THEN** a Messages answer carries `anthropic-ratelimit-unified-overage-utilization: 0.80`

#### Scenario: Amount too large to report
- **WHEN** the caller's one budget sets only a day amount of 2^53
- **THEN** a Messages answer carries no `anthropic-ratelimit-unified-*` header and the usage endpoint returns
  `{"limits":[]}`

### Requirement: Cap reported from the budget
Of the reported budget's windows, Core SHALL report the fullest one, or once a window is used up, the used-up window that resets last, ties going to the month. The Messages headers, the Codex headers and the usage rows SHALL carry it with the values used for a role cost limit (`docs/anthropic-spend-limits.md` and the Codex header requirements above). The active usage row SHALL be the cap the headers of a call to a model report.

#### Scenario: Usage endpoint agrees with the headers
- **WHEN** the caller's one budget is at 82% of its day amount and 40% of its month amount
- **THEN** the usage endpoint returns a `daily` row with `percent` 82 and `is_active` true, a `monthly` row with
  `percent` 40, and `extra_usage.utilization` 82, and `resets_at` of the daily row equals the
  `anthropic-ratelimit-unified-overage-reset` of a Messages answer

### Requirement: Exhausted budgets rejected in the client's format
Under the first requirement, for a deployment that is not translated, when no budget that covers the model has room in every window it sets, Core SHALL reject the request before contacting the upstream with the route's spend-cap 429: `billing_error` on Messages, `usage_limit_reached` on Responses. It SHALL name the used-up window and reset of the budget that can pay again first, the earlier budget in priority order on a tie, and SHALL win over a token or request limit.

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
- **WHEN** a caller matches two budgets that cover the model, the first is exhausted and the second has room
- **THEN** the request reaches the upstream and the answer carries no `x-codex-*` header

#### Scenario: Exhausted budget wins over a token limit
- **WHEN** a caller's budgets are exhausted and the model's per-minute token limit is reached too
- **THEN** Core answers with the spend-cap 429

### Requirement: No budget for the model
Under the first requirement, for a deployment that is not translated, when no budget of the caller's cost object covers the model, Core SHALL reject the request before contacting the upstream with 429, a DIAL error body that names the model and the cost object, no `retry-after` and no spend header.

#### Scenario: Budget for another model only
- **WHEN** a caller's only budget in their cost object covers only `claude-opus` and they send a Messages request
  for another model
- **THEN** Core answers 429 without calling the upstream, with an `error.message` that names that model and the
  cost object, and with no `anthropic-ratelimit-unified-*` header

### Requirement: Unreadable budget counter
Under the first requirement, for a deployment that is not translated, when Core cannot read the counter of a budget it needs to check the call, a token or request limit that rejects SHALL decide the answer. Otherwise Core SHALL reject the request before contacting the upstream with the route's "spend limit could not be read" 429 when the caller has a budget for the model with a window below 2^53, and with 500 when it has none.

#### Scenario: Counter unreadable on Messages
- **WHEN** a caller with one budget sends a Messages request and the budget counter cannot be read
- **THEN** Core answers 429 without calling the upstream, with
  `anthropic-ratelimit-unified-overage-disabled-reason: fetch_error`

#### Scenario: Counter unreadable on Responses
- **WHEN** a caller with one budget sends a Responses request and the budget counter cannot be read
- **THEN** Core answers 429 without calling the upstream, and the `error.type` is not `usage_limit_reached`

#### Scenario: Token limit decides over an unreadable counter
- **WHEN** the budget counter cannot be read and the model's per-minute token limit is reached
- **THEN** Core answers with the token limit's 429

#### Scenario: Budget too large to cap, counter unreadable
- **WHEN** a caller whose only budget for the model sets only a day amount of 2^53 sends a Messages request and the
  budget counter cannot be read
- **THEN** Core answers 500 with `x-should-retry: true` without calling the upstream

#### Scenario: Token limit decides for a budget too large to cap
- **WHEN** the caller of the scenario above has also reached the model's per-minute token limit
- **THEN** Core answers with the token limit's 429

### Requirement: Unreadable budget counter on the usage endpoint
Under the first requirement, when Core cannot read the counter of the budget it would report, `GET /anthropic/api/oauth/usage` SHALL answer 500 with `x-should-retry: true` and the body `{"type":"error","error":{"type":"api_error","message":"Failed to get spend limits"}}`. Core SHALL NOT read the counter of a budget that sets no window below 2^53.

#### Scenario: Counter unreadable
- **WHEN** a caller with one budget calls the usage endpoint and the budget counter cannot be read
- **THEN** Core answers 500 with that body

#### Scenario: Budget too large to report, counter unreadable
- **WHEN** a caller whose one budget sets only a day amount of 2^53 calls the usage endpoint and the budget counter
  cannot be read
- **THEN** Core answers 200 with `{"limits":[]}`

### Requirement: Budget of the chain's initiator
Under the first requirement, a request made with a per-request key (an interceptor's or application's call to a model) SHALL be reported and rejected with the budget of the user who initiated the chain, in the cost object of that user's token.

#### Scenario: Application's call to the model
- **WHEN** a user whose token carries project `projA` and who has one budget in `projA` at 82% calls an application,
  and the application calls a model through `POST /openai/v1/responses` with its per-request key, and the upstream
  answers 200
- **THEN** Core's answer to the application carries `x-codex-primary-used-percent: 82`

### Requirement: Translated deployments read the budget
Under the first requirement, for a translated deployment, Core SHALL only read the caller's budgets, counted whatever models they cover. With exactly one budget it SHALL send its spend headers, even when that budget does not cover the model, and reject with the spend-cap 429 when it is used up. With several budgets, none, or an unreadable counter it SHALL forward the request with no spend header. The translator's call back to Core is checked and charged like any call to a model.

#### Scenario: Translated deployment, one budget
- **WHEN** a caller with one budget at 82% of its day amount sends a Responses request to a translated deployment
  that answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 82`

#### Scenario: Translated deployment, one budget exhausted
- **WHEN** a caller whose one budget is exhausted sends a Messages request to a translated deployment
- **THEN** Core answers the `billing_error` 429 without calling the translator

#### Scenario: Translated deployment, two budgets
- **WHEN** a caller with two budgets in their cost object sends a Responses request to a translated deployment that
  answers 200
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Translated deployment, several budgets all exhausted
- **WHEN** a caller whose two budgets are both used up sends a Responses request to a translated deployment
- **THEN** Core forwards the request to the translator and returns the translator's answer, with no `x-codex-*`
  header

#### Scenario: Translated deployment, no budget in the cost object
- **WHEN** a caller with no budget in their cost object sends a Messages request to a translated deployment
- **THEN** Core forwards the request to the translator and returns the translator's answer, with no
  `anthropic-ratelimit-unified-*` header

#### Scenario: Translated deployment, one budget for another model
- **WHEN** a caller whose one budget covers only `claude-opus`, at 40% of its month amount, sends a Messages request
  for another model to a translated deployment that answers 200
- **THEN** Core forwards the request to the translator, and the answer carries
  `anthropic-ratelimit-unified-overage-utilization: 0.40`

#### Scenario: Translated deployment, counter unreadable
- **WHEN** a caller with one budget sends a Responses request to a translated deployment and the budget counter
  cannot be read
- **THEN** Core forwards the request with no `x-codex-*` header

### Requirement: Shadow mode keeps the role caps
When `spendingLimits.mode` is `shadow`, the Messages, Responses and usage routes and translated deployments SHALL report and enforce the role cost limit as in `off`. For a JWT caller of a model that is not translated, Core SHALL also check the budgets, never reject on them, and answer 500 when a budget counter cannot be read, as the other model routes do.

#### Scenario: Responses in shadow
- **WHEN** a caller with one budget at 82% and a role day cost limit at 30% sends a Responses request that the
  upstream answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 30`

#### Scenario: Usage endpoint in shadow
- **WHEN** the same caller calls the usage endpoint
- **THEN** the active row has `percent` 30

#### Scenario: Translated deployment in shadow
- **WHEN** the same caller sends a Responses request to a translated deployment that answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 30`

#### Scenario: Budgets exhausted in shadow
- **WHEN** a caller whose budgets are all used up, under their role cost limit, sends a Messages request
- **THEN** the request reaches the upstream and `dial_budget_rejections_total` with `mode` `shadow` grows by one

#### Scenario: Counter unreadable in shadow
- **WHEN** a caller with one budget sends a Messages request and the budget counter cannot be read
- **THEN** Core answers 500 with `x-should-retry: true` without calling the upstream

## MODIFIED Requirements

### Requirement: No Codex headers without a cost cap
Core SHALL NOT send any `x-codex-*` header when the caller's caps come from the role cost limit and the caller has no day, week or month cost limit, or only limits too large to enforce, or a limit of 0; when the caller's caps come from budgets and the caller does not have exactly one budget in their cost object, or that budget sets no window below 2^53; or when the upstream answer is not 200.

#### Scenario: Uncapped caller
- **WHEN** a caller whose role sets no day, week or month cost limit sends a Responses request
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Upstream error
- **WHEN** a capped caller sends a Responses request and the upstream answers 400
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Several budgets
- **WHEN** `spendingLimits.mode` is `enforce` and a JWT caller with two budgets in their cost object sends a
  Responses request that the upstream answers 200
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Budget too large to report
- **WHEN** `spendingLimits.mode` is `enforce` and a JWT caller whose one budget sets only a day amount of 2^53 sends
  a Responses request that the upstream answers 200
- **THEN** the answer carries no `x-codex-*` header

### Requirement: Unreadable spend record
When the caller's caps come from the role cost limit, the caller has an enforced cost limit and Core cannot read the caller's spend, Core SHALL reject the Responses request with 429 and a DIAL error body that says the spend limit could not be read, without `retry-after` and without any `x-codex-*` header. Any other failure of the limit check SHALL keep today's 500.

#### Scenario: Cost-limited caller, spend unreadable
- **WHEN** a caller with a day cost limit sends a Responses request and the spend counter cannot be read
- **THEN** Core answers 429 without calling the upstream, and the `error.type` is not `usage_limit_reached`

#### Scenario: Limit check fails otherwise
- **WHEN** a caller with no cost limit sends a Responses request and the limit check fails
- **THEN** Core answers 500 without calling the upstream
