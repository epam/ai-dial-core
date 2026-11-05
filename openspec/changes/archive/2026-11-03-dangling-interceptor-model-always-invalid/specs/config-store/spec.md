## MODIFIED Requirements

### Requirement: Invalid entity policy
On a rebuild, Core SHALL skip a blob that fails to parse or decrypt and record it as an invalid entity, in every
mode: `config.onInvalidEntity` does not apply to it. A semantic violation (duplicate deployment id, rebuild invariant)
SHALL, under `config.onInvalidEntity: abort` (default), fail the rebuild and keep the previous config; under `skip` it
SHALL drop that entity alone and record it. A dangling model interceptor reference is not one: Dangling interceptor
reference gives its outcome.

#### Scenario: Malformed blob skipped under abort
- **WHEN** the blob `models/platform/broken-blob-model` holds `{ this is not json ` and the config reloads, whatever
  `config.onInvalidEntity`
- **THEN** the reload answers `200`, the other entities load, and the broken model is recorded as invalid

### Requirement: Dangling interceptor reference
A model interceptor that does not resolve (a dangling reference) SHALL be checked on every write and on every rebuild,
whatever `config.onInvalidEntity`. A checked model SHALL be dropped alone and recorded as an invalid entity; it never
fails the rebuild. A write SHALL answer `422` when `config.write.softValidation` is `false`, and SHALL be accepted,
then recorded, when it is `true`. The scenarios give each outcome.

#### Scenario: Strict write
- **WHEN** `config.write.softValidation` is `false`, whatever `config.onInvalidEntity`, and an admin sends
  `PUT /v1/models/platform/m` with `interceptors: ["unknown-interceptor"]`
- **THEN** Core answers `422` with warning `interceptors[0]` and writes nothing

#### Scenario: Soft write under skip
- **WHEN** `config.write.softValidation` is `true`, `config.onInvalidEntity: skip`, and an admin sends
  `PUT /v1/models/platform/soft-cr-invalid` with `interceptors: ["unknown-interceptor"]`
- **THEN** Core answers `200` with an `ETag`, the model is not a deployment, its GET answers `status: "invalid"` with
  warning `interceptors[0]`, and `GET /v1/admin/health/config` answers `status: "degraded"`

#### Scenario: Soft write under abort
- **WHEN** `config.write.softValidation` is `true`, `config.onInvalidEntity: abort`, and an admin sends the same `PUT`
- **THEN** Core answers `200` with an `ETag`, the model is not a deployment, its GET answers `status: "invalid"` with
  warning `interceptors[0]`, and `GET /v1/admin/health/config` answers `status: "degraded"`

#### Scenario: Rebuild under skip
- **WHEN** `config.onInvalidEntity: skip`, whatever `config.write.softValidation`, a blob model references
  interceptor `unknown-interceptor`, and the config reloads
- **THEN** the reload answers `200`, the other entities load, the model is not a deployment, its GET answers
  `status: "invalid"` with warning `interceptors[0]`, and the health endpoint lists `models/platform/<name>` as skipped

#### Scenario: Rebuild under abort
- **WHEN** `config.onInvalidEntity: abort`, whatever `config.write.softValidation`, a blob model references
  interceptor `unknown-interceptor`, and the config reloads
- **THEN** the reload answers `200`, the other entities load, the model is not a deployment, its GET answers
  `status: "invalid"` with warning `interceptors[0]`, and the health endpoint lists `models/platform/<name>` as skipped

#### Scenario: File model under abort
- **WHEN** `config.onInvalidEntity: abort`, whatever `config.write.softValidation`, and the file config declares a
  model that references a missing interceptor
- **THEN** Core starts, the other models are served, the model is not listed by `GET /openai/models`, and the health
  endpoint lists `models/platform/<name>` as skipped

#### Scenario: Other violations still abort
- **WHEN** `config.onInvalidEntity: abort`, whatever `config.write.softValidation`, a blob model with a dangling
  interceptor reference also sets `pricing.cacheRead` with a `pricing.unit` other than `token`, and an admin sends
  `POST /v1/ops/config/reload`
- **THEN** Core answers `500` and keeps the previous config

#### Scenario: Apply under abort with soft writes
- **WHEN** `config.onInvalidEntity: abort`, `config.write.softValidation` is `true`, and an admin applies a `Model`
  referencing a missing interceptor with `precheck: false`
- **THEN** Core answers `200` with that result `APPLIED_INVALID`, the model is not a deployment, and its GET answers
  `status: "invalid"`

#### Scenario: Validate with soft writes
- **WHEN** `config.write.softValidation` is `true`, whatever `config.onInvalidEntity`, and an admin validates a
  `Model` referencing a missing interceptor
- **THEN** Core answers `200` with that result `VALID`, and the model is not written

#### Scenario: Interceptor delete under abort
- **WHEN** `config.onInvalidEntity: abort`, whatever `config.write.softValidation`, interceptor `i1` and a model `m`
  that references it exist, and an admin sends `DELETE /v1/interceptors/platform/i1`
- **THEN** Core answers `204`, `m` is not a deployment, and its GET answers `status: "invalid"` with warning
  `interceptors[0]`

#### Scenario: Interceptor created later
- **WHEN** `config.onInvalidEntity: abort`, model `m` is recorded as invalid for the missing interceptor `i2`, an
  admin sends `PUT /v1/interceptors/platform/i2` with a valid interceptor, and the config reloads
- **THEN** `m` is a deployment again and its GET answers `status: "valid"`
