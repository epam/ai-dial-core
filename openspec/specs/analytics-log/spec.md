# analytics-log Specification

## Purpose
What Core writes to the analytics log for a pass: which parts of the request and response a row carries, and when
Core leaves the bodies out. Which passes write a row, and the usage and trace fields in it, belong to
`app-to-model-accounting` and the capabilities that own those passes.

## Requirements

### Requirement: Bodies in the analytics row
For a key that is not `secured`, Core SHALL write the request body as `request.body` and the response body as
`response.body`, a body over 4 MiB cut there and ending in `>>`, and, for a streamed response that Core assembled, the
assembled response as `assembled_response`, except while the log writer is behind or in the row of an alias pass (see
the two requirements on rows without bodies). For a `secured` key, Core SHALL write none of these three fields.

#### Scenario: Key not secured
- **WHEN** a pass with a key that is not `secured` writes its row
- **THEN** the row has `request.body` and `response.body` with the bodies of that pass

#### Scenario: Secured key
- **WHEN** a pass with a `secured` key writes its row
- **THEN** the row has no `request.body`, `response.body` or `assembled_response`, and has every other field

#### Scenario: Call from an origin plugin
- **WHEN** plugin `p1` serving application `app` calls model `m` with a key that is not `secured`, and both passes
  write their rows
- **THEN** `app`'s row and `m`'s row each have their `request.body` and `response.body`

### Requirement: No limit on bodies waiting to be logged by default
Core SHALL keep the bodies of every row of a key that is not `secured`, whatever the backlog, when
`analytics.maxPendingBodyHeapPercent` (env
`aidial.analytics.maxPendingBodyHeapPercent`) is absent, which means `-1`, or is 0 or below.

#### Scenario: Setting absent
- **WHEN** Core runs without `analytics.maxPendingBodyHeapPercent`, a row with a 2,000-byte request body is waiting to
  be written, and another pass writes its row
- **THEN** that row has its `request.body` and `response.body`

#### Scenario: Value 0
- **WHEN** Core runs with `analytics.maxPendingBodyHeapPercent: 0`, a row with a 2,000-byte request body is waiting to
  be written, and another pass writes its row
- **THEN** that row has its `request.body` and `response.body`

### Requirement: Rows without bodies while the log writer is behind
When `analytics.maxPendingBodyHeapPercent` is positive, Core SHALL take that percent of the JVM's maximum heap as the
limit on the bodies held by rows waiting to be written. While the held bodies reach the limit, Core SHALL write each
new row without `request.body`, `response.body` and `assembled_response`, with every other field, and SHALL NOT drop
the row. Once the held bodies fall below the limit, new rows SHALL carry their bodies again.

#### Scenario: Writer behind
- **WHEN** the JVM's maximum heap is 10,000 bytes, `analytics.maxPendingBodyHeapPercent` is 10 (a limit of 1,000
  bytes), a row with a 2,000-byte request body is waiting to be written, and another pass writes its row
- **THEN** that row has no `request.body`, `response.body` or `assembled_response`, and has its `deployment`,
  `token_usage`, `trace` and `response.status`

#### Scenario: Nothing waiting
- **WHEN** the JVM's maximum heap is 10,000 bytes, `analytics.maxPendingBodyHeapPercent` is 10 (a limit of 1,000
  bytes), no row is waiting, and a pass with a 2,000-byte request body writes its row
- **THEN** the row has `request.body` with that body

#### Scenario: Writer caught up
- **WHEN** the JVM's maximum heap is 10,000 bytes, `analytics.maxPendingBodyHeapPercent` is 10 (a limit of 1,000
  bytes), rows were written without bodies, and the rows waiting since then have been written
- **THEN** the next row has its `request.body` and `response.body` again

#### Scenario: Larger heap
- **WHEN** the JVM's maximum heap is 40,000 bytes, `analytics.maxPendingBodyHeapPercent` is 10 (a limit of 4,000
  bytes), a row with a 2,000-byte request body is waiting to be written, and another pass writes its row
- **THEN** that row has its `request.body` and `response.body`

### Requirement: Rows without bodies for an alias pass
Core SHALL write the row of an alias pass without `request.body`, `response.body` and `assembled_response`, and with
every other field, whatever the key, the interface and whether the target pass runs in process. The row of the
alias's target pass SHALL carry its bodies as "Bodies in the analytics row" says; a target pass that refuses the call
before its origin writes no row, as on a direct call.

#### Scenario: Messages through an alias
- **WHEN** a caller with a key that is not `secured` sends `/anthropic/v1/messages` with `model: multi-alias`, and
  both passes write their rows
- **THEN** the alias's row has no `request.body`, `response.body` or `assembled_response` and has its `deployment`
  and `trace`, and the target's row has `request.body` with `model` set to the target name and `response.body`

#### Scenario: Streamed chat completion through an alias
- **WHEN** a caller with a key that is not `secured` sends a streamed chat completion to `assistant`
- **THEN** the alias's row has no `assembled_response`, and the row of `gpt-4-internal` has its `assembled_response`

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
check, of a deployment that is not a model (including the router row of a decided request), or that Core writes
from a background Responses job's record when the job ends.

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
