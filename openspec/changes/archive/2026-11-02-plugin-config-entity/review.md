# Review: plugin-config-entity

## What shipped

- New module `plugin-api` with one interface, `PluginProvider`: `type()` and `validate(ObjectNode settings)`, which
  returns a list of `Problem(path, message)`. Its only dependency is jackson-databind.
- `Plugin` config POJO (`type`, `settings`) and `Config.plugins`, keyed by short name; a blank `type` or an unknown
  field fails deserialization.
- `ResourceTypes.PLUGIN` (`plugins`), stored in the `platform` bucket, with the same wiring as translators:
  - the merged store: managed types, build, partial and replica updates, clone, shadowing, skip records;
  - the Configuration API: `GET`/`PUT`/`DELETE /v1/plugins/platform/{name}`, and metadata;
  - the batch endpoints: manifest kind `Plugin`, dependency order 2, before `Translator`;
  - file inspection and migration, routes, bucket binding, OpenAPI.
- `PluginRegistry`, loaded once in `AiDial.start`:
  - Sources: Core's classpath, plus the jars in the static setting `plugins.dir` (default `plugins`) through one
    `URLClassLoader`.
  - The start fails on a jar that cannot be opened, the same provider class registered by two jars, a provider
    that cannot be created, a blank type, or two providers with the same type.
- `ConfigPostProcessor.validatePlugin`, used by every write surface:
  - An unknown type is a dangling reference.
  - Rejected settings, and a provider that throws while validating, are an invariant violation.
  - In soft mode, a batch apply of an unknown type answers `APPLIED_INVALID`.
- Operator doc `docs/dynamic-settings/plugins.md`, with README rows for `plugins.dir` and `plugins`.

## Drift from design.md

design.md "Apply notes" records each change:

- **D5:** where `processPlugins` runs, and how the registry reaches the batch endpoints.
- **D4:** the `JarFile` pre-check and the blank-type check.
- **D1:** no secret walk for plugins.
- **D7:** the `jar-echo` fixture, which the scenarios "Minimal declaration" and "Jar in the directory" now name; the
  test jars carry the registration only.
- **Review round:** three behaviours added to the spec delta (see "Fix round 1").

The Risks entry on a throwing provider was rewritten because the behaviour changed.

## Checks run

- `./gradlew checkstyleMain checkstyleTest`: clean, after the fix round.
- `:openapi-generator:replaceSpec` regenerated `docs/open_api_core.yaml`, and `checkOpenApiDiff` passes (lint valid).
- `npx -y @fission-ai/openspec@1.14.3 validate plugin-config-entity --strict`: valid.
- After the archive, `validate --specs --strict` failed once on `config-store` "Write rejected with 422", whose text
  had grown past 500 characters. The parenthesis repeating the plugin-registry rules was cut, in the spec and in the
  archived delta; all specs are valid.
- After the fix round, passing (Windows, local):

  | Class | Tests |
  |---|---|
  | `PluginRegistryTest` | 7 |
  | `ConfigPostProcessorTest` | 57 |
  | `FileConfigStoreTest` | 8 |
  | `MergedConfigStoreTest` | 13 |
  | `MergedConfigStorePolicyTest` | 11 |
  | `MergedConfigStorePartialUpdateTest` | 14 |
  | `MergedConfigStoreReplicaUpdateTest` | 21 |
  | `ConfigPluginApiTest` | 10 |
  | `ConfigPluginApiTest$SkipMode` | 3 |
  | `MergedConfigStoreApiTest` | 20 |
  | `AdminApplyApiTest` | 39 |
  | `AdminApplyApiTest$SoftValidation` | 3 |

- `PluginTest` (7, in `config`) passed before the fix round, which did not touch it.
- `AdminValidateApiTest*` (35) and `ConfigFileMigrateApiTest` (21) passed before the fix round. The fix round changed `validatePlugin`, which both reach. Their rerun after the fix round was stopped by the local runner for low memory, so CI is their check.
- `AdminApplyApiTest$SoftValidation` failed once with `Failed to start Redis service`. A test run in another checkout
  held the fixed embedded Redis port 16370. It passed on rerun.
