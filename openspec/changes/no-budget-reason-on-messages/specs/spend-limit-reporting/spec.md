## MODIFIED Requirements

### Requirement: No budget for the model
Under the requirement "Budgets decide the caps in enforce mode", for a deployment that is not translated, when no budget of the caller's cost object covers the model, Core SHALL reject the request before contacting the upstream. The message SHALL be `No DIAL spend limit is assigned for <model> in project <cost object>. Ask your DIAL admin to assign one.` The answer SHALL carry `x-should-retry: false`, no `retry-after` and no spend header. On Messages its status and body SHALL be as the requirement "No budget on the Messages route" states.

#### Scenario: Budget for another model only
- **WHEN** a caller's only budget in their cost object covers only `claude-opus` and they send a Messages request
  for another model
- **THEN** Core answers 402 without calling the upstream, with the Anthropic error body of `error.type`
  `billing_error` and an `error.message` of
  `No DIAL spend limit is assigned for <model> in project <cost object>. Ask your DIAL admin to assign one.` that
  names that model and the cost object, with `x-should-retry: false`, and with no `retry-after` and no
  `anthropic-ratelimit-unified-*` header

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

### Requirement: The no-budget mark stays inside Core
On `POST /openai/v1/responses`, `POST /openai/responses`, `POST /anthropic/v1/messages` and `POST /anthropic/v1/messages/count_tokens`, Core SHALL send `X-DIAL-REFUSAL` to no caller outside Core: not to a client, and not to an application's, interceptor's or translator's call. An `X-DIAL-REFUSAL` header in an answer from outside Core SHALL NOT change Core's answer. This covers an answer from a model's upstream, an application or an interceptor. Core SHALL remove the header from that answer before relaying it.

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

## ADDED Requirements

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
