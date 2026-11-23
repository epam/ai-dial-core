# Spec Delta

## MODIFIED Requirements

### Requirement: Router properties validated against the schema
Core SHALL validate a typed router's `applicationProperties` against its schema on the file load (against the
file's schemas, as for an application) and on every Configuration API write (against the merged config plus a
batch's earlier `Schema` entries), and SHALL reject the file, or answer `422` or fail the manifest entry and write
nothing, when the schema is unknown or the properties do not validate, whatever the `softValidation` parameter.

#### Scenario: Unknown schema
- **WHEN** an admin sends `PUT /v1/routers/platform/r-bad` with `applicationTypeSchemaId: "https://dial.test/none"`
  and `applicationProperties: {"tier": "medium"}`
- **THEN** Core answers `422` with a warning on `applicationTypeSchemaId` and `GET /v1/routers/platform/r-bad`
  answers `404`

#### Scenario: Properties that break the schema
- **WHEN** the schema `https://dial.test/router-type` requires the string `tier` and an admin sends
  `PUT /v1/routers/platform/r-bad` with that schema and `applicationProperties: {"tier": 5}`
- **THEN** Core answers `422` with a warning on `applicationProperties`

#### Scenario: File router that breaks its schema
- **WHEN** the config file declares the schema `https://dial.test/router-type` and a router with that schema and
  `applicationProperties: {"tier": 5}`
- **THEN** the file load fails

#### Scenario: Schema and router in one batch
- **WHEN** an admin applies, in this input order, a `Router` using the schema `https://dial.test/batch-type` and the
  `Schema` with that `$id`
- **THEN** Core answers `200` with both `APPLIED`
