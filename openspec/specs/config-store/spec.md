# config-store Specification

## Purpose
The dynamic config as a store: how Core loads and hot-reloads the config files, how it merges them with the
entities written through the Configuration API into blob storage (`platform` bucket), which source wins on a name
clash and per field, who may read and write it, and what the CRUD, validate, apply and file-migration endpoints
accept and answer. The meaning of each deployment field is `deployment-interfaces-config`; access to user and
`public` resources outside the config types is `resource-access-control`.

Requirements run in four parts that share one write path, admin role and validation: config files, merge, reload and
the invalid entity policy (Config files to Config health endpoint); the per-entity Configuration API (Config
resource URLs to External services on write); the batch validate and apply endpoints (Manifest batch request to
Apply partial failure); and file config inspection and migration.

## Requirements

### Requirement: Config files
Core SHALL build the file config by deep-merging, in order, the JSON files listed in the static setting
`config.files` (built-in default `[]`), later files overriding earlier keys. Arrays SHALL concatenate unless
`config.jsonMergeStrategy.overwriteArrays` is `true` (default `false`). A path not found on disk SHALL be read
from the classpath.

#### Scenario: Arrays concatenate by default
- **WHEN** `config.files` lists two files that both declare `roles.<r>.userRoles` arrays, and
  `config.jsonMergeStrategy.overwriteArrays` is unset
- **THEN** the loaded role carries the elements of both arrays

#### Scenario: Arrays overwrite on request
- **WHEN** the same two files load with `config.jsonMergeStrategy.overwriteArrays: true`
- **THEN** the loaded role carries only the second file's array

### Requirement: Canonical-shaped file keys rejected
Core SHALL fail the file load when a key under `models`, `interceptors`, `roles`, `keys` or `routes` has the shape
`<that type>/<bucket>/<name>`, with a message naming the key and "canonical-ID shape". Any other file key containing
`/` under `models`, `applications`, `interceptors`, `translators`, `roles`, `routes` or `toolsets` SHALL be dropped
with a warning and not recorded as an invalid entity.

#### Scenario: Canonical-shaped model key
- **WHEN** a config file declares `models: {"models/platform/gpt": {...}}`
- **THEN** the load fails with an error containing `models/platform/gpt` and `canonical-ID shape`

#### Scenario: Slash-keyed model dropped
- **WHEN** a config file declares models `bad/model` and `valid-model`
- **THEN** the merged config contains `valid-model`, not `bad/model`, and `GET /v1/admin/health/config` lists no
  skipped entity for it

### Requirement: Periodic reload keeps the last good config
Core SHALL re-read the config files every `config.reload` ms (default `60000`) and then rebuild the merged config
from the files and the blobs. A periodic re-read that fails to open, parse or validate the files SHALL keep the
last good config and log a warning.

#### Scenario: Broken file on periodic reload
- **WHEN** a config file is edited to invalid JSON while Core runs
- **THEN** after the next reload period Core still serves the deployments of the last good config

### Requirement: Startup fails on a rejected config
Core SHALL fail to start when the initial file load fails, or when `config.onInvalidEntity` is `abort` and the
initial merged rebuild finds a semantic violation.

#### Scenario: Duplicate deployment id at startup
- **WHEN** the file config declares a model and an application both named `x` and `config.onInvalidEntity` is `abort`
- **THEN** Core does not start

### Requirement: Admin reload endpoint
Core SHALL serve `POST /v1/ops/config/reload` to admins: it re-reads the files, rebuilds the merged config
synchronously and answers `200` with the merged config as JSON. Unlike the rest of this capability, it SHALL decide
admin by the resource admin rule of resource-access-control, so an empty `access.admin.rules` lets every caller
without a per-request key reload. A non-admin SHALL get `403` `User must be admin`; a failed file load or rebuild
SHALL answer `500` and keep the previous config.

#### Scenario: Reload picks up a new blob
- **WHEN** a model blob `test-model-v1` is written straight to the `platform` bucket and an admin sends
  `POST /v1/ops/config/reload`
- **THEN** Core answers `200` with a body containing `"models"`, and the merged model `test-model-v1` is the blob's

#### Scenario: Non-admin reload
- **WHEN** a caller without the admin role sends `POST /v1/ops/config/reload`
- **THEN** Core answers `403` with body `User must be admin`

