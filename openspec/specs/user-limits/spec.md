# user-limits Specification

## Purpose
TBD - created by archiving change user-budget-stats. Update Purpose after archive.

## Requirements

### Requirement: Budgets in the user stats
`GET /v1/user/limits` and `GET /v1/user/usage` SHALL return `budgets[]`, one entry per budget that matches the caller, each
with its `costObject`, `policy` and `priority`, and `day` and `month` as `{total, used, resetsAt}`, and SHALL list
only budgets whose subject matches the caller's claims. The other fields of the response SHALL NOT change.

#### Scenario: Several budgets in one cost object
- **WHEN** a caller matches two budgets in one project
- **THEN** `budgets` has two entries with that `costObject`, ordered by priority, each with its own `policy` and totals

#### Scenario: Entries of every cost object of the caller
- **WHEN** a caller has matching budgets in two projects and in the default cost object
- **THEN** `budgets` has three entries with the totals of those budgets, ordered by cost object

#### Scenario: Budget of another subject
- **WHEN** a budget's subject does not match the caller
- **THEN** it is not listed

#### Scenario: No budgets for an API key
- **WHEN** an API key calls the endpoint
- **THEN** `budgets` is empty and the other fields are as before

### Requirement: Reset instant without usage
`resetsAt` SHALL be the end of the current day or month in the rate-limit schedule, also when the caller has no
counter yet, and `used` SHALL then be 0.

#### Scenario: Budget never charged
- **WHEN** the caller has a budget with no spend
- **THEN** `used` is 0 and `resetsAt` is the next period start of the schedule

### Requirement: Legacy cost stats stay
The top-level `*CostStats` and the per-deployment stats SHALL keep reporting the role-based cost limit.

#### Scenario: Budget present
- **WHEN** the caller has a budget
- **THEN** `dayCostStats` and `monthCostStats` are still present
