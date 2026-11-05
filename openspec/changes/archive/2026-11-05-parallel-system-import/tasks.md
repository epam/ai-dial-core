## 1. Bounded parallel calls

- [x] 1.1 `AdminSystemImportService.Settings.parallelism` (default 4) with `validate()` rejecting a value below 1; wire it and an
  `Executor` over `AsyncTaskExecutor` in `AiDial`
- [x] 1.2 A bounded runner (semaphore of `parallelism`, first failure kept, every exit waits for the calls in flight)
- [x] 1.3 Stage: entries of at most 5 MiB uploaded by workers, larger ones inline; wait for all before returning; on a
  rejection, wait for the uploads in flight before deleting the staging
- [x] 1.4 Wipe, copy and staging deletion through the runner, each finishing before the next phase; atomic progress counters
- [x] 1.5 Remove `TempFolderCleaner.deleteFolder` and its test

## 2. Tests

- [x] 2.1 `AdminSystemImportServiceTest`: thread-safe fake storage that records the calls in flight; `testDefaults` and
  `testNonPositiveSettingsFailStartup` cover `parallelism`
- [x] 2.2 `testStorageCallsRunInParallelUpToTheLimit` (scenario "At most the configured number of calls at once")
- [x] 2.3 `testCopyStartsAfterTheLastDelete` (scenario "Copy waits for the wipe")
- [x] 2.4 `testFailedDeleteFailsTheReplaceAfterCallsInFlight` (scenario "A failed call stops the phase")
- [x] 2.5 `./gradlew checkstyleMain checkstyleTest`, `:server:test --tests "*AdminSystemImportServiceTest"`,
  `:storage:test --tests "*TempFolderCleanerTest"`

## 3. Docs

- [x] 3.1 `README.md`: `adminSystemImport.parallelism` row; `docs/admin-system-import.md`: parallelism and `http.maxConnections`
- [x] 3.2 `npx -y @fission-ai/openspec@1.14.3 validate parallel-system-import --strict`
- [ ] 3.3 (deferred: needs the review environment) Re-run the 3,584-blob import and compare the phase timings
