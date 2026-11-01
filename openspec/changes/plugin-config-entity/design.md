# Design

## Context

See proposal.md for the motivation. The facts of the code on `development` that shape this change:

- The runtime `Config` is the union of the config files and the `platform` bucket blobs. `MergedConfigStore.rebuild`
  lists one blob folder per type in `MANAGED_TYPES` (`MergedConfigStore.java:rebuild`), parses each blob once
  (`MergedConfigStore.java:addBlobEntity`), then runs the semantic checks over the whole merged config
  (`ConfigPostProcessor.java:processSemantic`). A Configuration API write skips the rebuild and patches the live
  config in place (`MergedConfigStore.java:applyEntityWrite`, `applyChangeInPlace`); other pods apply the same
  write from the resource event (`MergedConfigStore.java:onResourceEvent`, `applyReplicaEvent`,
  `deserializeReplicaEntity`). The file-only config is validated on its own first
  (`FileConfigStore.java:load` calls `ConfigPostProcessor.java:process`).
- A short-name type (`MergedConfigStore.java:isShortNameKeyed`) is keyed by its name in both sources, so a blob
  replaces the file entry of the same name. `translators` is the newest such type and is the template this change
  follows: `Translator` in the `config` module, `Config.translators`, `ResourceTypes.TRANSLATOR`, and one `case`
  per switch in the store, the controllers and the manifest support (listed under Decisions).
