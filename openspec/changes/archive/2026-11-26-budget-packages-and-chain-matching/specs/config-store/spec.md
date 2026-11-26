# Spec Delta

## MODIFIED Requirements

### Requirement: Manifest names
A manifest `name` SHALL be `<segment>/<bucket>/<name>` with segment `settings`, `schemas`, `catalog_schemas`,
`plugins`, `interceptors`, `translators`, `routers`, `roles`, `keys`, `routes`, `models`, `toolsets`, `applications`,
`packages`, `policies` or `policy_assignments` matching `kind`; bucket `platform`, or `public` for `Application` and `ToolSet`;
one segment matching the entity-name pattern (policies may add one folder). `Settings` SHALL be
`settings/platform/global`.

#### Scenario: Wrong bucket
- **WHEN** a manifest has `kind: "Model"` and `name: "models/public/m"`
- **THEN** that entry fails with `'name' bucket segment must be 'platform', got 'public'`

#### Scenario: Plugin manifest
- **WHEN** a manifest has `kind: "Plugin"` and `name: "plugins/platform/p1"` with a valid `spec`
- **THEN** that entry is accepted

#### Scenario: Router manifest in the public bucket
- **WHEN** a manifest has `kind: "Router"` and `name: "routers/public/r"`
- **THEN** that entry fails with `'name' bucket segment must be 'platform', got 'public'`

#### Scenario: Package manifest with a folder
- **WHEN** a manifest has `kind: "Package"` and `name: "packages/platform/team/p"`
- **THEN** that entry fails and nothing is written

### Requirement: Batch dependency order
Core SHALL process a batch in kind order `Settings`, `Schema`/`CatalogSchema`, `Plugin`, `Translator`,
`Interceptor`, `Router`, `Role`, `Key`, `Route`, `Model`, `ToolSet`, `Application`, `Package`, `Policy`,
`PolicyAssignment`
(input order within a kind), report results in that order, and resolve each entry's references against the merged
config plus the batch's earlier accepted entries.

#### Scenario: Model before its interceptor in the input
- **WHEN** a batch lists a `Model` referencing interceptor `i1` before the `Interceptor` `interceptors/platform/i1`
- **THEN** both entries are accepted

#### Scenario: Plugin reported before the translator
- **WHEN** a batch lists a `Translator` before a `Plugin`
- **THEN** the results list the `Plugin` entry first

#### Scenario: Router applied with its in-batch translator
- **WHEN** an admin applies, in this input order, a `Model`, then a `Router` `routers/platform/r-batch` with
  `{"baseUrl": "http://localhost:4848", "interfaces": {"openaiChatCompletions": {}, "anthropicMessages": {"mode":
  "translator", "translator": "t-batch"}}}`, then the `Translator` `translators/platform/t-batch` converting
  `anthropicMessages` to `openaiChatCompletions`
- **THEN** Core answers `200` with all `APPLIED`, results ordered translator, router, model, and
  `GET /v1/routers/platform/r-batch` answers `200`

#### Scenario: Policy before its package in the input
- **WHEN** a batch lists a `Policy` with `object` `"@engineering"` before the `Package` `packages/platform/engineering`
- **THEN** both entries are accepted and the results list the `Package` entry first
