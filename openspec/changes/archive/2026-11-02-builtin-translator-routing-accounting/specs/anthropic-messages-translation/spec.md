# Spec Delta

## ADDED Requirements

### Requirement: Analytics row of a builtin-translated request
For `POST /anthropic/v1/messages` served through a builtin translator, Core SHALL write exactly one analytics row, for
the model. The row SHALL carry `request.uri`
`/anthropic/v1/messages`, as `request.body` the Messages request the client sent with the model's
`anthropicMessages` defaults, as `response.body` the Anthropic event stream the client received, and the cost
charged as `deployment_price` and `price`.

#### Scenario: Streamed answer
- **WHEN** a client with a key that is not `secured` streams a Messages request for builtin-translated model `m`
  (`overrideName` `m-upstream`, pricing `prompt` `0.01` and `completion` `0.02`), and the upstream answers 200 with a
  Responses stream whose usage is 10 input and 8 output tokens
- **THEN** exactly one row is written for the request, with `deployment` `m`, `request.uri` `/anthropic/v1/messages`,
  a `request.body` whose `model` is `m` and whose `messages` are the client's, a `response.body` with
  `message_start` and `message_stop` events and no `response.*` events, `token_usage.total_tokens` 18 and
  `token_usage.price` 0.26

#### Scenario: Upstream error
- **WHEN** the upstream answers 400 to the same request
- **THEN** exactly one row is written for the request, with `response.status` `"400"` and no `token_usage`

### Requirement: Builtin-translated request billed as its Responses pass
For one upstream Responses answer whose `usage.total_tokens` is its input plus output tokens, Core SHALL add to the
initiator's token, cost and request-count records of the model what a `POST /openai/v1/responses` for that model adds
for the same answer. Core SHALL count the tokens from the Anthropic usage the client receives, not from
`total_tokens`, and SHALL price that usage through the model's `pricing` with the `anthropicMessages` usage fields.

#### Scenario: Plain usage
- **WHEN** model `m` has pricing `prompt` `0.01` and `completion` `0.02`, and the upstream answers a streamed request
  with usage `input_tokens` 10, `output_tokens` 8 and `total_tokens` 18, once for `POST /anthropic/v1/messages` and
  once for `POST /openai/v1/responses`
- **THEN** each of the two requests adds 18 tokens, a cost of 0.26 and one request to the caller's records for `m`

#### Scenario: Cached tokens
- **WHEN** the pricing also has `cacheRead` `0.001`, and the usage is `input_tokens` 100 with
  `input_tokens_details.cached_tokens` 40, `output_tokens` 10 and `total_tokens` 110
- **THEN** each of the two requests adds 110 tokens and a cost of 0.84 (60 × 0.01 + 40 × 0.001 + 10 × 0.02)

#### Scenario: Reasoning tokens
- **WHEN** the usage is `input_tokens` 10 and `output_tokens` 30 with `output_tokens_details.reasoning_tokens` 12, and
  `total_tokens` 40
- **THEN** each of the two requests adds 40 tokens and a cost of 0.70

### Requirement: Spend caps of a builtin-translated request
Core SHALL check the caller's spend caps on a builtin-translated request itself, as for a pass-through Messages
model: a 200 answer SHALL carry the `anthropic-ratelimit-unified-*` headers of the reported cap, and an unreadable
spend record of a caller with a cost limit SHALL get Core's spend-cap 429 before anything is sent upstream. A reached
cap gets the 429 of the scenario "Reached spend cap unchanged" of "Anthropic error body for Core errors on the builtin
path".

#### Scenario: At 82% of a day cap
- **WHEN** a caller who has spent $8.20 of a $10 day cap streams a Messages request to a builtin-translated model and
  the upstream answers 200
- **THEN** the answer carries `anthropic-ratelimit-unified-status: allowed_warning` and
  `anthropic-ratelimit-unified-overage-utilization: 0.82`

#### Scenario: Spend record unreadable
- **WHEN** a caller with a day cost limit sends the same request and the spend counter cannot be read
- **THEN** Core answers 429 with `anthropic-ratelimit-unified-overage-disabled-reason: fetch_error`, and the upstream
  receives no request

### Requirement: Upstream cache key of a builtin-translated request
Core SHALL compute the upstream cache key of a builtin-translated request from the translated Responses body, with
the `openaiResponses` hashing order and the model's `openaiResponses` features, and SHALL store the upstream that
answered under the prefix the `X-DIAL-CACHE-BREAKPOINT-PATH` answer header names, so a later request whose translated
body starts with that prefix goes first to the same upstream.

#### Scenario: Second turn pinned
- **WHEN** a builtin-translated model with two upstreams and `autoCachingSupported` in its `openaiResponses` features
  only answers a first streamed Messages request with `X-DIAL-CACHE-BREAKPOINT-PATH: prefix.body.input[0]` and an
  `X-DIAL-CACHE-EXPIRE-AT` ten minutes ahead, and the client then sends a request that repeats the first user message
  and adds two more
- **THEN** the second upstream request carries the same `X-UPSTREAM-KEY` as the first and
  `X-DIAL-CACHE-BREAKPOINT-PATH: prefix.body.input[0]`

#### Scenario: Messages prefix not cached
- **WHEN** the first answer names `prefix.body.messages[0]` instead
- **THEN** the second upstream request carries `X-DIAL-CACHE-BREAKPOINT-PATH: prefix.body.input[2]`, the last
  breakpoint of its body, not `prefix.body.input[0]`

### Requirement: Retry of a builtin-translated request
When the upstream's answer to a builtin-translated request is retriable (deployment-origin) and another attempt is
allowed, Core SHALL send the same translated request to the next upstream and translate only the answer it relays,
so the client receives one Anthropic event stream and only that answer's usage is charged.

#### Scenario: First upstream unavailable
- **WHEN** a builtin-translated model has two upstreams and `maxRetryAttempts: 2`, the first upstream asked answers
  503, and the second answers 200 with a stream of the text `Hi` and usage of 10 input and 8 output tokens
- **THEN** the two upstream requests have the same body and different `X-UPSTREAM-KEY` values, and the client gets
  200 with `X-UPSTREAM-ATTEMPTS: 2`, one `message_start`, one `message_stop` and the text `Hi`, and the caller's token
  record grows by 18

### Requirement: Client gone from a builtin-translated stream
When the client closes its connection while Core relays a builtin-translated stream, Core SHALL keep reading and
translating the upstream's Responses stream to its end, charge the usage it reports and write the analytics row, as
for any model (deployment-origin "Client gone during the relay").

#### Scenario: Client disconnects mid-stream
- **WHEN** the client closes its connection after the first event of a builtin-translated stream whose Responses
  stream later reports usage of 10 input and 8 output tokens
- **THEN** the upstream stream is read to its end, the caller's token record grows by 18, and one analytics row is
  written for the request
