# Tasks

## 1. The `plugin-api` module and the provider contract

- [x] 1.1 Add the `plugin-api` module (`settings.gradle`, `plugin-api/build.gradle` with `jackson-databind` only) with
      `PluginProvider` (`type()`, `validate(ObjectNode)`) and the `Problem` record, Javadoc stating that an empty list
      means valid; verify `./gradlew :plugin-api:build` passes and `server` compiles against
      `implementation project(':plugin-api')`
- [x] 1.2 Add the `plugin-api` row to the module table in `CLAUDE.md`; verify the table lists every module in
      `settings.gradle`
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest`

## 2. Loading providers at start

- [x] 2.1 Write `PluginRegistryTest` first (design D7): a jar built in a `@TempDir` registering `JarEchoPluginProvider` (type `jar-echo`) is
      found by type (plugin-registry "Plugin providers loaded at start", scenario "Jar in the directory"); a second jar
      declaring the same type fails `load` (scenario "Duplicate provider type"); `load` of a missing directory yields
      the classpath provider and nothing else (scenario "Missing directory"); an unreadable jar fails `load` naming the
      jar. Then implement `PluginRegistry.load(Path)`
      as design D4; verify the test class passes
- [x] 2.2 Add `TestEchoPluginProvider` (type `test-echo`, requires a string `settings.greeting`, problem path
      `greeting`) under `server/src/test` and register it in `server/src/test/resources/META-INF/services`; verify 2.1's
      missing-directory test finds it
- [x] 2.3 Add the static setting `plugins.dir` with code default `plugins` to `AiDial.start`, the built-in
      `aidial.settings.json` and the test-resources copy; build the registry before the config stores and fail start on
      a load error with a message naming the jar or the duplicate type; verify `PluginRegistryTest` asserts the message,
      and the README static settings table documents `plugins.dir`
- [x] 2.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
      "com.epam.aidial.core.server.config.PluginRegistryTest"`

## 3. The entity in the config model and the merged store

- [x] 3.1 Write `PluginTest` in the `config` module (blank `type` rejected, absent `settings` reads `{}`, unknown field
      fails binding), then add `Plugin` (design D2); verify `./gradlew :config:test --tests
      "com.epam.aidial.core.config.PluginTest"` passes
- [x] 3.2 Add `Config.plugins`, `ResourceTypes.PLUGIN` (constant, `of`, `EVENT_TYPES`), the
      `PlatformEntityLocationStrategy` case and the `EntityBucketBinding.ALLOWED_BUCKETS` entry; verify the storage,
      config and server modules compile and the existing `ResourceTypes` and `EntityBucketBinding` tests pass
- [x] 3.3 Add `ConfigPostProcessor.validatePlugin`, `processPlugins` (run right before `processSemantic` by
      `process` and by `MergedConfigStore.rebuildMergedConfig`, with the registry parameter on `process`; design Apply
      notes),
      `validateSinglePlugin`, and `plugins` in `processStructural`; extend `ConfigPostProcessorTest` with: unknown type
      kept when `onSkip` is null and dropped when it is set, rejected settings thrown or dropped by mode, slash-keyed
      plugin dropped; verify the test class passes
- [x] 3.4 Add the `PLUGIN` wiring to `MergedConfigStore` (design D1: `MANAGED_TYPES`, the map copies in
      `rebuildMergedConfig` and `shallowClone`, and the cases in `deserializeReplicaEntity`, `addBlobEntity`,
      `removeAddedEntity`, `isShortNameKeyed`, `cloneTypeMap`, `peekEntity`, `putEntityInPlace`,
      `removeEntityInPlace`, `applyChangeInPlace`), and the registry parameter on the full constructor of
      `MergedConfigStore` and on `FileConfigStore`; extend `MergedConfigStorePartialUpdateTest` with a plugin write and
      delete that leave the other plugins in place, and `MergedConfigStoreReplicaUpdateTest` with a plugin event from a
      foreign `senderPodId`; verify both classes pass
