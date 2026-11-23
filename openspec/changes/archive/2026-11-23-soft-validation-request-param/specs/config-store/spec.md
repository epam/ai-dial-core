# Spec Delta

## MODIFIED Requirements

### Requirement: Invalid entity policy
On a rebuild, Core SHALL skip a blob that fails to parse or decrypt and record it as an invalid entity, in every
mode: `config.onInvalidEntity` does not apply to it. A semantic violation (duplicate deployment id, rebuild invariant)
SHALL, under `config.onInvalidEntity: abort` (default), fail the rebuild and keep the previous config; under `skip` it
SHALL drop that entity alone and record it. A dangling reference is not one: Dangling interceptor reference and
plugin-registry give its outcome.

#### Scenario: Malformed blob skipped under abort
- **WHEN** the blob `models/platform/broken-blob-model` holds `{ this is not json ` and the config reloads with
  `config.onInvalidEntity` unset
- **THEN** the reload answers `200`, the other entities load, and the broken model is recorded as invalid

#### Scenario: Invalid router recorded under skip
- **WHEN** the blob `routers/platform/r-bad` declares `interceptors: ["i1"]` (written to storage directly, not
  through the Configuration API) and the config reloads with `config.onInvalidEntity: skip`
- **THEN** the other entities load, `r-bad` is not a deployment, `GET /v1/admin/health/config` lists
  `routers/platform/r-bad` among the skipped entities, and `GET /v1/routers/platform/r-bad` answers `200` with
  `status: "invalid"`

### Requirement: Dangling interceptor reference
A model interceptor that does not resolve (a dangling reference) SHALL be checked on every write and on every rebuild,
whatever `config.onInvalidEntity`. A checked model SHALL be dropped alone and recorded as an invalid entity, and stay
so until every reference resolves; it never fails the rebuild. A write SHALL answer `422`, or be accepted and recorded
when it carries `softValidation=true` (Soft validation). Creating the interceptor SHALL bring the model back within
seconds.

#### Scenario: Strict write
- **WHEN** whatever `config.onInvalidEntity`, an admin sends `PUT /v1/models/platform/m` with
  `interceptors: ["unknown-interceptor"]` and no `softValidation` parameter
- **THEN** Core answers `422` with warning `interceptors[0]` and writes nothing; the same `PUT` with
  `?softValidation=false` answers the same

#### Scenario: Soft write under skip
- **WHEN** `config.onInvalidEntity: skip` and an admin sends
  `PUT /v1/models/platform/soft-cr-invalid?softValidation=true` with `interceptors: ["unknown-interceptor"]`
- **THEN** Core answers `200` with an `ETag`, the model is not a deployment, its GET answers `status: "invalid"` with
  warning `interceptors[0]`, and `GET /v1/admin/health/config` answers `status: "degraded"`

#### Scenario: Soft write under abort
- **WHEN** `config.onInvalidEntity: abort` and an admin sends the same `PUT` with `?softValidation=true`
- **THEN** Core answers `200` with an `ETag`, the model is not a deployment, its GET answers `status: "invalid"` with
  warning `interceptors[0]`, and `GET /v1/admin/health/config` answers `status: "degraded"`

#### Scenario: Soft write survives a rebuild
- **WHEN** the model of the previous scenario is recorded, whatever `config.onInvalidEntity`, and an admin sends
  `POST /v1/ops/config/reload`
- **THEN** the reload answers `200`, the model is still not a deployment, and its GET still answers
  `status: "invalid"` with warning `interceptors[0]`

#### Scenario: Rebuild under skip
- **WHEN** `config.onInvalidEntity: skip`, a blob model references interceptor `unknown-interceptor`, and the config
  reloads
- **THEN** the reload answers `200`, the other entities load, the model is not a deployment, its GET answers
  `status: "invalid"` with warning `interceptors[0]`, and the health endpoint lists `models/platform/<name>` as skipped

