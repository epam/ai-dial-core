## RENAMED Requirements

- FROM: `### Requirement: Unknown plugin type is a dangling reference`
- TO: `### Requirement: Unknown plugin id is a dangling reference`

## MODIFIED Requirements

### Requirement: Plugin entity shape
A `plugins` entity SHALL be a JSON object with a non-blank string `pluginId`, the id of the provider that serves it,
and an optional object `settings` (default `{}`), whose content only the provider interprets. Any other field
SHALL make the body not bind to the entity type, except `type` (Deprecated plugin type alias).

#### Scenario: Minimal declaration
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with `{"pluginId": "jar-echo"}` and a provider of id
  `jar-echo` that accepts any settings is loaded
- **THEN** Core answers `200` `{"name": "p1"}` with an `ETag`, and `GET /v1/plugins/platform/p1` answers
  `pluginId: "jar-echo"`, `settings: {}`, `name: "p1"` and `status: "valid"`

#### Scenario: Missing pluginId
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with `{"settings": {}}`
- **THEN** Core answers `400` and writes nothing

#### Scenario: Unknown field
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with `{"pluginId": "test-echo", "kind": "origin"}`
- **THEN** Core answers `400` and writes nothing

### Requirement: Plugin providers loaded at start
Core SHALL load plugin providers once at start: those on its own classpath and those of the plugin directories under
the directory named by the static setting `plugins.dir` (built-in default `plugins`, relative to the working
directory). A plugin directory is `<plugins.dir>/<group>/<plugin>`, identified by `<group>/<plugin>`, and holds the
plugin's jars; a group or plugin directory whose name starts with `.`, or is `lost+found`, is ignored. Providers do
not reload while Core runs.

#### Scenario: Jar in the directory
- **WHEN** `<plugins.dir>/custom/echo/` holds a jar whose provider declares id `jar-echo`
- **THEN** after start a `PUT /v1/plugins/platform/p1` with `{"pluginId": "jar-echo"}` is accepted

#### Scenario: Missing directory
- **WHEN** Core starts with `plugins.dir` pointing to a directory that does not exist
- **THEN** Core starts, and only the providers of its own classpath are loaded

#### Scenario: Filesystem directories ignored
- **WHEN** `<plugins.dir>/custom/echo/` holds a jar whose provider declares id `jar-echo`, and the empty directories
  `<plugins.dir>/lost+found/`, `<plugins.dir>/custom/lost+found/` and `<plugins.dir>/custom/.cache/` exist
- **THEN** Core starts and serves plugin id `jar-echo`

### Requirement: Plugin load errors stop the start
Core SHALL fail to start when a jar in a plugin directory cannot be loaded, two jars of one plugin directory register
the same provider class, two providers declare the same id, a loaded plugin directory registers no provider, or a
jar lies directly in `plugins.dir` or directly in a group directory.

#### Scenario: Duplicate provider id
- **WHEN** `<plugins.dir>/builtin/echo/` and `<plugins.dir>/custom/echo/` each hold a jar whose provider declares
  id `jar-echo`
- **THEN** Core does not start, with a message naming the id and both plugin directories

#### Scenario: Same provider class in two jars
- **WHEN** `<plugins.dir>/custom/echo/` holds `echo-1.0.jar` and `echo-1.1.jar`, both registering the same provider
  class
- **THEN** Core does not start, with a message naming both jars

#### Scenario: Jar outside a plugin directory
- **WHEN** a jar lies at `<plugins.dir>/echo.jar` or at `<plugins.dir>/custom/echo.jar`
- **THEN** Core does not start, with a message naming the jar and the layout `<group>/<plugin>/`

#### Scenario: Plugin directory without a provider
- **WHEN** `<plugins.dir>/custom/empty/` holds no jar that registers a provider
- **THEN** Core does not start, with a message naming `custom/empty`

### Requirement: Unknown plugin id is a dangling reference
A `pluginId` no loaded provider declares SHALL be treated as a dangling reference: a Configuration API write
SHALL answer `422` with warning field `pluginId` unless `config.write.softValidation` is on, and an admin apply
entry SHALL be `FAILED`, or `APPLIED_INVALID` when it is on; a rebuild SHALL check
it only under `config.onInvalidEntity: skip`, where the entity is dropped and recorded as invalid; under
`abort` the entity SHALL stay in the merged config.

#### Scenario: Strict write
- **WHEN** no provider of id `missing` is loaded and an admin sends `PUT /v1/plugins/platform/p1` with
  `{"pluginId": "missing"}`
