# budget-counters Specification

## Purpose
The stored spend counters of budgets: when a counter row of a retired budget is removed, how the sweep is paged, and how Core reports a missing personal budget and a shadow-mode rejection.

## Requirements

### Requirement: Counters of a retired budget are removed
Core SHALL delete a budget counter row when both its day and month windows have closed and no assignment and no
self-applying policy is stored for its budget instance, and SHALL NOT delete any other row.

#### Scenario: Withdrawn budget, windows open
- **WHEN** an assignment is deleted and the month window of a row has not closed
- **THEN** the row is kept and still counts toward the user's usage

#### Scenario: Withdrawn budget, windows closed
- **WHEN** no assignment exists for a budget instance and both windows of its row have closed
- **THEN** the row is removed by the next sweep that reaches it

#### Scenario: Live assignment
- **WHEN** an assignment, including an updated one, is stored for the budget instance
- **THEN** its rows are kept whatever their windows

#### Scenario: Expired assignment
- **WHEN** the assignment's `expiresAt` has passed and both windows of a row have closed
- **THEN** the row is removed

#### Scenario: Self-applying policy
- **WHEN** a policy that pins both subject and cost object is stored at the budget instance path
- **THEN** its rows are kept whatever their windows

#### Scenario: Row outside the counter layout
- **WHEN** a `limit` resource does not have the layout of a counter row
- **THEN** it is not touched

### Requirement: Sweep is paged
Each sweep tick SHALL handle one page of rows and continue from the previous cursor, and SHALL run on one pod at a time.

#### Scenario: More rows than a page
- **WHEN** the rows outnumber `budgetCounterSweep.batch`
- **THEN** successive ticks remove all the eligible rows

### Requirement: Missing personal budget is reported
When `spendingLimits.mode` is `shadow` or `enforce` and `defaultCostObject` has no unexpired cost budget, Core SHALL log a
warning on each config rebuild and report `dial_budget_personal_missing` as 1, and SHALL NOT refuse to start or to load the config.

#### Scenario: Mode off
- **WHEN** the mode is `off`
- **THEN** no warning is logged and the gauge is 0

#### Scenario: No personal budget
- **WHEN** the mode is `shadow` or `enforce` and only other cost objects have budgets
- **THEN** the gauge is 1

#### Scenario: Personal budget present
- **WHEN** the default cost object has an unexpired cost budget
- **THEN** the gauge is 0

#### Scenario: Personal budget expired
- **WHEN** the only budget of the default cost object has expired
- **THEN** the gauge is 1

### Requirement: Shadow rejections name the user
In `shadow` mode a call that `enforce` would reject SHALL be logged with its reason, user, cost object and model.

#### Scenario: Would-be rejection
- **WHEN** a JWT caller has no budget that can pay in `shadow` mode
- **THEN** the call succeeds and an INFO line names the user, the cost object and the model
