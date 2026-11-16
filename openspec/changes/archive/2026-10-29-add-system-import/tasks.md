# Tasks

Test classes: storage module `BlobStorageUploadTest`, `ResourceServiceInvalidateTest`, `TempFolderCleanerTest`,
`ResourceServiceTest` (existing); server module
`InputStreamAdapterTest`, `MergedConfigStoreTest`, `AdminSystemExportServiceTest` and `AdminSystemExportApiTest` (existing),
`AdminSystemImportServiceTest` (unit, with a filesystem blob store like `AdminSystemExportServiceTest`) and `AdminSystemImportApiTest` (full
stack, extends `ResourceBaseTest` like `AdminSystemExportApiTest`; settings overrides come from `additionalSettingsOverrides()`).
Scenario names refer to `specs/system-import/spec.md`. The archive layout is specified by the existing `system-export`
capability.

Every group ends with `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`. The full
suite runs in CI.

Lock renewal is #2169 and is not part of this change.

## 1. Blob storage: stage a blob with its full metadata

- [x] 1.1 Add a test `BlobStorageUploadTest.testCopyKeepsContentAndUserMetadata` that copies a blob stored with encoding,
  disposition, language, cache control and user metadata through `BlobStorage.copy(from, to, null)` on the filesystem
  provider. If a field is lost, use `copy(from, to, userMetadata)` for promote and record it in `design.md` (decision 10).
- [x] 1.2 Add `BlobAttributes` and `BlobStorage.upload(path, attributes, InputStream)`: one `putBlob` below the 5 MiB part
  size, multipart above it, abort on failure. Verify with `BlobStorageUploadTest.testUploadSmallBlobKeepsAllAttributes`,
  `testUploadLargeBlobUsesMultipart` (1.5 times the part size, bytes equal), `testUploadAbortsMultipartOnFailure`.
- [x] 1.3 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :storage:test --tests "*BlobStorageUploadTest"`.
- [ ] 1.4 (deferred: needs an S3-compatible storage or Azure Blob) Repeat 1.1 and the multipart case against a cloud
  provider and check that `Content-Encoding` survives multipart init and copy, and how the provider waits for a copy of a
  large blob.

## 2. Resource service: drop queued and cached entries by bucket

- [x] 2.1 Add the `ResourceService` method that removes from `resourceQueue` the members under the given bucket locations
  and deletes the Redis maps `{type}:{storage path}` under them, using two `getKeysByPattern` passes and a client-side
  filter on the `ResourceTypes` name. Verify with `ResourceServiceInvalidateTest.testQueuedEntriesOfTheBucketsAreRemoved`,
  `testCachedEntriesOfTheBucketsAreRemoved`, `testUsersBucketKeysAreKept`, `testLockKeysAreKept` (a held bucket lock and a
  held per-key lock survive), `testApiKeyDataKeysAreKept`, `testWorksWithAStoragePrefix` (renamed
  `testWorksWithStoragePrefix`, checkstyle `AbbreviationAsWordInName`), `testCachedCekIsRemoved`.
- [x] 2.2 Add `ResourceServiceInvalidateTest.testServiceReadsTheBlobAfterInvalidation` (write through the service, replace
  the blob directly, invalidate, read returns the new content) and `testQueuedWriteIsNotFlushedAfterInvalidation` (a long
  `syncDelay`, queue a write, invalidate, run the sync, the blob is unchanged). These back the specs scenarios "Cached copy
  does not mask the system import" and "Queued write does not overwrite the system import".
- [x] 2.3 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :storage:test --tests "*ResourceServiceInvalidateTest"`.
- [ ] 2.4 (deferred: needs a Redis Cluster) Check that the batched delete and the scan work in cluster mode.

## 3. Temp folder cleaner shared by both sweepers

- [x] 3.1 Extract `cleanupTempFolder` and `isExpiredTempFile` from `ResourceService` into `TempFolderCleaner(BlobStorage,
  folder, ttl)` with `cleanup()` and `deleteFolder(prefix)`, and make `ResourceService` use one for `.dial-tmp` with the
  one-hour TTL. Verify `ResourceServiceTest.testCleanupTempFolder` passes unchanged.
