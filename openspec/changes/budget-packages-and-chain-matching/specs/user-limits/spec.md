# Spec Delta

## MODIFIED Requirements

### Requirement: Budgets in the user stats
`GET /v1/user/limits` and `GET /v1/user/usage` SHALL return `budgets[]`, one entry per budget that matches the caller,
each with its `costObject`, `policy`, `displayName`, `priority`, `object` (what its policy covers as the policy API
returns it: `"*"`, a package reference such as `"@engineering"`, or the sorted list of deployment names and package
references; never the members of a package), and `day` and `month` as `{total, used, resetsAt}`,
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

#### Scenario: Budget for a package
- **WHEN** the caller matches a budget whose policy has `object` `["gpt-6-luna", "@engineering"]`
- **THEN** its entry has `object` `["@engineering", "gpt-6-luna"]`, not the members of `engineering`
