## ADDED Requirements

### Requirement: No budget refused with 403
With `spendingLimits.mode` `enforce`, when no budget of a JWT caller's cost object covers the model of a call to a deployment that is not translated, Core SHALL refuse the call before contacting the upstream with 403 and the DIAL error body `{"error":{"code":"no_budget","message":…,"display_message":…}}`. The message SHALL name only the model and the caller's own cost object, and the answer SHALL have no `retry-after`.

#### Scenario: No budget on chat completions
- **WHEN** a caller whose token carries project `projA` has a budget in `projA` only for another model and sends a
  chat completions request for `gpt-translated`
- **THEN** Core answers 403 without calling the upstream, with `error.code` `no_budget`, `error.message`
  `No budget covers gpt-translated for cost object projA`, and no `retry-after` and no `x-should-retry`

### Requirement: Used-up budget refused with 429
With `spendingLimits.mode` `enforce`, when every budget that covers the model is used up, Core SHALL refuse the call with 429 and `retry-after`. Where the route answers with the DIAL error body, `error.code` SHALL be `budget_exhausted`.

#### Scenario: Budget used up on chat completions
- **WHEN** a caller's only budget has a day amount of $10, the caller has spent $10 today, and they send a chat
  completions request for `gpt-translated`
- **THEN** Core answers 429 without calling the upstream, with `error.code` `budget_exhausted` and a `retry-after`
  of the seconds until the day resets

### Requirement: Refusals metered by code
Core SHALL count each refusal of the budget check in `dial_budget_rejections_total` with `reason` set to its code (`no_budget` or `budget_exhausted`) and `mode` set to `spendingLimits.mode`. In `shadow` mode Core SHALL NOT refuse the call: it logs the refusal with its code and the call goes on to the other limit checks.

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