### Requirement: Merged sources
Core SHALL serve the union of the file config and the blobs of the `platform` bucket for `models`, `interceptors`,
`translators`, `roles`, `keys`, `routes`, `schemas`, `catalog_schemas`, `applications`, `toolsets`, `policies`
and `policy_assignments`, plus the `settings/platform/global` overlay. `public`-bucket applications and toolsets
SHALL NOT enter the merged config.

#### Scenario: Blob model is a deployment
- **WHEN** an admin sends `PUT /v1/models/platform/m1` with `{"type":"chat","endpoint":"http://host/chat"}`
- **THEN** `m1` resolves as a deployment for the next request, next to the file-defined models

### Requirement: Blob wins on a name clash
A blob entity SHALL replace, as a whole, the file entity of the same type with the same short name (models,
interceptors, translators, roles, applications, toolsets) or, for schemas, the same `$id`; no field of the file entity
SHALL survive. Requests and references resolve those types by that name, so the rebuild puts the blob entity at the
file entity's map key and replaces it there.

#### Scenario: Blob model shadows the file model
- **WHEN** the file defines model `test-model-v1` and a blob `models/platform/test-model-v1` declares
  `endpoint: http://localhost:7001/openai/deployments/migrated/chat/completions`, and the config reloads
- **THEN** deployment `test-model-v1` resolves to the blob entity with that endpoint, and `GET /openai/models` lists
  `test-model-v1` once

#### Scenario: Blob schema shadows the file schema by $id
- **WHEN** a blob `schemas/platform/blob-schema-1` declares the `$id` of a file-defined application type schema
- **THEN** the merged schema for that `$id` is the blob's, and no key `schemas/platform/blob-schema-1` exists

### Requirement: Keys and routes never shadowed
Keys and routes are not resolved by name (a key by its secret `key` value, a route by its `paths`), so a file entry of
those types SHALL keep its file key (the key secret, the route name) and a blob one SHALL be keyed by canonical id
`<type>/platform/<name>`: both SHALL stay in force side by side, and a blob never shadows a file entry.

#### Scenario: Blob route next to the file route of the same name
- **WHEN** the file declares route `plain` and an admin sends `PUT /v1/routes/platform/plain` with a valid route
- **THEN** the merged config holds both routes, under keys `plain` and `routes/platform/plain`

### Requirement: Settings overlay
When the blob `settings/platform/global` exists and parses, Core SHALL take `globalInterceptors`,
`retriableErrorCodes` and `rateLimitSchedule` from it, a missing field reading as empty (`rateLimitSchedule`
defaulting to timezone `UTC`), instead of from the file. Deleting the blob SHALL restore the file values.

#### Scenario: Empty settings blob
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with `{}`
- **THEN** `GET /v1/settings/platform/global` answers `globalInterceptors: []`, `retriableErrorCodes: []` and
  `rateLimitSchedule.timezone: "UTC"`

#### Scenario: Delete reverts to the file
- **WHEN** an admin sends `DELETE /v1/settings/platform/global` after a `PUT`
- **THEN** Core answers `204`, `GET /v1/settings/platform/global` answers `404`, and the effective settings are the
  file's

### Requirement: Write visible on return
Core SHALL apply a successful Configuration API write or delete to the merged config before answering, so the
next request on that pod sees it; other pods SHALL apply it on the resource event, and every pod SHALL converge on
the next full rebuild.

#### Scenario: New key authenticates at once
- **WHEN** an admin sends `PUT /v1/keys/platform/test-key-auth` with `{"key":"secret-auth-roundtrip","project":"projA","roles":["admin"]}`
  and Core answers `200`
- **THEN** `GET /v1/bucket` with `Api-key: secret-auth-roundtrip` answers `200`

### Requirement: Invalid entity policy
On a rebuild, Core SHALL skip a blob that fails to parse or decrypt and record it as an invalid entity, in every
mode: `config.onInvalidEntity` does not apply to it. A semantic violation (duplicate deployment id, rebuild invariant,
and under `skip` a dangling reference) SHALL, under `config.onInvalidEntity: abort` (default), fail the rebuild and keep
the previous config; under `skip` it SHALL drop that entity alone and record it.

