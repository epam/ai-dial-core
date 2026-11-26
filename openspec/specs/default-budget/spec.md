# default-budget Specification

## Purpose
Describe the user's default budget: the one budget they hold that they pick to pay their calls that name no budget,
`GET` and `PUT /v1/user/budgets/default`, who may use them, and the private record Core stores.

## Requirements

### Requirement: Set the default budget
`PUT /v1/user/budgets/default` with the body `{"budget": "<costObject>/<id>"}` SHALL store that budget as the
caller's default and answer 200 with `{"budget": "<costObject>/<id>"}`, when the budget is one the caller holds: a
cost budget whose subject matches the caller's claims and whose assignment has not expired, in any cost object, as
`GET /v1/user/limits` lists them. A later PUT SHALL replace the stored default. The endpoint SHALL work in every
`spendingLimits.mode`.

#### Scenario: Set and read a held budget
- **WHEN** a user who holds budget `projA/a1` PUTs `{"budget": "projA/a1"}`
- **THEN** the answer is 200 with `{"budget": "projA/a1"}`, and `GET /v1/user/budgets/default` answers
  `{"budget": "projA/a1"}`

#### Scenario: Replace the default
- **WHEN** a user who holds `projA/a1` and `projB/b1` sets `projA/a1` and then PUTs `{"budget": "projB/b1"}`
- **THEN** `GET /v1/user/budgets/default` answers `{"budget": "projB/b1"}`

### Requirement: Clear the default budget
`PUT /v1/user/budgets/default` with `{"budget": null}` SHALL remove the caller's default and answer 200 with
`{"budget": null}`, also when none is set.

#### Scenario: Clear a set default
- **WHEN** a user with default `projA/a1` PUTs `{"budget": null}`
- **THEN** the answer is 200 with `{"budget": null}`, and `GET /v1/user/budgets/default` answers `{"budget": null}`

### Requirement: Only a held budget can be set
`PUT /v1/user/budgets/default` SHALL answer 400 and keep the stored default when the named budget is not one the
caller holds. The message SHALL be the same whether the budget does not exist, belongs to another subject or has
expired, so it tells nothing about other people's budgets. A body that is not a JSON object with a `budget` field
holding a string or null SHALL get 400.

#### Scenario: Budget of another subject
- **WHEN** a user PUTs the id of a budget whose subject does not match them
- **THEN** the answer is 400, and the stored default is unchanged

#### Scenario: Budget that does not exist
- **WHEN** a user PUTs `{"budget": "projA/missing"}`
- **THEN** the answer is 400 with the same message as for a budget of another subject

#### Scenario: Malformed body
- **WHEN** a user PUTs `{}`, `{"budget": 5}` or a body that is not JSON
- **THEN** the answer is 400

### Requirement: A budget no longer held reads as unset
`GET /v1/user/budgets/default` SHALL answer 200 with `{"budget": null}` when no default is stored, and when the
stored budget is no longer one the caller holds (its assignment withdrawn or expired, or its subject no longer
matching). Reading SHALL NOT change the stored record.

#### Scenario: Nothing set
- **WHEN** a user who never set a default calls `GET /v1/user/budgets/default`
- **THEN** the answer is 200 with `{"budget": null}`

#### Scenario: Assignment withdrawn
- **WHEN** a user sets `projA/a1` as default and the admin then deletes the assignment `projA/a1`
- **THEN** `GET /v1/user/budgets/default` answers `{"budget": null}`

#### Scenario: Assignment expired
- **WHEN** a user's default budget's assignment passes its `expiresAt`
- **THEN** `GET /v1/user/budgets/default` answers `{"budget": null}`

### Requirement: Only the user sets their default
The endpoints SHALL answer 403 to a caller that is not a user signed in with a JWT calling directly: an API key, and
a call made with a per-request key, whatever the method.

#### Scenario: API key caller
- **WHEN** an API key calls `GET` or `PUT /v1/user/budgets/default`
- **THEN** the answer is 403

#### Scenario: Application on the user's behalf
- **WHEN** an application calls `PUT /v1/user/budgets/default` with its per-request key
- **THEN** the answer is 403

### Requirement: The default budget record is private
Core SHALL store the default as one blob record of type `user_budget_default` in the user's bucket, holding
`{"budget": "<costObject>/<id>"}`, and SHALL delete the record when the default is cleared. No files or resource API
route, listing or subscription SHALL read or write the record.

#### Scenario: Not reachable through the resource API
- **WHEN** a user with a default calls `GET /v1/user_budget_default/<their bucket>/default`
- **THEN** the answer is 404
