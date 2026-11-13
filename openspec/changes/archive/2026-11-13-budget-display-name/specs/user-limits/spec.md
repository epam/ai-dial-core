## MODIFIED Requirements

### Requirement: Budgets in the user stats
`GET /v1/user/limits` and `GET /v1/user/usage` SHALL return `budgets[]`, one entry per budget that matches the caller,
each with its `costObject`, `policy`, `displayName` and `priority`, and `day` and `month` as `{total, used, resetsAt}`,
and SHALL list only budgets whose subject matches the caller's claims. The other fields of the response SHALL NOT change.

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

## ADDED Requirements

### Requirement: Budget display name
Each budget's `displayName` SHALL be its policy's non-blank display name, unchanged. If that name is absent, null,
empty or whitespace-only, Core SHALL return the full policy path instead. The same rule SHALL apply to assigned
and self-applying policies on both user stats endpoints.

#### Scenario: Named assigned budgets
- **WHEN** a caller has two assigned budgets with configured policy names in one cost object
- **THEN** both endpoints return each configured name with its corresponding policy and preserve priority order

#### Scenario: Named self-applying budget
- **WHEN** a caller matches a self-applying file policy with a configured name
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