#### Scenario: Malformed blob skipped under abort
- **WHEN** the blob `models/platform/broken-blob-model` holds `{ this is not json ` and the config reloads with
  `config.onInvalidEntity` unset
- **THEN** the reload answers `200`, the other entities load, and the broken model is recorded as invalid

### Requirement: Dangling interceptor reference
A model interceptor that does not resolve (a dangling reference) SHALL be checked on every write, and on a rebuild
only under `config.onInvalidEntity: skip`, where it is a semantic violation. Under `abort` the rebuild SHALL NOT check
it, so that an inconsistency already in the file config cannot fail the whole config. The scenarios give the outcome
for each operation, `config.write.softValidation` and `config.onInvalidEntity`.

#### Scenario: Strict write
- **WHEN** `config.write.softValidation` is `false`, whatever `config.onInvalidEntity`, and an admin sends
  `PUT /v1/models/platform/m` with `interceptors: ["unknown-interceptor"]`
- **THEN** Core answers `422` with warning `interceptors[0]` and writes nothing

#### Scenario: Soft write under skip
- **WHEN** soft mode and `config.onInvalidEntity: skip` are on and an admin sends
  `PUT /v1/models/platform/soft-cr-invalid` with `interceptors: ["unknown-interceptor"]`
- **THEN** Core answers `200` with an `ETag`, the model is not a deployment, and its GET answers `status: "invalid"`
  with warning `interceptors[0]`

#### Scenario: Soft write under abort
- **WHEN** soft mode is on, `config.onInvalidEntity` is unset, and the same `PUT` is sent
- **THEN** Core answers `200`, the model is a deployment, its GET answers `status: "valid"`, and
  `POST /openai/deployments/soft-cr-invalid/chat/completions` answers `404` `Interceptor is not found`

#### Scenario: Rebuild under skip
- **WHEN** `config.onInvalidEntity: skip` and a blob model references interceptor `unknown-interceptor`
- **THEN** after the rebuild the model is not a deployment and is recorded as invalid with warning field
  `interceptors[0]`

#### Scenario: Rebuild under abort
- **WHEN** `config.onInvalidEntity` is unset and a blob model references interceptor `unknown-interceptor`
- **THEN** after the rebuild the model is a deployment, its GET answers `status: "valid"`, the health endpoint
  answers `status: "ok"`, and a deployment request to it answers `404` `Interceptor is not found`

### Requirement: Invalid entity on read
For a recorded invalid entity, per-entity GET SHALL answer `200` with the stored payload's fields minus every
encrypted field, `name` (the simple name), `status: "invalid"` and `validationWarnings` as
`[{"field": ..., "message": ...}]`. A record SHALL clear on the first rebuild that accepts the entity.

#### Scenario: Malformed blob read
- **WHEN** an admin sends `GET /v1/models/platform/broken-get-model` for a blob holding `not json at all`
- **THEN** Core answers `200` with `name: "broken-get-model"`, `status: "invalid"` and a non-empty
  `validationWarnings` array, and no `source` field

#### Scenario: Fixed blob clears
- **WHEN** the same blob is replaced by a valid model and the config reloads
- **THEN** the GET answers `status: "valid"` and the health endpoint answers `status: "ok"` with `skipped: []`

### Requirement: Config health endpoint
Core SHALL serve `GET /v1/admin/health/config` to admins only, answering `200`
`{"status": "ok"|"degraded", "skipped": [{"id": "<canonical id>", "reason": "<reason>"}]}`, `degraded` whenever an
invalid entity is recorded; a non-admin SHALL get `403`. `id` SHALL always be the canonical id
`<type>/platform/<name>`, also for a file entity, which has no blob at that id.

#### Scenario: Degraded on a parse failure
- **WHEN** the blob `models/platform/broken-health-model` holds `garbage` and the config reloads
- **THEN** the endpoint answers `status: "degraded"` with a `skipped` entry `id: "models/platform/broken-health-model"`
  whose `reason` contains `parse`

### Requirement: Config resource URLs
Core SHALL serve config entities at the config entity URLs request-routing maps to the config entity API
(`/v1/{type}/{bucket}/{name}` and `/v1/metadata/{type}/{bucket}/{path}`, `name` percent-decoded). A `(type, bucket)`
pair outside the binding (every config type binds only `platform`) SHALL answer `404` with no body, before
authorization.

