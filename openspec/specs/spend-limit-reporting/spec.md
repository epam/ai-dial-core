# spend-limit-reporting Specification

## Purpose

How Core reports a caller's DIAL day, week and month cost limits (spend caps) to coding clients, in the response
headers and error bodies those clients read, so the client can warn the user before a cap is reached and explain the
rejection when it is.

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
Core SHALL NOT send any `x-codex-*` header when the caller has no day, week or month cost limit, or only limits too large to enforce, or a limit of 0, or when the upstream answer is not 200.

#### Scenario: Uncapped caller
- **WHEN** a caller whose role sets no day, week or month cost limit sends a Responses request
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Upstream error
- **WHEN** a capped caller sends a Responses request and the upstream answers 400
- **THEN** the answer carries no `x-codex-*` header

### Requirement: Upstream Codex headers removed
Core SHALL remove every response header whose name starts with `x-codex-` (case-insensitive) that the upstream sends on a Responses answer, whatever its status, before adding its own.

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
When the caller has an enforced cost limit and Core cannot read the caller's spend, Core SHALL reject the Responses request with 429 and a DIAL error body that says the spend limit could not be read, without `retry-after` and without any `x-codex-*` header. Any other failure of the limit check SHALL keep today's 500.

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

### Requirement: Anthropic limit headers on a successful Messages answer
On a 200 answer to `POST /anthropic/v1/messages` for a caller with a reported spend cap, Core SHALL send `anthropic-ratelimit-unified-status`, `anthropic-ratelimit-unified-representative-claim: overage`, `anthropic-ratelimit-unified-overage-status`, `anthropic-ratelimit-unified-overage-utilization`, `anthropic-ratelimit-unified-reset` and `anthropic-ratelimit-unified-overage-reset`. The headers SHALL be sent before the first body byte, for streaming and non-streaming answers.

#### Scenario: Non-streaming at 82% of a day cap
- **WHEN** a caller whose role sets `"costLimit": {"day": "10", "week": "100", "month": "1000"}` and who has spent
  $8.20 today sends a non-streaming Messages request and the upstream answers 200
- **THEN** the answer carries the upstream's body, `anthropic-ratelimit-unified-status: allowed_warning`,
  `anthropic-ratelimit-unified-representative-claim: overage`, `anthropic-ratelimit-unified-overage-status:
  allowed_warning`, `anthropic-ratelimit-unified-overage-utilization: 0.82`,
  `anthropic-ratelimit-unified-overage-surpassed-threshold: 0.75`, and `anthropic-ratelimit-unified-reset` and
  `anthropic-ratelimit-unified-overage-reset` both equal to the next day reset in Unix seconds

#### Scenario: Streaming
- **WHEN** the same caller sends a streaming Messages request and the upstream answers 200 with an event stream
- **THEN** the response headers carry the same values, ahead of the stream, which ends with `message_stop`

### Requirement: Reported spend cap
Core SHALL report one of the caller's capped day, week and month cost limits: while none is reached, the fullest (highest spent/limit); once any is reached (spent >= limit), the reached one that resets last. A tie SHALL go to the longer period.

#### Scenario: Fullest window
- **WHEN** a caller has spent $3 of a $10 day cap and $40 of a $50 week cap, and the upstream answers 200
- **THEN** `anthropic-ratelimit-unified-overage-utilization` is `0.80` and `anthropic-ratelimit-unified-reset` is the
  next week reset

#### Scenario: Tie on fullness
- **WHEN** a caller has spent $5 of a $10 day cap and $50 of a $100 week cap, and the upstream answers 200
- **THEN** `anthropic-ratelimit-unified-reset` is the next week reset

#### Scenario: Several reached windows
- **WHEN** a caller has spent $10 of a $10 day cap and $100 of a $100 week cap
- **THEN** the 429 carries `anthropic-ratelimit-unified-overage-period: weekly` and the week's reset

### Requirement: Anthropic caps from the role cost limits
The cost limits of `POST /anthropic/v1/messages` and `GET /anthropic/api/oauth/usage` SHALL be, per window, the largest `costLimit` among the caller's roles that set one (an unset window counting as unlimited), else the `default` role's, against the spend record of the caller's initiator bucket. Core SHALL NOT check budgets on `POST /anthropic/v1/messages`, whatever `spendingLimits.mode`.

#### Scenario: Largest role limit per window
- **WHEN** a caller has role `r1` with `"costLimit": {"day": "10"}` and role `r2` with
  `"costLimit": {"day": "20", "week": "50"}`, and has spent $8.20 this week, all of it today, and sends
  `GET /anthropic/api/oauth/usage`