#### Scenario: Rebuild under abort
- **WHEN** `config.onInvalidEntity: abort`, a blob model references interceptor `unknown-interceptor`, and the config
  reloads
- **THEN** the reload answers `200`, the other entities load, the model is not a deployment, its GET answers
  `status: "invalid"` with warning `interceptors[0]`, and the health endpoint lists `models/platform/<name>` as skipped

#### Scenario: File model under abort
- **WHEN** `config.onInvalidEntity: abort` and the file config declares a model that references a missing interceptor
- **THEN** Core starts, the other models are served, the model is not listed by `GET /openai/models`, and the health
  endpoint lists `models/platform/<name>` as skipped

#### Scenario: Other violations still abort
- **WHEN** `config.onInvalidEntity: abort`, a blob model with a dangling interceptor reference also sets
  `pricing.cacheRead` with a `pricing.unit` other than `token`, and an admin sends `POST /v1/ops/config/reload`
- **THEN** Core answers `500` and keeps the previous config

#### Scenario: Apply under abort with soft writes
- **WHEN** `config.onInvalidEntity: abort` and an admin sends `POST /v1/admin/apply?softValidation=true` with
  `precheck: false` and a `Model` referencing a missing interceptor
- **THEN** Core answers `200` with that result `APPLIED_INVALID`, the model is not a deployment, and its GET answers
  `status: "invalid"`

#### Scenario: Validate with soft writes
- **WHEN** whatever `config.onInvalidEntity`, an admin sends `POST /v1/admin/validate?softValidation=true` with a
  `Model` referencing a missing interceptor
- **THEN** Core answers `200` with that result `VALID`, and the model is not written

#### Scenario: Interceptor delete under abort
- **WHEN** `config.onInvalidEntity: abort`, interceptor `i1` and a model `m` that references it exist, and an admin
  sends `DELETE /v1/interceptors/platform/i1`
- **THEN** Core answers `204`, `m` is not a deployment, and its GET answers `status: "invalid"` with warning
  `interceptors[0]`

#### Scenario: Interceptor created later
- **WHEN** whatever `config.onInvalidEntity`, model `m` was written with `softValidation=true` for the missing
  interceptor `i2`, and an admin sends `PUT /v1/interceptors/platform/i2` with a valid interceptor
- **THEN** within a few seconds, with no reload request, `m` is a deployment again, its GET answers `status: "valid"`,
  `GET /openai/models` lists it under its short name `m`, and the health endpoint answers `status: "ok"`

#### Scenario: Interceptor deleted and created again
- **WHEN** `m` was recorded as invalid by the `DELETE` of its interceptor `i1`, and an admin sends
  `PUT /v1/interceptors/platform/i1` with a valid interceptor
- **THEN** within a few seconds, with no reload request, `m` is a deployment again and its GET answers
  `status: "valid"`

### Requirement: Soft validation
A config `PUT`, `/v1/admin/validate` and `/v1/admin/apply` SHALL take the query parameter `softValidation`, `true` or
`false` (default `false`); another value SHALL answer `400` and write nothing. With `true`, a write whose only failure
is a dangling reference (a model's interceptor, a plugin's type) SHALL be written and answer `200`; validate admits it
as `VALID`. Rebuild invariants SHALL still answer `422`. The setting `config.write.softValidation` SHALL have no
effect.

#### Scenario: Soft mode still rejects invariants
- **WHEN** an admin sends `PUT /v1/models/platform/m?softValidation=true` with `interfaces:
  {"openaiChatCompletions": {}}` and no `base_url`
- **THEN** Core answers `422` with a body containing `interfaces.openaiChatCompletions` and `declares no base_url`,
  and nothing is written

#### Scenario: Value that is not a boolean
- **WHEN** an admin sends `PUT /v1/models/platform/m?softValidation=yes` with a valid model
- **THEN** Core answers `400` and `GET /v1/models/platform/m` answers `404`