#### Scenario: Unbound bucket
- **WHEN** any caller sends `GET /v1/keys/public/foo`
- **THEN** Core answers `404` with an empty body

### Requirement: Admin role for config
Core SHALL require the admin role for every method on the `platform` bucket of the Configuration API, the metadata
listing, `/v1/admin/*` and the file migration: the caller is admin as resource-access-control defines it (matches
`access.admin.rules`, not a per-request key). A denial SHALL answer `403` `Forbidden`.

#### Scenario: Non-admin write
- **WHEN** a caller without the `admin` role sends `PUT /v1/interceptors/platform/x` with a valid body
- **THEN** Core answers `403` and writes nothing

### Requirement: Empty admin rules deny all
Failing closed where the resource rule would match everyone, an empty `access.admin.rules` SHALL deny everyone the
admin surfaces above; leaving the field out keeps the built-in default (resource-access-control).

#### Scenario: Fail closed on empty rules
- **WHEN** `access.admin.rules` is `[]` and a caller with role `admin` sends `GET /v1/models/platform/m` or
  `POST /v1/admin/apply`
- **THEN** Core answers `403`

### Requirement: Per-entity GET is blob-only
`GET /v1/{type}/platform/{name}` SHALL answer from the stored blob only: `200` with the entity JSON plus
`name: "<name>"` and `status: "valid"` and an `ETag` header, `404` when no blob exists (including file-only
entities) or `name` is empty. An `If-None-Match` matching the blob's ETag SHALL answer `304` with no body.

#### Scenario: File entity not addressable
- **WHEN** an admin sends `GET /v1/models/platform/test-model-v1` and `test-model-v1` exists only in the file
- **THEN** Core answers `404`

#### Scenario: Conditional GET
- **WHEN** an admin sends `GET /v1/models/platform/cond-304` with `If-None-Match` set to the ETag the creating
  `PUT` returned
- **THEN** Core answers `304` with an empty body; with `If-None-Match: "stale-etag"` it answers `200` with the full
  body

### Requirement: Secrets masked on read
No Configuration API read SHALL return a secret: key `key`, upstream and interface `key` and `secretExtraData` are
omitted; for applications and toolsets `auth_settings.client_secret` and `code_verifier` are removed and an admin
gets `client_secret_hint` instead.

#### Scenario: Route secret not returned
- **WHEN** a route with `upstreams: [{"endpoint": "...", "key": "route-secret-1"}]` is applied
- **THEN** `GET /v1/routes/platform/<name>` has no upstream `key`

### Requirement: Secrets encrypted at rest
Secrets SHALL be stored in the blob encrypted, never as plaintext: key `key` and upstream and interface `key` and
`secretExtraData` as `ENC[...]`; application and toolset `auth_settings.client_secret` and `code_verifier` as bare
base64 ciphertext, without the `ENC[` prefix.

#### Scenario: Route secret at rest
- **WHEN** a route with `upstreams: [{"endpoint": "...", "key": "route-secret-1"}]` is applied
- **THEN** the stored blob contains `ENC[` and not `route-secret-1`, and the merged route holds `route-secret-1`

### Requirement: PUT upsert and conditional headers
`PUT /v1/{type}/platform/{name}` SHALL create or replace the entity and answer `200` `{"name": "<name>"}` with an
`ETag` header. `If-None-Match: *` SHALL answer `412` when the blob exists; `If-Match: <etag>` SHALL answer `412`
when it differs from the stored ETag; a `412` SHALL write nothing.

#### Scenario: Create-only gate
- **WHEN** an admin sends `PUT /v1/models/platform/cond-exists` with `If-None-Match: *` twice
- **THEN** the first answers `200`, the second `412`

#### Scenario: Compare-and-swap
- **WHEN** an admin sends `PUT /v1/models/platform/cond-cas-ok` with `If-Match` equal to the current ETag and a new
  endpoint
- **THEN** Core answers `200` with a different `ETag`; with `If-Match: "wrong-etag"` it answers `412`

