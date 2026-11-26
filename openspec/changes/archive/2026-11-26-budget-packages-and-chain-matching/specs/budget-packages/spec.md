# Spec Delta

## Purpose

Packages: named, admin-curated sets of deployment names that a spending-limit policy references in its `object` with
`@name`, so one budget can cover a group of deployments such as the tier aliases.

## ADDED Requirements

### Requirement: Package record
Core SHALL serve packages at `/v1/packages/platform/<name>`, a single flat name segment, as a config entity: `GET`,
`PUT` and `DELETE` as for the other config types, admin only. The body SHALL be `deployments`, a non-empty list of
non-blank deployment names, and optional `description`. Core SHALL answer `400` to an unknown field, a missing or empty
`deployments`, a member starting with `@`, or a member `*`.

#### Scenario: Package written and read back
- **WHEN** an admin sends `PUT /v1/packages/platform/engineering` with
  `{"deployments": ["weak", "medium", "strong"], "description": "Engineering"}`
- **THEN** Core answers `200` with an `ETag`, and `GET /v1/packages/platform/engineering` answers `200` with those
  `deployments` and `description`, `name: "engineering"` and `status: "valid"`

#### Scenario: Non-admin write
- **WHEN** a caller without the admin role sends the same `PUT`
- **THEN** Core answers `403` and writes nothing

#### Scenario: Nested package or wildcard
- **WHEN** an admin sends `PUT /v1/packages/platform/p` with `deployments` `["@engineering"]`, `["*"]` or `[]`
- **THEN** Core answers `400` and writes nothing

#### Scenario: Unknown field
- **WHEN** an admin sends `PUT /v1/packages/platform/p` with `{"deployments": ["weak"], "owner": "x"}`
- **THEN** Core answers `400` and writes nothing

#### Scenario: Name with a folder
- **WHEN** an admin sends `PUT /v1/packages/platform/team/p` with a valid body
- **THEN** Core answers `400` and writes nothing

### Requirement: Package member that is not a deployment
Core SHALL accept a package member that names no deployment of the current config, on write and on a rebuild, and
SHALL log it at WARN with the package and the member. Such a member SHALL cover no call until a deployment of that
name exists.

#### Scenario: Dangling member accepted
- **WHEN** an admin sends `PUT /v1/packages/platform/learning` with `{"deployments": ["weak", "retired-model"]}` and
  no deployment is named `retired-model`
- **THEN** Core answers `200`, the package answers `status: "valid"` on `GET`, and a WARN line names `learning` and
  `retired-model`

### Requirement: Package manifest
`POST /v1/admin/validate` and `POST /v1/admin/apply` SHALL accept the kind `Package` with name
`packages/platform/<name>` and the package body as `spec`, checked as a `PUT` is.

#### Scenario: Package applied with a policy naming it
- **WHEN** an admin applies a batch listing a `Policy` whose `object` is `"@engineering"` before the `Package`
  `packages/platform/engineering`
- **THEN** Core answers `200` with both `APPLIED`, the `Package` result first

### Requirement: Policy names a package
A policy's `object` SHALL accept a package reference, `@<name>`, alone (`"@engineering"`) or as list members mixed
with deployment names (`["@engineering", "gpt-6-luna"]`). Policy reads and manifests SHALL return `object` as written:
a single reference as a string, a list sorted. The `packages` policy field SHALL stay rejected.

#### Scenario: Policy with a package object
- **WHEN** an admin writes a policy with `object` `"@engineering"` and the package `engineering` exists
- **THEN** the write succeeds and `GET` of the policy returns `object` `"@engineering"`

#### Scenario: Mixed list
- **WHEN** an admin writes a policy with `object` `["gpt-6-luna", "@engineering"]`
- **THEN** `GET` of the policy returns `object` `["@engineering", "gpt-6-luna"]`

#### Scenario: Reserved packages field
- **WHEN** an admin writes a policy with `packages: ["engineering"]`
- **THEN** Core answers `422` with `'packages' is not supported yet` and writes nothing

#### Scenario: Wildcard inside a list
- **WHEN** an admin writes a policy with `object` `["@engineering", "*"]`
- **THEN** Core answers `400` and writes nothing

### Requirement: Policy naming a missing package
Core SHALL refuse with `422` a policy write, by `PUT` or manifest, whose `object` names a package that is neither
stored nor earlier in the same batch. On a rebuild, a policy naming a missing package SHALL be an invalid entity under
`config.onInvalidEntity`, as a policy failing its other checks is.

#### Scenario: Write naming a missing package
- **WHEN** an admin writes a policy with `object` `"@nope"` and no package `nope` exists
- **THEN** Core answers `422` with an error naming `nope` and writes nothing

#### Scenario: Rebuild under skip
- **WHEN** `config.onInvalidEntity` is `skip` and a policy blob, written to storage directly, names `@nope`
- **THEN** after the rebuild the policy and its assignments cover nothing and `GET /v1/admin/health/config` lists the
  policy among the skipped entities

### Requirement: Referenced package not deleted
Core SHALL answer `409` to `DELETE /v1/packages/platform/<name>` while any stored policy names `@<name>` in its
`object`, with a message naming the package and at least one such policy, and SHALL keep the package.

#### Scenario: Delete of a referenced package
- **WHEN** the policy `IMF/imf-eng-1000` has `object` `"@engineering"` and an admin sends
  `DELETE /v1/packages/platform/engineering`
- **THEN** Core answers `409` naming `IMF/imf-eng-1000`, and the package still answers `200` on `GET`

#### Scenario: Delete of an unreferenced package
- **WHEN** no policy names `@learning` and an admin sends `DELETE /v1/packages/platform/learning`
- **THEN** Core answers `204`

### Requirement: Package edit re-scopes its policies
After a package is written, the next config rebuild SHALL make every policy naming it cover the package's new
members, and SHALL NOT change any budget counter.

#### Scenario: Member added
- **WHEN** a caller's only budget has `object` `"@learning"` = `["weak"]` with $3 spent this month, and an admin
  writes `learning` as `["weak", "medium"]`
- **THEN** after the rebuild a call through `medium` is paid by that budget, and `GET /v1/user/limits` shows its
  `month.used` as $3 before that call

#### Scenario: Member removed
- **WHEN** an admin writes `learning` as `["weak"]` again
- **THEN** after the rebuild a call through `medium` with no other covering budget is refused as `no_budget`