- **THEN** `limits` holds one row, `daily` with `percent` 41

#### Scenario: Budgets not checked
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller has no budget that can pay, its role's day cap is $10 at
  $5 spent, and it sends a Messages request that the upstream answers 200
- **THEN** the answer is 200 with `anthropic-ratelimit-unified-overage-utilization: 0.50`

### Requirement: Anthropic utilization and reset values
`anthropic-ratelimit-unified-overage-utilization` SHALL be the reported cap's spent/limit rounded down to two decimals and at most `0.99`. `anthropic-ratelimit-unified-reset` and `anthropic-ratelimit-unified-overage-reset` SHALL both be the cap's next reset (the next window start under `rateLimitSchedule`) in Unix seconds. The values SHALL reflect the spend before this request.

#### Scenario: Rounded down
- **WHEN** a caller has spent $7.499 of a $10 day cap
- **THEN** `anthropic-ratelimit-unified-overage-utilization` is `0.74`

#### Scenario: At most 0.99
- **WHEN** a caller has spent $9.997 of a $10 day cap
- **THEN** `anthropic-ratelimit-unified-overage-utilization` is `0.99`

#### Scenario: State before the request
- **WHEN** a caller at 60% of a day cap sends a request that costs another 10%
- **THEN** that request's answer reports `0.60`, and the caller's next request reports `0.70`

### Requirement: Anthropic status and thresholds
`anthropic-ratelimit-unified-status` and `anthropic-ratelimit-unified-overage-status` SHALL be `allowed` below 75% of the reported cap and `allowed_warning` from 75%. `anthropic-ratelimit-unified-overage-surpassed-threshold` SHALL be `0.95` from 95%, `0.75` from 75%, and absent below 75%.

#### Scenario: Below 75%
- **WHEN** a caller has spent $5 of a $10 day cap and the upstream answers 200
- **THEN** both status headers are `allowed`, `anthropic-ratelimit-unified-overage-utilization` is `0.50` and there is
  no `anthropic-ratelimit-unified-overage-surpassed-threshold`

#### Scenario: Exactly 75%
- **WHEN** a caller has spent $7.50 of a $10 day cap
- **THEN** both status headers are `allowed_warning` and `anthropic-ratelimit-unified-overage-surpassed-threshold` is
  `0.75`

#### Scenario: From 95%
- **WHEN** a caller has spent $9.60 of a $10 day cap
- **THEN** both status headers are `allowed_warning`, the utilization is `0.96` and
  `anthropic-ratelimit-unified-overage-surpassed-threshold` is `0.95`

### Requirement: No Anthropic limit headers without a cost cap
Core SHALL NOT send any `anthropic-ratelimit-unified-*` header on a Messages answer that is not 200, or when each of the caller's day, week and month cost limits is unset, 0, or 2^53 or more. A window whose limit is 0 or 2^53 or more SHALL never be the reported cap, nor SHALL the per-minute cost limit.

#### Scenario: Uncapped caller
- **WHEN** a caller none of whose roles, nor the `default` role, sets `costLimit` sends a Messages request and the
  upstream answers 200
- **THEN** the answer carries no `anthropic-ratelimit-unified-*` header

#### Scenario: Only a per-minute cost limit
- **WHEN** a caller whose role sets only `"costLimit": {"minute": "5"}` sends a Messages request and the upstream
  answers 200
- **THEN** the answer carries no `anthropic-ratelimit-unified-*` header

#### Scenario: Only a cap of 0
- **WHEN** a caller whose role sets only `"costLimit": {"day": "0"}` and who has no spend record sends a Messages
  request and the upstream answers 200
- **THEN** the answer carries no `anthropic-ratelimit-unified-*` header

#### Scenario: Upstream error
- **WHEN** a caller at 82% of a day cap sends a Messages request to a pass-through model and the upstream answers 400
  with an Anthropic error body
- **THEN** the client gets the 400 and the upstream's body unchanged, with no `anthropic-ratelimit-*` header

### Requirement: Upstream Anthropic rate-limit headers removed
Core SHALL remove every header whose name starts with `anthropic-ratelimit-` (case-insensitive) from an upstream answer it forwards to `POST /anthropic/v1/messages`, whatever its status, before adding its own.

#### Scenario: Upstream sends its own limits
- **WHEN** the upstream answers 200 with `anthropic-ratelimit-tokens-remaining: 5` and
  `anthropic-ratelimit-unified-status: allowed` to a caller at 82% of a day cap
