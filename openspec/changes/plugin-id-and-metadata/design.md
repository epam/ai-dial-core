# Design

## Context

At start Core finds each plugin provider with `ServiceLoader`, on its classpath and in the plugin directories, and
keeps it by the string its `type()` returns. A `plugins` entry `{type, settings}` is bound by a Jackson creator that
refuses unknown fields. Core stores and returns the bound object, not the request body, so a write normalizes the
blob. Every write surface (Configuration API, admin validate and apply, full and partial rebuilds) checks an entry
through one method: the provider of its `type` is looked up, a missing one is a dangling reference, and the
provider's `validate()` reports problems as `settings.<path>` warnings. A request served by a plugin finds the
provider the same way and creates an instance from the settings. Core keeps nothing else about a provider and
serves no list of them.

## Decisions

### D1. The entry says `pluginId`, the provider says `id()`

The entry field names what the entry runs, in the words a QA engineer uses ("the plugin"), and it cannot be mistaken
for the entry's own name in the way a bare `id` in the body could. On the Java side the id is the provider's own, so
`id()` reads naturally there. The plugin directory `<group>/<plugin>` stays "plugin directory" in messages and
docs, and the listing shows it as `directory`, so the two ids are not confused.

**Rejected: `plugin`** - `plugins.p1.plugin` repeats itself.
**Rejected: `provider`** - clashes with the model field `provider` and with the name of the model-provider plugin.
**Rejected: `kind`** - the word is kept for the kind of a plugin (origin, filter).

### D2. `type` is read, never written

The creator takes `pluginId` and `type` as two optional parameters and refuses a body with neither, with a blank
value, or with both naming different ids, which the write path answers with `400`. Both naming the same id bind:
`FileConfigStore.loadConfig` merges the config files into one tree before binding, so an overlay that moves an entry
to `pluginId` over a base file that still says `type` gives both names, and refusing that would stop the whole file
config. `type` is a creator parameter only, with no field or getter, so Core stores and returns `pluginId` and the
OpenAPI schema shows only `pluginId` and `settings`.

An entity recorded as invalid is served from its stored body (`ConfigResourceController.projectInvalidItem`), not
from a bound object, so for a plugin that projection renames `type` to `pluginId` when the body has `type` only. A
body with both names that did not bind is shown as stored.

docs/CODE_STYLE.md forbids renaming a Jackson-bound field in `config`, because the name is a public key of the config
files. This change breaks that rule on purpose: no release tag contains `plugins`, and the alias keeps every file and
blob that says `type` loading.

**Rejected: `@JsonAlias("type")`** - a body with both names binds silently, the last one winning.
**Rejected: refuse both names whatever their values** - breaks the file overlay above for a rename that changes
nothing.
**Rejected: return both names until the alias goes** - every blob would carry the duplicate, and a client changing
the plugin would have to change both.

### D3. `id()` falls back to a deprecated `type()` that returns null

`default String id() { return type(); }` and `@Deprecated(forRemoval = true) default String type() { return null; }`.
Core calls only `id()`. A jar built today overrides `type()` and loads unchanged; a new jar overrides `id()` only; a
provider that overrides neither has a null id, which the existing "declares no id" load error reports.

**Rejected: each default calling the other** - a provider overriding neither recurses until the stack overflows.
**Rejected: keep `type()` abstract** - every new plugin would have to implement a deprecated method.

### D4. Core enforces the schema, before the provider's check

The schema is the provider's own declaration of its settings, so Core checks it on the same surfaces as `validate()`
and with the same outcomes. When the schema rejects the settings, `validate()` is not called. Each violation
becomes a warning: the field is `settings` plus the validator's instance location (`settings.greeting`,
`settings.items[0].name`), and for a violation the validator reports about one property (`required`,
`additionalProperties`, `dependencies`) the field names that property. `validate()` gets a
default that accepts everything, so a plugin whose rules a schema can say needs no validation code.