#### Scenario: Removed setting has no effect
- **WHEN** the settings file sets `config.write.softValidation: true` and an admin sends
  `PUT /v1/models/platform/m` with `interceptors: ["unknown-interceptor"]` and no `softValidation` parameter
- **THEN** Core answers `422` and writes nothing

#### Scenario: Validate admits what apply records
- **WHEN** an admin sends the same body, a `Model` referencing a missing interceptor, with `softValidation=true` to
  `POST /v1/admin/validate` and then to `POST /v1/admin/apply` with `precheck: true`
- **THEN** validate answers `200` with `VALID`, and apply answers `200` with `APPLIED_INVALID`

### Requirement: Write rejected with 422
A config `PUT` SHALL answer `422` `{"validationWarnings": [{"field": ..., "message": ...}]}` and write nothing when a
model breaks a rebuild invariant (pricing, interfaces, translator refs, catalog properties) or, unless
`softValidation=true`, references an interceptor absent from the merged config; when a router breaks a write rule of
router-deployment; when a translator or an `overridePaths` entry is invalid, always; and when a plugin declaration
fails as plugin-registry defines.

#### Scenario: Unknown interceptor
- **WHEN** an admin sends `PUT /v1/models/platform/cr-unknown` with `interceptors: ["unknown-interceptor"]` and no
  such interceptor exists
- **THEN** Core answers `422` with one warning, `field: "interceptors[0]"`, whose `message` contains
  `unknown-interceptor`, and `GET` on the model answers `404`

#### Scenario: Two unknown interceptors
- **WHEN** the model references two missing interceptors
- **THEN** the warnings are `interceptors[0]` and `interceptors[1]`

#### Scenario: Plugin with rejected settings
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with settings its loaded provider rejects
- **THEN** Core answers `422` with a warning whose `field` starts with `settings`, and `GET` on the plugin answers
  `404`

#### Scenario: Router with interceptors rejected on write
- **WHEN** an admin sends `PUT /v1/routers/platform/r-bad` with `{"baseUrl": "http://localhost:4848", "interfaces":
  {"openaiChatCompletions": {}}, "interceptors": ["i1"]}`
- **THEN** Core answers `422` with one warning on `interceptors` whose message contains `A router declares no
  interceptors`, and `GET /v1/routers/platform/r-bad` answers `404`

#### Scenario: Router with a translator mismatch rejected on write
- **WHEN** an admin sends `PUT /v1/routers/platform/r-bad` with `{"baseUrl": "http://localhost:4848", "interfaces":
  {"openaiChatCompletions": {}, "anthropicMessages": {"translator": {"out": "openaiChatCompletions", "baseUrl":
  "http://t"}}}}`
- **THEN** Core answers `422` with a body containing `requires mode 'translator'`

#### Scenario: Typed router properties rejected on write
- **WHEN** an admin sends a typed router whose `applicationProperties` break its schema with `?softValidation=true`
- **THEN** Core answers `422` and writes nothing

### Requirement: Apply partial failure
With `precheck: false`, `/v1/admin/apply` SHALL write each accepted entry and skip each failing one, answering `200`
with `applied` counting `APPLIED` and `APPLIED_INVALID` and `failed` counting `FAILED`. With `softValidation=true` a
model whose only failure is an unresolved interceptor reference, and a plugin whose only failure is an unknown type,
SHALL be written as `APPLIED_INVALID`.

#### Scenario: One good, one bad
- **WHEN** an admin applies with `"precheck": false` a valid model and a model referencing interceptor `does-not-exist`
- **THEN** Core answers `200` with `applied: 1`, `failed: 1`; the good model answers `200` on GET and the bad `404`

#### Scenario: One good, one dangling, with the parameter
- **WHEN** an admin applies with `"precheck": false` and `softValidation=true` a valid model and a model referencing
  interceptor `does-not-exist`
- **THEN** Core answers `200` with `applied: 2`, `failed: 0`, the second result `APPLIED_INVALID`
