# Spec Delta

## Purpose

How Core reports a caller's DIAL day, week and month cost limits (spend caps) to coding clients, in the response
headers and error bodies those clients read, so the client can warn the user before a cap is reached and explain the
rejection when it is.

## ADDED Requirements

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