- **THEN** Core answers `422` with one warning, `field: "pluginId"`, whose `message` contains `missing`, and
  `GET /v1/plugins/platform/p1` answers `404`

#### Scenario: Soft write under skip
- **WHEN** `config.write.softValidation` and `config.onInvalidEntity: skip` are on and the same `PUT` is sent
- **THEN** Core answers `200`, and `GET /v1/plugins/platform/p1` answers `status: "invalid"` with warning
  field `pluginId`

#### Scenario: Unknown pluginId in a soft batch
- **WHEN** `config.write.softValidation` is on and an admin sends `POST /v1/admin/apply` with one `Plugin`
  manifest `plugins/platform/p1` of plugin id `missing`
- **THEN** Core answers `200` with that result `APPLIED_INVALID`

#### Scenario: Rebuild under abort keeps the entity
- **WHEN** a blob `plugins/platform/p1` declares `pluginId: "missing"`, `config.onInvalidEntity` is unset and the
  config reloads
- **THEN** the reload answers `200`, `GET /v1/plugins/platform/p1` answers `status: "valid"` and
  `GET /v1/admin/health/config` answers `status: "ok"`

#### Scenario: Rebuild under skip drops the entity
- **WHEN** the same blob exists, `config.onInvalidEntity: skip` is on and the config reloads
- **THEN** `GET /v1/admin/health/config` answers `status: "degraded"` with a `skipped` entry
  `id: "plugins/platform/p1"`, and the entity GET answers `status: "invalid"` with warning field `pluginId`

### Requirement: Settings validated by the provider
When the provider of a declaration is loaded and its schema, if any, accepts the settings, Core SHALL hand
it the `settings` object to validate. A rejection, or an exception the provider throws while validating,
SHALL be an invariant violation: a Configuration API write and an admin validate or apply entry SHALL fail with
`422` or `FAILED` and a warning whose field starts with `settings`, whatever the mode; a rebuild SHALL fail under
`abort` and drop and record the entity under `skip`.

#### Scenario: Rejected settings on write
- **WHEN** the loaded provider `test-echo` rejects settings without a `greeting` string, and an admin sends
  `PUT /v1/plugins/platform/p1` with `{"pluginId": "test-echo", "settings": {}}`
- **THEN** Core answers `422` with one warning whose `field` is `settings.greeting`, and writes nothing

#### Scenario: Rejected settings in a batch
- **WHEN** an admin sends `POST /v1/admin/validate` with one `Plugin` manifest `plugins/platform/p1` whose
  `spec.settings` the provider rejects
- **THEN** Core answers `422` with that result `FAILED` and an `error` naming `settings.greeting`

#### Scenario: Provider failure on a rebuild under skip
- **WHEN** a blob `plugins/platform/p1` names a plugin id whose provider throws while validating,
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
- **WHEN** the config file declares `plugins: {"file-plugin": {"pluginId": "test-echo", "settings":
  {"greeting": "hi"}}}` and an admin sends `POST /v1/admin/config/file/migrate` with `{"types": ["plugins"]}`
- **THEN** the result has `resourceUrl: "plugins/platform/file-plugin"` and `status: "migrated"`, and
  `GET /v1/plugins/platform/file-plugin` answers `200` with the same `pluginId` and `settings`

#### Scenario: File plugin inspected
- **WHEN** the same file entry exists and an admin sends `GET /v1/admin/config/file/plugins/file-plugin`
- **THEN** Core answers `200` with `name: "file-plugin"`, `status: "valid"` and `pluginId: "test-echo"`

### Requirement: Origin plugins
A `plugins` entry SHALL be able to serve an interface whose base url, or whose translator's `baseUrl`, is a plugin
url naming it (deployment-interfaces-config, deployment-origin): its plugin answers the interface's requests in place
of an HTTP origin. Core SHALL create the plugin from the entry's `settings` when a request first needs it and again on
the first request after the entry changes, so a request is never served with settings older than the config it was
routed by.

#### Scenario: Settings change takes effect
- **WHEN** `plugins.p1` with `pluginId: test-origin` and `settings.greeting: hello` serves application `app`, a chat
  completion for `app` is answered with `hello`, and an admin then sends `PUT /v1/plugins/platform/p1` with
  `{"pluginId": "test-origin", "settings": {"greeting": "hi"}}`
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
- **WHEN** `plugins.p1` with `pluginId: test-origin` and `settings.greeting: hello` serves application `app`, a chat
  completion for `app` is answered with `hello`, an admin then sends `PUT /v1/plugins/platform/p1` with
  `{"pluginId": "test-origin", "settings": {"greeting": "hi"}}`, and the next chat completion is answered with `hi`
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