- **THEN** the answer carries no `anthropic-ratelimit-tokens-remaining` and carries
  `anthropic-ratelimit-unified-status: allowed_warning` once

#### Scenario: Uncapped caller
- **WHEN** the upstream answers 200 with `Anthropic-RateLimit-Tokens-Remaining: 5` to a caller with no cost limit
- **THEN** the answer carries no `anthropic-ratelimit-*` header

### Requirement: count_tokens neither limited nor reported
Core SHALL check none of the caller's limits on `POST /anthropic/v1/messages/count_tokens` and SHALL add no `anthropic-ratelimit-unified-*` header to its answer; the upstream's `anthropic-ratelimit-*` headers SHALL reach the client.

#### Scenario: Caller over the day cap counts tokens
- **WHEN** a caller who has spent $10 of a $10 day cap sends `POST /anthropic/v1/messages/count_tokens` and the
  upstream answers 200 `{"input_tokens":5}` with `anthropic-ratelimit-tokens-remaining: 5`
- **THEN** the client gets that 200 with `anthropic-ratelimit-tokens-remaining: 5` and no
  `anthropic-ratelimit-unified-*` header

### Requirement: Reached spend cap rejected in the Anthropic format
When the caller's reported spend cap is reached, Core SHALL reject `POST /anthropic/v1/messages` before contacting the upstream with 429, `retry-after` (seconds until the cap resets, rounded up), `x-should-retry: false`, the rejected-cap `anthropic-ratelimit-unified-*` headers and the body `{"type":"error","error":{"type":"billing_error","message":"DIAL spend limit reached (<period>; resets <yyyy-MM-dd HH:mm> UTC)"}}`.

#### Scenario: Day cap reached
- **WHEN** a caller who has spent $10 of a $10 day cap sends a Messages request
- **THEN** Core answers 429 without calling the upstream, with `content-type: application/json`,
  `x-should-retry: false`, a `retry-after` that counts down to the next day reset,
  `anthropic-ratelimit-unified-status: rejected`, `anthropic-ratelimit-unified-reset` and
  `anthropic-ratelimit-unified-overage-reset` equal to that reset in Unix seconds,
  `anthropic-ratelimit-unified-overage-utilization: 1`, `anthropic-ratelimit-unified-overage-surpassed-threshold: 1`,
  `anthropic-ratelimit-unified-overage-period: daily`,
  `anthropic-ratelimit-unified-overage-disabled-reason: org_spend_cap_reached`, no
  `anthropic-ratelimit-unified-representative-claim` and no `anthropic-ratelimit-unified-overage-status`

#### Scenario: Message names the reset in UTC
- **WHEN** the day cap that is reached resets at `2026-10-24T00:00:00Z`
- **THEN** the body is
  `{"type":"error","error":{"type":"billing_error","message":"DIAL spend limit reached (daily; resets 2026-10-24 00:00 UTC)"}}`

#### Scenario: Reached spend cap wins over a token limit
- **WHEN** a caller has reached both a day spend cap and the model's per-minute token limit
- **THEN** Core answers the `billing_error` 429 with `x-should-retry: false` and
  `anthropic-ratelimit-unified-overage-disabled-reason: org_spend_cap_reached`

#### Scenario: Not counted toward the request limits
- **WHEN** a caller who has reached a day spend cap sends a Messages request
- **THEN** Core does not add the request to the caller's request-limit record of the model

### Requirement: Unreadable spend record on the Messages route
When the caller has an enforced cost limit (a minute, day, week or month `costLimit` below 2^53, 0 included), Core cannot read the caller's spend, and no token or request limit rejects the request, Core SHALL answer `POST /anthropic/v1/messages` with 429, `x-should-retry: false`, `anthropic-ratelimit-unified-overage-disabled-reason: fetch_error` and a `billing_error` body, without calling the upstream.

#### Scenario: Day-capped caller, spend unreadable
- **WHEN** a caller with a day cost limit sends a Messages request and the spend counter cannot be read
- **THEN** Core answers 429 without calling the upstream, with `x-should-retry: false`,
  `anthropic-ratelimit-unified-overage-disabled-reason: fetch_error`, `content-type: application/json`, no other
  `anthropic-ratelimit-*` header, no `retry-after`, and the body
  `{"type":"error","error":{"type":"billing_error","message":"DIAL could not read your spend limit; request rejected"}}`