**Rejected: a hint for the admin console only** - the schema and `validate()` would drift, and the console would
accept what Core refuses.
**Rejected: run both and merge the problems** - `validate()` would see settings of the wrong shape, and an exception
it throws would hide the schema's precise message.

### D5. Draft-07 with the validator Core already has, compiled once at start

At load Core checks each declared schema in four steps, and a failure of any stops the start with a message naming
the id and the plugin directory:

1. `$schema` is absent or the draft-07 IRI (`http://json-schema.org/draft-07/schema`, with or without `#`). Neither
   the meta-schema nor the V7 factory refuses another draft by itself: the meta-schema only asks for a URI, and the
   factory compiles a schema by the draft its `$schema` names.
2. The schema is valid against the draft-07 meta-schema bundled in the `networknt` jar.
3. The schema compiles with a V7 factory whose schema loaders start with an `AllowSchemaLoader` that allows only
   `classpath:draft-07/schema`. The factory maps the json-schema.org IRIs to `classpath:` before it asks a loader,
   so the bundled draft-07 meta-schema still loads, and any other `$ref` target (`http`, `https`, `file`, another
   `classpath:` resource, a relative path) throws instead of being loaded.
4. `initializeValidators()` runs on the compiled schema, so a `$ref` fails here, at start, and never on a check.

Core keeps the compiled schema beside the provider. A check reuses it, and it is thread-safe, so the event-loop
checks stay cheap and never load anything.

Every call into the provider at load (`id()`, `settingsSchema()`, `targets()`, `description()`) runs in one try
block: an exception or a `LinkageError` from plugin code is rethrown as an `IllegalStateException` naming the
provider class, the id when known and the plugin directory, since `addProviders` catches only
`ServiceConfigurationError` today.

**Rejected: draft 2020-12** - Core's application type and catalog schemas are draft-07, and the console's JSON
editor supports draft-07 fully.
**Rejected: compile on every check** - repeats the same work on every write and rebuild for a schema that cannot
change while Core runs.
**Rejected: allow remote `$ref` and ask for self-contained schemas in the docs** - a fetch at start makes the start
depend on another host, and a lazy fetch on a check blocks an event loop.
**Rejected: resolve `$ref` against the plugin's own jar** - no plugin needs it yet; a schema inlines its definitions
under `definitions`.

### D6. Targets are an enum, all four by default, and a hint

`PluginTarget { MODEL, APPLICATION, ROUTER, TRANSLATOR }` lists the places a plugin url is accepted;
`default Set<PluginTarget> targets()` returns all of them. An empty or null set stops the start. The listing writes
the names in lower case with `Locale.ROOT` (a Turkish default locale would turn `APPLICATION` into `applıcatıon`), in
the enum's order. Core does not compare a plugin url with the targets.

**Rejected: strings** - Core would need its own check of the values at start; an enum cannot hold a typo.
**Rejected: an empty set meaning "any"** - every reader of the listing would need that special case.
**Rejected: check plugin urls against the targets** - a deployment may name an entry that does not exist yet
(deployment-interfaces-config), so the check could run only sometimes, and it would make the order of writes matter.

### D7. Core reads a provider's metadata once, at start

The registry keeps, per id, the provider, its directory, the jar's version, the description, the targets and the
schema (the node as declared and the compiled form). The listing serves that copy and calls no plugin code.

**Rejected: ask the provider on every listing request** - plugin code on a request path for no gain, and the
listed schema could differ from the one Core checks with.

### D8. `version` is the `Implementation-Version` of the jar that registers the provider

Core already opens every jar of a plugin directory to read its service registrations, and now also reads the jar's
manifest. The version of the jar that registers a provider is the version of what the operator deployed.

**Rejected: `Package.getImplementationVersion()` of the provider class** - with parent-first loading the class can
come from another jar or from Core's classpath (the test jars carry only the registration), and the package
version is unset unless the jar's manifest declares it for that package.

### D9. The listing is `GET /v1/admin/plugin-providers`, for the admin role

