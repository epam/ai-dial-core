# plugin-registry Specification

## Purpose
The `plugins` config entity: a named declaration of an in-process plugin instance, its provider `type` and its
`settings`; how Core loads the plugin code the declarations refer to at start; and how a declaration is checked
against that code on write and on a config rebuild.

## Requirements

### Requirement: Plugin entity shape
A `plugins` entity SHALL be a JSON object with a non-blank string `type`, naming the provider that serves it,
and an optional object `settings` (default `{}`), whose content only the provider interprets. Any other field
SHALL make the body not bind to the entity type.

#### Scenario: Minimal declaration
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with `{"type": "jar-echo"}` and a provider of type
  `jar-echo` that accepts any settings is loaded
- **THEN** Core answers `200` `{"name": "p1"}` with an `ETag`, and `GET /v1/plugins/platform/p1` answers
  `type: "jar-echo"`, `settings: {}`, `name: "p1"` and `status: "valid"`

#### Scenario: Missing type
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with `{"settings": {}}`
- **THEN** Core answers `400` and writes nothing

#### Scenario: Unknown field
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with `{"type": "test-echo", "kind": "origin"}`
- **THEN** Core answers `400` and writes nothing

### Requirement: Plugin providers loaded at start
Core SHALL load plugin providers once at start: those on its own classpath and those in the jars of the directory
named by the static setting `plugins.dir` (built-in default `plugins`, relative to the working directory; a
missing directory adds none). Core SHALL fail to start when a jar in that directory cannot be loaded, two jars
register the same provider class, or two providers declare the same `type`. Providers do not reload while Core runs.

#### Scenario: Jar in the directory
- **WHEN** the plugins directory holds a jar whose provider declares type `jar-echo`
- **THEN** after start a `PUT /v1/plugins/platform/p1` with `{"type": "jar-echo"}` is accepted

#### Scenario: Missing directory
- **WHEN** Core starts with `plugins.dir` pointing to a directory that does not exist
- **THEN** Core starts, and only the providers of its own classpath are loaded

#### Scenario: Duplicate provider type
- **WHEN** the plugins directory holds two jars whose providers both declare type `jar-echo`
- **THEN** Core does not start

#### Scenario: Same provider class in two jars
- **WHEN** the plugins directory holds `echo-1.0.jar` and `echo-1.1.jar`, both registering the same provider class
- **THEN** Core does not start, with a message naming both jars

### Requirement: Unknown plugin type is a dangling reference
A `type` no loaded provider declares SHALL be treated as a dangling reference: a Configuration API write
SHALL answer `422` with warning field `type` unless `config.write.softValidation` is on, and an admin apply
entry SHALL be `FAILED`, or `APPLIED_INVALID` when it is on; a rebuild SHALL check
it only under `config.onInvalidEntity: skip`, where the entity is dropped and recorded as invalid; under
`abort` the entity SHALL stay in the merged config.

#### Scenario: Strict write
- **WHEN** no provider of type `missing` is loaded and an admin sends `PUT /v1/plugins/platform/p1` with
  `{"type": "missing"}`
- **THEN** Core answers `422` with one warning, `field: "type"`, whose `message` contains `missing`, and
  `GET /v1/plugins/platform/p1` answers `404`

#### Scenario: Soft write under skip
- **WHEN** `config.write.softValidation` and `config.onInvalidEntity: skip` are on and the same `PUT` is sent
- **THEN** Core answers `200`, and `GET /v1/plugins/platform/p1` answers `status: "invalid"` with warning
  field `type`

#### Scenario: Unknown type in a soft batch
- **WHEN** `config.write.softValidation` is on and an admin sends `POST /v1/admin/apply` with one `Plugin`
  manifest `plugins/platform/p1` of type `missing`
- **THEN** Core answers `200` with that result `APPLIED_INVALID`

#### Scenario: Rebuild under abort keeps the entity
- **WHEN** a blob `plugins/platform/p1` declares `type: "missing"`, `config.onInvalidEntity` is unset and the
  config reloads
- **THEN** the reload answers `200`, `GET /v1/plugins/platform/p1` answers `status: "valid"` and
  `GET /v1/admin/health/config` answers `status: "ok"`

#### Scenario: Rebuild under skip drops the entity
- **WHEN** the same blob exists, `config.onInvalidEntity: skip` is on and the config reloads
- **THEN** `GET /v1/admin/health/config` answers `status: "degraded"` with a `skipped` entry
  `id: "plugins/platform/p1"`, and the entity GET answers `status: "invalid"` with warning field `type`

### Requirement: Settings validated by the provider
When the provider of a declaration is loaded, Core SHALL hand it the `settings` object to validate. A rejection,
or an exception the provider throws while validating, SHALL be an invariant violation: a Configuration API write and an admin validate or apply entry SHALL fail
with `422` or `FAILED` and a warning whose field starts with `settings`, whatever the mode; a rebuild SHALL
fail under `abort` and drop and record the entity under `skip`.

#### Scenario: Rejected settings on write
- **WHEN** the loaded provider `test-echo` rejects settings without a `greeting` string, and an admin sends
  `PUT /v1/plugins/platform/p1` with `{"type": "test-echo", "settings": {}}`
