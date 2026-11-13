# Spec Delta

## MODIFIED Requirements

### Requirement: Omitted secret preserved
On a `PUT` that replaces an existing model, key or route, a secret field that is absent or `null` in the body SHALL
keep the stored value; any string SHALL be taken as the new secret. When a secret that would be kept cannot be
decrypted, Core SHALL answer `400` naming the field and the upstream, and write nothing.

#### Scenario: Key update without the secret
- **WHEN** key `test-key-preserve` holds secret `secret-preserve` and an admin sends `PUT` with
  `{"project": "projB", "roles": ["admin"]}`
- **THEN** Core answers `200` and `Api-key: secret-preserve` still authenticates

#### Scenario: Model update that would keep an unreadable key
- **WHEN** a model with two upstreams is stored and the `key` of `upstreams[0]` cannot be decrypted, and an admin sends
  `PUT` with a changed `display_name` and no `key` on either upstream
- **THEN** Core answers `400` with a message naming `upstreams[0].key`, the model's ETag is unchanged, and `GET` on the
  model still returns the old `display_name`

#### Scenario: New key replaces an unreadable one
- **WHEN** the same model is sent with a `key` on `upstreams[0]` and none on `upstreams[1]`, whose stored key can be
  decrypted
- **THEN** Core answers `200`, and both upstreams authenticate with their keys: the new one and the stored one

#### Scenario: Key update that would keep an unreadable secret
- **WHEN** the secret of key `test-key-unreadable` cannot be decrypted and an admin sends `PUT` without `key`
- **THEN** Core answers `400` naming `key`, and a `PUT` with a new `key` then answers `200` and `Api-key: <new>`
  authenticates

### Requirement: Route upstream key on write
A route upstream whose `authType` is not `API_KEY` SHALL have a `key`. A route `PUT`, and a `Route` entry of
`/v1/admin/validate` or `/v1/admin/apply`, that breaks this SHALL be refused and write nothing. A `PUT` that replaces a
stored route and omits the key keeps the stored one (see Omitted secret preserved) and passes, unless the stored key
cannot be decrypted, which answers `400`.

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

#### Scenario: Update without the key when the stored key is unreadable
- **WHEN** the stored `key` of that route cannot be decrypted and an admin sends a `PUT` for it that omits the `key`
- **THEN** Core answers `400` naming `upstreams[0].key` and the stored route is unchanged

#### Scenario: Validate
- **WHEN** an admin validates a `Route` with a `BEARER` upstream and no `key`, with `"precheck": false`
- **THEN** Core answers `200` with `valid: 0`, `failed: 1`, that result `FAILED` with an `error` containing
  `requires a key`, and the route is not written

#### Scenario: Apply
- **WHEN** an admin applies with `"precheck": false` a valid `Interceptor` and a `Route` with a `BEARER` upstream and
  no `key`
- **THEN** Core answers `200` with `applied: 1`, `failed: 1`, the route result `FAILED`, the interceptor answers `200`
  on `GET` and the route answers `404`

### Requirement: External services on write
A Resource-API application `PUT` that omits `external_services` SHALL keep the stored services. When the body has the
field, and on every config-API `PUT` and admin apply, the map SHALL be the desired state: services it drops SHALL be
removed with their application-level credentials. A service that omits `client_secret` while its stored secret cannot
be decrypted SHALL be refused with `400` naming it.

#### Scenario: Partial update keeps services
- **WHEN** an application has `external_services.myapi` and its owner `PUT`s a body without `external_services`
- **THEN** `myapi` is still present afterwards

#### Scenario: Application write that would keep an unreadable service secret
- **WHEN** the stored `client_secret` of `external_services.myapi` cannot be decrypted and its owner `PUT`s the
  application with `myapi` and no `client_secret`
- **THEN** Core answers `400` with a message naming `myapi`, and the stored application is unchanged

## ADDED Requirements

### Requirement: External service write with an unreadable secret
An external-service write SHALL succeed when the stored `client_secret` cannot be decrypted and the body supplies a new
`client_secret`; an application-level service also succeeds when it changes `authentication_type` or, in an application
`PUT`, is dropped. An omitted `client_secret` SHALL answer `400` naming the service. A write to one service SHALL NOT
read or change another service's stored secret.

#### Scenario: New secret replaces an unreadable one
- **WHEN** the stored `client_secret` of service `myapi` cannot be decrypted and its owner sends the service `PUT` with a
  new `client_secret`
- **THEN** Core answers `200` and a `GET` of the service now carries a `client_secret_hint`, which it did not before

#### Scenario: Omitted secret
- **WHEN** the same service is sent without `client_secret`
- **THEN** Core answers `400` with a message naming `myapi` and the stored service is unchanged

#### Scenario: Another service of the application is written
- **WHEN** an application has services `bad`, whose secret cannot be decrypted, and `good`, and the owner sends the
  service `PUT` for `good` with a new `client_secret`
- **THEN** Core answers `200`, `good` carries a `client_secret_hint` of the new secret, and `bad` is stored as it was

#### Scenario: Application write drops the unreadable service
- **WHEN** the application `PUT` carries `external_services` without `bad`
- **THEN** Core answers `200` and `bad` is gone

#### Scenario: User-authored service
- **WHEN** the stored secret of a user-authored service cannot be decrypted and its owner sends the `PUT` without
  `client_secret`, and then with a new one
- **THEN** Core answers `400`, and then `200`

### Requirement: Toolset write with an unreadable secret
A toolset `PUT` (Resource API, config API or admin apply) SHALL succeed when the stored `client_secret` cannot be
decrypted and the body supplies `client_secret`, and SHALL then issue a new PKCE code challenge and verifier. A body
without it, where the write would keep the stored one, SHALL answer `400` naming the toolset.

#### Scenario: New secret replaces an unreadable one
- **WHEN** the stored `client_secret` and `code_verifier` of an OAuth toolset cannot be decrypted and its owner sends
  `PUT` with a new `client_secret`
- **THEN** Core answers `200`, a `GET` carries a `client_secret_hint`, and a later sign-in completes its token
  exchange with a `code_verifier` that matches the `code_challenge` of its authorization request

#### Scenario: Omitted secret
- **WHEN** the same toolset is sent without `client_secret`
- **THEN** Core answers `400` with a message that names the toolset and says the stored `client_secret` cannot be used,
  and the toolset's ETag is unchanged

#### Scenario: Omitted secret on a dynamically registered toolset
- **WHEN** the toolset was dynamically registered and the `PUT` omits `client_secret`
- **THEN** the `400` message also names the repair endpoint, because the client never held the secret

#### Scenario: Authentication type changes
- **WHEN** the body changes `authentication_type` from `OAUTH` to `API_KEY` and supplies the API key
- **THEN** Core answers `200`

#### Scenario: Admin apply
- **WHEN** `/v1/admin/apply` carries a toolset manifest without `client_secret` for a stored toolset whose secret
  cannot be decrypted
- **THEN** that entry is `FAILED` with the same message and the toolset is not written

### Requirement: Toolset repair with an unreadable secret
Toolset repair SHALL succeed for an eligible toolset, one that is OAuth and dynamically registered, whose stored
`client_secret` or `code_verifier` cannot be decrypted. It SHALL replace both with the new registration's values.

#### Scenario: Repair after the key is lost
- **WHEN** the stored `client_secret` and `code_verifier` of a dynamically registered toolset cannot be decrypted and
  its owner calls `/v1/ops/toolset/<bucket>/<path>/repair`
- **THEN** Core answers `200`, the toolset carries the new `client_id`, a `GET` carries a `client_secret_hint`, and its
  credentials are cleared as on any repair