Like `/v1/admin/health/config`: one route, `isAdmin` or `403`, `x-preview`, operation id `listPluginProviders`.
Items are ordered by `id`; empty fields are left out. A provider on Core's classpath has no `directory` and no
`version`. The path says "providers", the word of the API and this spec, so it cannot be read as the entries' API.

**Rejected: `/v1/admin/plugins`** - reads as the API of the `plugins` entries, which live at `/v1/plugins/{bucket}/...`
and `/v1/admin/config/file/plugins`.
**Rejected: a path under `/v1/plugins/`** - that prefix is followed by a bucket for the entries.
**Rejected: a field of `/v1/admin/health/config`** - that answer is about the config, and this list does not change
with it.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| Entry field | `type` | `pluginId`; `type` accepted as an alias | Plugin entity shape; Deprecated plugin type alias |
| Body with `pluginId` | `400` (unknown field) | accepted | Plugin entity shape |
| Body with both names | - | `400` when the ids differ, else bound | Deprecated plugin type alias |
| Stored blob and GET, invalid entities included | `type` | `pluginId` | Deprecated plugin type alias |
| Warning for an unknown plugin | field `type` | field `pluginId` | Unknown plugin id is a dangling reference |
| Settings check | the provider's `validate()` | the schema, then `validate()` | Settings checked against the provider's schema |
| Start | - | fails on an invalid schema, another draft, an outside `$ref`, empty targets or throwing metadata | Plugin metadata errors stop the start |
| Load error messages | "type" | "id" | Plugin load errors stop the start |
| Listing | none | `GET /v1/admin/plugin-providers` | Plugin providers listing |

## Effect per request

None: requests served by a plugin find the provider and create the instance as today. The schema check runs on
config writes and rebuilds; the listing is an admin request that reads data kept since start.

## Risks / Trade-offs

- A plugin cannot share definitions between schemas through `$ref` to another file; it inlines them. Allowing refs
  into the plugin's jar can come later without changing the API.
- `@Deprecated(forRemoval = true)` on `type()` gives a plugin build that still overrides it a removal warning, which
  fails a build that turns warnings into errors. Such a plugin moves to `id()`.
- A Core built before this change cannot bind a blob written with `pluginId`: during a rolling update of development
  builds, an older pod records it as a parse error and drops the entry in both modes (`MergedConfigStore` skips a
  blob that does not bind before the `abort` check), so on that pod the deployments naming the entry answer `502`.
  A config file edited to `pluginId` stops an older Core from starting. No release has plugins, so only development
  environments can see this.
- The listing shows the plugins of the pod that answered; pods of different versions during a rolling update can
  answer differently. Write validation already depends on the pod in the same way.
- Entries keep `type` in a blob until their next write, and in config files until someone edits them, so removing
  the alias later needs those rewritten first.

## Migration Plan

- Blobs and config files that say `type` keep loading; a blob switches to `pluginId` on its next write. A file entry
  moved with `POST /v1/admin/config/file/migrate` lands with `pluginId`.
- Plugin jars built against today's API load unchanged; `ai-dial-model-provider-plugin` and the `forward` sample
  move to `id()` when convenient.
- The admin console reads `pluginId` in its own PR. Until then its detail view shows an empty type for an entry,
  and a save after retyping the type still works through the alias.
- Rollback: an earlier Core cannot read blobs written by this one, nor config files that say `pluginId` (see Risks);
  rewrite both with `type` first.

## Code map

- `aidial-core-plugin-api/src/main/java/com/epam/aidial/core/plugin/PluginProvider.java`: `id`, `type` (deprecated),
  `description`, `settingsSchema`, `targets`, `validate` (default), `create`; new `PluginTarget.java` beside it.
- `config/src/main/java/com/epam/aidial/core/config/Plugin.java`: the `@JsonCreator` constructor and `pluginId`;
  `config/src/test/java/com/epam/aidial/core/config/PluginTest.java`.
