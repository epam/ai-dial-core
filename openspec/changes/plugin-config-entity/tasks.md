# Tasks

## 1. The `plugin-api` module and the provider contract

- [ ] 1.1 Add the `plugin-api` module (`settings.gradle`, `plugin-api/build.gradle` with `jackson-databind` only) with
      `PluginProvider` (`type()`, `validate(ObjectNode)`) and the `Problem` record, Javadoc stating that an empty list
      means valid; verify `./gradlew :plugin-api:build` passes and `server` compiles against
      `implementation project(':plugin-api')`
- [ ] 1.2 Add the `plugin-api` row to the module table in `CLAUDE.md`; verify the table lists every module in
      `settings.gradle`
- [ ] 1.3 `./gradlew checkstyleMain checkstyleTest`

## 2. Loading providers at start

- [ ] 2.1 Write `PluginRegistryTest` first: a jar built in a `@TempDir` from `TestEchoPluginProvider` is found by type;
      a second jar declaring the same type fails `load`; a missing directory gives an empty registry; `of(List)` indexes
      by type. Then implement `PluginRegistry.load(Path)` and `of` as design D4; verify the test passes
- [ ] 2.2 Add `TestEchoPluginProvider` (type `test-echo`, requires a string `settings.greeting`, problem path
      `greeting`) under `server/src/test` and register it in `server/src/test/resources/META-INF/services`; verify
      `PluginRegistry.load` of an empty directory finds it through the parent loader (assert in `PluginRegistryTest`)
- [ ] 2.3 Add the static setting `plugins.dir` with code default `plugins` to `AiDial.start`, the built-in
      `aidial.settings.json` and the test-resources copy; build the registry before the config stores and fail start on
      a load error; verify a unit test of the start path (or `PluginRegistryTest`) covers the failure message naming the
      jar, and the README static settings table documents `plugins.dir`
- [ ] 2.4 Create `/app/plugins` owned by `appuser` in the `Dockerfile`; verify `docker build` succeeds
      (deferred: needs the GitHub Packages credentials the image build uses)
- [ ] 2.5 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
      "com.epam.aidial.core.server.config.PluginRegistryTest"`

## 3. The entity in the config model and the merged store

- [ ] 3.1 Add `Plugin` to the `config` module (design D2) with a unit test for a blank `type`, an absent `settings`
      and an unknown field; verify `./gradlew :config:test` passes
- [ ] 3.2 Add `Config.plugins`, `ResourceTypes.PLUGIN` (`plugins`, `of`, `EVENT_TYPES`) and the
      `PlatformEntityLocationStrategy` case; verify the storage and config modules compile and their tests pass
- [ ] 3.3 Add `ConfigPostProcessor.validatePlugin`, `processPlugins` (in `processSemantic`, with the registry
      parameter threaded through `process` and `processSemantic`), `validateSinglePlugin`, and `plugins` in
      `processStructural`; extend `ConfigPostProcessorTest` with: unknown type tolerated when `onSkip` is null and
      dropped when it is set, rejected settings dropped or thrown by mode, slash-keyed plugin dropped; verify the test
      class passes
- [ ] 3.4 Add the `PLUGIN` cases to `MergedConfigStore` (`MANAGED_TYPES`, `deserializeReplicaEntity`, `addBlobEntity`,
      `removeAddedEntity`, `isShortNameKeyed`, `cloneTypeMap`, `peekEntity`, `putEntityInPlace`,
      `removeEntityInPlace`, `applyChangeInPlace`) and the registry constructor parameter to `MergedConfigStore` and
      `FileConfigStore`; extend `MergedConfigStorePartialUpdateTest` with a plugin write, delete and a replica event;
      verify the class passes
- [ ] 3.5 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
      "com.epam.aidial.core.server.config.ConfigPostProcessorTest" --tests
      "com.epam.aidial.core.server.config.MergedConfigStorePartialUpdateTest"`

## 4. The Configuration API, batch endpoints, inspection and migration