#### Scenario: Only a per-minute cost limit
- **WHEN** a caller whose only cost limit is `"minute": "5"` sends a Messages request and the spend counter cannot be
  read
- **THEN** Core answers the same `fetch_error` 429

#### Scenario: A token limit decides
- **WHEN** a caller with a day cost limit is over the model's per-minute token limit and the spend counter cannot be
  read
- **THEN** Core answers the `rate_limit_error` 429 of the token limit

### Requirement: Limit check failure on the Messages route
Core SHALL answer `POST /anthropic/v1/messages` with 500, `x-should-retry: true` and the body `{"type":"error","error":{"type":"api_error","message":"DIAL could not check your limits; please retry"}}`, without calling the upstream, when the limit check fails for another reason than an unreadable spend record, or when the spend of a caller with no enforced cost limit cannot be read.

#### Scenario: Uncapped caller, spend unreadable
- **WHEN** a caller with no cost limit sends a Messages request and the spend counter cannot be read
- **THEN** Core answers 500 with `x-should-retry: true` and `error.type` `api_error`, without calling the upstream

#### Scenario: Token record unreadable
- **WHEN** a caller with a day cost limit sends a Messages request, the spend is read, and the model's token-limit
  record cannot be read
- **THEN** Core answers 500 with `error.type` `api_error`, not `billing_error`

### Requirement: Other limits rejected in the Anthropic format
When a token limit, a request limit, the per-minute cost limit or a cost limit of 0 rejects a Messages request and no spend cap is reached, Core SHALL answer 429 with `retry-after`, `x-should-retry: true` for a per-minute or hourly window and `false` for a daily, weekly or monthly one, no `anthropic-ratelimit-*` header, and the body `{"type":"error","error":{"type":"rate_limit_error","message":…}}`.

#### Scenario: Rolling token limit
- **WHEN** a model allows the caller 10 tokens per minute, the caller's first request is charged 20 tokens, and the
  caller sends a second request within the minute
- **THEN** Core answers 429 with `x-should-retry: true`, a `retry-after` from 1 to 60, a message that starts with
  `DIAL per-minute token limit for <model> reached; retry in`, and no `anthropic-ratelimit-unified-*` header

#### Scenario: Daily request limit
- **WHEN** a caller has used the model's daily request limit
- **THEN** Core answers 429 with `x-should-retry: false` and the message
  `DIAL daily request limit for <model> reached (resets <yyyy-MM-dd HH:mm> UTC)`

#### Scenario: Cost limit of 0
- **WHEN** a caller whose role sets `"costLimit": {"day": "0"}` and who has a spend record sends a Messages request
- **THEN** Core answers 429 `rate_limit_error`, not `billing_error`, with `x-should-retry: false` and the message
  `DIAL daily spend limit reached (resets <yyyy-MM-dd HH:mm> UTC)`

### Requirement: Other-limit message and retry-after
The message SHALL be `DIAL <window> token limit for <deployment> reached`, `DIAL <window> request limit for <deployment> reached` or `DIAL <window> spend limit reached`, `<window>` being `per-minute`, `hourly`, `daily`, `weekly` or `monthly`, then `; retry in <N> s` for a rolling window or ` (resets <yyyy-MM-dd HH:mm> UTC)` for a calendar one. `retry-after` SHALL be N, at least 1, or the seconds to the reset, rounded up.

#### Scenario: Rolling window in its last second
- **WHEN** a per-minute token limit rejects a request whose wait rounds down to 0 seconds
- **THEN** the answer carries `retry-after: 1` and a message that ends with `; retry in 1 s`

#### Scenario: Calendar window
- **WHEN** a daily token limit that resets at `2026-10-24T00:00:00Z` rejects a request at `2026-10-23T20:00:00.500Z`
- **THEN** the answer carries `retry-after: 14400` and a message that ends with `(resets 2026-10-24 00:00 UTC)`

#### Scenario: Per-minute cost limit
- **WHEN** the caller's per-minute cost limit rejects a request with a 4 second wait
- **THEN** the message is `DIAL per-minute spend limit reached; retry in 4 s`

### Requirement: Order of the Messages limit checks
Core SHALL check a Messages request's limits in this order: a reached spend cap, the model's token limits, its request limits, an unreadable spend record, then the per-minute cost limit and cost limits of 0. The first that rejects SHALL decide the answer; within one limit, the fired window with the longest wait SHALL decide, the longer window on a tie. A request that passes the request limits SHALL count toward them, even when a later check rejects it.