### Requirement: Write request rejected with 400
A config `PUT` SHALL answer `400` and write nothing when the body is empty (`Request body must not be empty`), is not
JSON (`Invalid JSON at line <l>, column <c>`, never echoing the body), is not a JSON object, or does not bind to the
entity type, and a `PUT` or `DELETE` SHALL answer `400` when `name` is empty, ends with `/` or does not match
`^[A-Za-z0-9._%:@\[\]()-]+$` (toolsets: `^[A-Za-z0-9-_]+$`).

#### Scenario: Empty body rejected
- **WHEN** an admin sends `PUT /v1/interceptors/platform/test-interceptor-keep` with an empty body
- **THEN** Core answers `400` and the stored interceptor is unchanged

### Requirement: Omitted secret preserved
On a `PUT` that replaces an existing model, key or route, a secret field that is absent or `null` in the body SHALL
keep the stored value; any string SHALL be taken as the new secret.

#### Scenario: Key update without the secret
- **WHEN** key `test-key-preserve` holds secret `secret-preserve` and an admin sends `PUT` with
  `{"project": "projB", "roles": ["admin"]}`
- **THEN** Core answers `200` and `Api-key: secret-preserve` still authenticates

### Requirement: Key secrets
A key `PUT` SHALL answer `400` when the merged `key` is blank, `project` is blank or no `role`/`roles` is set, and
`409`, without echoing the secret, when another key (blob or file) already uses a changed secret. A secret change SHALL revoke the old secret; a key `DELETE` SHALL revoke the
deleted secret before answering `204`.

#### Scenario: Duplicate secret
- **WHEN** key `test-key-dup-a` uses `secret-dup-409` and an admin sends `PUT /v1/keys/platform/test-key-dup-b` with
  the same `key`
- **THEN** Core answers `409` with a body not containing `secret-dup-409`, and `test-key-dup-b` answers `404`

#### Scenario: Rotation revokes the old secret
- **WHEN** an admin re-`PUT`s `test-key-rotate` changing `key` from `secret-rotate-old` to `secret-rotate-new`
- **THEN** `Api-key: secret-rotate-old` gets `401` and `Api-key: secret-rotate-new` gets `200`

### Requirement: Deployment id uniqueness on write
A `PUT` of a model, interceptor, application or toolset SHALL answer `409`
`Deployment ID '<name>' is already used by a different entity` when a deployment of another of those types already
has that name in the merged config.

#### Scenario: Model name taken by an application
- **WHEN** the merged config has application `x` and an admin sends `PUT /v1/models/platform/x`
- **THEN** Core answers `409` and writes nothing

### Requirement: Schema id on write
A `schemas` or `catalog_schemas` `PUT` SHALL answer `400` when the body has no non-blank `$id`, and `409` when it
changes the `$id` of an existing blob or uses a `$id` another schema of that type already has.

#### Scenario: $id change rejected
- **WHEN** schema blob `s1` has `$id` `https://a/x` and an admin sends `PUT /v1/schemas/platform/s1` with `$id`
  `https://a/y`
- **THEN** Core answers `409`

### Requirement: Write rejected with 422
A config `PUT` SHALL answer `422` `{"validationWarnings": [{"field": ..., "message": ...}]}` and write nothing when a
model breaks a rebuild invariant (pricing, interfaces, translator refs, catalog properties) or, unless soft mode is
on, references an interceptor absent from the merged config; and when a translator or an `overridePaths` entry is
invalid, whatever the mode.

#### Scenario: Unknown interceptor
- **WHEN** an admin sends `PUT /v1/models/platform/cr-unknown` with `interceptors: ["unknown-interceptor"]` and no
  such interceptor exists
- **THEN** Core answers `422` with one warning, `field: "interceptors[0]"`, whose `message` contains
  `unknown-interceptor`, and `GET` on the model answers `404`

#### Scenario: Two unknown interceptors
- **WHEN** the model references two missing interceptors
- **THEN** the warnings are `interceptors[0]` and `interceptors[1]`

### Requirement: Soft validation
With `config.write.softValidation: true` (default `false`), a model write whose only failure is a dangling reference
SHALL be written and answer `200`; whether the model is then a deployment follows Dangling interceptor reference.
Rebuild invariants SHALL still answer `422`.

#### Scenario: Soft mode still rejects invariants
- **WHEN** soft mode is on and a model declares `interfaces: {"openaiChatCompletions": {}}` with no `base_url`
- **THEN** Core answers `422` with a body containing `interfaces.openaiChatCompletions` and `declares no base_url`,
  and nothing is written

