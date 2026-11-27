# spending-refusals Specification

## Purpose
How Core refuses a call that budgets cannot pay when `spendingLimits.mode` is `enforce`: each refusal has a code,
written to `error.code` of the DIAL error body, and the status that fits it (403 when no budget can pay, on every route but
the Messages and Responses ones, which answer in their own way; 429 when a budget is used up until it resets). It also covers how `shadow` mode meters the refusals it does not make.

## Requirements

### Requirement: No budget refused with 403
With `spendingLimits.mode` `enforce`, when no budget can pay for a JWT caller's call to a model that is not translated (none of the cost object covers it, the indicated one is not held, or nothing is indicated or defaulted and the walk is off), on any route but `POST /anthropic/v1/messages`, `POST /openai/v1/responses` and `POST /openai/responses`, Core SHALL refuse it before contacting the upstream with 403, `error.code` `no_budget` and no `retry-after`.

#### Scenario: No budget on chat completions
- **WHEN** a caller whose token carries project `projA` has a budget in `projA` only for another model and sends a
  chat completions request for `gpt-translated`
- **THEN** Core answers 403 without calling the upstream, with `error.code` `no_budget`, `error.message`
  `No DIAL spend limit is assigned for gpt-translated in project projA. Ask your DIAL admin to assign one.`, and no
  `retry-after` and no `x-should-retry`

#### Scenario: Indicated budget not held
- **WHEN** a caller whose token carries `projA` sends `X-DIAL-BUDGET: projA/b2`, an assignment of another subject,
  with a chat completions request
- **THEN** Core answers 403 without calling the upstream, with `error.code` `no_budget` and `error.message`
  `Budget projA/b2 is not available to you`, the same as for `projA/missing`

#### Scenario: Walk turned off
- **WHEN** `autoBudgetSelection` is `false` and a caller with no default sends a chat completions request for `m`
  without `X-DIAL-BUDGET`
- **THEN** Core answers 403 with `error.code` `no_budget` and `error.message`
  `No budget is indicated for m. Indicate a budget with the X-DIAL-BUDGET header or set a default budget.`

### Requirement: No budget code on the Messages and Responses routes
On `POST /anthropic/v1/messages`, `POST /openai/v1/responses` and `POST /openai/responses` the no-budget answers of the spend-limit-reporting capability SHALL apply to every 403 refusal of the budget check (`no_budget`, `budget_not_applicable`, `default_budget_not_applicable`), each with its own message. Where one has the DIAL error body, its `error.code` SHALL be the refusal's code.

#### Scenario: Responses call with a per-request key
- **WHEN** a caller with no budget for `gpt-spend` calls an application, and the application calls `gpt-spend`
  through `POST /openai/v1/responses` with its per-request key
- **THEN** Core answers the application's call with 429 and the DIAL error body with `error.code` `no_budget`

#### Scenario: Indicated budget not covering on Messages
- **WHEN** a caller indicates `projA/t1`, whose `object` is `["strong"]`, and sends a Messages request for `hi-end`
- **THEN** Core answers 402 with the Anthropic error body of `error.type` `billing_error` and the message
  `Budget projA/t1 does not cover hi-end`, with `x-should-retry: false`

### Requirement: Used-up budget refused with 429
With `spendingLimits.mode` `enforce`, when the budget that would pay is used up (the indicated budget, the default budget in effect, or every budget the walk considers), Core SHALL refuse the call with 429 and `retry-after`. Where the route answers with the DIAL error body, `error.code` SHALL be `budget_exhausted`.

#### Scenario: Budget used up on chat completions
- **WHEN** a caller's only budget has a day amount of $10, the caller has spent $10 today, and they send a chat
  completions request for `gpt-translated`
- **THEN** Core answers 429 without calling the upstream, with `error.code` `budget_exhausted` and a `retry-after`
  of the seconds until the day resets

#### Scenario: Indicated budget used up
- **WHEN** a caller indicates `projA/t1`, which has spent its day amount, holds another budget with room, and sends a
  chat completions request
- **THEN** Core answers 429 with `error.code` `budget_exhausted` and a `retry-after` until `projA/t1`'s day resets

### Requirement: Refusals metered by code
Core SHALL count each refusal of the budget check in `dial_budget_rejections_total` with `reason` set to its code and `mode` set to `spendingLimits.mode`. In `shadow` mode Core SHALL NOT refuse a call for a 403 or 429 code: it logs the refusal with its code and the call goes on to the other limit checks. The 400 codes SHALL refuse the call in every mode.

