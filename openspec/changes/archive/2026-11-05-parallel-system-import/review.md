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

## Not done

- 3.3: the timing measurement on the review environment, with the same 3,584-blob archive.

## Known limits

- Parallelism above 5 pays a TLS handshake on most calls unless `-Dhttp.maxConnections` is raised too (documented).
- A replace longer than the 5-minute lock period still lets the locks lapse; it is only less likely now.
- Config rebuilds queued behind the bucket locks are not coalesced (#2240).

## Review

Part of pull request #2213, not yet reviewed there.