### Requirement: Plugin directories included and excluded
Core SHALL load a plugin directory only when its path `<group>/<plugin>` matches a pattern of the static setting
`plugins.include` (default `["*"]`) and no pattern of `plugins.exclude` (default `[]`). A pattern is `*` or
`<group>/<plugin>` where either part may be `*`; each setting is a JSON array of patterns or one comma-separated
string. Core SHALL fail to start on any other pattern and on an empty `plugins.include`, and SHALL not open a plugin
directory it does not load.

#### Scenario: Default loads everything
- **WHEN** `builtin/echo` and `custom/other` exist and neither setting is given
- **THEN** the providers of both are loaded

#### Scenario: Excluded plugin directory
- **WHEN** `builtin/echo` provides plugin id `jar-echo`, `plugins.exclude` is `["builtin/echo"]`, and an admin sends
  `PUT /v1/plugins/platform/p1` with `{"pluginId": "jar-echo"}`
- **THEN** Core answers `422` with one warning, `field: "pluginId"`

#### Scenario: Excluded group
- **WHEN** `builtin/echo` and `custom/other` exist and `plugins.exclude` is the string `builtin/*`
- **THEN** only the providers of `custom/other` are loaded

#### Scenario: Include list
- **WHEN** `builtin/echo` and `custom/other` exist and `plugins.include` is `["custom/*"]`
- **THEN** only the providers of `custom/other` are loaded

#### Scenario: Built-in replaced by a mounted build
- **WHEN** `builtin/echo` and `custom/echo` both provide plugin id `jar-echo` and `plugins.exclude` is
  `["builtin/echo"]`
- **THEN** Core starts and serves plugin id `jar-echo` with the provider of `custom/echo`

#### Scenario: Excluded broken plugin directory
- **WHEN** `custom/broken` holds a file `broken.jar` that is not a jar and `plugins.exclude` is `["custom/broken"]`
- **THEN** Core starts

#### Scenario: Malformed pattern
- **WHEN** `plugins.exclude` is `["a/b/c"]`
- **THEN** Core does not start, with a message naming the pattern

#### Scenario: Empty include
- **WHEN** `plugins.include` is `[]`, or the empty string an unset environment variable gives
- **THEN** Core does not start, with a message naming `plugins.include`

## ADDED Requirements

### Requirement: Deprecated plugin type alias
Core SHALL accept `type` as a deprecated alias of `pluginId` wherever it reads a `plugins` entity: API writes, admin
validate and apply manifests, config files and stored blobs. A body with both SHALL not bind; each config file SHALL
be read on its own before the files merge, so a later file's `pluginId` replaces an earlier `type`. Core SHALL store
and return `pluginId` only, for an invalid entity with one of the names too, and name `pluginId` in its warnings.

#### Scenario: Written with the alias
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with `{"type": "jar-echo"}` and a provider of id `jar-echo`
  that accepts any settings is loaded
- **THEN** Core answers `200`, the blob `plugins/platform/p1` holds `pluginId: "jar-echo"` and no `type`, and
  `GET /v1/plugins/platform/p1` answers `pluginId: "jar-echo"` and no `type`

#### Scenario: Both names
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with `{"pluginId": "jar-echo", "type": "jar-echo"}`
- **THEN** Core answers `400` and writes nothing

#### Scenario: Unknown id written with the alias
- **WHEN** no provider of id `missing` is loaded and an admin sends `PUT /v1/plugins/platform/p1` with
  `{"type": "missing"}`
- **THEN** Core answers `422` with one warning, `field: "pluginId"`

#### Scenario: Manifest with the alias
- **WHEN** an admin sends `POST /v1/admin/apply` with one `Plugin` manifest `plugins/platform/p1` whose `spec` is
  `{"type": "test-echo", "settings": {"greeting": "hi"}}`
- **THEN** that result is `APPLIED`, and `GET /v1/plugins/platform/p1` answers `pluginId: "test-echo"`

#### Scenario: Stored blob with the alias
- **WHEN** a blob `plugins/platform/p1` holds `{"type": "test-echo", "settings": {"greeting": "hi"}}` and the config
  reloads
- **THEN** `GET /v1/plugins/platform/p1` answers `pluginId: "test-echo"` and `status: "valid"`

#### Scenario: Invalid blob with the alias
- **WHEN** `config.onInvalidEntity: skip` is on, no provider of id `missing` is loaded, a blob
  `plugins/platform/p1` holds `{"type": "missing"}` and the config reloads