- [ ] 4.1 Write `ConfigPluginApiTest` first, one test per scenario: plugin-registry "Plugin entity shape" (3),
      "Unknown plugin type is a dangling reference" (4, with `additionalSettingsOverrides` per mode), "Settings
      validated by the provider" (3), "File plugins" (2); config-store "Slash-keyed plugin dropped", "Blob plugin next
      to the file plugins", "Blob plugin shadows the file plugin", "Plugin manifest", "Plugin reported before the
      translator", "Plugin with rejected settings"; request-routing "Plugin entity", "Plugin metadata", "File plugin
      entry". Add `plugins` entries (`file-plugin`, `bad/plugin`, `valid-plugin`) to the test-resources
      `aidial.config.json`
- [ ] 4.2 Add `plugins` to the three `RouteTemplate` patterns and the `PLUGIN` cases to `ConfigResourceController`
      (`handleGet`, `descriptorFor`, `prepareWrite`, `entityClassFor`, the `422` block in `handlePut` per design D5)
      with the OpenAPI-annotated method blocks for `/v1/plugins/{bucket}/{path}`; verify the entity-shape, dangling,
      settings and routing tests of 4.1 pass
- [ ] 4.3 Add the `/v1/metadata/plugins/{bucket}/{path}` annotated block to `ConfigResourceMetadataController` and the
      `PLUGIN` case plus annotated blocks to `FileConfigController`; verify "Plugin metadata" and "File plugin
      entry" pass
- [ ] 4.4 Add kind `Plugin`: `AdminPluginManifest`, the `@ApiSubType`, `KIND_URL_SEGMENT`, `DEPENDENCY_ORDER` (rank 2,
      later kinds moved down), `parseManifest`, `mutateScratch`, `ConfigValidationService.validateOnly`,
      `ConfigApplyService.applyPlugin`; extend `AdminValidateApiTest` and `AdminApplyApiTest` with one plugin case
      each (rejected settings `FAILED`; a valid plugin `APPLIED` and readable); verify "Plugin manifest", "Plugin
      reported before the translator" and "Rejected settings in a batch" pass
- [ ] 4.5 Add the `plugins` `ManagedTypeSpec` to `ConfigFileMigrateController`; extend `ConfigFileMigrateApiTest`
      with the plugin migration; verify "File plugin migrated" passes
- [ ] 4.6 Confirm `POST /v1/admin/export` includes `plugins/platform/*` blobs without a type list to extend; if a list
      exists, add `plugins` and a test; verify by a test or by reading `AdminExportController` and recording the result
      in design.md
- [ ] 4.7 Regenerate `docs/open_api_core.yaml` with `./gradlew :openapi-generator:replaceSpec` and verify
      `./gradlew :openapi-generator:checkOpenApiDiff` passes (deferred: needs `npx @redocly/cli` if lint is on)
- [ ] 4.8 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
      "com.epam.aidial.core.server.ConfigPluginApiTest" --tests "com.epam.aidial.core.server.AdminValidateApiTest"
      --tests "com.epam.aidial.core.server.AdminApplyApiTest" --tests
      "com.epam.aidial.core.server.ConfigFileMigrateApiTest" --tests
      "com.epam.aidial.core.server.MergedConfigStoreApiTest"`

## 5. Operator documentation and rollback check

- [ ] 5.1 Write `docs/dynamic-settings/plugins.md` (shape, `type` and `settings`, the two validation severities and
      the modes, how provider jars are deployed through `plugins.dir`, no secrets in settings, the Configuration API
      URLs and manifest kind) and add the `plugins` row to the README dynamic settings table; verify every URL and
      field named in the doc appears in a test of 4.1
- [ ] 5.2 Check `FileConfigStore.loadConfig` against an older release's tolerance of an unknown `plugins` key and
      record the result in design.md Migration Plan; verify by reading the Jackson configuration used there
- [ ] 5.3 `./gradlew checkstyleMain checkstyleTest` and `npx -y @fission-ai/openspec@1.14.3 validate
      plugin-config-entity --strict`

## Workflow follow-up

- Write `review.md`, archive the change, then `validate --specs --strict` and one commit staging explicit paths.
- Changes `embedded-origin` and `embedded-filter` build on the archived `plugin-registry` spec and extend
  `plugin-api`.
