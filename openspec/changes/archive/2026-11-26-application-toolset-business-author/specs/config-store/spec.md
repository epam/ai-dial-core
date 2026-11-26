## ADDED Requirements

### Requirement: Apply and migration record the caller as audit author
`/v1/admin/apply` and `/v1/admin/config/file/migrate` SHALL record the display name of the calling admin as the audit
author of each entity they create, as a Configuration API `PUT` does, so that `GET /v1/metadata/{type}/platform/{name}`
reports it as `author`. An entity that already exists keeps the audit author it has.

#### Scenario: Applied entity
- **WHEN** an admin applies a `Model` manifest `models/platform/m1` and sends `GET /v1/metadata/models/platform/m1`
- **THEN** the answer has `author` equal to the admin's display name

#### Scenario: Migrated entity
- **WHEN** an admin migrates a model that exists only in the file config with
  `POST /v1/admin/config/file/migrate` and sends `GET /v1/metadata/models/platform/<name>` for it
- **THEN** the answer has `author` equal to the admin's display name

#### Scenario: Existing entity keeps its audit author
- **WHEN** `models/platform/m2` is stored with the audit author `first-admin`, an admin applies a manifest for it, and
  sends `GET /v1/metadata/models/platform/m2`
- **THEN** the answer still has `author` `first-admin`