- **THEN** Core answers `422` with one warning whose `field` is `settings.greeting`, and writes nothing

#### Scenario: Rejected settings in a batch
- **WHEN** an admin sends `POST /v1/admin/validate` with one `Plugin` manifest `plugins/platform/p1` whose
  `spec.settings` the provider rejects
- **THEN** Core answers `422` with that result `FAILED` and an `error` naming `settings.greeting`

#### Scenario: Provider failure on a rebuild under skip
- **WHEN** a blob `plugins/platform/p1` names a type whose provider throws while validating,
  `config.onInvalidEntity: skip` is on and the config reloads
- **THEN** the entity is dropped and recorded as invalid with warning field `settings`, and the other entities
  load

#### Scenario: Rejected settings on a rebuild under skip
- **WHEN** a blob `plugins/platform/p1` holds settings the loaded provider rejects, `config.onInvalidEntity:
  skip` is on and the config reloads
- **THEN** the entity is recorded as invalid with a warning field starting with `settings`, and
  `GET /v1/admin/health/config` lists `plugins/platform/p1`

### Requirement: File plugins
Core SHALL read a `plugins` object from the config files, keyed by short name, with the same shape and the
same validation as a blob; a file entry SHALL be inspectable at `GET /v1/admin/config/file/plugins[/{name}]`
and SHALL migrate to the `platform` bucket through `POST /v1/admin/config/file/migrate` with type `plugins`.

#### Scenario: File plugin migrated
- **WHEN** the config file declares `plugins: {"file-plugin": {"type": "test-echo", "settings":
  {"greeting": "hi"}}}` and an admin sends `POST /v1/admin/config/file/migrate` with `{"types": ["plugins"]}`
- **THEN** the result has `resourceUrl: "plugins/platform/file-plugin"` and `status: "migrated"`, and
  `GET /v1/plugins/platform/file-plugin` answers `200` with the same `type` and `settings`

#### Scenario: File plugin inspected
- **WHEN** the same file entry exists and an admin sends `GET /v1/admin/config/file/plugins/file-plugin`
- **THEN** Core answers `200` with `name: "file-plugin"`, `status: "valid"` and `type: "test-echo"`

### Requirement: Origin plugins
A `plugins` entry SHALL be able to serve an interface that names it, directly or through its translator
(deployment-interfaces-config, deployment-origin): its plugin answers the interface's requests in place of an HTTP
origin. Core SHALL create the plugin from the entry's `settings` when a request first needs it and again on the first
request after the entry changes, so a request is never served with settings older than the config it was routed by.

#### Scenario: Settings change takes effect
- **WHEN** `plugins.p1` of type `test-origin` with `settings.greeting: hello` serves application `app`, a chat
  completion for `app` is answered with `hello`, and an admin then sends `PUT /v1/plugins/platform/p1` with
  `{"type": "test-origin", "settings": {"greeting": "hi"}}`
- **THEN** the next chat completion for `app` is answered with `hi`, with no restart

#### Scenario: Entry removed
- **WHEN** an admin then sends `DELETE /v1/plugins/platform/p1`
- **THEN** the next chat completion for `app` is answered `502`

### Requirement: Closing a plugin at once
Core SHALL close each plugin instance it creates once and never on an event loop: an instance replaced after its
entry changed when its replacement is created, an instance whose entry was removed at the next request naming the
entry, both without waiting for the requests in flight on it, and every instance at shutdown, one created while Core
stops included. Requests racing on a new or changed entry SHALL share one instance, and a close that fails SHALL not
affect later requests.

#### Scenario: Replaced instance closed
- **WHEN** `plugins.p1` of type `test-origin` with `settings.greeting: hello` serves application `app`, a chat
  completion for `app` is answered with `hello`, an admin then sends `PUT /v1/plugins/platform/p1` with
  `{"type": "test-origin", "settings": {"greeting": "hi"}}`, and the next chat completion is answered with `hi`
- **THEN** the instance that answered `hello` is closed, once, on a thread that is not an event loop, and the
  instance that answered `hi` is not

#### Scenario: Busy instance closed at once
- **WHEN** the `hello` instance is still streaming an answer when the settings change and the `hi` instance answers
  the next chat completion
- **THEN** the `hello` instance is closed without waiting for that stream to end

#### Scenario: Racing requests share one instance
- **WHEN** two requests need `p1` at once and no instance was created from its settings yet
- **THEN** one instance is created and both requests get it

#### Scenario: Removed entry closed
- **WHEN** an admin sends `DELETE /v1/plugins/platform/p1`, and the next chat completion for `app`, whose embedded
  interface still names `p1`, is answered `502`
- **THEN** the instance of `p1` is closed

#### Scenario: Closed at shutdown
- **WHEN** Core stops while instances of `p1` and `p2` exist, one of them with no request in flight and one streaming
- **THEN** both are closed

#### Scenario: Created while Core stops
- **WHEN** a request is still creating the instance of `p1` when Core stops
- **THEN** that instance is closed once it is created, and the request fails

#### Scenario: Close fails
- **WHEN** the replaced instance throws from its close
- **THEN** its close was called once, and the next chat completion for `app` is answered by the new instance
