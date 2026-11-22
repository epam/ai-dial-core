# Spec Delta

## ADDED Requirements

### Requirement: Spending limits in the analytics row
When Core checks the limits of a JWT caller's call to a model, it SHALL write, in that model's row, right after
`project`, a `spending_limits` object with `mode` (`spendingLimits.mode`: `off`, `shadow` or `enforce`). In
`shadow` and `enforce` the object SHALL also carry `cost_object` (the cost object the call is charged to) and
`outcome`: `paid` when a budget pays, `no_budget` when no budget covers the caller and the model, or `exhausted`
when every such budget is used up.

#### Scenario: Enforce mode
- **WHEN** `spendingLimits.mode` is `enforce`, a caller whose token carries project `projA` has one budget, policy
  `projA-a1` assigned at `projA/a1`, and sends a Messages request to model `claude-spend`
- **THEN** the model's row has `spending_limits` with `mode` `enforce`, `cost_object` `projA` and `outcome` `paid`

#### Scenario: Shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and the same caller sends the same request
- **THEN** the model's row has `spending_limits` with `mode` `shadow`, `cost_object` `projA` and `outcome` `paid`

#### Scenario: Spending limits off
- **WHEN** `spendingLimits.mode` is `off` and a caller with a budget assignment calls the model
- **THEN** the row has `spending_limits` with `mode` `off` and no other key

#### Scenario: No budget in shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and a caller whose token has no project claim has no budget
- **THEN** the call succeeds and its row has `spending_limits` with `cost_object` `ORG` and `outcome` `no_budget`,
  while `project.id` is empty

#### Scenario: Every budget used up in shadow mode
- **WHEN** `spendingLimits.mode` is `shadow` and the caller's only budget is used up
- **THEN** the call succeeds and its row has `spending_limits` with `outcome` `exhausted`

#### Scenario: Every budget used up in enforce mode
- **WHEN** `spendingLimits.mode` is `enforce` and the caller's only budget is used up
- **THEN** Core refuses the call with 429 and writes no row for it

### Requirement: Budget in the analytics row
When a budget pays for the call, its `spending_limits` object SHALL also name that budget, the one charged with
the call's cost: `policy` (the policy path, as `budgets[].policy` of `GET /v1/user/limits` has it) and
`assignment` (the assignment path, absent for a policy that applies by itself).

#### Scenario: Assigned budget
- **WHEN** a caller whose token carries project `projA` has one budget, policy `projA-a1` assigned at `projA/a1`,
  and calls the model
- **THEN** the row's `spending_limits` has `policy` `projA-a1` and `assignment` `projA/a1`

#### Scenario: Fall-through to the next budget
- **WHEN** the caller's first budget `projA/a1` is used up and the second `projA/a2` has room
- **THEN** the row's `spending_limits` has `outcome` `paid`, `assignment` `projA/a2` and `policy` `projA-a2`

#### Scenario: Token without a project claim
- **WHEN** a caller whose token has no project claim has a budget assigned at `ORG/o1`
- **THEN** the row's `spending_limits` has `cost_object` `ORG` and `assignment` `ORG/o1`, while `project.id` is empty

#### Scenario: Policy that applies by itself
- **WHEN** a caller is charged to the policy `ORG/personal`, which names the caller's subject and cost object `ORG`
  and has no assignment
- **THEN** the row's `spending_limits` has `cost_object` `ORG` and `policy` `ORG/personal`, and no `assignment`

### Requirement: Rows without spending limits
Core SHALL NOT write `spending_limits` in the row of an API key caller, of a pass whose limits Core does not
check, of a deployment that is not a model (including the router row of a decided request), or of a background
Responses job when it ends.

#### Scenario: API key caller
- **WHEN** an API key calls the model
- **THEN** the row has no `spending_limits`

#### Scenario: Application calling a model
- **WHEN** a caller with budget `projA/a1` calls application `app-chain`, which calls model `gpt-spend` with its
  per-request key
- **THEN** the model's row has `spending_limits` with `outcome` `paid` and `assignment` `projA/a1`, and the
  application's row has no `spending_limits`

#### Scenario: Router row
- **WHEN** the serving row of a decided request has `spending_limits` and Core builds the router's row from it
- **THEN** the router's row has no `spending_limits`

#### Scenario: Candidate after a failed model
- **WHEN** a token caller's decided request goes to model `m1`, whose limits Core checks, `m1` fails, and the
  router's next candidate, application `app`, serves the request
- **THEN** `app`'s row has no `spending_limits`

#### Scenario: Background job row
- **WHEN** a background Responses job ends and Core writes its row from the job record
- **THEN** the row has no `spending_limits`
