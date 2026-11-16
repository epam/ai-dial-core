# Review: system-import-merge-mode

## What shipped

- `POST /v1/admin/system-import` reads the query parameter `mode`: `merge` (the default) or `replace`, in any case. An unknown or
  empty value is `400`, answered in `AdminSystemImportController.handle` before the body is read and after the admin check.
  `ImportMode.parse` in `AdminSystemImportService` does the parsing; `stage` takes the mode and `StagedImport` carries it with
  the warnings found while staging; `Progress` takes it too, so a `merge` import starts in the `copy` phase and never writes a
  `wipe` line.
- `merge` skips `wipeBuckets`: nothing is deleted, `credentials/` stays, and `deletedBlobs` is `0`. `replace` is unchanged.
- `merge` drops from Redis only the queued and cached entries of the blobs it copied, through the new
  `ResourceService.invalidateBlobs` (batches of 50 for the cache maps and the queue members, no scan, a path whose group is
  not a resource type is skipped). `replace` still calls `invalidate` for the whole bucket.
- Keys in `merge`: an `encryption_keys/` entry whose path already exists in the target is not staged and not unwrapped, and
  adds a warning to the final line. The target is asked again under the locks before the copy, and a staged key is skipped
  with the same warning if the bucket has got one since. The CEK caches are dropped only for a bucket whose key was copied.
  `replace` keeps the check on every key and the archive's key overwriting.
- Docs: `docs/admin-system-import.md` (the mode section and table, the breaking default, the merge key rule, the hand-placed key
  note per mode, the status table and the curl example) and the README description of the endpoint.

## Checks

- `./gradlew checkstyleMain checkstyleTest`: passes.
- `:storage:test`: `ResourceServiceInvalidateTest` (14).
- `:server:test`: `AdminSystemImportServiceTest` (53), `AdminSystemImportValidationServiceTest` (24).
  `AdminSystemImportApiTest` (7) fails in three tests that stage through the real filesystem provider,
  `testSuccessRoundTripViaExportThenImport`, `testNoModeMeansMerge` and `testModeIsCaseInsensitive`, all with the known Windows
  error (`InvalidPathException: Illegal char <:>` in the staging path). `testUnknownModeIsRejectedBeforeTheBodyIsRead` and the
  other tests of that class pass. The three are expected to pass on Linux CI.
- `npx -y @fission-ai/openspec@1.14.3 validate system-import-merge-mode --strict`: valid.

## Drift from design.md

Recorded in design.md under "Drift recorded while applying": the key test uses the entry's own path, an archive holding only a
kept key is accepted, `Progress` takes the mode, and two log lines carry `mode=`.

## Not done

- Task 5.3: the `invalidate=` figure of `merge` against `replace` for an archive of about 1000 blobs on a real Redis and blob
  store. The estimate in design.md (about 40 Redis commands for 1000 blobs) is not measured.
- The three Linux-only API tests were not seen passing on this machine.
