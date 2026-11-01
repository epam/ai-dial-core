# Design

## Context

See proposal.md for the motivation. The facts of the code on `development` that shape this change:

- The runtime `Config` is the union of the config files and the `platform` bucket blobs. `MergedConfigStore.rebuild`
  calls `rebuildMergedConfig`, which copies each type map of the file config, lists one blob folder per type in
  `MANAGED_TYPES`, parses each blob once (`MergedConfigStore.java:addBlobEntity`) and runs the semantic checks over
  the merged config (`ConfigPostProcessor.java:processSemantic`). A Configuration API write skips the rebuild and
  patches the live config in place (`MergedConfigStore.java:applyEntityWrite`, `shallowClone`, `cloneTypeMap`,
  `applyChangeInPlace`). Other pods apply the same write from the resource event
  (`MergedConfigStore.java:onResourceEvent`, `applyReplicaEvent`, `deserializeReplicaEntity`). The file-only config
  is validated on its own first (`FileConfigStore.java:load` calls `ConfigPostProcessor.java:process`).
- A short-name type (`MergedConfigStore.java:isShortNameKeyed`) is keyed by its name in both sources, so a blob
  replaces the file entry of the same name. `translators` is the newest such type and is the template this change
  follows (D1).
- Validation has two severities. An invariant (`ConfigPostProcessor.java:validateTranslator`,
  `validateModelInvariants`) rejects the write in every mode and makes the entity invalid on a rebuild in every
  mode. A dangling reference (`ConfigPostProcessor.java:validateCrossReferences`, a model's interceptors) is checked
  on write unless `config.write.softValidation` is on (`ConfigResourceController.java:checkModel`), and on a rebuild
  only under `config.onInvalidEntity: skip` (`processModels` runs the check only when `onSkip` is non-null). So an
  inconsistency already in the files cannot fail a rebuild under `abort`.
- Every config controller first checks the `(type, bucket)` pair against `EntityBucketBinding.ALLOWED_BUCKETS`
  (`EntityBucketBinding.java:isAllowed`) and answers a body-less `404` for an unknown pair, before authorization.
- The Configuration API controllers dispatch per type: `ConfigResourceController.java:handleGet`, `descriptorFor`,
  `prepareWrite` (the `WriteSpec` switch), `entityClassFor`, `handlePut`. The metadata listing is generic over
  `ResourceTypes.of` (`ConfigResourceMetadataController.java:handle`). File inspection maps a type to a `Config` map
  (`FileConfigController.java:entitySource`). Migration has two lists, `ALL_TYPES` and `MANAGED_TYPE_SPECS`
  (`ConfigFileMigrateController.java`). The batch endpoints parse a manifest by `kind`
  (`ConfigManifestSupport.java:parseManifest`, `newScratch`, `mutateScratch`, `DEPENDENCY_ORDER`,
  `KIND_URL_SEGMENT`), validate it (`ConfigValidationService.java:validateOnly`) and write it
  (`ConfigApplyService.java:applyTranslator` and the typed-manifest switch that calls it). The typed manifests are a
  sealed interface (`AdminTypedManifest.java`), so a new kind must be added to `permits`, and the switches over it
  stop compiling until each has its case. The URL patterns enumerate the types in `RouteTemplate.java` (three
  patterns: `/v1/{type}/...`, `/v1/metadata/{type}/...`, `/v1/admin/config/file/{type}`).
- `Config` is `@JsonIgnoreProperties(ignoreUnknown = true)` and the file loader binds with
  `convertValue(tree, Config.class)` (`FileConfigStore.java:loadConfig`), so a release that does not know a key
  ignores it.
- The admin export (`AdminExportService.java`) copies every blob of the `platform` and `public` buckets except an
  exclusion list (`EXCLUDED_GROUPS`: encryption keys and credentials). There is no inclusion list to extend.
- Secrets at rest work on `@EncryptedField` string fields of typed classes only (`SecretFieldProcessor.java:walk`).
  An opaque JSON object is never encrypted or masked. Every static setting has one code default, because the
  test-resources `aidial.settings.json` shadows the built-in one.
- The distribution's start script (stock Gradle `application` plugin, `server/build.gradle`) lists the jars one by
  one, so a jar dropped into `/app/lib` is not on the classpath. `ServiceLoader.load(Class, loader)` reads
  `META-INF/services` through `loader.getResources`, which is parent-first, so a child class loader also finds the
  services of the application classpath.
- Resource events are published only for types in `ResourceTypes.EVENT_TYPES`; a pod of a release that does not
  list a type does not publish it.
- `dial_policy_assignments` is the only per-type gauge; `translators` has none. The skip counters
  (`dial_config_skip_events_total`) are registered for every `MANAGED_TYPES` entry automatically.

## Goals / Non-Goals

**Goals:**
- `plugins` behaves exactly like `translators` on every admin surface: files, blobs, merge, shadowing, write, batch,
  migration, inspection, health, metadata, export, routing, OpenAPI, docs.
- Provider code is loaded once at start and fails the start loudly. Declarations are checked against it with the two
  existing severities and no new one.
- A plugin author gets one artifact to compile against. The provider contract lands here, the kind contracts in the
  follow-ups, so the module is created here and extended there.

**Non-Goals:**
- Creating plugin instances, or anything on the request path. The registry holds providers, not instances.
- Isolating plugin jars from each other or from Core's classpath.
- Publishing `plugin-api`. A repository-local module is enough for the tests.

## Decisions

### D1. A managed entity type, built by the translator recipe

`plugins` becomes a `ResourceTypes` constant and a `Config` map, and gets a `case` or an entry wherever
`TRANSLATOR` or `translators` has one. On `development`:

- `ResourceTypes.java`: the constant `PLUGIN("plugins", true, 30 days)`, the `of` case, `EVENT_TYPES`.
- `Config.java`: `Map<String, Plugin> plugins = Map.of()`, keyed by short name.
- `PlatformEntityLocationStrategy.java:resolveBucket`.
- `EntityBucketBinding.java:ALLOWED_BUCKETS`: `plugins` bound to the `platform` bucket.
- `MergedConfigStore.java`: `MANAGED_TYPES`; the map wiring in `rebuildMergedConfig` (a fresh `LinkedHashMap`
  copy of the file map, set on the merged config) and in `shallowClone`; the cases in `deserializeReplicaEntity`,
  `addBlobEntity`, `removeAddedEntity`, `isShortNameKeyed`, `cloneTypeMap`, `peekEntity`, `putEntityInPlace`,
  `removeEntityInPlace`, `applyChangeInPlace`. Without the two wiring sites the default `Map.of()` is immutable:
  a rebuild would record every blob plugin as a parse error, and a write would drop every other plugin from the
  live config.
- `ConfigPostProcessor.java`: `processStructural` (`rejectSlashKeyedNames` for `plugins`) and the validation of D5.
- `ConfigResourceController.java`: `handleGet`, `descriptorFor`, `prepareWrite`, `entityClassFor`, the `422` block
  of `handlePut` (D5), and the three OpenAPI-annotated method blocks that describe `/v1/translators/{bucket}/{path}`.
- `ConfigResourceMetadataController.java`: the annotated block for `/v1/metadata/translators/{bucket}/{path}`.
- `FileConfigController.java`: `entitySource` and the annotated blocks for `/v1/admin/config/file/translators`.
- `ConfigFileMigrateController.java`: `ALL_TYPES` and a `ManagedTypeSpec` in `MANAGED_TYPE_SPECS`.
- `AdminTypedManifest.java` (`permits`), `AdminManifest.java` (an `@ApiSubType` for `Plugin`), a new record
  `AdminPluginManifest(kind, name, Plugin spec)`.
- `ConfigManifestSupport.java`: `DEPENDENCY_ORDER` (`Plugin` at rank 2; `Translator` and the later kinds move one
  rank down), `KIND_URL_SEGMENT`, `parseManifest`, `newScratch` (copy the live map), `mutateScratch`.
- `ConfigValidationService.java:validateOnly` and `ConfigApplyService.java` (an `applyPlugin` next to
  `applyTranslator`, and its case in the typed-manifest switch).
- `RouteTemplate.java`: the three type lists.
- No gauge: `translators` has none, and the skip counters come with `MANAGED_TYPES`.

Rejected: declaring plugin instances inline where they attach (inside `interfaces`, `interceptors` and
`translators`). That would need none of the above, because those types are already managed. The user chose a
registry of its own, so one declaration can be referenced from several places and the admin tooling can list
plugins as a type. Recorded here so the follow-up changes do not reopen it.

### D2. The entity class

`Plugin` in the `config` module: a final `type` (`String`, required, non-blank) and a final `settings`
(`ObjectNode`, `{}` when absent), built by a `@JsonCreator` constructor that throws on a blank `type`, as
`Translator` does for `out` and `baseUrl`. No `kind` field: the provider knows its kind, and a field that can
disagree with it would need one more rule. The class carries no `@JsonIgnoreProperties`, so an unknown field fails
binding under `ConfigEntityCodec.BLOB_MAPPER`, which the write path already answers with `400`
(`ConfigResourceController.java:handlePut` through `ConfigEntityCodec.java:treeToEntity`).

Rejected: `Map<String, Object>` for settings, which would make Jackson rebuild the tree on every provider call.
`ObjectNode` is handed to the provider as is.

### D3. The provider contract and the `plugin-api` module

A new Gradle module `plugin-api` (added to `settings.gradle`; `server` depends on it; the module depends on
`jackson-databind` only, at `jackson_core_version`) holds:

- `PluginProvider`: `String type()` and `List<Problem> validate(ObjectNode settings)`. `Problem` is a record
  `(String path, String message)` in the same module. An empty list means valid. The host turns a problem into a
  `ValidationWarning` with field `settings.<path>`, or `settings` when the path is empty.

Returning a list lets a provider report several fields at once, as the model invariants do. The kind contracts
(origin, filter) are added to this module by the follow-up changes, so a plugin jar compiles against one artifact.
Vert.x types are allowed in that later API by decision; this change needs none.

Rejected: a contract taking a JSON string, which would force every provider to parse the settings again.

### D4. Loading providers

`PluginRegistry` in the server `config` package: `static PluginRegistry load(Path dir)` builds one `URLClassLoader`
over the `*.jar` files of `dir`, with Core's application class loader as parent, runs
`ServiceLoader.load(PluginProvider.class, loader)` and indexes the providers by `type`. A missing directory adds no
jars; providers on Core's own classpath are found in every case, because the lookup is parent-first. A
`ServiceConfigurationError` or a duplicate `type` throws, and `AiDial.start` fails with a message naming the jar or
the type.

The directory comes from the static setting `plugins.dir`, read as `settings("plugins").getString("dir", "plugins")`
in `AiDial.java`, with `"plugins": {"dir": "plugins"}` added to the built-in `aidial.settings.json` and to the
test-resources copy. A deployment mounts or copies jars into that directory; a volume mount or a derived image's
`COPY` creates it, so the `Dockerfile` does not change.

In production the application classpath carries no provider. In the server tests it carries
`TestEchoPluginProvider` (D7), so `load` of a missing directory already yields that provider and no second
constructor is needed.

Rejected: one class loader per jar (isolation nobody asked for; first-party jars share `plugin-api` and Jackson
with Core); a classpath wildcard in the start script (Gradle lists jars explicitly, and a settings-driven directory
is the contract operators can see).

### D5. Validation wiring

`ConfigPostProcessor` is static and already receives a runtime service as a nullable parameter (`ApiKeyStore` in
`process` and `processSemantic`). `PluginRegistry` is added the same way; `null` reads as an empty registry.
`FileConfigStore` and `MergedConfigStore` receive the registry in their constructors and pass it on. In
`MergedConfigStore` the registry goes on the full constructor; the two delegating constructors pass an empty
registry, so the existing fixtures do not change. `AiDial.start` builds the registry before the stores.

- `validatePlugin(name, plugin, registry, checkType, warnings)`: when the provider is loaded, its problems become
  `settings.*` warnings (invariant). When it is not loaded and `checkType` is true, one warning with field `type`
  (dangling reference).
- `processPlugins(config, registry, onSkip)` runs in `processSemantic` before `processTranslators`, because the
  follow-ups make translators and interceptors reference plugins. It passes `checkType = onSkip != null`, mirroring
  `processModels`: under `abort` an unknown type is tolerated on a rebuild, under `skip` the entity is dropped and
  recorded.
- `validateSinglePlugin(config, registry, mapKey, onSkip)` for `applyChangeInPlace`, as `validateSingleTranslator`.
- The write path (`ConfigResourceController.java:handlePut`, the block that calls `checkTranslator`) calls
  `validatePlugin` with `checkType = !softValidation` and answers `422` on warnings, as `checkModel` does for a
  model's interceptors. `ConfigValidationService.validateOnly` and `ConfigApplyService.applyPlugin` do the same for
  the batch endpoints. A plugin references no other entity, so nothing is resolved against the scratch config.

Rejected: making an unknown `type` an invariant. Code arrives by rollout and declarations by bucket, so during a
rolling update an older pod sees declarations whose jar it does not have. An invariant would fail that pod's
rebuild on every bucket write; the dangling rule exists for exactly this case.

### D6. What the mixed-release window means

`PLUGIN` is added to `EVENT_TYPES` in this change. The one-release-ahead rule for that set protects existing types
from a consumer that appears before its publisher. Here the type is new and no older pod can write a plugin blob,
so there is no window to protect. An older pod ignores plugin events before parsing them
(`MergedConfigStore.java:isManagedEventUrl` filters on its own segment set) and never lists the `plugins` folder.

### D7. Tests

- `plugin-api`: none beyond compilation; the contract has no logic.
- `PluginTest` in the `config` module, next to `TranslatorTest`: blank `type`, absent `settings`, unknown field.
- `PluginRegistryTest` (unit, server module): a jar written in a `@TempDir` from the test provider's class bytes and a
  `META-INF/services` entry is found by type; a second jar with the same `type` fails `load`; `load` of a missing
  directory still yields the classpath provider. These are the tests of the loading requirement, which sits at the
  process boundary and has no HTTP scenario.
- `TestEchoPluginProvider` in `server/src/test`, registered in `server/src/test/resources/META-INF/services`, of
  type `test-echo`, rejecting settings without a string `greeting` with problem path `greeting`. Every integration
  scenario uses it; the `additionalSettingsOverrides` hook of `ResourceBaseTest` sets `config.onInvalidEntity` and
  `config.write.softValidation` per test class, as the existing config-store tests do.
- Each HTTP scenario has one home, next to the translator case it mirrors: entity shape, dangling type, settings on
  write and routing in a new `ConfigPluginApiTest`; the reload scenarios in `MergedConfigStoreApiTest`; the batch
  scenarios in `AdminValidateApiTest` and `AdminApplyApiTest`; migration in `ConfigFileMigrateApiTest`; the partial
  update in `MergedConfigStorePartialUpdateTest` and the replica event in `MergedConfigStoreReplicaUpdateTest`.
  File plugins come from a `plugins` block added to `server/src/test/resources/aidial.config.json`.

### D8. Effect per request

None. No request Core serves or sends changes, no body is parsed or copied, no Redis or blob call is added on the
request path. A full config rebuild lists one more blob folder. A Configuration API write of a plugin costs the same
blob put and resource event as a translator write. No cost effect is claimed, so there is no measurement task.

## Risks / Trade-offs

- [A bad jar stops Core from starting] → intended for first-party code shipped by rollout. The message names the jar
  and the error, and the directory defaults to empty, so an image without plugins is unaffected.
- [Two jars bring conflicting versions of a shared library] → not handled: one flat loader, parent first. Plugins
  depend on `plugin-api` and what Core already ships. Revisit with per-jar loaders only when a conflict appears.
- [Settings end up holding a secret anyway] → the entity is documented as non-secret and `GET` returns settings as
  stored. The upgrade path is an encrypted string map on the entity, as `Key.key` and `Upstream.key`.
- [The merged config JSON of `POST /v1/ops/config/reload` grows by the plugin declarations] → it already carries
  every other type; nothing new is exposed.

## Migration Plan

Deploy with no plugins directory and no `plugins` declarations: behaviour is unchanged. Rollback is safe: an older
release ignores a `plugins` key in the files (`Config` ignores unknown properties, see Context) and ignores the
blobs under `plugins/platform/` (D6). Plugin blobs appear in the admin export like every other platform blob.