- Validation has two severities. An invariant (`ConfigPostProcessor.java:validateTranslator`,
  `validateModelInvariants`) rejects the write in every mode and makes the entity invalid on a rebuild in every mode.
  A dangling reference (`ConfigPostProcessor.java:validateCrossReferences` for a model's interceptors) is checked on
  write unless `config.write.softValidation` is on, and on a rebuild only under `config.onInvalidEntity: skip`
  (`processModels` passes the check only when `onSkip` is non-null), so an inconsistency already in the files
  cannot fail a rebuild under `abort`.
- The Configuration API controllers dispatch per type: `ConfigResourceController.java:handleGet`, `descriptorFor`,
  `prepareWrite` (the `WriteSpec` switch), `entityClassFor`, `handlePut`; the metadata listing is generic over
  `ResourceTypes.of` (`ConfigResourceMetadataController.java:handle`); the file inspection maps a type to a
  `Config` map (`FileConfigController.java:entitySource`); migration lists its types in
  `ConfigFileMigrateController.java` (`ManagedTypeSpec` entries). The batch endpoints parse a manifest by `kind`
  (`ConfigManifestSupport.java:parseManifest`, `mutateScratch`, `DEPENDENCY_ORDER`, `KIND_URL_SEGMENT`), validate
  it (`ConfigValidationService.java:validateOnly`) and write it (`ConfigApplyService.java:applyTranslator` and the
  typed-manifest switch that calls it). The URL patterns that reach these controllers enumerate the types in
  `RouteTemplate.java` (the three patterns for `/v1/{type}/...`, `/v1/metadata/{type}/...` and
  `/v1/admin/config/file/{type}`).
- Secrets at rest work on `@EncryptedField` string fields of typed classes only (`SecretFieldProcessor.java:walk`);
  an opaque JSON object is never encrypted or masked. The static settings have one code default per key, because
  the test-resources `aidial.settings.json` shadows the built-in one.
- The distribution's start script lists the jars of `server/build/distributions` one by one, so a jar dropped into
  `/app/lib` is not on the classpath. `ServiceLoader` with a child class loader also finds services the parent
  class loader can see.
- Resource events are published only for types in `ResourceTypes.EVENT_TYPES`; a pod of a release that does not
  list a type does not publish it.

## Goals / Non-Goals

**Goals:**
- `plugins` behaves exactly like `translators` on every admin surface: files, blobs, merge, shadowing, write, batch,
  migration, inspection, health, metadata, routing, OpenAPI, docs.
- Provider code is loaded once at start and fails the start loudly; declarations are checked against it with the two
  existing severities and no new one.
- A plugin author gets one artifact to compile against, with the provider contract in this change and the kind
  contracts in the follow-ups, so the module is created here and extended there.

**Non-Goals:**
- Creating plugin instances, or anything on the request path. The registry holds providers, not instances.
- Isolating plugin jars from each other or from Core's classpath.
- Publishing `plugin-api`; a repository-local module is enough for the tests.

## Decisions

### D1. A managed entity type, built by the translator recipe

`plugins` becomes a `ResourceTypes` constant and a `Config` map, and gets a `case` wherever `TRANSLATOR` has one.
On `development` those are: `ResourceTypes.java` (constant, `of`, `EVENT_TYPES`),
`PlatformEntityLocationStrategy.java:resolveBucket`, `MergedConfigStore.java` (`MANAGED_TYPES`,
`deserializeReplicaEntity`, `addBlobEntity`, `removeAddedEntity`, `isShortNameKeyed`, `cloneTypeMap`,
`peekEntity`, `putEntityInPlace`, `removeEntityInPlace`, `applyChangeInPlace`), `ConfigResourceController.java`
(`handleGet`, `descriptorFor`, `prepareWrite`, `entityClassFor`, the three OpenAPI-annotated method blocks that
describe `/v1/translators/{bucket}/{path}`), `ConfigResourceMetadataController.java` (the annotated block for
`/v1/metadata/translators/{bucket}/{path}`), `FileConfigController.java` (`entitySource` and the annotated blocks for
`/v1/admin/config/file/translators`), `ConfigFileMigrateController.java` (a `ManagedTypeSpec`),
`ConfigManifestSupport.java` (`DEPENDENCY_ORDER`, `KIND_URL_SEGMENT`, `parseManifest`, `mutateScratch`),
`AdminManifest.java` (an `@ApiSubType` for `Plugin` and a new `AdminPluginManifest` record),
`ConfigValidationService.java:validateOnly`, `ConfigApplyService.java` (an `applyPlugin` next to
`applyTranslator`), `ConfigPostProcessor.java:processStructural` (`rejectSlashKeyedNames` for `plugins`) and
`RouteTemplate.java` (the three type lists). `DEPENDENCY_ORDER` gets `Plugin` at rank 2 and the kinds from
`Translator` on move one rank down, so a plugin is applied before the kinds that will reference it.

Rejected: declaring plugin instances inline where they attach (inside `interfaces`, `interceptors` and
`translators`), which would need none of the above because those types are already managed. The user chose a
registry of its own, so one declaration can be referenced from several places and the admin tooling can list
plugins as a type. Recorded here so the follow-up changes do not reopen it.

### D2. The entity class

`Plugin` in the `config` module: a final `type` (`String`, required, non-blank) and a final `settings`
(`ObjectNode`, `{}` when absent), built by a `@JsonCreator` constructor that throws on a blank `type`, as
`Translator` does for `out` and `baseUrl`. No `kind` field: the provider knows its kind, and a field that can
disagree with it would need one more rule. Unknown fields fail binding, which the write path already answers with
`400` for a body that does not bind (`ConfigResourceController.java:handlePut` through
`ConfigEntityCodec.java:treeToEntity`). `Config.plugins` is a `Map<String, Plugin>` defaulting to `Map.of()`,
keyed by short name like `translators`.

Rejected: `Map<String, Object>` for settings, which would make Jackson rebuild the tree on every provider call;
`ObjectNode` is handed to the provider as is.

### D3. The provider contract and the `plugin-api` module

A new Gradle module `plugin-api` (added to `settings.gradle`; `server` depends on it; the module depends on
`jackson-databind` only, at the `jackson_core_version` the other modules use) holds:

- `PluginProvider`: `String type()` and `List<Problem> validate(ObjectNode settings)`, where `Problem` is a record
  `(String path, String message)` in the same module. An empty list means valid. The host turns a problem into a
  `ValidationWarning` with field `settings.<path>` (or `settings` when the path is empty).
- `PluginSettingsException` is not introduced: returning problems lets a provider report several fields at once, as
  the model invariants do.

The kind contracts (origin, filter) are added to this module by the follow-up changes, so a plugin jar compiles
against one artifact. Vert.x types are allowed in that later API by decision; this change needs none.

Rejected: a JDK-only contract taking a JSON string, which would force every provider to parse the settings again.

### D4. Loading providers

`PluginRegistry` in the server `config` package: `static PluginRegistry load(Path dir)` builds one
`URLClassLoader` over the `*.jar` files of `dir` with Core's application class loader as parent, runs
`ServiceLoader.load(PluginProvider.class, loader)`, and indexes the providers by `type`. A `ServiceConfigurationError`
or a duplicate `type` throws, and `AiDial.start` fails with a message naming the jar or the type. A missing
directory gives an empty registry. `PluginRegistry.of(List<PluginProvider>)` exists for unit tests.

The directory comes from the static setting `plugins.dir`, read as `settings("plugins").getString("dir", "plugins")`
in `AiDial.java`, with `"plugins": {"dir": "plugins"}` added to the built-in `aidial.settings.json` and to the
test-resources copy. The Docker image gets `/app/plugins` created and owned by `appuser` in the `Dockerfile`, so a
deployment mounts or copies jars there; no start-script change.

Because the child loader's parent is the application loader, a provider registered in the application classpath's
`META-INF/services` is found too. That is how the integration tests get their provider (D7) without building a jar.
In production the application classpath carries no provider.

Rejected: one class loader per jar (isolation nobody asked for, and first-party jars share `plugin-api` and Jackson
with Core); a classpath wildcard in the start script (Gradle lists jars explicitly, and a settings-driven directory
is the contract operators can see).

### D5. Validation wiring

`ConfigPostProcessor` is static and already receives a runtime service as a nullable parameter (`ApiKeyStore` in
`process` and `processSemantic`). `PluginRegistry` is added the same way; `null` reads as an empty registry.
`FileConfigStore` and `MergedConfigStore` receive the registry in their constructors and pass it on; `AiDial.start`
builds the registry before the stores.

- `ConfigPostProcessor.validatePlugin(name, plugin, registry, checkType, warnings)`: when the provider is loaded, its
  problems become `settings.*` warnings (invariant); when it is not and `checkType` is true, one warning with field
  `type` (dangling reference).
- `processPlugins(config, registry, onSkip)` runs in `processSemantic` after `processRoutes`, with
  `checkType = onSkip != null`, mirroring `processModels`: under `abort` an unknown type is tolerated on a rebuild,
  under `skip` the entity is dropped and recorded.
- `validateSinglePlugin(config, registry, mapKey, onSkip)` for `applyChangeInPlace`, as `validateSingleTranslator`.
- The write path (`ConfigResourceController.java:handlePut`, the block that calls `checkTranslator`) calls
  `validatePlugin` with `checkType = !softValidation` and answers `422` on warnings, as it does for a model's
  interceptors. `ConfigValidationService.validateOnly` and `ConfigApplyService.applyPlugin` do the same for the
  batch endpoints, resolving nothing against the scratch config because a plugin references no other entity.

Rejected: making an unknown `type` an invariant. Code arrives by rollout and declarations by bucket, so during a
rolling update an older pod sees declarations whose jar it does not have; an invariant would fail that pod's
rebuild on every bucket write, and the dangling rule exists for exactly this case.

### D6. What the mixed-release window means

`PLUGIN` is added to `EVENT_TYPES` in this change. The one-release-ahead rule for that set protects existing types
from a consumer that appears before its publisher; here the type is new and no older pod can write a plugin blob,
so there is no window to protect. An older pod ignores plugin events before parsing them
(`MergedConfigStore.java:isManagedEventUrl` filters on its own segment set) and never lists the `plugins` folder.

### D7. Tests

- `plugin-api`: none beyond compilation; the contract has no logic.
- `PluginRegistryTest` (unit, server module): loads a jar written in a `@TempDir` from the test provider's class
  bytes and a `META-INF/services` entry, then asserts the provider is found, a second jar with the same `type` fails,
  and a missing directory loads nothing.
- `TestEchoPluginProvider` in `server/src/test`, registered in `server/src/test/resources/META-INF/services`, of
  type `test-echo`, rejecting settings without a string `greeting` with problem path `greeting`. Every integration
  scenario uses it; the `additionalSettingsOverrides` hook of `ResourceBaseTest` sets `config.onInvalidEntity` and
  `config.write.softValidation` per test class, as the existing config-store tests do.
- `ConfigPluginApiTest` (integration): the plugin-registry scenarios and the new config-store and request-routing
  scenarios; file plugins come from a `plugins` block added to `server/src/test/resources/aidial.config.json`.
  Existing suites that enumerate types (`ConfigFileMigrateApiTest`, `AdminApplyApiTest`, `AdminValidateApiTest`,
  `MergedConfigStorePartialUpdateTest`) gain one plugin case each where they list a per-type case today.

### D8. Effect per request

None. No request Core serves or sends changes, no body is parsed or copied, no Redis or blob call is added on the
request path. A full config rebuild lists one more blob folder; a Configuration API write of a plugin costs the same
blob put and resource event as a translator write. No cost effect is claimed, so there is no measurement task.

## Risks / Trade-offs

- [A bad jar stops Core from starting] → that is the intended behaviour for first-party code shipped by rollout;
  the message names the jar and the error, and the directory defaults to empty, so an image without plugins is
  unaffected.
- [Two jars bring conflicting versions of a shared library] → not handled: one flat loader, parent first. Plugins
  depend on `plugin-api` and what Core already ships. Revisit with per-jar loaders only when a conflict appears.
- [Settings end up holding a secret anyway] → the entity is documented as non-secret and `GET` returns settings
  as stored; the upgrade path is an encrypted string map on the entity, as `Key.key` and `Upstream.key`.
- [The merged config JSON of `POST /v1/ops/config/reload` grows by the plugin declarations] → it already carries
  every other type; nothing new is exposed.
- [Admin export] → `docs/admin-export.md` says the export covers the `platform` bucket; a task confirms plugin blobs
  are included without a type list to extend.

## Migration Plan

Deploy with no plugins directory and no `plugins` declarations: behaviour is unchanged. Rollback: an older release
ignores `plugins` in the files only if the file loader tolerates unknown top-level keys; check
`FileConfigStore.java:loadConfig` during apply and, if it does not, document that `plugins` must be removed from the
files before rolling back. Blobs under `plugins/platform/` are ignored by an older release (D6).

## Open Questions

- Whether to add a `dial_plugins` gauge next to `dial_policy_assignments`: cosmetic, decided at apply by whether
  the operators' dashboard uses the existing per-type gauges.
