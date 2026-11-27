# budget-resolution Specification Delta

## Purpose

Which budget pays a JWT caller's call to a model: the effective cost object and where it came from, the budget the
request indicates, the user's default budget, the walk behind `autoBudgetSelection`, and how calls made with a
per-request key resolve the root request's payer.

## ADDED Requirements

### Requirement: Effective cost object with a claim path
Where the caller's identity provider sets `costObjectPath`, the effective cost object of a JWT caller's call SHALL be
the token's claim at that path, with source `claim`, else `spendingLimits.defaultCostObject`, with source `default`.
A token without the claim SHALL be bound to `defaultCostObject`. `X-DIAL-COST-OBJECT` SHALL NOT change it.

#### Scenario: Claim present
- **WHEN** `spendingLimits.mode` is `enforce`, the provider sets `costObjectPath`, alice's token carries `IMF`, and
  alice sends `X-DIAL-COST-OBJECT: EDU` with a chat completion to model `m`
- **THEN** the model's analytics row has `cost_object` with `id` `IMF` and `source` `claim`

#### Scenario: Claim missing, header ignored
- **WHEN** the provider sets `costObjectPath`, alice's token carries no claim, alice holds `IMF/TKT-1` (subject
  `sub` equal to alice) and the personal budget `ORG/personal`, and sends `X-DIAL-COST-OBJECT: IMF` with no
  indication
- **THEN** the call is paid by `ORG/personal`, and its row has `cost_object` with `id` `ORG` and `source` `default`

### Requirement: Cost-object header without a claim path
Where the caller's identity provider does not set `costObjectPath`, a JWT caller's root call with
`X-DIAL-COST-OBJECT` and no `X-DIAL-BUDGET` SHALL have the header's value as its effective cost object, with source
`header`; without the header it SHALL be `defaultCostObject`, with source `default`. On a call that carries
`X-DIAL-BUDGET`, `X-DIAL-COST-OBJECT` SHALL be ignored.

#### Scenario: Header scopes the walk
- **WHEN** the provider sets no `costObjectPath`, alice holds `IMF/TKT-1` (subject `sub` equal to alice) and
  `ORG/personal`, and sends `X-DIAL-COST-OBJECT: IMF` with a chat completion to `m`, which `IMF/TKT-1` covers
- **THEN** the call is paid by `IMF/TKT-1`, and its row has `cost_object` with `id` `IMF` and `source` `header`

#### Scenario: Header and indication
- **WHEN** the provider sets no `costObjectPath` and alice sends `X-DIAL-COST-OBJECT: EDU` and
  `X-DIAL-BUDGET: IMF/TKT-1`
- **THEN** the call is paid by `IMF/TKT-1`, and its row has `cost_object` `IMF`

### Requirement: Malformed budget headers refused
On a JWT caller's call to any deployment that is not made with a per-request key, in every `spendingLimits.mode`,
Core SHALL refuse with 400 before calling the deployment: `invalid_cost_object` when `X-DIAL-COST-OBJECT` is not one
path element, `invalid_budget_indication` when `X-DIAL-BUDGET` is not exactly two path elements. Path elements follow
the rules of a policy name, with no `/` and no `%`.

#### Scenario: Indication with one element
- **WHEN** `spendingLimits.mode` is `off` and a JWT caller sends `X-DIAL-BUDGET: TKT-1` with a chat completion to `m`
- **THEN** Core answers 400 with `error.code` `invalid_budget_indication` and does not call the upstream

#### Scenario: Cost object with a slash
- **WHEN** a JWT caller sends `X-DIAL-COST-OBJECT: IMF/eng` with a chat completion to application `app`
- **THEN** Core answers 400 with `error.code` `invalid_cost_object` and does not call `app`

#### Scenario: Malformed header where a claim path is set
- **WHEN** the provider sets `costObjectPath` and a JWT caller sends `X-DIAL-COST-OBJECT: a/b`
- **THEN** Core answers 400 with `error.code` `invalid_cost_object`

### Requirement: Indicated budget's cost object must match
Where the caller's identity provider sets `costObjectPath`, a JWT caller's call whose `X-DIAL-BUDGET` names a cost
object other than the effective one SHALL be refused, in every mode, with 400 `cost_object_mismatch` before calling
the deployment. The message SHALL name the effective cost object and its source.

