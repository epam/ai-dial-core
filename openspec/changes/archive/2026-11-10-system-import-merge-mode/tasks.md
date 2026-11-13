# Tasks

## 1. The `mode` parameter

- [x] 1.1 `ImportMode` (`MERGE`, `REPLACE`, case-insensitive `parse`, default `MERGE`) in `AdminSystemImportService`; `stage(InputStream, ImportMode)`; `StagedImport` carries the mode and the warnings; `AdminSystemImportController.handle` parses `mode` before the stage task is submitted, answers `400` for an unknown or empty value, and declares it with `@ApiParameter`. Tests in `AdminSystemImportApiTest`: `testNoModeMeansMerge`, `testModeIsCaseInsensitive`, `testUnknownModeIsRejectedBeforeTheBodyIsRead`; the existing `testSuccessRoundTripViaExportThenImport` and the service tests that expect a wipe pass `replace`.
- [x] 1.2 `./gradlew checkstyleMain checkstyleTest` and `:server:test --tests "*AdminSystemImportApiTest" --tests "*AdminSystemImportServiceTest"`; `AdminSystemImportApiTest.testSuccessRoundTripViaExportThenImport` may fail only with the known Windows `:` path error

## 2. Merge does not delete

- [x] 2.1 `replaceUnderLocks` skips `wipeBuckets` in `MERGE`, reports `deleted = 0` and does not set the `wipe` phase. Tests in `AdminSystemImportServiceTest`: `testMergeKeepsBlobAbsentFromArchive`, `testMergeKeepsCredentials`, `testMergeOverwritesExistingBlob`, `testMergeWritesNoWipeProgress`; `Export then system import` is covered by the existing round trip with `replace`
- [x] 2.2 `./gradlew checkstyleMain checkstyleTest` and `:server:test --tests "*AdminSystemImportServiceTest"`

## 3. Redis invalidation of the imported blobs only

- [x] 3.1 `ResourceService.invalidateBlobs(Collection<String>)` in batches of 50, skipping a path whose group is not a resource type, and the merge replace phase calls it with the copied paths instead of `invalidate`. Tests in `ResourceServiceInvalidateTest`: `testInvalidateBlobsDropsCachedAndQueuedEntryOfThatBlob`, `testInvalidateBlobsKeepsOtherResourcesOfTheBucket`, `testInvalidateBlobsKeyEqualsRedisKeyForEveryResourceType`, `testInvalidateBlobsHandlesMoreBlobsThanOneBatch`, `testInvalidateBlobsSkipsPathOfUnknownGroup`; in `AdminSystemImportServiceTest`: `testMergeInvalidatesOnlyImportedBlobs`, `testReplaceStillInvalidatesTheBuckets`
- [x] 3.2 `./gradlew checkstyleMain checkstyleTest`, `:storage:test --tests "*ResourceServiceInvalidateTest"` and `:server:test --tests "*AdminSystemImportServiceTest"`

## 4. Keys in merge mode

- [x] 4.1 Stage: in `MERGE` an `encryption_keys/` entry whose bucket already has a key is neither staged nor checked and adds a warning; one whose bucket has none is checked as today. Replace phase: a staged key is skipped with the same warning if the bucket has a key by then, and `invalidateEncryptionKeys` runs only for a bucket whose key was copied. Tests in `AdminSystemImportServiceTest`: `testMergeKeepsTheKeyOfTheBucketThatHasOne`, `testMergeTakesTheKeyOfTheBucketWithoutOne`, `testMergeRejectsAnUnwrappableKeyOfTheBucketWithoutOne`, `testMergeWithoutKeyAsksNoKms`, `testMergeSkipsKeyThatAppearedBeforeTheReplace`
- [x] 4.2 `./gradlew checkstyleMain checkstyleTest` and `:server:test --tests "*AdminSystemImportServiceTest" --tests "*AdminSystemImportValidationServiceTest"`

## 5. Docs and the whole change

- [x] 5.1 `docs/admin-system-import.md`: the `mode` parameter and its default, that a restore or clone needs `mode=replace`, what `merge` keeps and the key rule, the Redis invalidation per mode, and the curl example; the README only if it describes the endpoint. Verify by reading the page against the spec scenarios
- [x] 5.2 `npx -y @fission-ai/openspec@1.14.3 validate system-import-merge-mode --strict`; checkstyle; `:storage:test` and `:server:test` for the classes above
- [ ] 5.3 (deferred: needs an environment with Redis and a blob store holding about 1000 blobs) Import the same archive in `merge` and in `replace` and compare the `invalidate=` figure of the `System import replace finished` log line