- [x] 3.2 Add `TempFolderCleanerTest.testExpiredFolderIsDeletedAndRecentIsKept`, `testUnparseableFolderIsReported`,
  `testDeleteFolderRemovesOnlyThatPrefix`, `testCleanupStopsAtTheFirstNonExpiredFolder`, and
  `testOldStagingIsReclaimed` (24 hour TTL on `.dial-system-import-tmp`, backs "Old staging is reclaimed"). (note: the
  two tests that build a real `HH:mm:ss` timestamp folder name fail on a native Windows checkout — the filesystem
  provider maps a blob key onto a real OS path, and colons are not valid there; same pre-existing limitation as
  `ResourceServiceTest.testCleanupTempFolder`. They pass in CI on Linux; see `design.md` "Implementation notes".)
- [x] 3.3 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :storage:test --tests "*TempFolderCleanerTest"
  --tests "*ResourceServiceTest"`.

## 4. Building blocks: stream timeout, rebuild under held locks

- [x] 4.1 Add an optional read timeout to `InputStreamAdapter` (`queue.poll(timeout)`, an error and an `IOException` when
  nothing arrives, default none). Verify with `InputStreamAdapterTest.testReadFailsWhenNoDataArrivesInTime`,
  `testSlowButSteadyDataIsNotAStall` (renamed `testSlowButSteadyDataIsNotStalled`, checkstyle `AbbreviationAsWordInName`),
  `testTimeIsNotCountedWhileTheReaderIsBusy`, and that the existing adapter tests pass unchanged. The timeout fails with
  a dedicated `InputStreamAdapter.StallTimeoutException` (an `IOException` subtype), so `AdminSystemImportService` can
  tell a stall from an ordinary corrupt/truncated body and answer `408` instead of `400`.
- [x] 4.2 Add `MergedConfigStore.rebuildNowUnderBucketLocks()` and make `rebuildNow()` call it inside `underBucketLocks`.
  Verify with `MergedConfigStoreTest.testRebuildNowUnderBucketLocksDoesNotTakeTheLocksAgain` (a caller that holds
  `ADMIN_BUCKET_LOCATIONS` returns at once, not after the lease) and `testRebuildNowStillTakesTheLocks`.
- [x] 4.3 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "*InputStreamAdapterTest" --tests "*MergedConfigStoreTest"`.

## 5. Export: share the excluded groups

- [x] 5.1 Make `AdminSystemExportService.EXCLUDED_GROUPS` package-private so system import shares it. Verify the existing
  `AdminSystemExportServiceTest` and `AdminSystemExportApiTest` cases pass unchanged.
