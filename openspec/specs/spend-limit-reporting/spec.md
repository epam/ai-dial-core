# spend-limit-reporting Specification

## Purpose

How Core reports a caller's DIAL spend caps to coding clients (Claude Code on `/anthropic`, Codex CLI on the
Responses API), in the response headers, error bodies and usage answers those clients read, so the client can warn the
user before a cap is reached and explain the rejection when it is. The caps are the role's day, week and month cost
limits, or, when `spendingLimits.mode` is `enforce`, the caller's budget.

## Requirements

### Requirement: Codex limit headers on a successful Responses answer
On a 200 answer to `POST /openai/v1/responses` or `POST /openai/responses`, Core SHALL send `x-codex-primary-used-percent`, `x-codex-primary-window-minutes` and `x-codex-primary-reset-at` for the caller's reported spend cap, and the same three `x-codex-secondary-*` headers for the fullest of the remaining capped windows. The headers SHALL be sent before the first body byte, for streaming and non-streaming answers.

#### Scenario: Two capped windows, streaming
- **WHEN** a caller with a day cap of $10 at $8.20 spent and a month cap of $100 at $40 spent sends a streaming
  Responses request and the upstream answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 82`, `x-codex-primary-window-minutes: 1440`,
  `x-codex-primary-reset-at` equal to the next day reset in Unix seconds, `x-codex-secondary-used-percent: 40`,
  `x-codex-secondary-window-minutes: 43200` and `x-codex-secondary-reset-at` equal to the next month reset

#### Scenario: One capped window, non-streaming
- **WHEN** a caller whose only cost cap is a week cap at 30% sends a non-streaming Responses request and the upstream
  answers 200
- **THEN** the answer carries the three `x-codex-primary-*` headers with `x-codex-primary-window-minutes: 10080` and
  no `x-codex-secondary-*` header

#### Scenario: Unversioned path
- **WHEN** the same caller sends the request to `POST /openai/responses`
- **THEN** the answer carries the same headers as on `/openai/v1/responses`

### Requirement: Codex header values
`used-percent` SHALL be the spent share of the window's limit as a whole percent, rounded down. `window-minutes` SHALL be 1440 for a day, 10080 for a week and 43200 for a month. `reset-at` SHALL be the window's next reset in Unix seconds. The values SHALL reflect the spend before this request.

#### Scenario: Percent rounded down
- **WHEN** a caller has spent $7.499 of a $10 day cap
- **THEN** `x-codex-primary-used-percent` is `74`

#### Scenario: State before the request
- **WHEN** a caller at 60% of a day cap sends a request that costs another 10%
- **THEN** that request's answer reports `60`, and the caller's next request reports `70`

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

### Requirement: Upstream Codex headers removed
Core SHALL remove every response header whose name starts with `x-codex-` (case-insensitive) that the upstream sends on a Responses answer, whatever its status, before adding its own. The pass of an alias SHALL NOT remove them (requirement "Spend headers through an alias").

#### Scenario: Upstream sends its own limits
- **WHEN** the upstream answers 200 with `x-codex-primary-used-percent: 5` to an uncapped caller
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Upstream headers replaced
- **WHEN** the upstream answers 200 with `x-codex-primary-used-percent: 5` to a caller at 82% of a day cap
- **THEN** the answer carries `x-codex-primary-used-percent: 82` once

### Requirement: Reached spend cap rejected in the Codex format
When a caller's spend cap is reached, Core SHALL reject a Responses request before contacting the upstream with 429, `retry-after` (seconds until the cap resets), the `x-codex-primary-*` headers for that cap with `used-percent` 100, and the body `{"error":{"type":"usage_limit_reached","code":"429","message":…,"display_message":…,"resets_at":…,"limit_window_minutes":…}}`.

#### Scenario: Day cap reached
- **WHEN** a caller who has spent $10 of a $10 day cap sends a Responses request
- **THEN** Core answers 429 without calling the upstream, with `error.type` `usage_limit_reached`, `error.resets_at`
  equal to the next day reset in Unix seconds, `error.limit_window_minutes` `1440`, an `error.message` that names
  the day limit and its reset time, an `error.display_message` of `You've exceeded your daily cost limit`,
  `x-codex-primary-used-percent: 100` and a `retry-after` that counts down to that reset

