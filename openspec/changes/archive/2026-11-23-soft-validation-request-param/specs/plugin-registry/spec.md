# Spec Delta

## ADDED Requirements

### Requirement: Unknown plugin type is recorded as invalid
A `type` no loaded provider declares SHALL be a dangling reference, checked on every write and on every rebuild,
whatever `config.onInvalidEntity`. A checked plugin SHALL be dropped alone and recorded as an invalid entity; it never
fails the rebuild. A write SHALL answer `422` with warning field `type`, and an admin apply entry SHALL be `FAILED`,
unless the request carries `softValidation=true`: the write is then accepted and recorded, and the entry is
`APPLIED_INVALID`.

#### Scenario: Strict write
- **WHEN** no provider of type `missing` is loaded and an admin sends `PUT /v1/plugins/platform/p1` with
  `{"type": "missing"}` and no `softValidation` parameter
- **THEN** Core answers `422` with one warning, `field: "type"`, whose `message` contains `missing`, and
  `GET /v1/plugins/platform/p1` answers `404`

#### Scenario: Soft write
- **WHEN** whatever `config.onInvalidEntity`, the same `PUT` is sent with `?softValidation=true`
- **THEN** Core answers `200`, and `GET /v1/plugins/platform/p1` answers `status: "invalid"` with warning field `type`

#### Scenario: Unknown type in a soft batch
- **WHEN** an admin sends `POST /v1/admin/apply?softValidation=true` with one `Plugin` manifest
  `plugins/platform/p1` of type `missing`
- **THEN** Core answers `200` with that result `APPLIED_INVALID`

#### Scenario: Unknown type validated softly
- **WHEN** an admin sends `POST /v1/admin/validate?softValidation=true` with the same `Plugin` manifest
- **THEN** Core answers `200` with that result `VALID`, and `GET /v1/plugins/platform/p1` answers `404`

#### Scenario: Unknown type in a strict batch
- **WHEN** an admin sends the same body to `POST /v1/admin/apply` with `precheck: false`
- **THEN** Core answers `200` with that result `FAILED`, and `GET /v1/plugins/platform/p1` answers `404`

#### Scenario: Rebuild of a blob plugin
- **WHEN** a blob `plugins/platform/p1` declares `type: "missing"`, whatever `config.onInvalidEntity`, and the config
  reloads
- **THEN** the reload answers `200`, `GET /v1/admin/health/config` answers `status: "degraded"` with a `skipped`
  entry `id: "plugins/platform/p1"`, and the entity GET answers `status: "invalid"` with warning field `type`

#### Scenario: File plugin
- **WHEN** whatever `config.onInvalidEntity`, the config file declares `plugins: {"file-plugin": {"type":
  "missing"}}`
- **THEN** Core starts, the other entities load, and the health endpoint lists `plugins/platform/file-plugin` as
  skipped

#### Scenario: Deployment naming an invalid plugin
- **WHEN** plugin `p1` of type `missing` is recorded as invalid, and model `m` declares `baseUrl:
  dial-plugin://p1` with `interfaces.openaiChatCompletions: {}`
- **THEN** `m` is a deployment and its GET answers `status: "valid"`

## REMOVED Requirements

### Requirement: Unknown plugin type is a dangling reference
**Reason**: The rule depended on two server settings. A rebuild checked the type only under
`config.onInvalidEntity: skip` and left the entity live under `abort`, and a write was accepted only under
`config.write.softValidation`. The type is now checked on every rebuild and the write choice is a request parameter,
which "Unknown plugin type is recorded as invalid" states.
**Migration**: Send `softValidation=true` on a write that must be accepted with an unknown type. Under `abort`, a
plugin of an unknown type is no longer served as valid: it is recorded as invalid until a provider of its type is
loaded.