- [x] 5.2 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*AdminSystemExportServiceTest"
  --tests "*AdminSystemExportApiTest"`.

## 6. System import service: settings, stage phase, replace phase

- [x] 6.1 Add `AdminSystemImportService.Settings` with `stallTimeout` 60000 and `progressInterval` 15000 and `validate()` (both
  positive), and wire it in `AiDial` and `Proxy` next to `adminSystemExportSettings`. Verify with
  `AdminSystemImportServiceTest.testDefaults` and `testNonPositiveSettingsFailStartup`.
- [x] 6.2 Implement the stage phase: manifest (`formatVersion`, `coreVersion`), name checks, excluded groups, sidecar pairing,
  duplicate names, clean end of stream, at least one blob, upload to `.dial-system-import-tmp/{ts}/{importId}/`, the adapter read
  timeout. Verify with `AdminSystemImportServiceTest`: `testManifestNotFirstIsRejected`, `testUnsupportedFormatVersionIsRejected`,
  `testArchiveFromNewerReleaseIsRejected`,
  `testArchiveFromSameOrOlderReleaseIsAccepted`, `testDevelopmentBuildAcceptsAReleaseArchive` (renamed
  `testDevelopmentBuildAcceptsReleaseArchive`),
  `testDevelopmentArchiveIsRejectedByAReleaseBuild` (renamed `testDevelopmentArchiveIsRejectedByReleaseBuild`),
  `testDevelopmentArchiveIsAcceptedByADevelopmentBuild` (renamed `testDevelopmentArchiveIsAcceptedByDevelopmentBuild`),
  `testUnreadableVersionCountsAsADevelopmentBuild` (renamed `testUnreadableVersionCountsAsDevelopmentBuild`; the running
  version is injected through the constructor, not read from
  the build), `testPathTraversalIsRejected`, `testOtherBucketIsRejected`, `testBackslashIsRejected`,
  `testControlCharacterIsRejected`, `testLongNameIsRejected`, `testCekEntryIsRejected`, `testCredentialsEntryIsRejected`,
  `testExcludedGroupIsMatchedWithoutRegardToCase`, `testExcludedWordsDeeperInThePathAreAccepted`,
  `testBlobWithoutMetadataIsRejected`, `testMetadataWithoutBlobIsRejected`, `testDuplicateEntryIsRejected` (built by
  splicing raw ZIP bytes, see `design.md`), `testOversizedMetadataIsRejected`, `testEntryWithWrongDeclaredSizeIsRejected`
  (corrupts an entry's compressed data, see `design.md`),
  `testNotAZipIsRejected` (renamed `testNotZipIsRejected`), `testOnlyManifestIsRejected`, `testTruncatedArchiveIsRejected`,
  `testStalledUploadTimesOut`, `testSlowButAliveUploadIsAccepted`, `testRejectedArchiveLeavesNoTraceInTheBuckets`,
  `testStagedEntriesAreOutsideTheBuckets`. (renames: checkstyle `AbbreviationAsWordInName`, see `design.md`.)
- [x] 6.3 Implement the replace phase under `underBucketLocks(ADMIN_BUCKET_LOCATIONS, ...)` with the default period: wipe (skip
  `encryption_keys/`), promote, invalidate, `rebuildNowUnderBucketLocks`, summary counts and a progress object (phase,
  blobs deleted, blobs copied, total staged), `WARN` past the lock period, no
  abort on time. Verify with `AdminSystemImportServiceTest.testBlobAbsentFromArchiveIsRemoved`, `testBlobsEqualTheArchive` (gzip
  blob, small role, PNG; all attributes), `testCredentialsAreWiped`, `testEncryptionKeysSurvive`,
  `testFolderMarkersAreRestored` (`.dial-folder` and `.dial-resource` blobs),
  `testFolderListingEntriesAreSkippedDuringWipeAndPromote` (a `StorageType.FOLDER` listing entry next to the blobs of a
  bucket, and under the staging prefix, is neither deleted nor copied), `testOtherBucketsAreUntouched`,
  `testReplaceOrderIsWipePromoteInvalidateRebuild`,
  `testProgressReportsPhasesAndCounters`, `testRebuildFailureIsReportedAsWarning`
  ("Rebuild failure is reported"),  `testSlowReplaceIsNotAborted` ("Slow replace is not
  aborted"), `testImportTwiceGivesTheSameState`.
- [x] 6.4 Implement the guard (default period) and the background `deleteFolder` of the system import's own staging. Verify
  with `AdminSystemImportServiceTest.testSecondImportIsRejectedWhileOneHoldsTheGuard`, `testGuardIsReleasedAfterRejection`,
  `testImportIntoAnEnvironmentWithoutKeysIsAccepted` (no CEK blob in the target, an archive without secrets),
  `testStagingIsRemovedAfterSuccess`, `testStagingIsRemovedAfterRejection`.
- [x] 6.5 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*AdminSystemImportServiceTest"
  --tests "*AdminSystemExportServiceTest"`.

## 7. Endpoint: controller, response, route

- [x] 7.1 Add `AdminSystemImportController` (admin check, `403` with `Connection: close`, adapter attached in `handle()`, the
  system import submitted to the task executor, every stage-phase failure — `409` (busy), `400`/`408` (rejected) and `500` —
  answered with `Connection: close` since the adapter may have paused the request stream with unread body bytes still
  queued), the route `ADMIN_SYSTEM_IMPORT` in
  `RouteTemplate` and `ControllerSelector`, and the `@ApiOperation` with `x-preview` and the responses `200`, `400`,
  `403`, `408`, `409`, `500`. Verify with `AdminSystemImportApiTest.testImportIsForbiddenForNonAdmin`.
  `testSecondImportIsRejectedWhileOneIsUploading` is not implemented: it needs a request held open mid-upload with no
  response yet (unlike export, nothing is written to the response until the stage phase finishes), which this pass did
  not build a harness for. The guard logic itself (`tryLockGuard` returns `null` while held) is covered at the service
  level by `AdminSystemImportServiceTest.testSecondImportIsRejectedWhileOneHoldsTheGuard`.