- [x] 3.5 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
      "com.epam.aidial.core.server.config.ConfigPostProcessorTest" --tests
      "com.epam.aidial.core.server.config.MergedConfigStorePartialUpdateTest" --tests
      "com.epam.aidial.core.server.config.MergedConfigStoreReplicaUpdateTest"`

## 4. The Configuration API, batch endpoints, inspection and migration

- [x] 4.1 Write the integration tests first, one per scenario, each in the suite that holds the translator case it
      mirrors: `ConfigPluginApiTest` (new, `plugins.dir` set to a directory holding a jar that registers `jar-echo`) for
      plugin-registry "Jar in the directory", "Plugin entity shape" (3 scenarios), "Strict write", "Rebuild under abort
      keeps the entity", "Rejected settings on write", "File plugin inspected", config-store "Slash-keyed plugin dropped"
      and "Plugin with rejected settings", request-routing "Plugin entity", "Plugin metadata" and "File plugin entry";
      its nested `ConfigPluginApiTest.SkipMode` (soft write, `skip` rebuild) for "Soft write under skip", "Rebuild under
      skip drops the entity" and "Rejected settings on a rebuild under skip"; `MergedConfigStoreApiTest` for
      config-store "Blob plugin next to the file plugins" and "Blob plugin shadows the file plugin";
      `AdminValidateApiTest` for plugin-registry "Rejected settings in a batch" and config-store "Plugin manifest";
      `AdminApplyApiTest` for config-store "Plugin reported before the translator"; `ConfigFileMigrateApiTest` for
      plugin-registry "File plugin migrated". Add `plugins` entries (`file-plugin`, `bad/plugin`, `valid-plugin`) to
      the test-resources `aidial.config.json`
- [x] 4.2 Add `plugins` to the three `RouteTemplate` patterns and the `PLUGIN` cases to `ConfigResourceController`
      (`handleGet`, `descriptorFor`, `prepareWrite`, `entityClassFor`, the `422` block in `handlePut` per design D5)
      with the OpenAPI-annotated method blocks for `/v1/plugins/{bucket}/{path}`; verify the `ConfigPluginApiTest`
      and `MergedConfigStoreApiTest` tests of 4.1 pass
- [x] 4.3 Add the `/v1/metadata/plugins/{bucket}/{path}` annotated block to `ConfigResourceMetadataController` and the
      `PLUGIN` case plus annotated blocks to `FileConfigController`; verify "Plugin metadata", "File plugin entry" and
      "File plugin inspected" pass
- [x] 4.4 Add kind `Plugin`: `AdminPluginManifest`, the `permits` entry in `AdminTypedManifest`, the `@ApiSubType`,
      `KIND_URL_SEGMENT`, `DEPENDENCY_ORDER` (rank 2, later kinds moved down), `parseManifest`, `newScratch`,
      `mutateScratch`, `ConfigValidationService.validateOnly`, `ConfigApplyService.applyPlugin` and its switch case;
      verify the `AdminValidateApiTest` and `AdminApplyApiTest` tests of 4.1 pass
- [x] 4.5 Add `plugins` to `ALL_TYPES` and a `ManagedTypeSpec` to `MANAGED_TYPE_SPECS` in
      `ConfigFileMigrateController`; verify "File plugin migrated" passes
- [x] 4.6 Regenerate `docs/open_api_core.yaml` with `./gradlew :openapi-generator:replaceSpec` and verify
      `./gradlew :openapi-generator:checkOpenApiDiff` passes
- [x] 4.7 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
      "com.epam.aidial.core.server.ConfigPluginApiTest*" --tests "com.epam.aidial.core.server.MergedConfigStoreApiTest"
      --tests "com.epam.aidial.core.server.AdminValidateApiTest*" --tests
      "com.epam.aidial.core.server.AdminApplyApiTest*" --tests "com.epam.aidial.core.server.ConfigFileMigrateApiTest"`

## 5. Operator documentation

- [x] 5.1 Write `docs/dynamic-settings/plugins.md` (shape, `type` and `settings`, the two validation severities and
      the modes, how provider jars are deployed through `plugins.dir`, no secrets in settings, the Configuration API
      URLs and the manifest kind) and add the `plugins` row to the README dynamic settings table; verify every URL and
      field named in the doc appears in a test of 4.1
- [x] 5.2 `./gradlew checkstyleMain checkstyleTest` and `npx -y @fission-ai/openspec@1.14.3 validate
      plugin-config-entity --strict`

## Workflow follow-up

- Write `review.md`, archive the change, then `validate --specs --strict` and one commit staging explicit paths.
- Changes `embedded-origin` and `embedded-filter` build on the archived `plugin-registry` spec and extend
  `plugin-api`.
