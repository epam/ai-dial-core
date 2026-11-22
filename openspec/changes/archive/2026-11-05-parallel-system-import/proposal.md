## Why

A system import makes one blob storage call per blob in each phase, one after another. On a review environment on
Google Cloud Storage, an archive of 3,584 blobs (705 MiB) took 14 min 48 s:
- The stage phase took 408 s, but only 52 s of it was spent waiting for the client. The rest was uploads, about 99 ms
  each.
- The wipe took 222 s (3,699 deletes at 60 ms each), and the copy 241 s (3,584 copies at 67 ms each).

The replace phase took 477 s, 177 s more than the 5-minute lock period. The bucket locks lapsed during the copy: the
other pod rebuilt its config from half-copied buckets, and the importing pod failed to unlock at the end.

## What Changes

- New setting `adminSystemImport.parallelism` (default 4, at least 1). A system import runs at most that many blob
  storage calls at a time.
- The stage phase still reads and validates the archive in order. A blob entry of at most 5 MiB is read into memory and
  uploaded by a worker; a larger one is still streamed as it is read.
- The wipe deletes, the copies and the deletion of the import's own staging run in parallel, each with the listing
  still read page by page. Every delete finishes before the first copy starts, and every copy before the Redis entries
  are dropped.
- When a call fails, no more calls start, the calls in flight finish, and the phase fails as it does today.
- `TempFolderCleaner.deleteFolder` is removed: the import was its only caller. The 24-hour staging sweeper is unchanged.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `system-import`: a new requirement, "Bounded parallel storage calls".

## Impact

- Issue [#2204](https://github.com/epam/ai-dial-core/issues/2204), pull request #2213.
- Code: `AdminSystemImportService`, `AiDial` (wiring), `TempFolderCleaner`. Tests: `AdminSystemImportServiceTest`,
  `TempFolderCleanerTest`. Docs: `README.md` (settings table), `docs/admin-system-import.md`.
- Expected on the same archive: a replace phase of about 2.5 minutes and an import of about 4 minutes, to be measured on the
  review environment.
- Out of scope: lock renewal (#2169), coalescing the config rebuilds queued behind the bucket locks (#2240), and the
  GCS 32-part compose limit for blobs over 160 MiB.