#### Scenario: No budget in shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and a caller with no budget for the model sends a Messages request
- **THEN** the request reaches the upstream, and `dial_budget_rejections_total` with `reason` `no_budget` and `mode`
  `shadow` grows by one

#### Scenario: Used up in shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and a caller whose only budget is used up sends a Messages request
- **THEN** the request reaches the upstream, and `dial_budget_rejections_total` with `reason` `budget_exhausted` and
  `mode` `shadow` grows by one

#### Scenario: No budget in enforce mode
- **WHEN** `spendingLimits.mode` is `enforce` and a caller with no budget for the model sends a chat completions
  request
- **THEN** `dial_budget_rejections_total` with `reason` `no_budget` and `mode` `enforce` grows by one

#### Scenario: Default not applicable in shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and a caller's default budget does not cover the model they call
- **THEN** the request reaches the upstream, and `dial_budget_rejections_total` with `reason`
  `default_budget_not_applicable` and `mode` `shadow` grows by one

#### Scenario: Malformed indication in shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and a caller sends `X-DIAL-BUDGET: x`
- **THEN** Core answers 400, and `dial_budget_rejections_total` with `reason` `invalid_budget_indication` and `mode`
  `shadow` grows by one

### Requirement: Declared payer not covering refused with 403
With `spendingLimits.mode` `enforce`, on a route other than Messages and Responses, Core SHALL answer 403 with the DIAL error body: `budget_not_applicable` with `Budget <id> does not cover <name>` when the indicated budget does not cover the call, and `default_budget_not_applicable` with `Your default budget <id> does not cover <name>. Indicate a budget with the X-DIAL-BUDGET header.` for the default. `<name>` is the deployment at the head of the call's coverage chain.

#### Scenario: Indicated budget through a tier alias
- **WHEN** a caller indicates `projA/t1`, whose `object` is `["strong"]`, and sends a chat completions request for
  `hi-end`, an alias of model `m2`
- **THEN** Core answers 403 with `error.code` `budget_not_applicable` and `error.message`
  `Budget projA/t1 does not cover hi-end`, and no `retry-after`

#### Scenario: Default budget not covering
- **WHEN** a caller's default is `projA/t1`, whose `object` is `["strong"]`, and they send a chat completions request
  for `hi-end` without `X-DIAL-BUDGET`
- **THEN** Core answers 403 with `error.code` `default_budget_not_applicable` and `error.message`
  `Your default budget projA/t1 does not cover hi-end. Indicate a budget with the X-DIAL-BUDGET header.`

### Requirement: Budget header refusals with 400
Core SHALL answer the 400 refusals of the budget-resolution capability with the DIAL error body on every route,
`error.code` set to the refusal's code and a message that says what to fix. The `cost_object_mismatch` message SHALL
name the indicated budget, its cost object, the effective cost object and its source.

#### Scenario: Malformed indication message
- **WHEN** a caller sends `X-DIAL-BUDGET: x` with a chat completions request
- **THEN** Core answers 400 with `error.code` `invalid_budget_indication` and `error.message`
  `X-DIAL-BUDGET must be <costObject>/<id>`

#### Scenario: Malformed cost object message
- **WHEN** a caller sends `X-DIAL-COST-OBJECT: a/b` with a chat completions request
- **THEN** Core answers 400 with `error.code` `invalid_cost_object` and `error.message`
  `X-DIAL-COST-OBJECT must be one cost object name`

#### Scenario: Cost object mismatch message
- **WHEN** a caller whose token carries no claim, where the provider sets `costObjectPath`, sends
  `X-DIAL-BUDGET: IMF/TKT-1` with a Messages request
- **THEN** Core answers 400 with the DIAL error body, `error.code` `cost_object_mismatch` and `error.message`
  `Budget IMF/TKT-1 is on cost object IMF, but this call is charged to ORG (default)`

#### Scenario: Indication required on Responses
- **WHEN** `budgetMandatory` is `true` and a caller sends a Responses request without `X-DIAL-BUDGET`
- **THEN** Core answers 400 with the DIAL error body, `error.code` `budget_indication_required` and `error.message`
  `Indicate the budget that pays with the X-DIAL-BUDGET header`
