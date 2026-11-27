# Spec Delta

## MODIFIED Requirements

### Requirement: Who may declare resource dependencies
While the static setting `applications.allowUserAuthoredDependencies` is `false`, a write of an application by a caller
without explicit admin access that submits a `resource_dependencies` section **different from the stored one** SHALL be
answered `403` after the shape rules are checked (a malformed section is `400`). An empty section equals an absent one.
An admin's write is not gated. With the setting `true` (the default), a user's section SHALL pass the same shape rules
as an admin's.

#### Scenario: User creates an application with an empty section
- **WHEN** a user sends `PUT /v1/applications/<own bucket>/app` with `"resource_dependencies": []`, the setting off
- **THEN** Core answers `200`

#### Scenario: User declares with the setting left at its default
- **WHEN** the setting is not configured and a user sends `PUT /v1/applications/<own bucket>/app` with a valid section
  declaring `skills/{current-user}/`
- **THEN** Core answers `200`

### Requirement: An omitted or unchanged section is inherited
With the setting off, a non-admin write that omits `resource_dependencies` SHALL keep the stored list, and a write that
submits the same list as stored SHALL be accepted.

#### Scenario: Editor leaves the list out
- **WHEN** an org-unit editor PUTs an approved application without `resource_dependencies`, the setting off
- **THEN** Core answers `200`, and the stored list and the approval are kept

#### Scenario: User with the setting off — valid section
- **WHEN** a user sends `PUT /v1/applications/<own bucket>/app` with a valid section and no stored list, the setting off
- **THEN** Core answers `403`

#### Scenario: User with the setting off — malformed section
- **WHEN** a user sends `PUT /v1/applications/<own bucket>/app` with a malformed section
- **THEN** Core answers `400`

#### Scenario: Admin in their own bucket
- **WHEN** an admin writes an application with a valid section to the admin's own bucket, the setting off
- **THEN** Core answers `200`

#### Scenario: User with the setting on
- **WHEN** `applications.allowUserAuthoredDependencies` is `true` and a user writes a valid section to the own bucket
- **THEN** Core answers `200`, and a section declaring `{current-user}/` is answered `400`

#### Scenario: No admin rules
- **WHEN** no admin rules are configured and a user writes a valid section, the setting off
- **THEN** Core answers `403`
