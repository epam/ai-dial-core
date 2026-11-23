# Tasks

Branch from `development`. Run the checks in CLAUDE.md "Checks": checkstyle and the touched test classes locally; the
full suite runs in CI. Group 1 is the rebuild rule for models, group 2 the request parameter, group 3 plugins, group 4
the recovery. Group 1 still uses the server setting in the tests that need a soft write; group 2 converts them.

## 1. A model with a dangling reference is always recorded (config-store: Invalid entity policy, Dangling interceptor reference)

- [x] 1.1 Add the enum `OnInvalidEntity { ABORT, SKIP }` with `fromSetting(String)` (`skip`, case-insensitive, else
  `ABORT`). Replace the `MODE_ABORT` and `MODE_SKIP` strings and the `String onInvalidEntity` field and constructor
  arguments of `MergedConfigStore` with it, convert the setting in `AiDial`, and fix the test constructor calls. Verify
  with `./gradlew :server:compileTestJava` and `MergedConfigStoreTest` still passing.
- [x] 1.2 `ConfigPostProcessor`: replace the nullable `onSkip` of `process*`, `validateSingle*` and
  `cascadeInterceptorDelete` with the mode and a `recorderOfInvalid` that is never `null`; add the no-op recorder for
  the file load. `process` runs `ABORT` with `checkReferences = false`. A shared model helper runs
  `validateModelInvariants` first (throws under `ABORT`, collected under `SKIP`) and then the references; a dangling
  reference drops the model and passes it to the recorder in both modes. `cascadeInterceptorDelete` always runs. The
  plugin methods are converted mechanically and keep today's rule (type checked only under `SKIP`) until group 3.
  Verify with unit tests in `ConfigPostProcessorTest`: `testDanglingReferenceRecordedUnderAbort`,
  `testInvariantStillThrowsUnderAbort`, `testFileLoadDoesNotCheckReferences`,
  `testCascadeRecordsModelsWithDanglingReference`.
- [x] 1.3 `MergedConfigStore`: build one recorder in `rebuild` and one for the partial-update paths (`skipRouter`
  becomes `recorderOfInvalidFor`), pass the mode to `ConfigPostProcessor`, and pass `OnInvalidEntity.ABORT` explicitly
  for a policy assignment whose policy is missing. Verify with `MergedConfigStoreTest`
  (`testRebuildRecordsBlobModelWithDanglingReferenceUnderAbort`, `testRebuildRecordsFileModelWithDanglingReferenceUnderAbort`),
  `MergedConfigStorePartialUpdateTest` (`modelWriteWithDanglingReferenceIsRecordedUnderAbort`,
  `interceptorDeleteCascadesUnderAbort`), `MergedConfigStoreReplicaUpdateTest`
  (`replicaEventWithDanglingReferenceIsRecordedUnderAbort`) and the existing `MergedConfigStorePolicyTest` unchanged.