- **THEN** `GET /v1/plugins/platform/p1` answers `pluginId: "missing"`, no `type`, and `status: "invalid"`

#### Scenario: File entry with the alias
- **WHEN** the config file declares `plugins: {"legacy-plugin": {"type": "test-echo", "settings": {"greeting":
  "hi"}}}`
- **THEN** `GET /v1/admin/config/file/plugins/legacy-plugin` answers `pluginId: "test-echo"` and `status: "valid"`

#### Scenario: Overlay changes the plugin
- **WHEN** a config file declares `plugins.p1` as `{"type": "test-echo", "settings": {"greeting": "hi"}}` and a
  config file after it in the list declares `plugins.p1` as `{"pluginId": "test-origin"}`
- **THEN** Core starts, and the file config has `plugins.p1` with `pluginId: "test-origin"`, `settings.greeting:
  "hi"` and no `type`

### Requirement: Settings checked against the provider's schema
When a provider declares a settings schema (JSON Schema draft-07), Core SHALL check `settings` against it before the
provider's own check, and SHALL skip that check when the schema rejects them. Each violation SHALL be a warning on
`settings.<path>`, or on `settings` for the whole object, a missing or not allowed property naming that property; a
check that fails SHALL be one warning on `settings`. The outcomes are those of Settings validated by the provider.

#### Scenario: Wrong value type
- **WHEN** the loaded provider `test-schema` declares a schema requiring a string `greeting`, and an admin sends
  `PUT /v1/plugins/platform/p1` with `{"pluginId": "test-schema", "settings": {"greeting": 1}}`
- **THEN** Core answers `422` with one warning whose `field` is `settings.greeting`, writes nothing, and the
  provider's own check is not called

#### Scenario: Missing required property
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with `{"pluginId": "test-schema", "settings": {}}`
- **THEN** Core answers `422` with one warning whose `field` is `settings.greeting`, and writes nothing

#### Scenario: Property not allowed
- **WHEN** the schema of `test-schema` allows no other properties, and an admin sends `PUT /v1/plugins/platform/p1`
  with `{"pluginId": "test-schema", "settings": {"greeting": "hi", "greting": "hi"}}`
- **THEN** Core answers `422` with one warning whose `field` is `settings.greting`, and writes nothing

#### Scenario: Accepted by the schema
- **WHEN** an admin sends `PUT /v1/plugins/platform/p1` with
  `{"pluginId": "test-schema", "settings": {"greeting": "hi"}}`
- **THEN** Core answers `200`, and the provider's own check was called with those settings

#### Scenario: Schema check fails
- **WHEN** a loaded provider declares the schema `{"allOf": [{"$ref": "#"}]}`, which refers to itself without
  reading the settings, and an admin writes an entry of it
- **THEN** Core answers `422` with one warning whose `field` is `settings`, writes nothing, and the provider's own
  check is not called

#### Scenario: Rejected by the schema in a batch
- **WHEN** an admin sends `POST /v1/admin/validate` with one `Plugin` manifest `plugins/platform/p1` of plugin id
  `test-schema` whose `spec.settings` is `{"greeting": 1}`
- **THEN** Core answers `422` with that result `FAILED` and an `error` naming `settings.greeting`

#### Scenario: Rejected by the schema on a rebuild under skip
- **WHEN** a blob `plugins/platform/p1` of plugin id `test-schema` holds `settings: {"greeting": 1}`,
  `config.onInvalidEntity: skip` is on and the config reloads
- **THEN** the entity is dropped and recorded as invalid with warning field `settings.greeting`

### Requirement: Plugin metadata errors stop the start
Core SHALL fail to start, naming the provider and its plugin directory, when a loaded provider declares a settings
schema that is not valid JSON Schema draft-07, names another draft in `$schema`, or has a `$ref` in any subschema to a
schema outside itself other than the draft-07 meta-schema; when it declares no targets; or when reading its metadata
throws. Core SHALL load nothing a settings schema refers to from a url.

#### Scenario: Invalid settings schema
- **WHEN** `<plugins.dir>/custom/bad/` holds a jar whose provider declares id `bad` and the settings schema
  `{"type": "no-such-type"}`
- **THEN** Core does not start, with a message naming `bad`, `custom/bad` and the settings schema

#### Scenario: Schema of another draft
- **WHEN** the provider `bad` declares the settings schema
  `{"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object"}`
- **THEN** Core does not start, with a message naming `bad`, `custom/bad` and `$schema`

