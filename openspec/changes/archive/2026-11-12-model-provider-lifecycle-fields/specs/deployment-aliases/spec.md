# Spec Delta

## MODIFIED Requirements

### Requirement: Content of an alias entry
A listing entry of an alias SHALL carry the alias's name and display fields and the target's features, pricing,
limits, defaults, interfaces, provider and lifecycle dates (`provider`, `deprecation_date`, `end_of_service`), and
SHALL NOT carry the target's path or endpoint. The display name, description,
icon, intro and keywords SHALL be the target's when the alias does not set them; the display version, author, dates
and catalog fields SHALL NOT fall back to the target's.

#### Scenario: Model entry
- **WHEN** a caller with `default-user` lists `/openai/models`
- **THEN** the list has `assistant` with `display_name` `Assistant` and the pricing of `gpt-4-internal`, and not `gpt-4-internal`

#### Scenario: Display fields of the target
- **WHEN** an alias sets none of `displayName`, `description`, `iconUrl` and `displayVersion`, and its target model has all of them
- **THEN** `/openai/models` shows the alias with the model's display name, description and icon, and without a display version

#### Scenario: Application shown as a model
- **WHEN** an alias with `presentAs: model` targets an application
- **THEN** `/openai/models/<alias>` answers `200` with `object` `model`

#### Scenario: Provider and lifecycle dates of the target
- **WHEN** an alias targets a model with `provider` `Azure`, `deprecationDate` `2027-06-30` and `endOfService`
  `2027-12-31`
- **THEN** `/openai/models` shows the alias with the same `provider`, `deprecation_date` and `end_of_service`