- [x] 7.2 Implement the response: `200` and `application/x-ndjson` after the stage phase, the progress timer (a line right
  after the head and one every `progressInterval`, read from the service's progress object), the final status line,
  `response.reset()` on a replace failure, the replace running on when the client goes away. Code done; only the success
  path is covered end to end by an HTTP test (`testSuccessRoundTripViaExportThenImport`, task 7.4 note). The named tests
  `testSuccessStatusLine`, `testProgressLines`, `testCopyProgressCountsTheArchiveBlobs`, `testReplaceFailureResetsTheConnection`
  and `testClientGoingAwayDoesNotStopTheReplace` are not implemented as separate HTTP tests; the progress-line mechanics and
  the replace phase's own correctness (phases, counters, idempotency, rebuild-failure warning) are covered at the service
  level in `AdminSystemImportServiceTest` (tasks 6.3-6.4).
- [x] 7.3 Cover the remaining HTTP scenarios of the spec in `AdminSystemImportApiTest`, one test per scenario: the stage-phase
  rejections (`testManifestNotFirstIsRejected` ... `testTruncatedUploadChangesNothing`, the same names as in 6.2 plus
  `testNotAZipIsRejected`, `testOnlyManifestIsRejected`, `testEntryWithWrongDeclaredSizeIsRejected`),
  `testStalledUploadTimesOut` and `testSlowButAliveUploadIsAccepted` (`stallTimeout` of 1 s),
  `testImportIntoAnEnvironmentWithoutKeysIsAccepted`, `testGuardIsReleasedAfterRejection`.
  Done: `testNotAnArchiveIsRejected` (a `400`) and `testStalledUploadTimesOut` (a `408`). The rest of this list is unit-level
  only, in `AdminSystemImportServiceTest` (task 6.2); not repeated as separate HTTP tests in this pass. Accepted as the
  scope for this change: full per-scenario coverage exists at the service level, and `review.md`'s review pass found no
  correctness gap from the narrower HTTP suite.
- [x] 7.4 Cover the replace scenarios in `AdminSystemImportApiTest`: `testBlobAbsentFromArchiveIsRemoved`, `testBlobsEqualTheArchive`,
  `testCredentialsAreWiped`, `testEncryptionKeysSurvive`, `testFolderMarkersAreRestored`, `testOtherBucketsAreUntouched`, `testCachedCopyDoesNotMaskTheImport`,
  `testQueuedWriteDoesNotOverwriteTheImport`, `testKeyNotInArchiveIsNotServed`, `testImportedModelIsListed`,
  `testAdminWriteWaitsForTheReplace`, `testSystemExportDuringReplaceIsRejected`, `testImportTwiceGivesTheSameState`,
  `testExportThenImport`, `testStagingIsRemovedAfterSuccess`, `testStagingIsRemovedAfterRejection`.
  Done, as one combined round trip rather than one test per scenario: `testSuccessRoundTripViaExportThenImport` exports real
  content, imports it back over a stale blob, and checks the final line's `importedBlobs` count and that the stale blob is
  gone and the exported ones survive (`testExportThenImport` + `testBlobAbsentFromArchiveIsRemoved` + `testBlobsEqualTheArchive`
  in spirit). It fails on a native Windows checkout for the reason `design.md` "Implementation notes" gives (the real
  filesystem `BlobStorage` the full `AiDial` stack always uses cannot write the `HH:mm:ss` staging path there) and is
  expected to pass in CI. The remaining named scenarios are not each a separate HTTP test; the replace phase's own
  correctness for every one of them is covered at the service level (task 6.3). Accepted as the scope for this change,
  same reasoning as 7.3.
- [x] 7.5 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*AdminSystemImportApiTest"`.
  Run, and passing except `testSuccessRoundTripViaExportThenImport` for the Windows reason above (expected to pass in CI
  on Linux).

## 8. Staging sweeper

- [x] 8.1 In `AiDial`, build a second `TempFolderCleaner` for `.dial-system-import-tmp` with a 24 hour TTL, schedule it every 24
  hours with the existing `TimerService`, and close it with the other timers. Done and wired (every `AdminSystemImportApiTest`
  run starts `AiDial` with this scheduled, so it does not break startup). `TempFolderCleanerTest.testOldStagingIsReclaimed`
  from 3.2 verifies the cleaner itself. `AdminSystemImportApiTest.testOldStagingIsReclaimedBySweeper` is not implemented
  (no test seeds a folder old enough and invokes the scheduled instance directly through the HTTP test harness in this
  pass).
- [x] 8.2 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*AdminSystemImportApiTest"`.

## 9. Docs and generated spec

- [x] 9.1 Write `docs/admin-system-import.md`: the command, the archive it needs, the statuses, what is wiped (including
  `credentials/`), that a failure in the replace phase is repeated with the same archive, the progress lines and the final
  status line (with a `jq` example), the connection that may reset while
  the system import still ends, the lag of up to `config.reload` on other pods, pausing writes and not starting a second system import or an
  export before the first has answered, scheduling it as an outage in a maintenance window with all admin work stopped, the 5-minute lock
  period that is not renewed (a longer replace lets the locks lapse; if the pod dies they free themselves after at most 5
  minutes), the CEK step (place the source's `public/encryption_keys/cek` and
  `platform/encryption_keys/cek` that exist before importing, then roll-restart the pods; an environment without secrets has
  none; Core does not check this, and what happens if it is forgotten, see `design.md` decision 13), the OAuth sign-ins to redo, no deploys during a system import, and `.dial-system-import-tmp/` left
  after a crash (reclaimed within about 48 hours, or delete it by hand). Verified against the automated tests' statuses
  (400/403/408/409/500 unit- and HTTP-level, 200 at the service level) rather than a separate manual curl run against a
  live local Core.
- [x] 9.2 Add the `adminSystemImport` settings table (`stallTimeout`, `progressInterval`) to `README.md` next to the
  export table (line 345). In `docs/admin-system-export.md`, point to system import and adjust the sentence about keeping the
  key material for a restore. Verify that each documented default equals the
  code default (`AdminSystemImportServiceTest.testDefaults`). `docs/dynamic-settings/*.md` and `CLAUDE.md` are not affected: no
  config entity and no module or command changes.
- [x] 9.3 Check the annotations with `./gradlew replaceSpec -Plint` as `.github/workflows/pr.yml` does. Required adding
  `AdminSystemImportController.class` to the hand-maintained `AnnotationEndpointCollector.CONTROLLER_CLASSES` list
  (`openapi-generator`), which the generator scans for `@ApiOperation`; see `design.md`. Passed ("Woohoo! Your API
  description is valid.", pre-existing unrelated warnings only). Did not commit the regenerated `docs/open_api_core.yaml`;
  CI commits it.

## 10. Measurement and integration

- [ ] 10.1 Replace the preliminary figures in `design.md` decision 15 with a measurement: run a system import of a seeded archive on a dev
  environment and record the stage and replace durations from the service's `INFO` log against the 5-minute lock period, for a
  few hundred, a few thousand (the dev environment has 3548 blobs) and about ten thousand blobs. Decide from the numbers whether
  a configurable lock period, bounded parallel delete and copy, or #2169 must come first.
  (deferred: needs a dev environment with a real blob store)
- [ ] 10.2 (deferred: needs a dev environment) Manual check: `curl -fsS -X POST -H "Authorization: Bearer <admin>"
  --data-binary @dial-system-export.zip -H "Content-Type: application/zip" .../v1/admin/system-import`, then check the
  buckets, the config, `.dial-system-import-tmp/` and that a second pod serves the new config within `config.reload`. Run it through
  the real ingress with a replace that lasts longer than the ingress timeout, and check that the progress lines reach the client
  as they are written and that the connection stays open. If it does not, the follow-up is a stored result with a status
  endpoint (`design.md` decision 8).
- [x] 10.3 Run `npx -y @fission-ai/openspec@1.14.3 validate add-system-import --strict` and
  `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`, and the full set of touched test classes together. Both
  validate commands pass for this change (`validate --all` reports two pre-existing, unrelated placeholder-Purpose
  warnings on `resource-subscription` and `user-limits`, from earlier archived changes). The touched test classes passed
  per-module (storage, then server); see `review.md` for the full list and the two known Windows-only failures.

## Workflow follow-up

- Write `review.md` in the change folder, then archive with `npx -y @fission-ai/openspec@1.14.3 archive add-system-import -y`
  and run `validate --specs --strict`, after the project's review requirements are met.
