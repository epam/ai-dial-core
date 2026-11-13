# Spec Delta

## MODIFIED Requirements

### Requirement: Canonical-shaped file keys rejected
Core SHALL fail the file load when a key under `models`, `interceptors`, `roles`, `keys` or `routes` has the shape
`<that type>/<bucket>/<name>`, with a message naming the key and "canonical-ID shape". Any other file key containing
`/` under `models`, `applications`, `interceptors`, `translators`, `plugins`, `roles`, `routes` or `toolsets` SHALL
be dropped with a warning and not recorded as an invalid entity.

#### Scenario: Canonical-shaped model key
- **WHEN** a config file declares `models: {"models/platform/gpt": {...}}`
- **THEN** the load fails with an error containing `models/platform/gpt` and `canonical-ID shape`

#### Scenario: Slash-keyed model dropped
- **WHEN** a config file declares models `bad/model` and `valid-model`
- **THEN** the merged config contains `valid-model`, not `bad/model`, and `GET /v1/admin/health/config` lists no
  skipped entity for it

#### Scenario: Slash-keyed plugin dropped
- **WHEN** a config file declares plugins `bad/plugin` and `valid-plugin`, both of a loaded type
- **THEN** `GET /v1/admin/config/file/plugins` lists `valid-plugin` and not `bad/plugin`, and
  `GET /v1/admin/health/config` lists no skipped entity for it

### Requirement: Merged sources
Core SHALL serve the union of the file config and the blobs of the `platform` bucket for `models`, `interceptors`,
`translators`, `plugins`, `roles`, `keys`, `routes`, `schemas`, `catalog_schemas`, `applications`, `toolsets`,
`policies` and `policy_assignments`, plus the `settings/platform/global` overlay. `public`-bucket applications and
toolsets SHALL NOT enter the merged config.

#### Scenario: Blob model is a deployment
- **WHEN** an admin sends `PUT /v1/models/platform/m1` with `{"type":"chat","endpoint":"http://host/chat"}`
- **THEN** `m1` resolves as a deployment for the next request, next to the file-defined models

#### Scenario: Blob plugin next to the file plugins
- **WHEN** the file declares plugin `file-plugin` and an admin sends `PUT /v1/plugins/platform/p1` with a valid
  declaration, then `POST /v1/ops/config/reload`
- **THEN** the reload answers `200` with a `plugins` object holding both `file-plugin` and `p1`

### Requirement: Blob wins on a name clash
A blob entity SHALL replace, as a whole, the file entity of the same type with the same short name (models,
interceptors, translators, plugins, roles, applications, toolsets) or, for schemas, the same `$id`; no field of the
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

#### Scenario: Blob plugin shadows the file plugin
- **WHEN** the file declares plugin `file-plugin` with `settings.greeting: "hi"` and a blob
  `plugins/platform/file-plugin` declares `settings.greeting: "hello"`, and the config reloads
- **THEN** `POST /v1/ops/config/reload` answers a `plugins.file-plugin` whose `settings.greeting` is `hello`, once

### Requirement: Manifest names
A manifest `name` SHALL be `<segment>/<bucket>/<name>` with segment `settings`, `schemas`, `catalog_schemas`,
`plugins`, `interceptors`, `translators`, `roles`, `keys`, `routes`, `models`, `toolsets`, `applications`,
`policies` or `policy_assignments` matching `kind`; bucket `platform`, or `public` for `Application` and `ToolSet`;
one segment matching the entity-name pattern (policies may add one folder). `Settings` SHALL be
`settings/platform/global`.

#### Scenario: Wrong bucket
- **WHEN** a manifest has `kind: "Model"` and `name: "models/public/m"`
- **THEN** that entry fails with `'name' bucket segment must be 'platform', got 'public'`

#### Scenario: Plugin manifest
- **WHEN** a manifest has `kind: "Plugin"` and `name: "plugins/platform/p1"` with a valid `spec`
- **THEN** that entry is accepted

### Requirement: Batch dependency order
Core SHALL process a batch in kind order `Settings`, `Schema`/`CatalogSchema`, `Plugin`, `Translator`,
`Interceptor`, `Role`, `Key`, `Route`, `Model`, `ToolSet`, `Application`, `Policy`, `PolicyAssignment` (input
order within a kind), report results in that order, and resolve each entry's references against the merged config
plus the batch's earlier accepted entries.

#### Scenario: Model before its interceptor in the input
- **WHEN** a batch lists a `Model` referencing interceptor `i1` before the `Interceptor` `interceptors/platform/i1`
- **THEN** both entries are accepted

#### Scenario: Plugin reported before the translator
- **WHEN** a batch lists a `Translator` before a `Plugin`
- **THEN** the results list the `Plugin` entry first

### Requirement: Write rejected with 422
A config `PUT` SHALL answer `422` `{"validationWarnings": [{"field": ..., "message": ...}]}` and write nothing when a
model breaks a rebuild invariant (pricing, interfaces, translator refs, catalog properties) or, unless soft mode is
on, references an interceptor absent from the merged config; when a translator or an `overridePaths` entry is
invalid, whatever the mode; and when a plugin declaration fails as plugin-registry defines.

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
