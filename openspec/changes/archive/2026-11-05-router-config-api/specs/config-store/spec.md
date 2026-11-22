# Spec Delta

## ADDED Requirements

### Requirement: Router managed in place
A router written or deleted through the Configuration API SHALL be visible on return as a deployment: after a
`PUT` the router answers requests and is listed by `GET /v1/routers` for a caller its `userRoles` allow; after a
`DELETE` it answers `404` on both.

#### Scenario: Blob router serves at once
- **WHEN** an admin sends `PUT /v1/routers/platform/r-blob` with `{"baseUrl": "http://localhost:4848",
  "userRoles": ["analysts"], "interfaces": {"openaiChatCompletions": {}}}` and Core answers `200`
- **THEN** `GET /v1/routers` by a caller with role `analysts` lists `r-blob`, and
  `POST /openai/deployments/r-blob/chat/completions` by that caller is forwarded to the router

#### Scenario: Deleted router gone at once
- **WHEN** an admin sends `DELETE /v1/routers/platform/r-blob` and Core answers `204`
- **THEN** `GET /v1/routers/r-blob` answers `404`

#### Scenario: Rejected write leaves nothing behind
- **WHEN** an admin's `PUT /v1/routers/platform/r-bad` is answered `422` and the admin retries a valid body with
  `If-None-Match: *`
- **THEN** Core answers `200`

## MODIFIED Requirements

### Requirement: Merged sources
Core SHALL serve the union of the file config and the blobs of the `platform` bucket for `models`, `interceptors`,
`translators`, `routers`, `roles`, `keys`, `routes`, `schemas`, `catalog_schemas`, `applications`, `toolsets`,
`policies` and `policy_assignments`, plus the `settings/platform/global` overlay. `public`-bucket applications and
toolsets SHALL NOT enter the merged config.

#### Scenario: Blob model is a deployment
- **WHEN** an admin sends `PUT /v1/models/platform/m1` with `{"type":"chat","endpoint":"http://host/chat"}`
- **THEN** `m1` resolves as a deployment for the next request, next to the file-defined models

#### Scenario: Blob router survives a reload
- **WHEN** an admin sends `PUT /v1/routers/platform/r-blob` with a valid router and then `POST /v1/ops/config/reload`
- **THEN** `r-blob` still resolves as a deployment, and `GET /v1/routers/platform/r-blob` answers `200`

### Requirement: Blob wins on a name clash
A blob entity SHALL replace, as a whole, the file entity of the same type with the same short name (models,
interceptors, translators, routers, roles, applications, toolsets) or, for schemas, the same `$id`; no field of the
file entity SHALL survive. Requests and references resolve those types by that name, so the rebuild puts the blob
entity at the file entity's map key and replaces it there.

#### Scenario: Blob model shadows the file model
- **WHEN** the file defines model `test-model-v1` and a blob `models/platform/test-model-v1` declares
  `endpoint: http://localhost:7001/openai/deployments/migrated/chat/completions`, and the config reloads
- **THEN** deployment `test-model-v1` resolves to the blob entity with that endpoint, and `GET /openai/models` lists
  `test-model-v1` once

#### Scenario: Blob schema shadows the file schema by $id
- **WHEN** a blob `schemas/platform/blob-schema-1` declares the `$id` of a file-defined application type schema
- **THEN** the merged schema for that `$id` is the blob's, and no key `schemas/platform/blob-schema-1` exists

#### Scenario: Blob router shadows the file router
- **WHEN** the file defines router `r1` with `userRoles: ["analysts"]` and an admin writes
  `routers/platform/r1` with `userRoles: ["default"]`, `baseUrl` and `interfaces.openaiChatCompletions: {}` only,
  and the config reloads
- **THEN** `GET /v1/routers` lists `r1` once for a caller with role `default` and not for one with role `analysts`
  only

### Requirement: Invalid entity policy
On a rebuild, Core SHALL skip a blob that fails to parse or decrypt and record it as an invalid entity, in every
mode: `config.onInvalidEntity` does not apply to it. A semantic violation (duplicate deployment id, rebuild invariant,
and under `skip` a dangling reference) SHALL, under `config.onInvalidEntity: abort` (default), fail the rebuild and keep
the previous config; under `skip` it SHALL drop that entity alone and record it.

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

### Requirement: Deployment id uniqueness on write
A `PUT` of a model, interceptor, router, application or toolset SHALL answer `409`
`Deployment ID '<name>' is already used by a different entity` when a deployment of another of those types already
has that name in the merged config.

#### Scenario: Model name taken by an application
- **WHEN** the merged config has application `x` and an admin sends `PUT /v1/models/platform/x`
- **THEN** Core answers `409` and writes nothing

#### Scenario: Router name taken by a model
- **WHEN** the merged config has model `gpt-4` and an admin sends `PUT /v1/routers/platform/gpt-4`
- **THEN** Core answers `409` and writes nothing

#### Scenario: Model name taken by a router
- **WHEN** the merged config has router `r1` and an admin sends `PUT /v1/models/platform/r1`
- **THEN** Core answers `409` and writes nothing

### Requirement: Write rejected with 422
A config `PUT` SHALL answer `422` `{"validationWarnings": [{"field": ..., "message": ...}]}` and write nothing when a
model breaks a rebuild invariant (pricing, interfaces, translator refs, catalog properties) or, unless soft mode is
on, references an interceptor absent from the merged config; when a router breaks one of its rebuild invariants
(router-deployment, "Router config entry"); and when a translator or an `overridePaths` entry is invalid, whatever
the mode.