#### Scenario: Reached spend cap wins over a token limit
- **WHEN** a caller has reached both a day spend cap and the model's per-minute token limit
- **THEN** Core answers with the `usage_limit_reached` 429 of the spend cap

### Requirement: Other limits keep today's answer
When a request is rejected by a token, request or per-minute cost limit, and no spend cap is reached, Core SHALL answer as it does today: 429 with the DIAL error body and `retry-after`, and no `x-codex-*` header.

#### Scenario: Rolling token limit
- **WHEN** a caller under every spend cap exceeds the model's per-minute token limit
- **THEN** Core answers 429 with `retry-after`, an `error.code` of `429` and no `error.type` of `usage_limit_reached`

### Requirement: Unreadable spend record
When the caller's caps come from the role cost limit, the caller has an enforced cost limit and Core cannot read the caller's spend, Core SHALL reject the Responses request with 429 and a DIAL error body that says the spend limit could not be read, without `retry-after` and without any `x-codex-*` header. Any other failure of the limit check SHALL keep today's 500.

#### Scenario: Cost-limited caller, spend unreadable
- **WHEN** a caller with a day cost limit sends a Responses request and the spend counter cannot be read
- **THEN** Core answers 429 without calling the upstream, and the `error.type` is not `usage_limit_reached`

#### Scenario: Limit check fails otherwise
- **WHEN** a caller with no cost limit sends a Responses request and the limit check fails
- **THEN** Core answers 500 without calling the upstream

### Requirement: Calls made with a per-request key
Core SHALL apply the Codex headers and the 429 answers above to a Responses request made with a per-request key (an interceptor's or application's call to a model) the same way as to a client's own request, using the spend caps of the user or key that initiated the chain.

#### Scenario: Application's call to the model
- **WHEN** a user at 82% of a day cap calls an application, and the application calls a model through
  `POST /openai/v1/responses` with its per-request key, and the upstream answers 200
- **THEN** Core's answer to the application carries `x-codex-primary-used-percent: 82`

#### Scenario: Application's call over the cap
- **WHEN** the user's day cap is reached while the application handles the request, and the application then calls
  the model
- **THEN** Core answers the application's call with the `usage_limit_reached` 429

### Requirement: Translated deployments
For a Responses request to a deployment that Core serves through a translator, whose limits are checked and charged on the translator's call back to Core, Core SHALL only read the caller's spend caps: it SHALL send the Codex headers and reject a reached cap as above, and SHALL forward the request with no `x-codex-*` header when the spend cannot be read.

#### Scenario: Translated deployment at 82%
- **WHEN** a caller at 82% of a day cap sends a Responses request to a translated deployment that answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 82`, and the outer request is not charged

#### Scenario: Translated deployment over the cap
- **WHEN** a caller who has reached a day cap sends a Responses request to a translated deployment
- **THEN** Core answers the `usage_limit_reached` 429 without calling the translator

### Requirement: Budgets decide the caps in enforce mode
When `spendingLimits.mode` is `enforce`, for a JWT caller of a model on `POST /anthropic/v1/messages`, `POST /openai/v1/responses` and `POST /openai/responses`, and for a JWT caller of `GET /anthropic/api/oauth/usage`, Core SHALL take the spend caps from the caller's budget in the effective cost object and SHALL NOT read or enforce the role cost limit. A JWT caller of anything else SHALL get no caps. API key callers and other modes keep the role limit.

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
Under the first requirement, for a deployment that is not translated, when the budget that would pay (indicated, default or every walk candidate) has no room in a window it sets, Core SHALL reject the request before contacting the upstream with the route's spend-cap 429: `billing_error` on Messages, `usage_limit_reached` on Responses. It SHALL name the used-up window and reset of the budget that can pay again first, the earlier by priority on a tie, and SHALL win over a token or request limit.

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

### Requirement: No budget for the model
Under the requirement "Budgets decide the caps in enforce mode", for a deployment that is not translated, when no budget of the caller's cost object covers the model, Core SHALL reject the request before contacting the upstream. The message SHALL be `No DIAL spend limit is assigned for <model> in project <cost object>. Ask your DIAL admin to assign one.` The answer SHALL carry `x-should-retry: false`, no `retry-after` and no spend header.

#### Scenario: Budget for another model only
- **WHEN** a caller's only budget in their cost object covers only `claude-opus` and they send a Messages request
  for another model
- **THEN** Core answers 402 without calling the upstream, with the Anthropic error body of `error.type`
  `billing_error` and an `error.message` of
  `No DIAL spend limit is assigned for <model> in project <cost object>. Ask your DIAL admin to assign one.` that
  names that model and the cost object, with `x-should-retry: false`, and with no `retry-after` and no
  `anthropic-ratelimit-unified-*` header

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

### Requirement: Spend headers through an alias
On the pass of an alias, Core SHALL leave the `anthropic-ratelimit-*` headers of a Messages answer and the `x-codex-*` headers of a Responses answer as the target pass sent them, whatever the status, and SHALL add none of its own. A router in decision mode SHALL be served as the candidate it chooses, so its pass follows this rule when the candidate is an alias.

#### Scenario: Messages through an alias
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller with one budget at 82% of its day amount sends
  `POST /anthropic/v1/messages` with `"model": "alias-m1"`, an alias of the model `m1`, and the upstream of `m1`
  answers 200
- **THEN** the answer carries `anthropic-ratelimit-unified-overage-utilization: 0.82` once

#### Scenario: Responses through an alias and a router
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller with one budget at 82% of its day amount sends
  `POST /openai/v1/responses` with `"model": "strong"`, an alias of the router `r1` in decision mode, `r1` answers
  `{"candidates": ["alias-m1"]}`, and the upstream of `m1` answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 82` once