#### Scenario: Token limit before request limit
- **WHEN** a caller under every spend cap is over both the model's per-minute token limit and its hourly request limit
- **THEN** the message starts with `DIAL per-minute token limit for`

#### Scenario: Longest wait decides
- **WHEN** a caller is over both the model's per-minute and daily token limits
- **THEN** the message names the `daily` token limit and `x-should-retry` is `false`

#### Scenario: Counted toward the request limits before the cost check
- **WHEN** a caller who has sent 2 of the model's 5 hourly requests is rejected by its per-minute cost limit
- **THEN** a following `GET /v1/deployments/<model>/limits` reports `hourRequestStats.used` 3

### Requirement: Messages calls made with a per-request key
Core SHALL apply the Anthropic limit headers and the 429 and 500 answers above to a `POST /anthropic/v1/messages` request made with a per-request key (an interceptor's or application's call to a model) the same way as to a client's own request, using the spend caps of the user or key that initiated the chain.

#### Scenario: Application's call to the model
- **WHEN** a user at 82% of a day cap calls an application, and the application calls a model through
  `POST /anthropic/v1/messages` with its per-request key, and the upstream answers 200
- **THEN** Core's answer to the application carries `anthropic-ratelimit-unified-overage-utilization: 0.82`

#### Scenario: Application's call over the cap
- **WHEN** the user's day cap is reached while the application handles the request, and the application then calls
  the model through `POST /anthropic/v1/messages`
- **THEN** Core answers the application's call with the `billing_error` 429

### Requirement: Messages deployments served by a translator service
For a model whose `anthropicMessages` interface has `"mode": "translator"` and a translator with a `baseUrl`, whose limits are checked and charged on the translator's call back to Core (app-to-model-accounting, "Translated interface not limit-checked"), Core SHALL only read the caller's spend caps: it SHALL send the Anthropic limit headers on a 200 and reject a reached cap as above, and SHALL forward the request with no `anthropic-ratelimit-unified-*` header when the spend cannot be read.

#### Scenario: Translated model at 82%
- **WHEN** a caller at 82% of a day cap sends a Messages request to a translated model and the translator answers 200
  with `anthropic-ratelimit-tokens-remaining: 5`
- **THEN** the answer carries `anthropic-ratelimit-unified-overage-utilization: 0.82` and no
  `anthropic-ratelimit-tokens-remaining`

#### Scenario: Translated model over the cap
- **WHEN** a caller who has spent $10 of a $10 day cap sends a Messages request to a translated model
- **THEN** Core answers the `billing_error` 429 with `anthropic-ratelimit-unified-overage-period: daily` without
  calling the translator

#### Scenario: Spend unreadable
- **WHEN** a caller with a day cost limit sends a Messages request to a translated model and the spend counter cannot
  be read
- **THEN** Core forwards the request to the translator, and the answer carries no `anthropic-ratelimit-unified-*`
  header

### Requirement: Builtin-translated Messages models
For a model whose `anthropicMessages` interface uses a builtin translator, Core SHALL check the caller's limits on the request itself, in the order and with the headers and the 429 and 500 answers above, the `fetch_error` 429 included, and send nothing upstream when it rejects. Its error rewrite ("Anthropic error body for Core errors on the builtin path") and its charge ("Limits of a builtin translation charged once") are in anthropic-messages-translation.

#### Scenario: Builtin model at 82% of a day cap
- **WHEN** a caller who has spent $8.20 of a $10 day cap streams a Messages request to a model served by a builtin
  translator and the upstream answers 200
- **THEN** the answer carries `anthropic-ratelimit-unified-status: allowed_warning` and
  `anthropic-ratelimit-unified-overage-utilization: 0.82` ahead of the event stream

#### Scenario: Builtin model over the cap
- **WHEN** a caller who has spent $10 of a $10 day cap streams a Messages request to a model served by a builtin
  translator
- **THEN** Core answers 429 with the body and headers of the scenario "Day cap reached" of "Reached spend cap
  rejected in the Anthropic format", and the upstream receives no request

#### Scenario: Builtin model, spend unreadable
- **WHEN** a caller with a day cost limit sends a Messages request to a model served by a builtin translator and the
  spend counter cannot be read
- **THEN** Core answers the `fetch_error` 429 of the scenario "Day-capped caller, spend unreadable", and the upstream
  receives no request

#### Scenario: Builtin model over a token limit
- **WHEN** a caller under every spend cap is over the per-minute token limit of a model served by a builtin translator
- **THEN** Core answers the `rate_limit_error` 429 of "Other limits rejected in the Anthropic format", with
  `x-should-retry: true`, and the upstream receives no request