- Four `ResourceServiceTest` cases in `storage` fail on Windows only (a colon in a temp path, a locked file). This
  change does not touch them.
- The full suite was not run locally; CI runs it.

## Review findings

One independent reviewer, three lenses, on the uncommitted diff.

| Lens | Severity | File:line | Finding |
|---|---|---|---|
| correctness | medium | `PluginRegistry.java:52` | `ServiceLoader` loads a provider class registered by two jars once, from the first jar. An old jar left beside its new version wins silently, and the duplicate-type check never sees it. |
| correctness | medium | `ConfigPostProcessor.java:199` | A provider exception, or a `null` result, escapes `validatePlugin`. Under `skip` it fails the first rebuild at start, which has no previous config, so every new pod stops. A write answers `500`. |
| conformance | low | `PluginRegistryTest.java:40`, `JarEchoPluginProvider.writeJar` | Unrecorded drift: the duplicate scenario names `test-echo`, but the test uses `jar-echo`. D7 says the test jar carries the class bytes; it carries only the registration. |
| conformance | low | `ConfigApplyService.java:264` | In soft mode, a batch apply of an unknown type answers `APPLIED`, then `skip` drops the entity. A model with a dangling interceptor answers `APPLIED_INVALID`. |
| docs | low | `plugins.md:41` | `/v1/admin/apply` answers `200` with the entry `FAILED`, not `422`. "The entry's GET" holds for bucket entries only. |
| correctness | low | `PluginProvider.java:23` | The contract does not say that `validate` is called concurrently, including on an event loop. |
| tests | low | `PluginRegistry.java:54`, `:64`; `ConfigPostProcessor.java:201` | No test covers the blank-type check, the `ServiceConfigurationError` wrap, or an empty problem path. |
| simplicity | low | `FileConfigStore.java:34`, `ConfigPostProcessor` | "No providers" has two encodings: `null` (test call sites only) and `EMPTY`. The registry also reaches its consumers through two channels. |
| simplicity | low | `ConfigPostProcessor.java:142` | `processPlugins` repeats the body of `validateSinglePlugin`. |
| simplicity | low | `ConfigPluginApiTest`, `PluginRegistryTest` | `testJarInTheDirectory` and `testPluginEntityRoute` are subsets of `testMinimalDeclaration`; `testEmptyRegistryKnowsNoType` asserts a constant. |

## Fix round 1

Fixed:

- **Same provider class in two jars.** `listJars` reads each jar's `META-INF/services` file and refuses a class
  registered by two jars, naming both. Spec scenario "Same provider class in two jars";
  `PluginRegistryTest.testSameProviderInTwoJarsFailsTheLoad`.
- **Provider exception.** `validatePlugin` catches a `RuntimeException` from `validate` (a `null` result ends up
  there too, through `List.copyOf`), logs it, and adds a `settings` warning. It then behaves as rejected settings.
  Spec scenario "Provider failure on a rebuild under skip"; `ConfigPostProcessorTest.testProviderFailureRejectsTheSettings`.
- **Soft batch apply.** An unknown type answers `APPLIED_INVALID` in soft mode and `FAILED` otherwise. Spec scenario
  "Unknown type in a soft batch"; `AdminApplyApiTest$SoftValidation.testApplyAdmitsPluginOfUnknownTypeAsInvalid`.
- **Drift and docs.** The duplicate scenario now names `jar-echo`, and Apply notes record the test jars. The
  `plugins.md` status codes and the GET wording are corrected, and the doc names the two-jar case.
- **Contract.** The `PluginProvider.validate` Javadoc states concurrent calls, the event loop, and that a throw
  counts as a rejection.
- **Tests.** Added `testBlankTypeFailsTheLoad`, `testMissingProviderClassFailsTheLoad` and
  `ConfigPostProcessorTest.testProblemWithoutPathIsReportedOnSettings`. The registry constructor is now
  package-private, so the unit test can build a registry with its own provider.