- `server/src/main/java/com/epam/aidial/core/server/plugin/PluginRegistry.java`: `load`, `addProviders` (ids, the
  metadata checks and the schema compile), `listJars` and `PluginJars` (the manifest version per registered class),
  `get`; a new `list` for the listing.
- `server/src/main/java/com/epam/aidial/core/server/config/ConfigPostProcessor.java:validatePlugin` (the schema check
  before `validate()`, field `pluginId`), `processPlugins` (javadoc).
- `server/src/main/java/com/epam/aidial/core/server/service/config/ConfigApplyService.java:applyPlugin` (the soft
  failure looks up `getPluginId()`).
- `server/src/main/java/com/epam/aidial/core/server/plugin/PluginManager.java:create` (looks up `getPluginId()`).
- `server/src/main/java/com/epam/aidial/core/server/controller/ConfigResourceController.java:checkPlugin` (unchanged
  call, for reference); `projectInvalidItem` (renames `type` to `pluginId` in a plugin payload that has `type` only).
- `server/src/main/java/com/epam/aidial/core/server/config/FileConfigStore.java:loadConfig` (the tree merge that can
  give both names; unchanged).
- New `server/src/main/java/com/epam/aidial/core/server/controller/AdminPluginProvidersController.java`, modelled on
  `AdminHealthConfigController.java:handle`; its answer item a record in `server/src/main/java/com/epam/aidial/core/server/data/`,
  as `HealthResponse.java`.
- `server/src/main/java/com/epam/aidial/core/server/data/RouteTemplate.java`: `ADMIN_PLUGIN_PROVIDERS` beside `CONFIG_HEALTH`;
  `server/src/main/java/com/epam/aidial/core/server/controller/ControllerSelector.java` (static initializer, beside
  the `CONFIG_HEALTH` route), with `AdminRoleAuthorizationService` for `isAdmin`.
- `server/src/main/java/com/epam/aidial/core/server/config/MergedConfigStore.java:getPluginRegistry`.
- Draft-07 factory and meta-schema: `com.networknt.schema.JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)`,
  as `config/src/main/java/com/epam/aidial/core/config/validation/ConformToMetaSchemaValidator.java` uses it; the
  meta-schema is the `draft-07/schema` resource of the validator jar; `builder.schemaLoaders(...)` as
  `CustomApplicationsConformToTypeSchemasValidator.java` uses it, with `com.networknt.schema.resource.AllowSchemaLoader`.
- `plugin-samples/src/main/java/com/epam/aidial/core/plugin/samples/ForwardOriginPluginProvider.java:type`.
- Tests: `server/src/test/java/com/epam/aidial/core/server/plugin/PluginRegistryTest.java` (with its
  `OtherPluginProvider` and the `IsolatedProvider` source string, which override `type()` today),
  `PluginManagerTest.java`; `server/src/test/java/com/epam/aidial/core/server/ConfigPluginApiTest.java`,
  `AdminApplyApiTest.java`, `AdminValidateApiTest.java`, `ConfigFileMigrateApiTest.java`, `EmbeddedOriginApiTest.java`,
  `EmbeddedCallApiTest.java`, `MergedConfigStoreApiTest.java`; `server/src/test/java/com/epam/aidial/core/server/config/`
  `ConfigPostProcessorTest.java` (the anonymous provider in `registryOf`), `MergedConfigStorePartialUpdateTest.java`, `MergedConfigStoreReplicaUpdateTest.java`,
  the providers `TestEchoPluginProvider.java`, `TestOriginPluginProvider.java`, `JarEchoPluginProvider.java:writeJar`
  (adds a manifest); `server/src/test/resources/aidial.config.json`,
  `server/src/test/resources/dial-config/router-decision-embedded.json`,
  `server/src/test/resources/META-INF/services/com.epam.aidial.core.plugin.PluginProvider`.
- Docs: `docs/dynamic-settings/plugins.md`; the `aidial-core-plugin-api` row of the module table in `CLAUDE.md`.