### Requirement: POST and DELETE on entity URLs
`POST` on a config entity URL SHALL, once the caller is authorized, answer `405` with body `Not implemented` and
`Allow: GET, PUT, DELETE`; an unauthorized `POST` gets `403`. `DELETE` SHALL answer `204` after removing the blob,
`404` when no blob exists and `412` on an `If-Match` mismatch; `DELETE /v1/settings/platform/global` SHALL answer
`204` whether or not the blob exists.

#### Scenario: POST on a model URL
- **WHEN** an admin sends `POST /v1/models/platform/any-name`
- **THEN** Core answers `405` with body `Not implemented` and `Allow` listing `GET`, `PUT` and `DELETE`

#### Scenario: POST on a policy
- **WHEN** an admin sends `POST /v1/policies/platform/post`
- **THEN** Core answers `405` with `Allow: GET, PUT, DELETE`

#### Scenario: POST by a non-admin
- **WHEN** a caller without the admin role sends `POST /v1/models/platform/any-name`
- **THEN** Core answers `403`

#### Scenario: Delete a missing role
- **WHEN** an admin sends `DELETE /v1/roles/platform/none`
- **THEN** Core answers `404`

### Requirement: Settings singleton URL
`/v1/settings/platform/global` SHALL be the only settings entity: another name SHALL answer `404` on `GET`, `PUT` and
`DELETE`, and `POST` SHALL answer `405`.
`GET` SHALL answer `404` while no settings blob exists, else `200` with the three fields, `name: "global"` and
`status: "valid"`. A `PUT` SHALL drop unknown fields and answer `400` on an invalid `rateLimitSchedule`.

#### Scenario: Unknown field dropped
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with `{"globalInterceptors": ["q"], "extraField": "ignored"}`
- **THEN** Core answers `200` and the GET has one global interceptor and no `extraField`

### Requirement: Metadata listing
`GET /v1/metadata/{type}/platform/{path}` SHALL list the stored blobs only, as the Resource API folder metadata
(`nodeType`, `items` with `name`, `resourceType` like `MODEL`, `bucket`, `nextToken` when more pages exist), honoring
`token`, `limit` (default `100`, `0`..`1000`) and `recursive` (default `false`). A bad query SHALL answer `400` and a
missing folder `404`.

#### Scenario: File entries not listed
- **WHEN** an admin sends `GET /v1/metadata/models/platform/` while `test-model-v1` exists only in the file
- **THEN** no item is named `test-model-v1`

#### Scenario: Limit out of range
- **WHEN** an admin sends `GET /v1/metadata/models/platform/?limit=1001`
- **THEN** Core answers `400`

### Requirement: Metadata listing is GET only
On a metadata URL with a bound `(type, bucket)` pair, `POST`, `PUT` and `DELETE` SHALL answer `405` with body
`Not implemented` and `Allow: GET` before authorization, and type `settings` SHALL answer the same `405` to an
authorized `GET`. `HEAD` and `PATCH` are not routed to the metadata API and fall through to the config routes
(request-routing).

#### Scenario: PUT on model metadata
- **WHEN** a caller without the admin role sends `PUT /v1/metadata/models/platform/`
- **THEN** Core answers `405` with `Allow: GET`

#### Scenario: Settings metadata
- **WHEN** an admin sends `GET /v1/metadata/settings/platform/`
- **THEN** Core answers `405` with `Allow: GET`

### Requirement: Admin-managed application fields
For `app_identity` and `allow_user_external_services`, a config-API `PUT` to `applications/platform/...` and an
admin apply SHALL take the body value, absent meaning cleared; an admin Resource-API `PUT` to `public` SHALL take the
fields present in the body and keep the stored value of an absent one. Every other application write SHALL keep the
stored values on update and drop them on create.

#### Scenario: Omitted fields survive an admin edit
- **WHEN** an admin applied app `applications/public/gov-edit-app` with `app_identity` and
  `allow_user_external_services: true`, then sends `PUT /v1/applications/public/gov-edit-app` without either field
- **THEN** both values are unchanged