- [x] 1.4 HTTP tests of the rebuild side in `InvalidEntityApiTest`: `testRebuildUnderAbort` ("Rebuild under abort"),
  `testFileModelUnderAbort` ("File model under abort", with the new resource
  `dial-config/dangling-interceptor-file-model.json`), `testOtherViolationsStillAbort` ("Other violations still
  abort"), `testInterceptorDeleteUnderAbort` ("Interceptor delete under abort"). "Rebuild under skip" is covered by
  the existing `ModelCrossRefValidationSoftModeApiTest.testSoftModeShowsStatusInvalidPostRebuild`, and "Malformed blob
  skipped under abort" and "Invalid router recorded under skip" by their existing tests; all pass. No existing test
  asserted the old live model, so none is rewritten. Docs: none,
  since `git grep onInvalidEntity -- README.md docs` finds nothing outside the plugin page; the release note covers the
  **BREAKING** change.
- [x] 1.5 `./gradlew checkstyleMain checkstyleTest` and the test classes of 1.1 to 1.4 with `--tests`.

## 2. The request parameter (config-store: Soft validation)

- [x] 2.1 Add one static parser for the `softValidation` query value next to the manifest helpers
  (`ConfigManifestSupport`): `null` is `false`, `true` and `false` in any case are themselves, anything else (an empty
  value too) is a `400`. Verify with `ConfigManifestSupportTest` (`testParseSoftValidation`,
  `testParseSoftValidationRejectsAnythingElse`).
- [x] 2.2 Remove the setting: the `softValidation` argument of the three `MergedConfigStore` constructors,
  `isSoftValidation()`, the field and the wiring in `AiDial`, the `config.write` block of the built-in
  `aidial.settings.json`, and the test constructor calls that pass it. Log a startup warning from `AiDial` when
  `config.write.softValidation` is present in the settings. Verify with `./gradlew :server:compileTestJava` and the
  test `ModelCrossRefValidationApiTest.LegacySetting#testRemovedSettingHasNoEffect` (scenario "Removed setting has no
  effect": settings with the old key, a `PUT` without the parameter answers `422`).
- [x] 2.3 `ConfigResourceController`: read and parse the parameter in the `PUT` handler before anything is written,
  pass it to `checkModel` and `checkPlugin` (the plugin gate is reworked in group 3, so for now it keeps its call with
  `!softValidation`), and drop the field. Verify with these tests: in `ModelCrossRefValidationApiTest`
  `testStrictWrite` (scenario "Strict write", with and without `?softValidation=false`) and
  `testNonBooleanValueAnswers400` ("Value that is not a boolean"); in `ModelCrossRefValidationSoftModeApiTest`
  `testSoftModeCommitsUnknownRef` and `testSoftModeShowsStatusInvalidPostRebuild` ("Soft write under skip"),
  `AbortMode#testSoftWriteUnderAbort` ("Soft write under abort"), and `testSoftModeStillRejectsUnservedInterface`
  and `testSoftModeStillRejectsBadCachePricing` ("Soft mode still rejects invariants"); in `AdminValidateApiTest`
  `testSoftValidationStillRejectsTypedRouterProperties` ("Typed router properties rejected on write").
- [x] 2.4 Batch surfaces: `ConfigApplyService.applyEntries` and `ConfigValidationService.validateOnly` (both overloads)
  take the value; `AdminApplyController` (precheck phase and write phase use the same value) and
  `AdminValidateController` read it. `ConfigFileMigrateController` passes `false` and reads no parameter. Drop the
  constructor arguments and the `AiDial` wiring. Verify with `AdminApplyApiTest`: `testApplyAdmitsInvalidUnderSoftValidation`
  ("Apply under abort with soft writes"), `testOneGoodOneDanglingWithParameter` ("One good, one dangling, with the
  parameter") and `testValidateAdmitsWhatApplyRecords` ("Validate admits what apply records"); with
  `AdminValidateApiTest`: `testV17SoftValidationModePerEntityValid` ("Validate with soft writes", validate answers
  `VALID`); and with the existing `ConfigFileMigrateApiTest` and the existing validate and apply status tests passing
  unchanged.
- [x] 2.5 Convert the tests that switched soft mode on through settings: the `SoftValidation` nested classes of
  `AdminApplyApiTest` and `AdminValidateApiTest`, `ModelCrossRefValidationSoftModeApiTest`, `ConfigPluginApiTest`
  (the soft write under `skip`), `OverridePathsWriteApiTest`. They send `?softValidation=true` and keep their
  assertions; the nested classes that existed only to set the setting are folded into their outer class
  (`AdminApplyApiTest`, `AdminValidateApiTest`) or become parameterized tests (`OverridePathsWriteApiTest`); the
  `skip`-mode classes stay and drop the setting. Verify that every scenario of 1.4, 2.3 and 2.4 has a passing test
  after the conversion.
- [x] 2.6 Docs: add `softValidation` as a query parameter to the `PUT` operations of models and plugins, `apply` and
  `validate` in the controller annotations and regenerate `docs/open_api_core.yaml`; describe the parameter and the
  removed setting under `interceptors` in `docs/dynamic-settings/models.md` (no page describes the `422` write rules of
  the Configuration API as a whole); fix `.claude/skills/validate-config-on-every-write/SKILL.md` line 22, which says
  a rule that reads another entity "follows `softValidation`". Verify that the OpenAPI generation step of
  `./gradlew build -x test` succeeds and that the generated document lists the parameter on each of those operations.
- [x] 2.7 `./gradlew checkstyleMain checkstyleTest` and the test classes of 2.1 to 2.5 with `--tests`.

## 3. Plugin validation and the rebuild rule (plugin-registry: Unknown plugin type is recorded as invalid)

- [x] 3.1 In `ConfigPostProcessor`, split `validatePlugin` into `validatePluginSettings` (hard, appends, no-op without a
  provider) and `validatePluginType` (soft, returns `true` when a provider is loaded) and remove `checkType`. Rewrite
  `checkPlugin`, `applyPlugin` and the `Plugin` case of `validateOnly` as the model gates are written: hard warnings,
  then the soft boolean, reject on `hard || (soft && !softValidation)`, `log.warn` on the soft case, `APPLIED_INVALID`
  from the boolean (no second registry lookup), and `VALID` from `validateOnly` for a soft failure. Verify with unit
  tests in `ConfigPostProcessorTest` (`testPluginSettingsAreHardAndSilentWithoutProvider`,
  `testPluginTypeReportsOnlyWithoutProvider`) and these tests in `ConfigPluginApiTest`: `testStrictWrite` ("Strict
  write"), `testSoftWrite` and `SkipMode#testSoftWriteUnderSkip` ("Soft write", both modes),
  `testUnknownTypeValidatedSoftly` ("Unknown type validated softly"), `testUnknownTypeInStrictBatch` ("Unknown type in
  a strict batch"); and in `AdminApplyApiTest` `testApplyAdmitsPluginOfUnknownTypeAsInvalid` ("Unknown type in a soft
  batch").
- [x] 3.2 Rebuild side: `processPlugins` and `validateSinglePlugin` use the mode and the recorder of 1.2; the type
  warning is recorded in both modes, a settings warning throws under `ABORT`. The file-only load
  (`ConfigPostProcessor.process`) keeps the type unchecked. Verify with unit tests in `ConfigPostProcessorTest`
  (`testPluginTypeRecordedUnderAbort`, `testFileLoadKeepsPluginOfAnUnknownType` and the existing
  `testPluginWithRejectedSettingsFailsInAbortMode`) and these tests in `ConfigPluginApiTest`:
  `testRebuildUnderAbortRecordsTheEntity` and `SkipMode#testRebuildUnderSkipDropsTheEntity` ("Rebuild of a blob
  plugin", both modes), `testFilePluginUnderAbort` and `SkipMode#testFilePluginUnderSkip` ("File plugin", both modes,
  with the new resource `dial-config/plugin-unknown-type.json`), `testDeploymentNamingInvalidPlugin` ("Deployment naming
  an invalid plugin"). The tests that expected the entity to stay `valid` under `abort` are rewritten.
- [x] 3.3 Docs: rewrite the two bullets of `docs/dynamic-settings/plugins.md` (lines 43-44) that describe the unknown
  type and `softValidation`. Verify by reading the page against the scenarios of "Unknown plugin type is recorded as
  invalid".
- [x] 3.4 `./gradlew checkstyleMain checkstyleTest` and `ConfigPostProcessorTest` and `ConfigPluginApiTest` with
  `--tests`.

## 4. Recovery when the interceptor is created (config-store: Dangling interceptor reference)

- [x] 4.1 In `MergedConfigStore.applyChangeInPlace`, replace the call to `resurrectInvalidModels` in the `INTERCEPTOR`
  case with `requestRebuild()` when the invalid entities hold a `MODEL` record, and delete `resurrectInvalidModels`.
  Verify with `MergedConfigStorePartialUpdateTest` (`interceptorWriteRequestsRebuildWhenModelRecordsExist`,
  `interceptorWriteDoesNotRebuildWithoutModelRecords`; the two tests that asserted resurrection, one of them the
  canonical-id key, are removed, with the reflective helper they used) and `MergedConfigStoreReplicaUpdateTest`
  (`replicaInterceptorEventRequestsRebuild`).
- [x] 4.2 HTTP tests in `InvalidEntityApiTest`, each waiting up to 10 s for the model to return and sending no reload
  request: `testInterceptorCreatedLater` ("Interceptor created later": the model is a deployment, GET `valid`,
  listed by `GET /openai/models` under its short name, health `ok`), `testInterceptorDeletedAndCreatedAgain`
  ("Interceptor deleted and created again"), and `testSoftWriteSurvivesRebuild` ("Soft write survives a rebuild", with
  `POST /v1/ops/config/reload`). The `skip` mode is covered in `ModelCrossRefValidationSoftModeApiTest` by
  `testInterceptorCreatedLaterUnderSkip` and `testSoftWriteSurvivesRebuildUnderSkip`. Verify that all of them pass.
- [x] 4.3 `./gradlew checkstyleMain checkstyleTest` and the classes of 4.1 and 4.2 with `--tests`.

## 5. Whole-change checks

- [x] 5.1 `npx -y @fission-ai/openspec@1.14.3 validate --all --strict` passes, and
  `git grep -n "config.write.softValidation" -- server docs` finds only the startup warning and the removal note in
  `models.md` (`openspec/specs` still names it until the archive merges the deltas; check it again then).
- [x] 5.2 Run every test class touched in groups 1 to 4 together with `--tests`, with checkstyle, and record the
  result in `review.md`.

## Workflow follow-up

- The issue for this change is #2350, linked in proposal.md Impact.
- PR text: fixes #2350, #2189 and #2228, mentions #2226. Close #2226 by hand when this one merges, with a pointer to the
  PR, as not needed.
- Write `review.md`, then archive the change; the archive merges the deltas into `openspec/specs`.
- Release note: the **BREAKING** removal of `config.write.softValidation`, and that a model or plugin with a dangling
  reference is out of service on every deployment.