- **One encoding of "no providers".** The registry is never `null`; test call sites pass `PluginRegistry.EMPTY`.
- **Duplicated loop.** `processPlugins` loops over a copy of the keys and calls `validateSinglePlugin`.
- **Redundant tests.** Removed the three; the scenarios they named are listed in `testMinimalDeclaration`'s Javadoc.

Rejected:

- **One channel for the registry.** `ConfigValidationService` has no `MergedConfigStore` to read it from, so the
  constructor argument stays.

Still open: none.

## Spec-to-test map

| Scenario | Test |
|---|---|
| Slash-keyed plugin dropped | `ConfigPluginApiTest.testSlashKeyedPluginDropped`, `ConfigPostProcessorTest.testStructuralDropsSlashKeyedPlugin` |
| Blob plugin next to the file plugins | `MergedConfigStoreApiTest.testBlobPluginNextToTheFilePlugins` |
| Blob plugin shadows the file plugin | `MergedConfigStoreApiTest.testBlobPluginShadowsTheFilePlugin` |
| Plugin manifest | `AdminValidateApiTest.testValidateAcceptsPluginManifest` |
| Plugin reported before the translator | `AdminApplyApiTest.testApplyReportsPluginBeforeTranslator` |
| Plugin with rejected settings | `ConfigPluginApiTest.testRejectedSettingsOnWrite` |
| Minimal declaration | `ConfigPluginApiTest.testMinimalDeclaration`, `PluginTest` |
| Missing type | `ConfigPluginApiTest.testMissingType`, `PluginTest` |
| Unknown field | `ConfigPluginApiTest.testUnknownField`, `PluginTest` |
| Jar in the directory | `ConfigPluginApiTest.testMinimalDeclaration`, `PluginRegistryTest.testJarInTheDirectoryIsLoaded` |
| Missing directory | `PluginRegistryTest.testMissingDirectoryLoadsOnlyTheClasspathProviders` |
| Duplicate provider type | `PluginRegistryTest.testDuplicateTypeFailsTheLoad` |
| Same provider class in two jars | `PluginRegistryTest.testSameProviderInTwoJarsFailsTheLoad` |
| Strict write | `ConfigPluginApiTest.testStrictWrite` |
| Soft write under skip | `ConfigPluginApiTest$SkipMode.testSoftWriteUnderSkip` |
| Unknown type in a soft batch | `AdminApplyApiTest$SoftValidation.testApplyAdmitsPluginOfUnknownTypeAsInvalid` |
| Rebuild under abort keeps the entity | `ConfigPluginApiTest.testRebuildUnderAbortKeepsTheEntity`, `ConfigPostProcessorTest.testPluginOfAnUnknownTypeIsKeptInAbortMode` |
| Rebuild under skip drops the entity | `ConfigPluginApiTest$SkipMode.testRebuildUnderSkipDropsTheEntity` |
| Rejected settings on write | `ConfigPluginApiTest.testRejectedSettingsOnWrite` |
| Rejected settings in a batch | `AdminValidateApiTest.testValidateRejectsPluginWithRejectedSettings` |
| Provider failure on a rebuild under skip | `ConfigPostProcessorTest.testProviderFailureRejectsTheSettings` |
| Rejected settings on a rebuild under skip | `ConfigPluginApiTest$SkipMode.testRejectedSettingsOnRebuildUnderSkip` |
| File plugin migrated | `ConfigFileMigrateApiTest.testMigratePlugins` |
| File plugin inspected | `ConfigPluginApiTest.testFilePluginInspected` |
| Plugin entity | `ConfigPluginApiTest.testMinimalDeclaration`, `testDelete` |
| Plugin metadata | `ConfigPluginApiTest.testPluginMetadata` |
| File plugin entry | `ConfigPluginApiTest.testFilePluginInspected` |

The baseline scenarios carried in the MODIFIED requirements keep their existing tests.

## Open

- Nothing references a plugin yet. The follow-up changes are `embedded-origin` (an ORIGIN plugin answering a
  deployment in process, which also skips the internal pass) and `embedded-filter`.
- One flat class loader for all jars. Per-jar loaders come only when two plugins need conflicting library versions.
- Settings carry no secrets in v1. The upgrade path is an encrypted string map on the entity.
- CI must still confirm the full suite.
