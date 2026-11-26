# budget-coverage Specification

## Purpose
Which budgets cover a JWT caller's model call: a budget policy's object set, the names the call was reached through
(its coverage chain), and how budgets covering different names on that chain are narrowed to the candidates the
priority walk runs over.

## Requirements

### Requirement: Coverage chain
The coverage chain of a model call SHALL be the model and, read back from the call's execution path, the names before
it (the aliases and routers that led to it) up to the first application, interceptor or toolset, which is not on the
chain. The chain SHALL be the same whether the call came from the client or with a per-request key.

#### Scenario: Tier alias through a router
- **WHEN** a JWT caller sends a chat completion to `strong`, an alias of router `r1`, whose decision chooses the alias
  `claude-opus-vertex` of model `m`
- **THEN** the chain of the call to `m` is `strong`, `r1`, `claude-opus-vertex`, `m`

#### Scenario: Application calling an alias
- **WHEN** application `codemie`, called by a JWT caller, calls `strong` with its per-request key and the call reaches
  `m` as above
- **THEN** the chain of the call to `m` is `strong`, `r1`, `claude-opus-vertex`, `m`, without `codemie`

#### Scenario: Interceptor in front of the model
- **WHEN** model `m` has interceptor `icpt`, and a JWT caller calls `strong`, which reaches `m`
- **THEN** the chain of `icpt`'s call to `m` is `m` alone

#### Scenario: Router in proxy mode
- **WHEN** a JWT caller calls `strong`, an alias of router `r2` in proxy mode, and `r2` calls `m` with its per-request
  key
- **THEN** the chain of the call to `m` is `strong`, `r2`, `m`

### Requirement: Budget covers a name on the chain
A budget SHALL cover a model call when its policy's `object` is `"*"`, or when the names it lists, with each package
it names replaced by the package's members, contain any name on the call's coverage chain. A budget that does not
cover the call SHALL NOT pay for it.

#### Scenario: Package of tier aliases
- **WHEN** a caller's only budget has `object` `"@engineering"`, `engineering` is `["weak", "medium", "strong"]`, and
  the caller calls `strong`, which reaches model `m`
- **THEN** the call to `m` is paid by that budget and charged under `m`'s name

#### Scenario: Model called directly
- **WHEN** the same caller calls `m` directly
- **THEN** the call is refused as `no_budget`

#### Scenario: Tier outside the package
- **WHEN** the same caller calls `hi-end`, which reaches model `m2`
- **THEN** the call is refused as `no_budget`

#### Scenario: Application calling a tier alias
- **WHEN** application `codemie`, called by the same caller, calls `strong` with its per-request key
- **THEN** the call to `m` is paid by that budget

#### Scenario: Interceptor breaks the chain
- **WHEN** the caller's only budget has `object` `"@engineering"`, model `m` has interceptor `icpt`, and the caller
  calls `strong`, which reaches `m`
- **THEN** `icpt`'s call to `m` is refused as `no_budget`

#### Scenario: Budget for every deployment
- **WHEN** a caller's only budget has `object` `"*"` and the caller calls `m` directly or through `strong`
- **THEN** both calls are paid by that budget

### Requirement: Earliest chain position takes the call
When budgets that can pay cover a call at different positions of its coverage chain, the candidates SHALL be only
the budgets covering the earliest position, nearest the head of the chain, that any of them covers. The priority walk
SHALL run over those candidates as before.

#### Scenario: Alias budget and model budget
- **WHEN** a caller has budget A with `object` `["strong"]` and priority 20 and budget B with `object` `["m"]` and
  priority 10, and calls `strong`, which reaches `m`
- **THEN** the call is paid by A

#### Scenario: Earliest budget exhausted
- **WHEN** budget A of the previous scenario has no money left in its window
- **THEN** the call is refused as `budget_exhausted`, and B is not charged

#### Scenario: Same position
- **WHEN** a caller has two budgets with `object` `"@engineering"` at priorities 10 and 20, the first with no money
  left, and calls `strong`
- **THEN** the call is paid by the budget at priority 20

#### Scenario: Direct call
- **WHEN** the caller of the first scenario calls `m` directly
- **THEN** the call is paid by B
