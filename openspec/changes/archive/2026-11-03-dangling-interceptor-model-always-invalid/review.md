# Review: dangling-interceptor-model-always-invalid

## What shipped

- `OnInvalidEntity { ABORT, SKIP }` (new) replaces the `MergedConfigStore.MODE_ABORT` / `MODE_SKIP` strings in the store's
  field, constructors and `getOnInvalidEntity`, in `AiDial` (`OnInvalidEntity.fromSetting`, same rule as before: `skip`
  case-insensitive, anything else `abort`) and in the tests.
- `ConfigPostProcessor` takes `mode` and a never-null `recorderOfInvalid` in place of the nullable `onSkip`, in
  `processSemantic` and every `process*` and `validateSingle*` method. A hard violation throws under `ABORT` and is
  dropped and recorded under `SKIP`; a model with a dangling interceptor reference is dropped and recorded in both.
  `processModels` and `validateSingleModel` share `checkModel` (rebuild invariants first, then references), so a model
  with an invariant failure still fails an `abort` rebuild. `cascadeInterceptorDelete` always runs.
- The file load, `ConfigPostProcessor.process`, passes `ABORT`, `NO_RECORDER_OF_INVALID` and `checkReferences = false`
  (it cannot see blob interceptors).
- `MergedConfigStore`: `rebuild` builds one `recorderOfInvalid`; the partial-update path builds an equivalent with
  `recorderOfInvalidFor(nextInvalid)`, which `applyChangeInPlace`, `applyEntityWriteLocked`, `applyEntityDeleteLocked` and
  `applyBatch` use. A policy assignment passes `OnInvalidEntity.ABORT` explicitly. The Javadoc of `isSoftValidation` says
  acceptance does not read it. The write gates (`checkModel`, `applyModel`, `validateOnly`) are unchanged.
- Unit tests: four in `ConfigPostProcessorTest` (`testSemanticAbortRecordsModelWithDanglingInterceptor`,
  `...StillThrowsOnInvariantWithDanglingInterceptor`, `testProcessDoesNotCheckReferencesOfTheFileLoad`,
  `testSemanticSkipRecordsInvariantAndReferenceTogether`); three in `MergedConfigStorePartialUpdateTest` (write, delete
  cascade, batch); one each in `MergedConfigStoreReplicaUpdateTest` and `MergedConfigStoreTest` (replica event, file
  model). About 45 existing `processSemantic` calls and the `MODE_*` uses in five test classes moved to the new API.
- HTTP tests: five in `ModelCrossRefValidationApiTest` (rebuild, other violations still abort with `500`, interceptor
  delete, interceptor created later after a reload, file model, on the fixture
  `dial-config/dangling-interceptor-file-model.json`); in `ModelCrossRefValidationSoftModeApiTest` the `degraded`
  assertion, `testRebuildUnderSkipIsInvalid`, `testMalformedBlobSkippedUnderSkip` and a nested class for the soft write
  under `abort`; a nested class each in `AdminApplyApiTest` and `AdminValidateApiTest`.
- A check by putting the old behaviour back (no recording under `abort`) failed six of the seven new HTTP tests that
  were run; the seventh, `testOtherViolationsStillAbort`, pins behaviour this change leaves alone. That was done before
  the refactor of the second step; the same tests pass after it.
- Docs: no file under `docs/` or `README.md` describes `softValidation` or `onInvalidEntity`, and no response shape
  changes, so `docs/open_api_core.yaml`, `docs/dynamic-settings/*.md`, `README.md` and `CLAUDE.md` are untouched.
- Issues opened from this change: #2226 (reject an interceptor `DELETE` that a model references, the follow-up that
  builds on this change) and #2228 (`resurrectInvalidModels`, below).

## Drift from design.md

- Decision 7 (recovery) was wrong as first written. Recovery through an interceptor write does not work for most
  records, and the revived model is stored under its canonical id, so it answers `valid` on GET and is not served
  (observed in the merged model map). Recovery is by full rebuild. design.md decision 7 and the scenario "Interceptor
  created later" (it now includes the reload) were corrected; the problems are #2228. The unit test I had written for
  resurrection was removed, since it asserted the canonical-id key.
- Decisions 1 and 5 were rewritten after the first implementation, at review: the first form, a second nullable
  `crossRefSink` next to `onSkip`, became the mode plus one `recorderOfInvalid`, with an explicit `checkReferences` flag
  for the file load. A no-op recorder alone would not do there, because the model is dropped before it is recorded.
- The test plan moved with it: `testSemanticWithoutCrossRefSinkDoesNotCheckReferences` became
  `testProcessDoesNotCheckReferencesOfTheFileLoad`, and `testInterceptorCreatedLater` deletes, recreates and reloads.

## Not done

- The full suite does not pass on this machine. `:server:test` finished with 3133 tests, 255 failed, all Windows
  file-system problems (`FileSystemException` on test paths, `Illegal char <:>`, `No content to map`). On a pristine
  copy of `development` (`git archive HEAD`), four of the failing classes (`PublicationApiTest`, `ResourceApiTest`,
  `ShareApiTest`, `ApplicationDeploymentApiTest`) failed 48 tests against 47 in this tree with the same errors; the
  failing sets differ in both directions, so they are flaky. `ModelWriteApiTest.testPutGetWithUrlEncodedName` fails on
  the baseline too. The other failing classes (`SkillResourceApiTest`, `ProjectResourceApiTest`, the org-unit tests and
  so on) were not run on the baseline; they show the same error signatures. The classes closest to this change pass
  when run alone (`OverridePathsApiTest`, `OverridePathsWriteApiTest`, `ConfigEntityWriteApiTest`, `ResponsesApiTest`,
  `config.*`, the `ModelCrossRef*` classes, apply and validate). CI on Linux decides the rest.
- `openspec validate --all --strict` fails on two specs this change does not touch, `user-limits` and
  `resource-subscription` (placeholder Purpose). This change validates on its own with `--strict`.
- The comment on #2189 that this change applies whatever `softValidation` is (the issue says "when `softValidation` is
  `true`") is not posted yet; the PR description is to say it too.
- Nothing is committed.
- No measurement: the change claims no cost effect (design.md, Effect per request).

## Known limits

- **BREAKING for operators, default settings included**: a model with a dangling interceptor reference, whether
  file-defined, written to blob storage outside the API, or left by an interceptor `DELETE`, stops being a deployment
  and is listed as `degraded` in `GET /v1/admin/health/config`. Before, under `abort` it was listed and answered `404`
  on use.
- An interceptor `DELETE` takes the models that use it out of service in every mode, with `softValidation: false` too,
  until the guard of #2226 refuses it.
- A recorded model recovers on the next full rebuild (the periodic reload, `config.reload`, default 60 s, or
  `POST /v1/ops/config/reload`), not when the interceptor is created (#2228).
- A file-defined model that is recorded invalid shows only in the health endpoint, not through GET, which is
  blob-only.
- A model is checked against the interceptors present when `processModels` runs; `processInterceptors` can drop one
  afterwards (duplicate deployment id, bad override paths). `skip` had the same ordering.
- Applications and toolsets still do not check their interceptor references on write.
- `softValidation` and `onInvalidEntity` are per-pod settings; for a dangling reference the pods now agree whatever they
  are set to.

## Review

Not yet reviewed. Issue #2189.
