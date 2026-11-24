## MODIFIED Requirements

### Requirement: No budget for the model
Under the requirement "Budgets decide the caps in enforce mode", for a deployment that is not translated, when no budget of the caller's cost object covers the model, Core SHALL reject the request before contacting the upstream. The message SHALL be `No DIAL spend limit is assigned for <model> in project <cost object>. Ask your DIAL admin to assign one.`, with the model's name and the caller's cost object. The answer SHALL carry `x-should-retry: false`, no `retry-after` and no spend header.

The answer depends on the route and the caller:
- On `POST /anthropic/v1/messages`, Core SHALL answer 429 with the DIAL error body whose `error.message` is the message.
- On `POST /openai/v1/responses` and `POST /openai/responses`, to a caller outside Core that uses a per-request key, Core SHALL answer the same 429 without `X-DIAL-REFUSAL`. Such a caller is an application, an interceptor, a translator, a router or an origin plugin.
- On those two routes, to the client's own request (one not made with a per-request key), Core SHALL answer 400 with the message as a plain-text body (`Content-Type: text/plain`). The answer SHALL have no `x-codex-*` and no `X-DIAL-REFUSAL` header, also when the request asked for a stream.

Through an alias, the client SHALL get the same answer as on a direct call of the alias's target. This holds also when the target is a router in decision mode, and when a router in decision mode chose the alias; in both cases, once no decision is left. The exception is a background request (`"background": true`) through an alias: Core reaches its target pass over HTTP, so it SHALL get the 429 of a caller that uses a per-request key.

When an interceptor runs in front of the model, global interceptors included, the refusal is made on the interceptor's call. That call gets the 429, and the client gets what the interceptor answers.

#### Scenario: Budget for another model only
- **WHEN** a caller's only budget in their cost object covers only `claude-opus` and they send a Messages request
  for another model
- **THEN** Core answers 429 without calling the upstream, with an `error.message` of
  `No DIAL spend limit is assigned for <model> in project <cost object>. Ask your DIAL admin to assign one.` that
  names that model and the cost object, with `x-should-retry: false`, and with no `retry-after` and no
  `anthropic-ratelimit-unified-*` header

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

#### Scenario: Router called directly, Responses
- **WHEN** the same caller sends `POST /openai/v1/responses` with `"model": "r1"`, and `r1` answers
  `{"candidates": ["m1"]}` to every decide request, and in a second call `{"candidates": ["alias-m1"]}`
- **THEN** in each call, once `r1` has been asked three times, Core answers the 400 with the message for `m1`, and
  the upstream of `m1` is not called

#### Scenario: No budget, through an alias and a router, Messages
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller whose only budget covers only `claude-opus` sends
  `POST /anthropic/v1/messages` with `"model": "strong"`, an alias of the router `r1` in decision mode, and `r1`
  answers `{"candidates": ["alias-m1"]}` to every decide request
- **THEN** once `r1` has been asked three times (its default `maxDecisions`), Core answers 429 with an
  `error.message` that names `m1` and the cost object, `x-should-retry: false`, no `retry-after` and no
  `anthropic-ratelimit-unified-*` header, and the upstream of `m1` is not called

#### Scenario: No budget, through an alias and a router, Responses
- **WHEN** the same caller sends `POST /openai/v1/responses` with `"model": "strong"`, and `r1` answers
  `{"candidates": ["alias-m1"]}` to every decide request
- **THEN** once `r1` has been asked three times, Core answers 400 with `Content-Type: text/plain` and the message
  for `m1` as the body, with `x-should-retry: false`, and with no `retry-after`, no `x-codex-*` and no
  `X-DIAL-REFUSAL` header, and the upstream of `m1` is not called

#### Scenario: Router behind an alias chooses the model, Responses
- **WHEN** the same caller sends `POST /openai/v1/responses` with `"model": "strong"`, and `r1` answers
  `{"candidates": ["m1"]}` to every decide request
- **THEN** once `r1` has been asked three times, Core answers the same 400, and the upstream of `m1` is not called

#### Scenario: Interceptor in front of the model behind an alias, Responses
- **WHEN** the same caller sends `POST /openai/v1/responses` with `"model": "alias-icpt"`, an alias of the model
  `m-icpt`, whose interceptor `icpt` calls Core back for `m-icpt` and answers with what it got
- **THEN** the interceptor's call gets the 429 without `X-DIAL-REFUSAL`, and the client gets that 429, not a 400

### Requirement: No limit check on an alias pass
On a Messages or Responses call, the pass of an alias SHALL NOT read the caller's spend caps, SHALL NOT check or count any token, request or cost limit, and SHALL NOT refuse the call on a limit. The target pass SHALL check the limits as on a direct call of the target. The caller SHALL get the refusal a direct call of the target gets, as the requirement "No budget for the model" states for the no-budget refusal on the Responses routes.

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

## ADDED Requirements

### Requirement: The no-budget mark stays inside Core
On `POST /openai/v1/responses` and `POST /openai/responses`, Core SHALL send `X-DIAL-REFUSAL` to no caller outside Core: not to a client, and not to an application's, interceptor's or translator's call. An `X-DIAL-REFUSAL` header in an answer from outside Core SHALL NOT change Core's answer. This covers an answer from a model's upstream, an application or an interceptor. Core SHALL remove the header from that answer before relaying it.

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