#### Scenario: Claim missing, indication on the project
- **WHEN** the provider sets `costObjectPath`, alice's token carries no claim and alice sends
  `X-DIAL-BUDGET: IMF/TKT-1` with a chat completion to `m`
- **THEN** Core answers 400 with `error.code` `cost_object_mismatch` and a message naming `ORG` and `default`

#### Scenario: Indication on the claim's cost object
- **WHEN** alice's token carries `IMF` and alice indicates `IMF/default-members`, a budget with the subject
  `[{"source": "sub", "function": "TRUE"}]` that covers `m`
- **THEN** the call is paid by `IMF/default-members`

### Requirement: Indication without a claim path
Where the caller's identity provider does not set `costObjectPath`, the cost object of the budget a call indicates
SHALL be the call's effective cost object, with source `header`, or `default` when it equals `defaultCostObject`.

#### Scenario: Ticket indicated
- **WHEN** the provider sets no `costObjectPath` and alice, who holds `IMF/TKT-1`, indicates it with a chat
  completion to `m`, which it covers
- **THEN** the call is paid by `IMF/TKT-1`, and its row has `cost_object` with `id` `IMF` and `source` `header`

### Requirement: Indicated budget is the payer
When a JWT caller's call to a model indicates a budget, that budget SHALL be the only one that can pay. Core SHALL
refuse with 403 `no_budget` when the caller does not hold it (no such assignment or self-applying policy, another
subject, expired), 403 `budget_not_applicable` when it does not cover the call, and 429 `budget_exhausted` when it is
used up, and SHALL NOT let another budget pay.

#### Scenario: Another person's ticket
- **WHEN** dave, whose token carries `IMF`, indicates `IMF/TKT-1`, whose subject is `sub` equal to alice
- **THEN** Core answers 403 with `error.code` `no_budget`, with the same message as for `IMF/missing`

#### Scenario: Indicated budget does not cover the model
- **WHEN** alice indicates `IMF/TKT-1`, whose `object` is `["strong"]`, and calls `hi-end`
- **THEN** Core answers 403 with `error.code` `budget_not_applicable`, even though `IMF/default-members` covers
  `hi-end`

#### Scenario: Indicated budget used up
- **WHEN** alice indicates `IMF/TKT-1`, which has spent its day amount, and also holds `IMF/default-members` with room
- **THEN** Core answers 429 with `error.code` `budget_exhausted` and a `retry-after`, and `IMF/default-members` is
  not charged

### Requirement: Header cost object does not attest
When the effective cost object has source `header`, a budget SHALL be held by the caller only through a subject rule
other than `TRUE` that matches the caller's claims. This SHALL hold for an indicated budget, the default budget and
the walk.

#### Scenario: Header walk skips a TRUE-only budget
- **WHEN** the provider sets no `costObjectPath`, the only budget on `IMF` is `IMF/default-members`, whose subject is
  only `TRUE`, and alice sends `X-DIAL-COST-OBJECT: IMF` with a chat completion to `m`, which it covers
- **THEN** in `enforce` mode Core answers 403 with `error.code` `no_budget`

#### Scenario: Indicated TRUE-only budget without a claim path
- **WHEN** the provider sets no `costObjectPath` and dave indicates `IMF/default-members`
- **THEN** Core answers 403 with `error.code` `no_budget`

#### Scenario: Header walk reaches a named budget
- **WHEN** the provider sets no `costObjectPath`, alice holds `IMF/TKT-1` (subject `sub` equal to alice) and
  `IMF/default-members`, and sends `X-DIAL-COST-OBJECT: IMF`
- **THEN** the call is paid by `IMF/TKT-1` whatever the priorities of the two

### Requirement: Indication required
When `spendingLimits.budgetMandatory` is `true`, a JWT caller's call to any deployment that is not made with a
per-request key and carries no `X-DIAL-BUDGET` SHALL be refused with 400 `budget_indication_required` before calling
the deployment, in every mode. `budgetMandatory` SHALL default to `false`.

#### Scenario: Unindicated call with the flag on
- **WHEN** `budgetMandatory` is `true`, `spendingLimits.mode` is `off`, and a JWT caller who has a default budget
  calls application `app` without `X-DIAL-BUDGET`
- **THEN** Core answers 400 with `error.code` `budget_indication_required` and does not call `app`

#### Scenario: API key caller with the flag on
- **WHEN** `budgetMandatory` is `true` and an API key calls `m` without `X-DIAL-BUDGET`
- **THEN** the call is not refused for the missing indication