#### Scenario: Upstream headers removed behind the alias
- **WHEN** an uncapped caller sends `POST /openai/v1/responses` with `"model": "alias-m1"` and the upstream of `m1`
  answers 200 with `x-codex-primary-used-percent: 5`
- **THEN** the answer carries no `x-codex-*` header

### Requirement: No limit check on an alias pass
On a Messages or Responses call, the pass of an alias SHALL NOT read the caller's spend caps, SHALL NOT check or count any token, request or cost limit, and SHALL NOT refuse the call on a limit. The target pass SHALL check the limits as on a direct call of the target. The caller SHALL get the refusal a direct call of the target gets, as the requirement "No budget through an alias" states for the no-budget refusal.

#### Scenario: Budget used up, through an alias and a router
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller whose one budget is used up sends
  `POST /anthropic/v1/messages` with `"model": "strong"`, an alias of the router `r1` in decision mode, and `r1`
  answers `{"candidates": ["alias-m1"]}` to every decide request
- **THEN** once `r1` has been asked three times (its default `maxDecisions`), Core answers 429 with `error.type`
  `billing_error`, `anthropic-ratelimit-unified-status: rejected` and `x-should-retry: false`, and the upstream of
  `m1` is not called

#### Scenario: Role cost limit reached, budget not
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller whose role day cost limit is reached and whose one budget
  is at 10% of its day amount sends `POST /anthropic/v1/messages` with `"model": "alias-m1"`, and the upstream of `m1`
  answers 200
- **THEN** the answer is 200 and carries `anthropic-ratelimit-unified-overage-utilization: 0.10`

#### Scenario: Request limit under the alias's name
- **WHEN** the caller's role has a `requestDay` limit of 1 under the name `alias-m1`, and the caller sends two
  `POST /anthropic/v1/messages` requests with `"model": "alias-m1"` that the upstream of `m1` answers 200
- **THEN** both answers are 200

### Requirement: No budget on the Responses routes
On `POST /openai/v1/responses` and `POST /openai/responses`, the refusal of "No budget for the model" SHALL depend on the caller:
- to a caller outside Core that uses a per-request key (an application, an interceptor, a translator, a router or an origin plugin), 429 with the DIAL error body and no `X-DIAL-REFUSAL`;
- to the client's own request, 400 with the message as a plain-text body (`Content-Type: text/plain`), with no `x-codex-*` and no `X-DIAL-REFUSAL`, also for a streaming request.

#### Scenario: Budget for another model only, Responses
- **WHEN** a caller whose token carries project `projA` has only a budget in `projA` that covers only `gpt-other`,
  and sends a Responses request for `gpt-spend` to `POST /openai/v1/responses` or to `POST /openai/responses`
