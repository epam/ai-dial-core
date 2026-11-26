# Spec Delta

## ADDED Requirements

### Requirement: Error rows are off by default
Core SHALL write the rows of "Error rows for answers Core gives itself" and "Error rows for a relay that breaks" only when
`analytics.errorRows` (env `aidial.analytics.errorRows`) is `true`. Absent, it SHALL be `false`. With it off, every row SHALL be
what Core writes without this setting.

#### Scenario: Setting absent
- **WHEN** Core runs without `analytics.errorRows` and a model's only upstream answers `429` with `maxRetryAttempts: 1`
- **THEN** the client gets `503` and no row is written

#### Scenario: Setting off, a successful pass
- **WHEN** `analytics.errorRows` is `false` and a model answers `200`
- **THEN** the row is the row Core writes without the setting

### Requirement: Error rows use the fields of any row
A row of "Error rows for answers Core gives itself" or "Error rows for a relay that breaks" SHALL have the fields every row has and
no field that other rows lack. When Core gave the answer itself, its `response.body` SHALL be empty and it SHALL have no
`token_usage`.

#### Scenario: Refusal
- **WHEN** `analytics.errorRows` is on and a key that is not `secured` is refused with `403` on
  `/openai/deployments/{id}/chat/completions`
- **THEN** the row has the `request.body` field (empty, as that surface reads the body after the refusal), `response.status: "403"` and `trace`, `response.body` is the empty string, and the row
  has no `token_usage` and no `error` member

#### Scenario: Successful pass with the setting on
- **WHEN** `analytics.errorRows` is on and a model answers `200`
- **THEN** the row is the row written with the setting off
