# Review: parallel-system-import

## What shipped

- `AdminSystemImportService.Settings.parallelism`, default 4. `validate()` rejects a value below 1, so Core does not
  start. `AiDial` decodes the settings before it builds the service.
- The service takes an `Executor` and the parallelism. `AiDial` passes an executor that submits to `AsyncTaskExecutor`,
  so each blob call runs on a virtual thread with the request's Vert.x context.
- `BlobCalls`, a bounded runner: a semaphore of `parallelism`, the first failure kept, and `submit` and `awaitAll` throw
  it only after every call in flight has ended.
- Stage: reading and validation stay in archive order. An entry of at most 5 MiB is read into memory and uploaded by a
  worker; a larger one streams inline through a close-ignoring wrapper, because `SequenceInputStream` closes each
  stream it finishes. `stage` waits for all uploads before it returns, and on a failure before it deletes the staging.
- Wipe, copy and staging deletion run through the runner, and each phase waits for its calls before the next starts.
  Staging deletion still logs a failed delete and carries on, as `TempFolderCleaner.deleteFolder` did.
- `Progress` counters are atomic, incremented by the workers.
- `TempFolderCleaner.deleteFolder` and its test are removed: the import was its only caller.
- Docs: a `README.md` settings row, and a "Speed" note in `docs/admin-system-import.md` about `http.maxConnections`.

## Checks

- `./gradlew checkstyleMain checkstyleTest`: passes.
- `:server:test --tests "*AdminSystemImportServiceTest"`: passes, including the three new scenario tests and the
  extended settings tests.
- `:storage:test --tests "*TempFolderCleanerTest"` and `:server:test --tests "*AdminSystemImportApiTest"`: only the
  known Windows failures, where the staging timestamp's `:` is not a valid path character
  (`testExpiredFolderIsDeletedAndRecentIsKept`, `testCleanupStopsAtTheFirstNonExpiredFolder`,
  `testSuccessRoundTripViaExportThenImport`). The API test's log shows the staging upload running through
  `AsyncTaskExecutor` with the request's trace id.
- `npx -y @fission-ai/openspec@1.14.3 validate parallel-system-import --strict`: valid.

## Drift from design.md

- Staging deletion catches and logs each failed delete instead of stopping at the first. Design said it uses "the same
  parallel delete"; this keeps the best-effort behaviour of the removed `TempFolderCleaner.deleteFolder`.

## Measurement (task 3.3)

The same archive (3,584 blobs, 705 MiB) was imported on the review environment on Google Cloud Storage, before this change
and with it (`parallelism` 4). Timings are from the `System import … finished` log lines.

| Phase | Before (one call at a time) | After (4 workers) | Speedup |
|---|---|---|---|
| Stage | 408 s, of which 52 s waiting for the client | 170 s, of which 57 s waiting for the client | 2.4x |
| Stage without the client wait | 356 s (99 ms per blob) | 113 s (32 ms per blob) | 3.1x |
| Wipe | 222 s, 3,699 deletes (60 ms each) | 44 s, 3,584 deletes (12 ms each) | 4.8x |
| Copy | 241 s, 3,584 copies (67 ms each) | 52 s (15 ms each) | 4.6x |
| Invalidate + rebuild | 12.7 s + 2.2 s | 14.4 s + 2.1 s | - |
| Replace | 477 s, 177 s over the lock period | 113 s, 187 s under it | 4.2x |
| Whole request | 14 min 48 s | 4 min 45 s | 3.1x |

- Before, the locks lapsed during the copy: the other pod ran 5 queued config rebuilds on half-copied buckets, and
  the importing pod logged `Lock service failed to unlock` twice. After, neither pod logged a lock warning, a failed
  delete or an error, and the staging folder was empty after the response.
- Wipe and copy scale close to the worker count, so 4 workers reuse connections without new TLS handshakes.
- At about 27 ms per blob plus 16 s for invalidate and rebuild, a replace stays inside the lock period up to about
  10,000 blobs on this storage.
- The stage gained less: the 28 blobs over 5 MiB (654 of the archive's 840 MiB, uncompressed) still upload one at a time
  on the import thread, and the client wait is now a third of the stage.

## Known limits

- Parallelism above 5 pays a TLS handshake on most calls unless `-Dhttp.maxConnections` is raised too (documented).
- A replace longer than the 5-minute lock period still lets the locks lapse; it is only less likely now.
- Config rebuilds queued behind the bucket locks are not coalesced (#2240).

## Review

Part of pull request #2213, not yet reviewed there.