- **THEN** Core answers 400 without calling the upstream, with `Content-Type: text/plain` and the body
  `No DIAL spend limit is assigned for gpt-spend in project projA. Ask your DIAL admin to assign one.`, with
  `x-should-retry: false`, and with no `retry-after`, no `x-codex-*` and no `X-DIAL-REFUSAL` header

#### Scenario: Streaming Responses request
- **WHEN** the caller of the scenario above sends the same request with `"stream": true`
- **THEN** Core answers the same 400 with the same plain-text body, not an event stream

#### Scenario: Responses call with a per-request key
- **WHEN** the caller of the scenarios above calls an application, and the application calls `gpt-spend`, and then
  `alias-m1`, through `POST /openai/v1/responses` with its per-request key
- **THEN** Core answers each of the application's calls with 429 and the DIAL error body whose `error.message` is
  the message for that call's model and `projA`, with `x-should-retry: false`, and with no `retry-after`, no
  `x-codex-*` and no `X-DIAL-REFUSAL` header

#### Scenario: Router called directly, Responses
- **WHEN** a caller whose only budget covers only `claude-other` sends `POST /openai/v1/responses` with
  `"model": "r1"`, and `r1` answers `{"candidates": ["m1"]}` to every decide request, and in a second call
  `{"candidates": ["alias-m1"]}`
- **THEN** in each call, once `r1` has been asked three times, Core answers the 400 with the message for `m1`, and
  the upstream of `m1` is not called

### Requirement: No budget through an alias
Through an alias, the client SHALL get the no-budget answer of a direct call of the alias's target. This SHALL hold also when the target is a router in decision mode, and when a router in decision mode chose the alias; in both cases, once no decision is left. A background Responses request (`"background": true`) through an alias SHALL get the 429 of a caller that uses a per-request key, since Core reaches its target pass over HTTP.

#### Scenario: No budget through an alias, Responses
- **WHEN** a caller whose only budget covers only `claude-other` sends `POST /openai/v1/responses` with
  `"model": "alias-m1"`, an alias of the model `m1`, streaming and not
- **THEN** Core answers 400 with `Content-Type: text/plain` and the message for `m1` as the body, with
  `x-should-retry: false`, and with no `retry-after`, no `x-codex-*` and no `X-DIAL-REFUSAL` header, and the
  upstream of `m1` is not called

#### Scenario: No budget through an alias, background Responses request
- **WHEN** the same caller sends `POST /openai/v1/responses` with `"model": "alias-m1"` and `"background": true`, so
  that the alias reaches its target over HTTP to Core's own address
- **THEN** Core answers 429 with the DIAL error body whose `error.message` is the message for `m1`, with
  `x-should-retry: false`, and with no `retry-after`, no `x-codex-*` and no `X-DIAL-REFUSAL` header, and the upstream
  of `m1` is not called

#### Scenario: No budget through an alias, Messages
- **WHEN** a caller whose only budget covers only `claude-other` sends `POST /anthropic/v1/messages` with
  `"model": "alias-m1"`, streaming and not
- **THEN** Core answers the 402 of "Budget for another model only" with the message for `m1`, without
  `X-DIAL-REFUSAL`, and the upstream of `m1` is not called

#### Scenario: No budget, through an alias and a router, Messages
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller whose only budget covers only `claude-opus` sends
  `POST /anthropic/v1/messages` with `"model": "strong"`, an alias of the router `r1` in decision mode, and `r1`
  answers `{"candidates": ["alias-m1"]}` to every decide request
- **THEN** once `r1` has been asked three times (its default `maxDecisions`), Core answers the 402 of "Budget for
  another model only" with the message for `m1`, and the upstream of `m1` is not called

#### Scenario: No budget, through an alias and a router, Responses
- **WHEN** a caller whose only budget covers only `claude-other` sends `POST /openai/v1/responses` with
  `"model": "strong"`, and `r1` answers `{"candidates": ["alias-m1"]}` to every decide request
- **THEN** once `r1` has been asked three times, Core answers 400 with `Content-Type: text/plain` and the message
  for `m1` as the body, with `x-should-retry: false`, and with no `retry-after`, no `x-codex-*` and no
  `X-DIAL-REFUSAL` header, and the upstream of `m1` is not called

