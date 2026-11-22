# Spec Delta

## ADDED Requirements

### Requirement: Route upstream key on write
A route upstream whose `authType` is not `API_KEY` SHALL have a `key`. A route `PUT`, and a `Route` entry of
`/v1/admin/validate` or `/v1/admin/apply`, that breaks this SHALL be refused and write nothing. A `PUT` that replaces a
stored route and omits the key keeps the stored one (see Omitted secret preserved) and passes.

#### Scenario: Bearer route without a key
- **WHEN** an admin sends `PUT /v1/routes/platform/bearer-nokey` with `If-None-Match: *` and
  `upstreams: [{"endpoint": "http://localhost:7001", "authType": "BEARER"}]`
- **THEN** Core answers `422` with one warning, `field: "upstreams[0].authType"`, whose `message` contains
  `requires a key`, and `GET` on the route answers `404`; the same `PUT` with a `key` added then answers `200`

#### Scenario: Custom header route without a key
- **WHEN** the upstream declares `authType: {"type": "CUSTOM_HEADER", "headerName": "X-Api-Token"}` and no `key`
- **THEN** Core answers `422` with the same warning field

#### Scenario: Update without the key
- **WHEN** a route with a `BEARER` upstream and a `key` is stored and an admin sends a `PUT` for it that omits the `key`
- **THEN** Core answers `200` and `GET` on the route answers `200`

#### Scenario: Validate
- **WHEN** an admin validates a `Route` with a `BEARER` upstream and no `key`, with `"precheck": false`
- **THEN** Core answers `200` with `valid: 0`, `failed: 1`, that result `FAILED` with an `error` containing
  `requires a key`, and the route is not written

#### Scenario: Apply
- **WHEN** an admin applies with `"precheck": false` a valid `Interceptor` and a `Route` with a `BEARER` upstream and
  no `key`
- **THEN** Core answers `200` with `applied: 1`, `failed: 1`, the route result `FAILED`, the interceptor answers `200`
  on `GET` and the route answers `404`