### Requirement: Usage endpoint answer
`GET /anthropic/api/oauth/usage` SHALL answer 200 with `cache-control: no-store` and `{"extra_usage":{"is_enabled":true,"monthly_limit":…,"used_credits":…,"utilization":…,"currency":"USD"},"limits":[…]}`. `extra_usage` SHALL describe the reported cap: `monthly_limit` and `used_credits` are its limit and spend in cents (rounded half up) whatever its period, `utilization` its spent share as a whole percent, rounded down, above 100 once over.

#### Scenario: At 82% of a day cap
- **WHEN** a caller whose role sets `"costLimit": {"day": "10", "week": "100", "month": "1000"}` and who has spent
  $8.20 today sends `GET /anthropic/api/oauth/usage`
- **THEN** Core answers 200 with `cache-control: no-store` and `extra_usage`
  `{"is_enabled":true,"monthly_limit":1000,"used_credits":820,"utilization":82,"currency":"USD"}`

#### Scenario: Over the cap
- **WHEN** a caller has spent $10.50 of a $10 day cap, the only cap
- **THEN** `extra_usage.utilization` is `105`

### Requirement: Usage rows
`limits` SHALL hold one row per capped day, week and month window, in that order: `{"kind":"spend","group":"daily"|"weekly"|"monthly","percent":…,"resets_at":…,"severity":…,"is_active":…}`, `percent` rounded down, `resets_at` the next reset as `yyyy-MM-ddTHH:mm:ss.SSSZ` in UTC, `severity` `critical` from 95%, `warning` from 75%, else `normal`, and `is_active` true only on the reported cap.

#### Scenario: Three capped windows
- **WHEN** the caller of the scenario "At 82% of a day cap" sends `GET /anthropic/api/oauth/usage`
- **THEN** `limits` holds a `daily` row with `percent` 82, `severity` `warning` and `is_active` true, a `weekly` row
  with `percent` 8, `severity` `normal` and `is_active` false, and a `monthly` row with `percent` 0, `severity`
  `normal` and `is_active` false

#### Scenario: Reset instant
- **WHEN** the day cap resets at midnight UTC on 2026-10-24
- **THEN** the `daily` row's `resets_at` is `2026-10-24T00:00:00.000Z`

#### Scenario: Critical
- **WHEN** a caller has spent $9.60 of a $10 day cap
- **THEN** the `daily` row's `severity` is `critical`

### Requirement: Usage endpoint callers
Core SHALL serve `GET /anthropic/api/oauth/usage` to any caller that the request-routing capability authenticates, with no other permission, from the caller's own caps. A caller with no reported cap SHALL get `{"limits":[]}`. A failure to read the spend SHALL give 500 with `x-should-retry: true` and `{"type":"error","error":{"type":"api_error","message":"Failed to get spend limits"}}`.

#### Scenario: Project key as a bearer token
- **WHEN** a caller sends `GET /anthropic/api/oauth/usage` with `Authorization: Bearer <project key>` of a capped
  project
- **THEN** Core answers 200 with that project's caps

#### Scenario: No cap
- **WHEN** a caller none of whose roles, nor the `default` role, sets `costLimit` sends
  `GET /anthropic/api/oauth/usage` with `api-key`
- **THEN** Core answers 200 with the body `{"limits":[]}`

#### Scenario: Spend unreadable
- **WHEN** a capped caller sends `GET /anthropic/api/oauth/usage` and the spend counter cannot be read
- **THEN** Core answers 500 with `x-should-retry: true`, `content-type: application/json` and the body
  `{"type":"error","error":{"type":"api_error","message":"Failed to get spend limits"}}`

### Requirement: Usage agrees with the Messages headers
For the same spend, Core SHALL give the `is_active` row of `GET /anthropic/api/oauth/usage` a `resets_at` equal, to the second, to the `anthropic-ratelimit-unified-overage-reset` of a 200 Messages answer, and an `extra_usage.utilization` that, divided by 100, equals its `anthropic-ratelimit-unified-overage-utilization`.

#### Scenario: Same cap in both answers
- **WHEN** a caller at 82% of a day cap gets a 200 Messages answer and then calls `GET /anthropic/api/oauth/usage`
- **THEN** the `is_active` row is `daily`, its `resets_at` in Unix seconds equals
  `anthropic-ratelimit-unified-overage-reset`, and `extra_usage.utilization` 82 matches the header's `0.82`
