# Tasks

## 1. Post-processor: a recording sink for dangling references

- [x] 1.1 Add the unit tests to `ConfigPostProcessorTest` first, and verify they fail before 1.2 (names as after the
  refactor of 2.5): `testSemanticAbortRecordsModelWithDanglingInterceptor` (`ABORT` and a recorder: the model is
  removed and recorded with warning `interceptors[0]`, the other models stay, nothing is thrown),
  `testSemanticAbortStillThrowsOnInvariantWithDanglingInterceptor` (a model with a dangling reference and a cache rate
  without `unit: token`: throws, the recorder is not called), `testProcessDoesNotCheckReferencesOfTheFileLoad` (the
  file-load entry point: the model stays), `testSemanticSkipRecordsInvariantAndReferenceTogether` (`SKIP`: one record
  holds both warnings, as today).
- [x] 1.2 In `ConfigPostProcessor`, make a dangling reference go to a recorder in every mode (design.md, decisions 1
  to 3): `processSemantic`, `processModels`, `validateSingleModel` and `cascadeInterceptorDelete`. Update the javadoc
  of `validateSingleModel` and `cascadeInterceptorDelete`, and the comment in `processModels`, that say
  cross-references are skip-mode only. Done first with a second `crossRefSink` next to `onSkip`; 2.5 replaced both with
  the mode and one `recorderOfInvalid`. Verify the 1.1 tests pass and every other test in `ConfigPostProcessorTest`
  still passes.
- [x] 1.3 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.config.ConfigPostProcessorTest"`; both are green.

## 2. Merged config store: record in every mode

- [x] 2.1 Add the unit tests first, all on `ABORT` with `softValidation: false`, the default. In
  `MergedConfigStorePartialUpdateTest`: `modelWriteWithDanglingInterceptorIsRecordedInAbort`,
  `interceptorDeleteCascadesToModelInvalidEntitiesInAbort` (scenario "Interceptor delete under abort"; mirrors
  `interceptorDeleteCascadesToModelInvalidEntities`) and `applyBatchRecordsModelWithDanglingInterceptorInAbort` (the
  store half of scenario "Apply under abort with soft writes"). In `MergedConfigStoreReplicaUpdateTest`:
  `replicaModelEventWithDanglingInterceptorIsRecordedInAbort`. In `MergedConfigStoreTest`:
  `rebuildRecordsFileModelWithDanglingInterceptorInAbort` (scenario "File model under abort": the file model is gone
  from `get().getModels()` and recorded). Verify they fail first.
- [x] 2.2 In `MergedConfigStore`, apply design.md decision 5: extract the `rebuild` lambda into a local recorder,
  pass it to `processSemantic`, give the partial-update path an equivalent for API-sourced writes, and make
  `applyChangeInPlace` use it (`applyEntityWriteLocked`, `applyEntityDeleteLocked` and `applyBatch`). Done first with
  `onSkip`, `crossRefSink`, `skipRouter` and `crossRefRouter`; 2.5 collapsed them into one `recorderOfInvalid`.
  Update the javadoc of `isSoftValidation`, which says the rebuild's `skip` path records the entity. Verify the 2.1
  tests pass and the other tests in the three classes still pass; fix any test that relied on an `abort` store keeping
  a model with a dangling reference.