#### Scenario: User-bucket write drops them
- **WHEN** a user creates an application in their own bucket with `app_identity` and `allow_user_external_services`
- **THEN** a GET of it contains neither field

### Requirement: External services on write
A Resource-API application `PUT` that omits `external_services` SHALL keep the stored services. When the body has the
field, and on every config-API `PUT` and admin apply, the map SHALL be the desired state: services it drops SHALL be
removed with their application-level credentials.

#### Scenario: Partial update keeps services
- **WHEN** an application has `external_services.myapi` and its owner `PUT`s a body without `external_services`
- **THEN** `myapi` is still present afterwards

### Requirement: Manifest batch request
`POST /v1/admin/validate` and `POST /v1/admin/apply` SHALL take `{"precheck": <bool, default true>, "manifests":
[{"kind", "name", "spec"}]}`. Invalid JSON, a missing `manifests`, a null `kind` (`manifests[<i>].kind must be a
string`) or kind `Bundle` SHALL answer `400`; a non-admin `403`. An unknown kind SHALL fail that entry with
`Unknown kind: <kind>`.

#### Scenario: Missing manifests
- **WHEN** an admin sends `POST /v1/admin/validate` with `{}`
- **THEN** Core answers `400`

#### Scenario: Empty batch
- **WHEN** an admin sends `POST /v1/admin/validate` with `{"manifests": []}`
- **THEN** Core answers `200` `{"valid": 0, "failed": 0, "results": []}`

### Requirement: Manifest names
A manifest `name` SHALL be `<segment>/<bucket>/<name>` with segment `settings`, `schemas`, `catalog_schemas`,
`interceptors`, `translators`, `roles`, `keys`, `routes`, `models`, `toolsets`, `applications`, `policies` or
`policy_assignments` matching `kind`; bucket `platform`, or `public` for `Application` and `ToolSet`; one segment
matching the entity-name pattern (policies may add one folder). `Settings` SHALL be `settings/platform/global`.

#### Scenario: Wrong bucket
- **WHEN** a manifest has `kind: "Model"` and `name: "models/public/m"`
- **THEN** that entry fails with `'name' bucket segment must be 'platform', got 'public'`

### Requirement: Batch dependency order
Core SHALL process a batch in kind order `Settings`, `Schema`/`CatalogSchema`, `Translator`, `Interceptor`, `Role`,
`Key`, `Route`, `Model`, `ToolSet`, `Application`, `Policy`, `PolicyAssignment` (input order within a kind), report
results in that order, and resolve each entry's references against the merged config plus the batch's earlier
accepted entries.

#### Scenario: Model before its interceptor in the input
- **WHEN** a batch lists a `Model` referencing interceptor `i1` before the `Interceptor` `interceptors/platform/i1`
- **THEN** both entries are accepted

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

### Requirement: Apply with precheck
`/v1/admin/apply` with `precheck` true SHALL validate the whole batch first; on any failure it SHALL write nothing and
answer `422` `{"applied": 0, "failed": <n>, "results": [...]}` with failures `FAILED` (with `error`) and the rest
`SKIPPED`. Otherwise it SHALL write the entries and answer `200` with the result of each write.

#### Scenario: Mixed batch rejected atomically
- **WHEN** an admin applies a valid `Interceptor` and a `Model` referencing a missing interceptor
- **THEN** Core answers `422` with one `FAILED` and one `SKIPPED`, and neither entity answers `200` on GET

#### Scenario: All kinds applied
- **WHEN** an admin applies an `Interceptor`, a `Model` referencing it and `Settings` `settings/platform/global`
- **THEN** Core answers `200` with `applied: 3`, `failed: 0`, all `APPLIED`, and both entities answer `200` on GET

### Requirement: Apply partial failure
With `precheck: false`, `/v1/admin/apply` SHALL write each accepted entry and skip each failing one, answering `200`
with `applied` counting `APPLIED` and `APPLIED_INVALID` and `failed` counting `FAILED`. In soft mode a model whose only
failure is an unresolved interceptor reference SHALL be written as `APPLIED_INVALID`.

#### Scenario: One good, one bad
- **WHEN** an admin applies with `"precheck": false` a valid model and a model referencing interceptor `does-not-exist`
- **THEN** Core answers `200` with `applied: 1`, `failed: 1`; the good model answers `200` on GET and the bad `404`