#### Scenario: Unknown interceptor
- **WHEN** an admin sends `PUT /v1/models/platform/cr-unknown` with `interceptors: ["unknown-interceptor"]` and no
  such interceptor exists
- **THEN** Core answers `422` with one warning, `field: "interceptors[0]"`, whose `message` contains
  `unknown-interceptor`, and `GET` on the model answers `404`

#### Scenario: Two unknown interceptors
- **WHEN** the model references two missing interceptors
- **THEN** the warnings are `interceptors[0]` and `interceptors[1]`

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

### Requirement: Manifest names
A manifest `name` SHALL be `<segment>/<bucket>/<name>` with segment `settings`, `schemas`, `catalog_schemas`,
`interceptors`, `translators`, `routers`, `roles`, `keys`, `routes`, `models`, `toolsets`, `applications`,
`policies` or `policy_assignments` matching `kind`; bucket `platform`, or `public` for `Application` and `ToolSet`;
one segment matching the entity-name pattern (policies may add one folder). `Settings` SHALL be
`settings/platform/global`.

#### Scenario: Wrong bucket
- **WHEN** a manifest has `kind: "Model"` and `name: "models/public/m"`
- **THEN** that entry fails with `'name' bucket segment must be 'platform', got 'public'`

#### Scenario: Router manifest in the public bucket
- **WHEN** a manifest has `kind: "Router"` and `name: "routers/public/r"`
- **THEN** that entry fails with `'name' bucket segment must be 'platform', got 'public'`

### Requirement: Batch dependency order
Core SHALL process a batch in kind order `Settings`, `Schema`/`CatalogSchema`, `Translator`, `Interceptor`,
`Router`, `Role`, `Key`, `Route`, `Model`, `ToolSet`, `Application`, `Policy`, `PolicyAssignment` (input order
within a kind), report results in that order, and resolve each entry's references against the merged config plus
the batch's earlier accepted entries.

#### Scenario: Model before its interceptor in the input
- **WHEN** a batch lists a `Model` referencing interceptor `i1` before the `Interceptor` `interceptors/platform/i1`
- **THEN** both entries are accepted

#### Scenario: Router applied with its in-batch translator
- **WHEN** an admin applies, in this input order, a `Model`, then a `Router` `routers/platform/r-batch` with
  `{"baseUrl": "http://localhost:4848", "interfaces": {"openaiChatCompletions": {}, "anthropicMessages": {"mode":
  "translator", "translator": "t-batch"}}}`, then the `Translator` `translators/platform/t-batch` converting
  `anthropicMessages` to `openaiChatCompletions`
- **THEN** Core answers `200` with all `APPLIED`, results ordered translator, router, model, and
  `GET /v1/routers/platform/r-batch` answers `200`

### Requirement: Validate endpoint
`/v1/admin/validate` SHALL write nothing and answer `{"valid": <n>, "failed": <n>, "results": [{"entityId", "status",
"error"}]}` with status `VALID` or `FAILED`, predicting the answer of `/v1/admin/apply` to the same body. With
`precheck` true and any failure it SHALL answer `422` with every `VALID` entry turned `SKIPPED`, as apply with
`precheck` does; otherwise `200`. `valid` counts `VALID` entries only, so a `SKIPPED` entry counts in neither.

#### Scenario: Dangling reference with precheck
- **WHEN** an admin validates one `Model` referencing a missing interceptor
- **THEN** Core answers `422` with `valid: 0`, `failed: 1`, that result `FAILED`, and the model is not written

#### Scenario: Dangling reference without precheck
- **WHEN** the same body carries `"precheck": false`
- **THEN** Core answers `200` with `valid: 0`, `failed: 1`, status `FAILED`, and the model is not written

#### Scenario: Router manifest with a legacy endpoint fails
- **WHEN** an admin validates one `Router` manifest `routers/platform/r-bad` whose spec has `endpoint:
  http://router/chat` and `interfaces.openaiChatCompletions: {}`
- **THEN** that result is `FAILED` with an error containing `A router declares interfaces, not endpoint`, and
  `POST /v1/admin/apply` with the same body and `"precheck": false` answers the same result and writes nothing

#### Scenario: Model manifest named after a file router fails
- **WHEN** an admin validates one `Model` manifest `models/platform/r1` while the file defines router `r1`
- **THEN** that result is `FAILED` with error `Deployment ID 'r1' is already used by a different entity`

### Requirement: File migration
`POST /v1/admin/config/file/migrate` with `{"types": [...] | ["all"] (default all), "dryRun": <bool, default false>}`
SHALL copy each file entity of those types (`routers` included) missing from the `platform` bucket into a blob
through the apply path, answering `200` `{"results": [{"kind", "resourceUrl", "key", "status", "reason"}]}`. An
unsupported type SHALL answer `400`; a non-admin `403`. The file is not changed.

#### Scenario: Models migrated, then skipped
- **WHEN** an admin sends `{"types": ["models"]}` twice
- **THEN** the first answer has `resourceUrl: "models/platform/test-model-v1"`, `key: "test-model-v1"`, `status:
  "migrated"`; the second has every status `skipped` with reason `already in blob`

#### Scenario: Unsupported type
- **WHEN** an admin sends `{"types": ["not-a-real-type"]}`
- **THEN** Core answers `400`

#### Scenario: File router migrated
- **WHEN** the file defines router `r1` and an admin sends `{"types": ["routers"]}`
- **THEN** the answer has `resourceUrl: "routers/platform/r1"`, `kind: "Router"`, `status: "migrated"`, and
  `GET /v1/routers/platform/r1` answers `200`