#### Scenario: Router behind an alias chooses the model, Responses
- **WHEN** the same caller sends `POST /openai/v1/responses` with `"model": "strong"`, and `r1` answers
  `{"candidates": ["m1"]}` to every decide request
- **THEN** once `r1` has been asked three times, Core answers the same 400, and the upstream of `m1` is not called

### Requirement: No budget behind an interceptor
When an interceptor runs in front of the model, global interceptors included, Core SHALL make the no-budget refusal on the interceptor's call, as to a caller that uses a per-request key, and the client SHALL get what the interceptor answers.

#### Scenario: Interceptor in front of the model behind an alias, Responses
- **WHEN** a caller whose only budget covers only `claude-other` sends `POST /openai/v1/responses` with
  `"model": "alias-icpt"`, an alias of the model `m-icpt`, whose interceptor `icpt` calls Core back for `m-icpt` and
  answers with what it got
- **THEN** the interceptor's call gets the 429 without `X-DIAL-REFUSAL`, and the client gets that 429, not a 400

### Requirement: The no-budget mark stays inside Core
On the Responses routes, `POST /anthropic/v1/messages` and its `count_tokens`, Core SHALL send `X-DIAL-REFUSAL` to no caller outside Core: not to a client, and not to an application's, interceptor's or translator's call. The header in an answer from a model's upstream, an application or an interceptor SHALL NOT change Core's answer, and Core SHALL remove it before relaying that answer.

#### Scenario: Header from an interceptor behind an alias
- **WHEN** a caller with a budget that covers `m-icpt` sends `POST /openai/v1/responses` with
  `"model": "alias-icpt"`, and the interceptor `icpt` answers 429 itself, with `X-DIAL-REFUSAL: no_budget` and a JSON
  error body
- **THEN** the client gets that 429 with its body and without `X-DIAL-REFUSAL`, not a 400

#### Scenario: Header from a model's upstream or an application
- **WHEN** a caller with a budget that covers `gpt-spend` and `m1` sends Responses requests for `gpt-spend`, for
  `alias-m1` and for the application `app-responses`, each streaming and not, and each upstream answers 200 with
  `X-DIAL-REFUSAL: no_budget`
- **THEN** every answer is 200 without `X-DIAL-REFUSAL`

#### Scenario: Header from a model's upstream, Messages
- **WHEN** a caller with a budget that covers every model sends Messages requests for `claude-spend`, for `alias-m1`
  and for the application `app-claude`, each streaming and not, and a `count_tokens` request for `claude-spend`, and
  each upstream answers 200 with `X-DIAL-REFUSAL: no_budget`
- **THEN** every answer is 200 without `X-DIAL-REFUSAL`

### Requirement: No budget on the Messages route
On `POST /anthropic/v1/messages`, the refusal of "No budget for the model" SHALL be 402 with the Anthropic error body of `error.type` `billing_error` to the client's own request, streaming or not, and 429 with the DIAL error body to a caller outside Core that uses a per-request key. Neither SHALL carry `X-DIAL-REFUSAL` or an `anthropic-ratelimit-unified-*` header.

#### Scenario: Streaming Messages request
- **WHEN** the caller of "Budget for another model only" sends the same request with `"stream": true`
- **THEN** Core answers the same 402 with the same JSON body, not an event stream

#### Scenario: Messages call with a per-request key
- **WHEN** a caller whose only budget covers only `claude-other` calls an application, and the application calls
  `alias-m1` through `POST /anthropic/v1/messages` with its per-request key
- **THEN** Core answers the application's call with 429 and the DIAL error body whose `error.message` is the message
  for `m1`, with `x-should-retry: false`, and with no `retry-after`, no `anthropic-ratelimit-unified-*` and no
  `X-DIAL-REFUSAL` header

#### Scenario: Router called directly, Messages
- **WHEN** a caller whose only budget covers only `claude-other` sends `POST /anthropic/v1/messages` with
  `"model": "r1"`, and `r1` answers `{"candidates": ["m1"]}` to every decide request, and in a second call
  `{"candidates": ["alias-m1"]}`
- **THEN** in each call, once `r1` has been asked three times, Core answers the 402 with the message for `m1`, and the
  upstream of `m1` is not called