### Requirement: Default budget pays an unindicated call
When a JWT caller's call to a model indicates no budget, the caller's default budget, while they hold it, SHALL pay
when it is on the effective cost object and covers the call. When it does not cover the call, Core SHALL refuse with
403 `default_budget_not_applicable`, even when another budget covers it; used up, with 429 `budget_exhausted`.

#### Scenario: Default pays over a higher-priority budget
- **WHEN** alice's token carries `IMF`, her default is `IMF/default-members` (priority 20) and she also holds
  `IMF/TKT-1` (priority 10), both covering `m`, and she calls `m` without an indication
- **THEN** the call is paid by `IMF/default-members`

#### Scenario: Default does not cover the call
- **WHEN** alice's default `IMF/TKT-1` has `object` `["strong"]`, `IMF/default-members` covers `hi-end`, and she
  calls `hi-end` without an indication
- **THEN** Core answers 403 with `error.code` `default_budget_not_applicable`

#### Scenario: Default used up
- **WHEN** alice's default has spent its month amount and she calls a model it covers without an indication
- **THEN** Core answers 429 with `error.code` `budget_exhausted`, and no other budget is charged

#### Scenario: Default on another cost object
- **WHEN** alice's token carries `IMF`, her default is `EDU/e1`, and she calls `m` without an indication
- **THEN** the default does not pay and the call goes to the walk over `IMF`

#### Scenario: Default withdrawn
- **WHEN** alice's default's assignment has been deleted
- **THEN** her unindicated calls go to the walk

### Requirement: Walk behind autoBudgetSelection
When a JWT caller's call to a model has no indication and no default that is in effect, and
`spendingLimits.autoBudgetSelection` is `true` (the default), the budgets of the effective cost object SHALL pay as
the M1 priority walk picks them. When it is `false`, Core SHALL refuse with 403 `no_budget` and a message asking to
indicate a budget.

#### Scenario: Zero setup
- **WHEN** a JWT caller without a claim and without a default holds `ORG/personal` and calls `m`
- **THEN** the call is paid by `ORG/personal` and charged to the same counters as before this change

#### Scenario: Walk turned off
- **WHEN** `autoBudgetSelection` is `false` and a caller who holds `ORG/personal` and has no default calls `m`
  without an indication
- **THEN** in `enforce` mode Core answers 403 with `error.code` `no_budget` and a message that asks to indicate a
  budget

### Requirement: Calls with a per-request key resolve the root's payer
A call made with a per-request key SHALL ignore `X-DIAL-BUDGET` and `X-DIAL-COST-OBJECT` and resolve the payer from
the root request's indication and effective cost object, as the root would. The root's indication SHALL be resolved
against the config current at each call, so a budget withdrawn after the root request started refuses later calls.

#### Scenario: Application calls a model under the root's indication
- **WHEN** alice indicates `IMF/TKT-1` on a call to application `app`, and `app` calls `m` with its per-request key
- **THEN** `app`'s call to `m` is paid by `IMF/TKT-1`

#### Scenario: Application sends its own indication
- **WHEN** alice calls `app` without an indication, her default is `IMF/default-members`, and `app` calls `m` with
  its per-request key and `X-DIAL-BUDGET: IMF/TKT-1`
- **THEN** `app`'s call to `m` is paid by `IMF/default-members`

#### Scenario: Header cost object carried to the model
- **WHEN** the provider sets no `costObjectPath`, alice sends `X-DIAL-COST-OBJECT: IMF` to `app`, and `app` calls `m`
  with its per-request key
- **THEN** `app`'s call to `m` is paid by alice's budget on `IMF`, and the row of `m` has `cost_object` `IMF` with
  `source` `header`

#### Scenario: Budget withdrawn mid-session
- **WHEN** alice indicates `IMF/TKT-1` on a call to `app`, `app`'s first call to `m` is paid, the admin then deletes
  the assignment `IMF/TKT-1`, and `app` calls `m` again
- **THEN** Core answers `app`'s second call with 403 and `error.code` `no_budget`

### Requirement: API key callers ignore the budget headers
Core SHALL ignore `X-DIAL-BUDGET` and `X-DIAL-COST-OBJECT` on a call authenticated with an API key, whatever their
value.

#### Scenario: Malformed header from an API key
- **WHEN** an API key calls `m` with `X-DIAL-BUDGET: x`
- **THEN** Core does not answer 400 for the header