- [x] 2.3 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.config.MergedConfigStorePartialUpdateTest" --tests
  "com.epam.aidial.core.server.config.MergedConfigStoreReplicaUpdateTest" --tests
  "com.epam.aidial.core.server.config.MergedConfigStoreTest"`; all green.
- [x] 2.4 Readability refactor, step one (design.md, decision 1): add the enum `OnInvalidEntity { ABORT, SKIP }` and
  use it instead of the `MergedConfigStore.MODE_ABORT` / `MODE_SKIP` strings in `MergedConfigStore` (field, constructors,
  `getOnInvalidEntity`), `AiDial` (same parsing rule as today) and every test. Verify `./gradlew compileTestJava`
  and the store test classes of 2.3 plus `InvalidEntityApiTest` pass unchanged in behaviour.
- [x] 2.5 Readability refactor, step two (design.md, decisions 1, 2, 3 and 5): in `ConfigPostProcessor` replace every
  `onSkip` / `crossRefSink` with the pair `mode` and `recorderOfInvalid` (never `null`), with `process` for the file
  load passing `ABORT`, `NO_RECORDER_OF_INVALID` and no reference check; in `MergedConfigStore` build one
  `recorderOfInvalid` for `rebuild` and one for the partial-update path, and pass `OnInvalidEntity.ABORT` explicitly
  for a policy assignment. Move the ~45 calls in `ConfigPostProcessorTest` and the other callers to the new signature.
  Verify no `onSkip` or `crossRefSink` is left (`grep`), and `ConfigPostProcessorTest` and the store test classes pass.
- [x] 2.6 Run `./gradlew checkstyleMain checkstyleTest` and the test classes of 2.4 and 2.5; all green.

## 3. HTTP behaviour

- [x] 3.1 In `ModelCrossRefValidationApiTest` (default settings: `softValidation: false`, `abort`), add
  `testRebuildUnderAbortRecordsDanglingModel` (scenario "Rebuild under abort": a blob written straight to storage,
  `POST /v1/ops/config/reload` answers `200`, GET `invalid` with `interceptors[0]`, health lists
  `models/platform/<name>` as skipped), `testOtherViolationsStillAbort` (scenario "Other violations still abort": the
  reload answers `500`, as the "Admin reload endpoint" requirement says for a failed rebuild, and the previous model is
  still served; no existing test pins that `500`, so this one does), `testInterceptorDeleteUnderAbort` (scenario
  "Interceptor delete under abort": `204`, the model's GET answers `invalid`) and `testInterceptorCreatedLater`
  (scenario "Interceptor created later": the interceptor is created again after the delete, the config reloads, and
  the model is valid and listed; recovery through the interceptor write alone is #2228, not relied on). Add
  `testFileModelWithDanglingInterceptorUnderAbort` (scenario "File model
  under abort") on a small fixture under `dial-config/` loaded with `@DialConfigLocation`, asserting that the model is
  missing from `GET /openai/models`, another model is served, and the health endpoint lists it. "Strict write" is
  this class as it stands. Verify all fail before 2.2 and pass after.
- [x] 3.2 In `ModelCrossRefValidationSoftModeApiTest` (`softValidation: true`, `skip`), extend
  `testSoftModeShowsStatusInvalidPostRebuild` with the health assertion (`degraded`) that scenario "Soft write under
  skip" now states, and add `testRebuildUnderSkipIsInvalid` (scenario "Rebuild under skip": reload `200`, GET
  `invalid`, health lists the model as skipped) and `testMalformedBlobSkippedUnderSkip` (the `skip` half of scenario
  "Malformed blob skipped under abort", whose `WHEN` is "whatever `config.onInvalidEntity`"; the `abort` half is
  `InvalidEntityApiTest#testMalformedBlobSurfacesAsInvalidUnderGet` on the default). Add a nested class with
  `softValidation: true` and `onInvalidEntity` left at its default `abort` (the pattern of the nested `SoftValidation`
  classes in `AdminApplyApiTest`) with `testSoftWriteUnderAbortIsInvalid` (scenario "Soft write under abort": `200`
  with an `ETag`, GET `invalid`, health `degraded`). Verify they pass.
- [x] 3.3 In `AdminApplyApiTest`, add a nested class with `softValidation: true` and `onInvalidEntity` left at its
  default `abort`, with `testApplyAdmitsInvalidUnderAbortWithSoftWrites` (scenario "Apply under abort with soft
  writes": `APPLIED_INVALID`, then GET `invalid`, mirroring `testApplyAdmitsInvalidUnderSoftValidation`). In
  `AdminValidateApiTest`, a nested class with the same settings and `testValidateSoftFailureIsValidUnderAbort`
  (scenario "Validate with soft writes": `VALID`, GET `404`). Verify they pass.
- [x] 3.4 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.ModelCrossRefValidationSoftModeApiTest" --tests
  "com.epam.aidial.core.server.ModelCrossRefValidationApiTest" --tests "com.epam.aidial.core.server.AdminApplyApiTest"
  --tests "com.epam.aidial.core.server.AdminValidateApiTest" --tests "com.epam.aidial.core.server.InvalidEntityApiTest"`;
  all green.

## 4. Spec check and integration

- [x] 4.1 The change reaches default deployments, so look for tests that would flip. The JSON fixtures under
  `server/src/test/resources` only define interceptors (checked: no model in them references one), so scan the
  `*ApiTest` classes for a model body with `"interceptors"` naming an interceptor the test does not create, other than
  the ones this change adds. Fix or note each hit; verify the touched tests pass.
- [x] 4.2 Docs: no file under `docs/` or `README.md` describes `softValidation` or `onInvalidEntity` and no response
  shape changes, so `docs/dynamic-settings/*.md`, `docs/open_api_core.yaml` and `CLAUDE.md` need no edit. Verify by
  `grep -rn "softValidation\|onInvalidEntity" docs README.md CLAUDE.md` outside `docs/notes` returning nothing.
- [x] 4.3 Verify `npx -y @fission-ai/openspec@1.14.3 validate dangling-interceptor-model-always-invalid --strict`
  and `npx -y @fission-ai/openspec@1.14.3 validate --all --strict` pass, apart from the two placeholder-Purpose
  warnings in `resource-subscription` and `user-limits`, which this change does not touch.
- [x] 4.4 Run `./gradlew :server:test --tests "com.epam.aidial.core.server.config.*"` and confirm it is green, in
  particular `MergedConfigStorePolicyTest` (its `abort` dangling-assignment test must still abort). The full suite
  runs in CI.

## Workflow follow-up

- Write `review.md` in the change folder, then archive with
  `npx -y @fission-ai/openspec@1.14.3 archive dangling-interceptor-model-always-invalid -y`, and run
  `validate --specs --strict`. Confirm the merged `config-store` spec matches the shipped behaviour first.
- One commit, staging explicit paths; the PR description links #2189, says the change applies whatever
  `softValidation` is (the issue limits it to `true`), and names #2226 (the interceptor `DELETE` guard, a separate
  change that builds on this one) as the follow-up. Add the same note as a comment on #2189.