### Requirement: File config inspection
Core SHALL serve `GET /v1/admin/config/file/{type}[/{name}]` from the file config alone, to admins:
`{"items": [{"name": ...}]}` for a type, the entity with `name` and `status: "valid"` for a name, `404` for a missing
name. Type `keys` SHALL answer `403` with body `Access to file-sourced keys via this surface is disabled` for every
caller.

#### Scenario: Keys hidden
- **WHEN** an admin sends `GET /v1/admin/config/file/keys/proxyKey1`
- **THEN** Core answers `403`

### Requirement: File config inspection is GET only
On `/v1/admin/config/file/{type}[/{name}]`, `POST`, `PUT` and `DELETE` SHALL answer `405` with body `Not implemented`
and `Allow: GET` before any authorization check; `HEAD` and `PATCH` fall through to the config routes
(request-routing).

#### Scenario: DELETE on file config
- **WHEN** a caller without the admin role sends `DELETE /v1/admin/config/file/models/test-model-v1`
- **THEN** Core answers `405` with `Allow: GET`

### Requirement: File migration
`POST /v1/admin/config/file/migrate` with `{"types": [...] | ["all"] (default all), "dryRun": <bool, default false>}`
SHALL copy each file entity of those types missing from the `platform` bucket into a blob through the apply path,
answering `200` `{"results": [{"kind", "resourceUrl", "key", "status", "reason"}]}`. An unsupported type SHALL answer
`400`; a non-admin `403`. The file is not changed.

#### Scenario: Models migrated, then skipped
- **WHEN** an admin sends `{"types": ["models"]}` twice
- **THEN** the first answer has `resourceUrl: "models/platform/test-model-v1"`, `key: "test-model-v1"`, `status:
  "migrated"`; the second has every status `skipped` with reason `already in blob`

#### Scenario: Unsupported type
- **WHEN** an admin sends `{"types": ["not-a-real-type"]}`
- **THEN** Core answers `400`

### Requirement: Migration dry run
With `dryRun: true` the migration SHALL write nothing and report `would_migrate`, `would_skip` or `would_fail`
instead of `migrated`, `skipped` or `failed`.

#### Scenario: Dry run of roles
- **WHEN** an admin sends `{"types": ["roles"], "dryRun": true}`
- **THEN** the result for `roles/platform/default` is `would_migrate` and `GET /v1/roles/platform/default` answers
  `404`

### Requirement: Key migration naming
Core SHALL migrate a file key to blob name `<project lowercased>-<first 12 hex of sha256(secret)>`, or the hash alone
when the project does not match the entity-name pattern, skip it when a blob key already holds that secret, fail it
when another secret holds that name, and never report the secret: key results carry no `key`.

#### Scenario: Migrated key authenticates
- **WHEN** an admin migrates `{"types": ["keys"]}` with file key `proxyKey1` of project `EPM-RTC-GPT`
- **THEN** the result is `migrated` with `resourceUrl: "keys/platform/epm-rtc-gpt-<hash>"` and no `key` field, the
  blob GET answers `project: "EPM-RTC-GPT"`, and `Api-key: proxyKey1` still authenticates

### Requirement: Schema migration naming
Core SHALL migrate a file schema (`schemas` or `catalog_schemas`) to the blob name taken from the last path segment of
its `$id` (the host when the path is empty), skip it when a blob already holds that `$id`, and fail it, with no
`resourceUrl`, when that name does not match the entity-name pattern or equals, ignoring case, another file schema's
or another blob's name. Core SHALL NOT rename a schema to avoid a collision.

#### Scenario: Schema named by its $id
- **WHEN** an admin migrates `{"types": ["schemas"]}` with a file schema whose `$id` is
  `https://mydial.somewhere.com/custom_application_schemas/schema_endpoint`
- **THEN** the result is `migrated` with `resourceUrl: "schemas/platform/schema_endpoint"` and the `$id` as `key`,
  `GET /v1/schemas/platform/schema_endpoint` answers `200`, and a second run reports `skipped` with the same
  `resourceUrl`

#### Scenario: Unusable schema name
- **WHEN** the file schema's `$id` ends in `bad~schema~name`
- **THEN** the result is `failed` with the `$id` as `key` and no `resourceUrl`
