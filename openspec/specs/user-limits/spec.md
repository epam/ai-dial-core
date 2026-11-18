# user-limits Specification

## Purpose
Describe what `GET /v1/user/limits` and `GET /v1/user/usage` return about the caller's own spend: the budgets that
match them in every cost object with their labels, models, spend and reset times, the cost object their model calls
are charged to, and the legacy role cost statistics.

## Requirements

### Requirement: Budgets in the user stats
`GET /v1/user/limits` and `GET /v1/user/usage` SHALL return `budgets[]`, one entry per budget that matches the caller,
each with its `costObject`, `policy`, `displayName`, `priority`, `object` (the models its policy covers, `"*"` or the
sorted list of deployment names, as the policy API returns it), and `day` and `month` as `{total, used, resetsAt}`,
and SHALL list only budgets whose subject matches the caller's claims. The `deployments` and `*CostStats` fields SHALL
NOT change.

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
- **THEN** `budgets` is empty, there is no `costObject`, and the other fields are as before

#### Scenario: Budget for every model
- **WHEN** the caller matches a budget whose policy has `object` `"*"`
- **THEN** its entry has `object` `"*"`

#### Scenario: Budget for listed models
- **WHEN** the caller matches a budget whose policy has `object` `["gpt-4o", "claude-opus"]`
- **THEN** its entry has `object` `["claude-opus", "gpt-4o"]`

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

### Requirement: Budget display name
Each budget's `displayName` SHALL be its policy's non-blank display name, unchanged. If that name is absent, null,
empty or whitespace-only, Core SHALL return the full policy path instead. The same rule SHALL apply to assigned
and self-applying policies on both user stats endpoints.

#### Scenario: Named assigned budgets
- **WHEN** a caller has two assigned budgets with configured policy names in one cost object
- **THEN** both endpoints return each configured name with its corresponding policy and preserve priority order

#### Scenario: Named self-applying budget
- **WHEN** a caller matches a self-applying policy with a configured name
- **THEN** both endpoints return that name for the budget

#### Scenario: Missing or blank policy name
- **WHEN** a matching policy has an absent, null, empty or whitespace-only display name
- **THEN** both endpoints return its full policy path as `displayName`

### Requirement: Budget rename preserves spend
Changing only a policy's display name SHALL update the name in subsequent user stats responses without changing
the budget's identifiers, accumulated spend, limits, ordering or reset times within the same windows.

#### Scenario: Rename after a charge
- **WHEN** a budget has accumulated spend and an admin changes only its policy's display name
- **THEN** both endpoints return the new name with the same policy, cost object, priority and window statistics

### Requirement: Caller's cost object in the user stats
`GET /v1/user/limits` and `GET /v1/user/usage` SHALL return a top-level `costObject` when spending limits are `shadow`
or `enforce` and the root caller has a JWT: the token's project claim, or `spendingLimits.defaultCostObject` when it
has none. The root caller is the caller itself, or for a per-request key the caller that started the chain. It SHALL
be absent when the root caller is an API key and when spending limits are `off`.

#### Scenario: Token with a project claim
- **WHEN** `spendingLimits.mode` is `shadow` and a caller whose token carries project `projA` and who matches budgets
  in `projA`, `projB` and the default cost object calls the endpoint
- **THEN** `costObject` is `projA`, and `budgets` still lists all three cost objects

#### Scenario: Enforce mode
- **WHEN** `spendingLimits.mode` is `enforce` and a caller whose token carries project `projA` calls the endpoint
- **THEN** `costObject` is `projA`

#### Scenario: Token without a project claim
- **WHEN** `spendingLimits.mode` is `shadow`, `defaultCostObject` is `PERSONAL` and a caller whose token carries no
  project calls the endpoint
- **THEN** `costObject` is `PERSONAL`

#### Scenario: No matching budget
- **WHEN** `spendingLimits.mode` is `shadow` and a caller whose token carries project `projA` matches no budget
- **THEN** `costObject` is `projA` and `budgets` is empty

#### Scenario: API key caller
- **WHEN** `spendingLimits.mode` is `shadow` and an API key whose key sets a project calls the endpoint
- **THEN** the response has no `costObject` and `budgets` is empty

#### Scenario: Spending limits off
- **WHEN** `spendingLimits.mode` is `off` and a caller whose token carries project `projA` calls the endpoint
- **THEN** the response has no `costObject`

#### Scenario: Application called by a user
- **WHEN** `spendingLimits.mode` is `shadow` and an application, called by a user whose token carries project `projA`,
  calls the endpoint with its per-request key
- **THEN** `costObject` is `projA`

#### Scenario: Application called by an API key
- **WHEN** `spendingLimits.mode` is `shadow` and an application, called with an API key whose key sets a project,
  calls the endpoint with its per-request key
- **THEN** the response has no `costObject` and `budgets` is empty