#### Scenario: Draft-07 named by its https address
- **WHEN** `<plugins.dir>/custom/good/` holds a jar whose provider declares id `good` and the settings schema
  `{"$schema": "https://json-schema.org/draft-07/schema#", "type": "object"}`
- **THEN** Core starts, and `GET /v1/admin/plugin-providers` lists `good` with that schema

#### Scenario: Remote reference
- **WHEN** the provider `bad` declares the settings schema `{"$ref": "<url>"}`, where `<url>` is served by a local
  HTTP server
- **THEN** Core does not start, with a message naming `bad`, `custom/bad` and `<url>`, and the server receives no
  request

#### Scenario: Remote reference no keyword uses
- **WHEN** the provider `bad` declares the settings schema `{"type": "object", "definitions": {"x": {"$ref":
  "<url>"}}}`, where nothing refers to `#/definitions/x`
- **THEN** Core does not start, with a message naming `bad`, `custom/bad` and `<url>`

#### Scenario: Reference and draft in a value
- **WHEN** the provider `good` declares a settings schema whose `default` is `{"$schema": "https://example.com/my-settings"}`
  and one of whose properties has `examples` `[{"$ref": "https://example.com/order.json"}]`
- **THEN** Core starts: a value of `default`, `const`, `enum` or `examples` is data, not a subschema

#### Scenario: Metadata that throws
- **WHEN** reading the settings schema of the provider `bad` throws
- **THEN** Core does not start, with a message naming the provider class and `custom/bad`

#### Scenario: No targets
- **WHEN** `<plugins.dir>/custom/bad/` holds a jar whose provider declares id `bad` and an empty list of targets
- **THEN** Core does not start, with a message naming `bad` and `custom/bad`

### Requirement: Plugin targets are a hint
Core SHALL NOT check a plugin url against the targets of the provider of the entry it names: a model, an application,
a router or a translator naming an entry SHALL be accepted and served whatever targets that provider declares.

#### Scenario: Plugin url outside the declared targets
- **WHEN** the loaded provider `test-origin` declares only the target `router`, `plugins.p1` has
  `pluginId: test-origin`, and an admin sends `PUT /v1/models/platform/m` with `{"type": "chat", "baseUrl":
  "dial-plugin://p1", "interfaces": {"openaiChatCompletions": {}}}`
- **THEN** Core answers `200`, and the `test-origin` plugin answers a chat completion for `m`

### Requirement: Plugin providers listing
Core SHALL answer `GET /v1/admin/plugin-providers` from a caller with the admin role with a JSON array of the
providers loaded at start, ordered by `id`. Each item SHALL have `id` and `targets`, and, when the provider has them,
`directory` (its plugin directory `<group>/<plugin>`), `version` (its jar manifest's `Implementation-Version`),
`description` and `settingsSchema`. Any other caller SHALL get `403`.

#### Scenario: Plugin with its metadata
- **WHEN** `<plugins.dir>/custom/echo/` holds a jar whose manifest has `Implementation-Version: 1.2.0` and whose
  provider declares id `jar-echo`, the description `Echoes the greeting`, the targets `model` and `application`, and
  a settings schema, and an admin sends `GET /v1/admin/plugin-providers`
- **THEN** the answer has the item `{"id": "jar-echo", "directory": "custom/echo", "version": "1.2.0",
  "description": "Echoes the greeting", "targets": ["model", "application"], "settingsSchema": <that schema>}`

#### Scenario: Provider without metadata
- **WHEN** a provider on Core's classpath declares only its id `test-echo`, and an admin sends
  `GET /v1/admin/plugin-providers`
- **THEN** the answer has the item `{"id": "test-echo", "targets": ["model", "application", "router",
  "translator"]}`

#### Scenario: Excluded plugin directory not listed
- **WHEN** `<plugins.dir>/builtin/echo/` provides id `jar-echo`, `plugins.exclude` is `["builtin/echo"]`, and an
  admin sends `GET /v1/admin/plugin-providers`
- **THEN** no item has `id: "jar-echo"`

#### Scenario: Listed whatever the config holds
- **WHEN** no `plugins` entry names `jar-echo`, and an admin sends `GET /v1/admin/plugin-providers`, then writes
  `plugins/platform/p1` with `pluginId: jar-echo`, then deletes it, sending the same `GET` after each step
- **THEN** each answer is the same and has the item of `jar-echo`

#### Scenario: Not an admin
- **WHEN** a caller without the admin role sends `GET /v1/admin/plugin-providers`
- **THEN** Core answers `403`
