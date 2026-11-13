# Spec Delta

## ADDED Requirements

### Requirement: Model provider and lifecycle dates
A model in the config file or the Configuration API MAY declare `provider` (free text), `deprecationDate` and
`endOfService` (dates written `YYYY-MM-DD`); `deprecation_date` and `end_of_service` SHALL be accepted as aliases.
Core SHALL store and return the values as written and SHALL NOT validate, compare or act on them. Applications,
routers and toolsets SHALL NOT take them.

#### Scenario: Written through the Configuration API
- **WHEN** an admin sends `PUT /v1/models/platform/m` with `provider` `AWS`, `deprecationDate` `2027-01-15` and
  `endOfService` `2027-07-01`, then `GET /v1/models/platform/m`
- **THEN** the answer has the same three values under those names

### Requirement: Model listing shows provider and lifecycle dates
`GET /openai/models` SHALL return a model's values as `provider`, `deprecation_date` and `end_of_service`, and SHALL
leave out a field that is not set.

#### Scenario: Listing of a model with the fields
- **WHEN** a config model `test-model-v1` sets `provider` `Azure`, `deprecationDate` `2027-06-30` and `endOfService`
  `2027-12-31`, and a caller sends `GET /openai/models`
- **THEN** the entry `test-model-v1` has `provider` `Azure`, `deprecation_date` `2027-06-30` and `end_of_service`
  `2027-12-31`

#### Scenario: Listing of a model without the fields
- **WHEN** a model sets none of the three and a caller sends `GET /openai/models`
- **THEN** the entry has none of `provider`, `deprecation_date` and `end_of_service`
