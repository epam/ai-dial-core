# Design

## Context

See `proposal.md` for why. This section is only what the approach depends on.

**What system export wrote.** The `system-export` capability (`openspec/specs/system-export/spec.md`) specifies the archive,
and this design relies on it. `AdminSystemExportService.java:48-50` fixes the layout: `.dial-system-export/manifest.json`
first, then for each blob `.dial-system-export/metadata/<path>` followed by `<path>` (bytes exactly as stored). The metadata
JSON holds `contentType`, `contentEncoding`, `contentDisposition`, `contentLanguage`, `cacheControl`, `creationDate`,
`lastModified`, `userMetadata` (`AdminSystemExportService.toBlobMetadata`). Export skips `encryption_keys/` and `credentials/`
at the resource-type position (`AdminSystemExportService.java:54-56`, `shouldExport`). Listing entries that are not blobs
(folders) get no entry, while the folder marker blobs `.dial-folder` and `.dial-resource` are exported like any blob
(`exportBucket`, `:119-130`). The manifest is the record `AdminSystemExportService.Manifest` (`:258`): `formatVersion`,
`coreVersion`, `createdAt`. Its only guard is `LockService.tryLockBuckets` (`AdminSystemExportService.export`,
`LockService.java:203-227`): a held bucket lock gives `409`.
The locks last `LockService.LOCK_PERIOD` (300 s), one constant for every caller (`LockService.java:36`, passed to the Redis
script as `ARGV[2]` in the private `tryLock(id, owner)`, `:237-251`), and are never renewed (renewal is #2169).

**What system import must beat.**
- `ResourceService` is a write-back cache. A write goes to Redis and a queue (`ResourceService.redisPut`, `:1252-1285`,
  queue `resourceQueue` at `:158`/`:202`) and reaches blob storage when `sync()` runs (`:1072-1122`). Reads go to Redis
  first (`getResourceBytesWithMetadata`, `:578-606`). Config types stay cached for 30 days (`ResourceTypes.java:16-38`).
- The merged config reads through that cache (`MergedConfigStore.applyReplicaEvent` `:374` and the read at `:1365` both call
  `resourceService.getResource`), so a rebuild after a raw blob write sees the old content.
- Raw blob writes publish no resource event (`ResourceService.publishEvent`, `:1033-1048`, only runs on `ResourceService`
  writes), so no pod is told. But every pod runs a full rebuild every `config.reload` ms: `FileConfigStore.java:44-47`
  calls `mergedConfigStore.requestRebuild()` on each tick (`AiDial.java:300-302`), default 60000
  (`aidial.settings.json:42`), and that rebuild takes the bucket locks (`MergedConfigStore.onRebuildTimerFire`, `:615-634`).
- A CEK is created lazily, by whoever first needs one for a bucket: `ContentEncryptionKeyManagerImpl.getOrCreateKey`
  generates and stores it when the blob is missing, and `CredentialEncryptionService.encryptOrDecrypt` calls it for decrypt
  as well as encrypt (`CredentialEncryptionService.java:54-59`). So an environment that never encrypted a secret has no
  CEK, which is a legitimate state, and a system import of ciphertext into an environment without the CEK would make the
  rebuild create a wrong one. Secret values in entities are written as `ENC[...]` (`SecretFieldProcessor.java:29-30`). Core
  does not check this (decision 13).
- `MergedConfigStore.rebuildNow()` (`:648-660`) takes `underBucketLocks` itself. Called from inside the replace phase it
  would spin on its own lock until the lease runs out (`LockService.lockInRedis`, `:120-137`, and `tryLock` only checks
  the deadline, never the owner).
- `Proxy.java:314-318` pauses the request while authorizing and resumes it when the controller's future completes, so a
  body reader has to be attached inside `handle()` (`CodeInterpreterController.java:191-201` does the same).

**Existing pieces to reuse.** `InputStreamAdapter` (request to blocking `InputStream`, with its own backpressure at
`InputStreamAdapter.java:113-120`), `ConfigAuthorizationService.isAdmin`, `AdminSystemExportController`'s response handling
(`:83-92`, `:97-125`), `LockService.tryLock` and `underBucketLocks`, `BlobStorage.list/copy/delete/exists`,
`ResourceService.cleanupTempFolder` and `isExpiredTempFile` (`:791-845`), `TimerService.scheduleWithFixedDelay`
(`ResourceService.java:206`, `ComplexResourceSweepService.java:90`), the `AdminSystemExportApiTest` harness.

## Goals / Non-Goals

**Goals:**
- Bring back the archive that system export writes, losslessly except for the blob times blob storage assigns.
- Reject a bad archive before anything is destroyed, and treat the archive as untrusted.
- After a successful system import, the importing pod serves the imported content, from Redis and from the merged config.
- Reuse the existing sweeper code instead of a second one.

**Non-Goals:**
- Renewing locks (#2169) or a configurable lock period in this change, a flush of queued writes, signalling other pods, the per-pod CEK cache, a job with status.
- Checking that the target holds the source's CEKs, or that they match (decision 13).
- Making the replace phase all-or-nothing. Blob storage has no transactions; the replace phase is repeatable instead.
- Importing archives that were not made by export (no manifest, no metadata entries).

## Decisions

Phases below: the **stage phase** reads the upload, validates it and stages it; the **replace phase** runs under the
bucket locks.

```
 handle() on the event loop
   isAdmin?            no -> 403, Connection: close
   attach InputStreamAdapter(request); submit the system import to AsyncTaskExecutor; return (request resumes)
 system import task (virtual thread)
   guard = lockService.tryLock(<prefix>/system-import)          null -> 409, Connection: close
   adapter read timeout = stallTimeout                          no bytes for that long -> 408
   STAGE: ZipInputStream over the adapter
          manifest (formatVersion, coreVersion) -> too new / unsupported: 400
          per entry: name checks, sidecar/blob pairing -> BlobStorage upload
          into .dial-system-import-tmp/{ts}/{importId}/<real path>
          end of stream clean, >= 1 blob   -> accepted          any failure -> 4xx/5xx, staging dropped
   onAccepted: event loop sends 200 + application/x-ndjson, starts the progress timer
   REPLACE: underBucketLocks(public/, platform/)
          wipe  -> promote  -> invalidate Redis -> rebuildNowUnderBucketLocks
   release locks, guard; stop timer; write the final status line and end   (failure: response.reset())
   afterwards: background deleteFolder(own staging)
 sweeper (every 24 h): delete .dial-system-import-tmp/{ts}/... folders older than 24 h
```

### 1. Stage in blob storage, under `.dial-system-import-tmp/`
Entries are written to `.dial-system-import-tmp/{yyyy-MM-dd HH:mm:ss}/{importId}/{real path}`. The real path is kept verbatim,
so promoting is a prefix strip and the staging listing is the manifest. The prefix is outside both buckets, so staged
data is outside the bucket locks, the config scan (`MergedConfigStore` scans only `platform/` groups) and export.
Each staged blob carries its content metadata and user metadata from its sidecar.
- *Pod disk:* needs disk as large as the archive and moves every byte back through the pod while the locks are held.
- *The raw ZIP as one blob:* the replace phase would then unzip under the lock.
- *Validate without storing:* a sequential read consumes the upload, so a second upload would be needed.
- *Reuse `.dial-tmp`:* its sweeper deletes anything older than an hour and does not know what is in use.

### 2. Wipe, then promote
Replace phase: list `public/` and `platform/` page by page (`BlobStorage.list`, 1000 per page, as `exportBucket` does) and
delete every blob that is not under `encryption_keys/` at the type position; then list the staging prefix and
`BlobStorage.copy(staged, real, null)` each blob (server-side, same container); then invalidate; then rebuild. Both listings
skip entries that are not blobs (`StorageType.BLOB`, as `exportBucket` does), and the folder marker blobs `.dial-folder` and
`.dial-resource` are handled like every other blob. `credentials/` is wiped because export
never carries it and its records are encrypted under the bucket CEK with the record path as AAD
(`ResourceCredentialsService.java:396-412`), so a CEK swap would leave them unreadable.
- *Overwrite, then prune* the live keys that are not in the archive: not all-or-nothing either (an application can be
  copied before its schema), and it needs a diff of two listings. It adds work and no safety.
- *Skip `credentials/` like export does:* leaves records tied to entities that may not exist after the system import.

Cost: one list call per 1000 blobs, one delete per live blob, one copy per staged blob, inside the lock.

### 3. The default lock period, never an abort on time
The replace phase takes `lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, ...)` with the default period
(`LockService.LOCK_PERIOD`, 300 s). It waits for an in-flight admin writer or export; the wait does not count against the
period. It logs a `WARN` when it passes the period and keeps going, because a stop after the wipe leaves the buckets worse
than finishing. `LockService` does not change.

Why the default is enough for now: a system import is rare, and admins are asked to stop all work during the maintenance
window, so nothing should be writing while it runs. The lapse risk below matters only if something does.

The dev figures (decision 15) give about 100 to 370 s for 3548 blobs, so a replace on that environment can take longer than
the period. That is accepted for now. If it happens, the locks lapse while the system import still works (see Risks), and
the `WARN` says so.
- *A longer, configurable period per call* (an earlier version of this design): `LockService` would take a `Duration` and
  the system import would pass, say, 15 minutes. It avoids the lapse for most sizes, at the cost of a longer wait after a
  crash (admin writes, every pod's periodic rebuild and a re-run all wait for the rest of the period), and it is still a
  ceiling. Left out; it can be added if the measurement in task 10.1 shows the replace does not fit.
- *Renew the locks (#2169):* no ceiling, and a crashed system import frees them within one renewal period. Heavier: a new
  Redis script, renewal calls in the replace loop or a heartbeat, handling of a lost lock. Tracked separately; the system
  import can adopt it later.
- *Abort at the deadline, as export does:* export can stop safely; system import cannot once the wipe has started.

### 4. The guard is `LockService.tryLock`
Key `BlobStorageUtil.toStoragePath(lockService.getPrefix(), "system-import")`, taken without waiting; `null` gives `409`.
System import and export do not share a guard. They exclude each other only through the bucket locks, which the replace phase
holds: a system export started meanwhile gets `409` (`system-export`, "Buckets are locked while exporting"), and a system
import's replace waits for a running export.
- *A Redis lease with a heartbeat:* needs renewal, which #2169 owns.
- *Bucket locks for the whole request:* would freeze the admin plane for as long as the client uploads.

If the guard lapses (it uses the default 300 s period and is not renewed), a second system import may start its stage phase while the first still uploads or
replaces. The second's replace waits on the bucket locks, so the replaces never overlap, and the result is the later one to
finish. This is acceptable because system import is repeatable, and the docs say not to start another system import before the first one
has answered.

### 5. New `ResourceService` method: drop queued and cached entries by bucket
`ResourceService.invalidate(Collection<String> bucketLocations)` (name to be settled in the code). It (a) removes from
`resourceQueue` every member whose blob key (`blobKeyFromRedisKey`, `:1212-1217`) starts with a location, and (b) deletes
every Redis map whose key is `{type}:{storage path}` for a `ResourceTypes` name and a storage path under a location
(`RedisUtil.redisKey`, `:72-75`).
Finding the keys: `RKeys.getKeysByPattern` was not used anywhere in the repo, so this adds the first use. Two passes only,
patterns `*:{prefix/}public/*` and `*:{prefix/}platform/*`. These also match lock keys (`lock:public/`,
`lock:file:public/...`), so each key is filtered on the client: the text before the first `:` must be a `ResourceTypes`
name (`RedisUtil.getResourceType`), which lock keys never are. Never delete on the pattern alone: that would delete the
bucket locks the system import is holding. `api_key_data:` keys live under their own bucket (`ApiKeyStore.java:45-46`) and are
not matched. It runs in the replace phase after promote and before the rebuild; it does not take per-key locks.
- *`flushPending`:* writes queued data to blob storage. System import wants it discarded, and the export side declined it.
- *`FLUSHDB`:* drops locks, other pods' state and unrelated caches.
- *Per-key deletes from the wipe and promote sets:* miss cached entries without a blob and queued creates.

A writer holding only a per-resource lock can race between promote and invalidate. This is the same class of limit export
has, and the docs say to pause writes to `public/` and `platform/` during a system import.

### 6. A rebuild that does not take the locks again
`MergedConfigStore.rebuildNowUnderBucketLocks()` is the body of `rebuildNow()` (`:648-660`): `rebuildLock`,
`cancelPendingRebuild()`, `rebuild()`. `rebuildNow()` calls it inside `underBucketLocks`. `reload()` (`:531-548`) and the timer
(`:615-634`) are not touched. If it throws, the final line gets a warning and the response is still `200`.

### 7. Other pods rely on the periodic rebuild
They rebuild within `config.reload` (60 s by default), under the bucket locks, after the Redis entries were dropped. The
docs state the delay. No event is published.
- *One event per imported entity:* reuses the replica path, but needs a diff of wiped and imported entities and makes
  `ResourceService.publishEvent` public.
- *One bulk event:* a new `ResourceEvent.Action` value breaks older pods during a rolling upgrade.

### 8. Response: `200`, progress lines, one final status line
Statuses before the body is accepted: `403`, `409` (guard), `400` (archive), `408` (stalled upload),
`500` (blob storage failed while staging). After `onAccepted`, the head is `200` and `Content-Type: application/x-ndjson`,
one JSON object per line:
- **Progress lines** while the replace runs, from a periodic timer on the event loop (`adminSystemImport.progressInterval`,
  15000 ms) plus one right after the head: `{"phase":"wipe","deleted":N}`, `{"phase":"copy","done":N,"total":M}`,
  `{"phase":"invalidate"}`, `{"phase":"rebuild"}`. The timer reads a small progress object that `AdminSystemImportService`
  updates (the phase, and volatile counters for the blobs deleted and copied; `total` is the number of staged blobs, counted
  during the stage phase). The wipe has no total, because the live blobs are not counted beforehand.
- **A final line** `{"status":"success","importedBlobs":N,"deletedBlobs":N,"warnings":[...]}`. `curl | jq` prints every line, and
  `jq -s 'last'` gives the result.
- **A failed replace** resets the connection (`AdminSystemExportController.java:97-125` does the same), writes no final line, and
  logs the cause, so a failure never looks like a success. A client that goes away does not cancel the replace.

Why lines at all: the client sends nothing and receives nothing while the replace runs, which can be minutes. The server's own
`idleTimeout` is 300000 ms by default (`aidial.settings.json:36-37`), and an ingress or load balancer in front of Core may
have a shorter one. Lines on the response keep the connection active and show the operator that the system import is alive and how
far it has got. Two things are assumed, not verified: that a response write resets Vert.x's idle timeout, and that the
ingress passes small chunked writes to the client instead of holding them back. Task 10.2 checks both on a real deployment.
- *Bare newlines* (an earlier version of this design): the same mechanism, with nothing for the operator to read.
- *Server-Sent Events:* a browser standard, but `curl | jq` no longer works and nothing in Core consumes it.
- *`202` and a stored result with a status endpoint:* the only option that still works if the connection is dropped anyway.
  It needs one Redis record (the system import is one at a time, so a single record is enough), one admin endpoint, a retention time
  and tests. Left as a follow-up if the check in task 10.2 shows the connection does not survive a long replace.
- *Silent synchronous:* the connection may time out at an ingress while the replace runs.

### 9. Archive checks (stage phase)
One pass over a `ZipInputStream`.
- Manifest first and `formatVersion == 1`, else `400`.
- Version: the running version is a constructor argument of `AdminSystemImportService` (as `AdminSystemExportService` takes
  `coreVersion`, `AiDial.java:415`), so tests inject any pair. The leading dotted numbers are compared
  (`0.50.0-SNAPSHOT` counts as `0.50.0`) and an archive newer than the running Core is rejected with `400`. A build whose
  version is `0.0.0` (the default project version, `build.gradle:10`, which local, test and other non-release builds report) or
  has no leading number (`AiDial.version()` returns `undefined` when the `version` resource cannot be read,
  `AiDial.java:604-615`) counts as newer than every release. Such a build accepts any archive, and its own archives are
  accepted only by such a build. Release archives therefore go into dev environments, and a dev archive cannot go into a
  release, where it may hold content newer than that release.
  - *Compare `0.0.0` as the oldest version:* lets a dev archive into any release, a downgrade risk.
  - *Strict equality for dev builds:* blocks cloning a released environment into a local or test one.
  - *Not compare `0.0.0` or `undefined` at all:* same as the first, for a dev archive.
- Names: rejected, never rewritten. Empty, backslash, control character or NUL, leading `/`, empty/`.`/`..` segment,
  longer than 1024 bytes in UTF-8 (a constant, the usual blob key limit), first segment not exactly `public` or `platform`.
- `encryption_keys/` and `credentials/` at the type position, compared case-insensitively, are rejected. The group names
  come from `AdminSystemExportService.EXCLUDED_GROUPS`, which becomes package-private so export and system import cannot drift.
- A blob needs the sidecar entry directly before it, and a sidecar the blob directly after it. Sidecars are read into
  memory up to 64 KiB (a constant) and parsed with `ProxyUtil.MAPPER`. String values with control characters are
  rejected. Duplicate names are found with a set of 64-bit hashes of the names (about 8 bytes each plus set overhead),
  not the names.
- No limit on the number of entries or on bytes. Memory does not depend on them (entries stream), and the cost is bounded by
  the client: the stage phase stops when the client stalls (decision 14) or ends the upload. A ZIP entry whose size or
  checksum does not match its header makes `ZipInputStream` throw, which becomes `400`. Entry count and bytes are logged at
  `INFO`, not enforced.
  - *Entry and byte caps as settings:* their defaults would be guesses, and a wrong one blocks a legitimate system import.
- The whole body must end without a transport error. `InputStreamAdapter` rethrows a stream error from `read()`, so a
  truncated body is an exception, never an EOF.
- At least one blob.
- Not a threat, so not checked: nested archives (opaque blobs).

Settings, all under `adminSystemImport` with code defaults (`aidial.settings.json` in test resources shadows the built-in one), in
`AdminSystemImportService.Settings`, validated at startup like `AdminSystemExportService.Settings` (`AiDial.java:415-417`):
`stallTimeout` 60000 ms and `progressInterval` 15000 ms. Each must be positive. The defaults are my choice, to be
adjusted in review.

### 10. New `BlobStorage` write path
`BlobStorage.upload(String path, BlobAttributes attributes, InputStream data)`: reads up to the multipart part size (a 5 MiB
constant in the `storage` module, the same value as `BlobWriteStream.MIN_PART_SIZE_BYTES`, which lives in `server` and
cannot be referenced from `storage`). If the stream ends first, one `putBlob` with the bytes; else
`initMultipartUpload` with the attributes, `storeMultipartPart` per part, `completeMultipartUpload`, and
`abortMultipartUpload` on any failure (`ResourceUpload.java` is the pattern). `BlobAttributes` carries content type,
encoding, disposition, language, cache control and user metadata. Today `BlobStorage.store` (`:153-172`) takes only type,
encoding and user metadata, and `buildContentMetadata` (`:309-314`) only the type.
- *`BlobWriteStream`:* goes through `ResourceService.putFile/initFileUpload` (etag, author, `.dial-tmp`), not raw blobs.

Promote is `BlobStorage.copy(from, to, null)`, which keeps the source's content and user metadata only if the provider
does so for a copy. The first task proves that on the filesystem provider and on S3 compatible storage; if a provider
does not, promote uses the existing overload `copy(from, to, userMetadata)` (`:212-222`), which passes the source's
content metadata, at the cost of one HEAD per blob inside the lock.

### 11. One sweeper, shared
`ResourceService.cleanupTempFolder` and `isExpiredTempFile` (`:791-845`) move into a small class
`TempFolderCleaner(BlobStorage, folder, ttl)` in the `storage` module with `cleanup()` (list, delete expired, stop at the
first folder that is not expired, since folders sort by time) and `deleteFolder(prefix)`. `ResourceService` builds one for
`.dial-tmp` with the one-hour TTL and keeps its timer; behaviour for `.dial-tmp` does not change, and the existing test
(`ResourceServiceTest.java:199-207`) keeps covering it. `AiDial` builds a second one for `.dial-system-import-tmp` with a 24 hour TTL
and schedules it every 24 hours through the same `TimerService`. The system import calls `deleteFolder` on its own prefix on a
background task after the response, whether it succeeded or not. Folder timestamps use `yyyy-MM-dd HH:mm:ss`, the
format `ResourceService.java:103-105` uses.
- *Delete in `finally` before the response:* delays the client by one delete per staged blob.
- *Delete the staging at the start of the next system import:* unsafe when the guard lapses mid-upload.
- *Scan with the existing code in place, only a new folder name:* the TTL, the folder and the format are hard-coded.

The worst case for a staging folder left by a dead pod is a little under 48 hours.

### 12. Differences from today's behaviour, and what was considered and not done
- System import writes blobs through `BlobStorage`, not `ResourceService`, so no `ResourceEvent` is published for imported
  resources. Only the rebuild and the periodic rebuild pick them up.
- `credentials/` is wiped. Export never wrote it, so the archive cannot bring it back.
- Considered: a Redis lease with a heartbeat and a system import id swept by lease. Here: `LockService.tryLock`, a plain 24 hour
  age rule, and the same cleaner as `.dial-tmp`.
- Considered: reject any `encryption_keys/` segment in a name. Here: at the type position only, as export excludes it, so a
  published file `public/files/<owner>/encryption_keys/notes.txt` stays importable. `credentials/` entries are rejected too.
- Considered: a flush of the write-back queue before the walk. Here: none, on export or system import.
- Considered: the platform CEK required before an import, the public CEK not. Here: no check of the CEKs (decision 13), and
  export does not change.

### 13. The encryption keys are not checked
The archive carries ciphertext (`ENC[...]` values) and no CEK, and Core does not look at the target's CEKs. A check was
designed and dropped:
- A fixed rule such as "the platform CEK must exist" is wrong for an environment that never stored a secret. It has no CEK,
  because a CEK is created on first use (`ContentEncryptionKeyManagerImpl.getOrCreateKey`).
- A rule that follows the source needs export to record which CEKs the source had, a field in the manifest. That discloses
  nothing the archive does not already hold, but it adds a field, a helper, a spec delta and an export change, and it rejects
  every older archive.
- Either way it is a presence check. It cannot tell that the CEK in the target is the source's, so a wrong key passes. It
  would catch only the case where the operator forgot the CEK entirely.
- That case is recoverable and loses nothing. The ciphertext stays in the blobs. The rebuild decrypts it, which creates a wrong
  CEK (`CredentialEncryptionService.encryptOrDecrypt` calls `getOrCreateKey`), the secrets do not decrypt and the rebuild
  reports invalid entities (`onInvalidEntity`; I have not traced what that does to a pod's live config). The operator
  replaces the CEK blob, rolling-restarts the pods and runs the system import again, which is repeatable.

The runbook in the docs carries the step instead: place the source's CEKs that exist (`public/encryption_keys/cek`,
`platform/encryption_keys/cek`) in the target before importing, and roll-restart the pods.
- *Always require the platform CEK:* wrong for an environment without secrets, and the public bucket needs the same treatment.
- *A manifest field written by export, checked by import:* the cost above, for one narrow mistake.
- *Scan the archive for `ENC[`:* needs a gunzip of every entity blob, and plain text can contain the marker.
- *A caller flag that skips a check:* extra API surface, and easy to use by habit.

### 14. Stalled uploads: a read timeout on the adapter
`InputStreamAdapter.read` waits on `queue.take()` with no limit (`InputStreamAdapter.java:83`). It gets an optional read
timeout that waits with `queue.poll(timeout)` and, when nothing arrives, records an error and throws; the default stays
"wait forever", so `CodeInterpreterController` does not change. System import sets it to `adminSystemImport.stallTimeout` (60000 ms,
the same default as export's). It counts only the time the importer waits for the client's next bytes. When blob storage is
slow and the adapter pauses the request, the importer is not waiting, so that is not a stall. The result is `408`, the
buckets untouched, the guard released and the staging dropped. There is no limit on the total upload time.
- *A total upload deadline* (an earlier version of this design): it kept the stage phase inside the 300 s guard, but the
  guard does not cover the replace phase anyway, and it rejects a large archive on a slow link even while bytes keep
  arriving, which is the complaint behind #2169 for export.
- *A watchdog timer that calls `adapter.close()`:* needs a timer and a flag to tell a stall from an abort; the read timeout
  does it in one place.

A client that sends a trickle keeps a worker and the guard busy for as long as it keeps sending; the guard expires after
300 s. This is accepted for an admin-only endpoint.

### 15. Cost
No change per chat, embedding or resource request: none of that code is on those paths. System import costs, per archive of N
blobs: stage `N` uploads (plus init, parts and complete for blobs over 5 MiB) and a delete of the staging; replace `N/1000 + 1`
list calls, one delete per live blob, `N` copies; invalidate two `SCAN` passes over the Redis keyspace, one scan of the
queue and batched deletes; one config rebuild, as `rebuildNow` does today. Export does not change. The stage phase is
sequential, so its length is about `N` times the upload latency plus the transfer.

Preliminary figures from the dev environment, read from the `dial_blob_operation` timer over 7 days (values from graphs, so
approximate): `store` p50 about 8 ms and p95 about 23 ms on about 100000 calls; `delete` p50 about 8 ms and p95 about 18 ms on
about 25000 calls; `copy` p50 about 20 ms and p95 about 85 ms on almost no calls, so it is weak evidence. The environment
holds 3548 blobs in `public/` and `platform/` (881 MB, 875 MB of it in 976 files). The replace needs one delete and one copy
per blob: about 100 s with delete 8 ms and copy 20 ms, about 206 s with 18 ms and 40 ms, about 366 s with 18 ms and 85 ms. This is
measured for real by the task that runs a system import of a seeded archive on a dev environment and records the stage and replace durations
that the service logs at `INFO`.

## Risks / Trade-offs

- **The replace phase may outlast the lock period (300 s).** It is bounded by sequential per-blob round trips (about 2 per
  blob). If it does, the bucket locks lapse while the system import still works, and then: a second system import's replace
  can start and its wipe can delete blobs the first just promoted; an admin write can interleave and leave a stray entity or
  fail on half-promoted state; an export started after the lapse can read the half state and write a valid-looking torn
  archive; a peer's periodic rebuild can read it (it corrects itself at the next rebuild). The system import's own keys still
  end with the archive's content unless a writer touches them after promote. The dev figures give 100 to 370 s for 3548
  blobs, so a lapse is possible on that environment. → Accepted for now: the docs ask for a maintenance window with all
  admin work stopped, so no writer, second system import or export should be running. The WARN says it happened, and the
  measurement task checks the real time. Follow-ups that remove the risk, not blocked by this design: a configurable lock
  period, lock renewal (#2169), bounded parallel delete and copy.
- **A crash mid-replace holds the locks until the 5-minute period ends.** → Documented. Everything recovers on its own; a
  re-run waits for the rest of the period.
- **The stage phase has no total time limit and no size limits.** A hostile or mistaken archive can stage until the client
  stalls or ends the upload, and a slow client can keep a worker busy. → Admin-only endpoint; the staging is deleted in the
  background and by the sweeper; the guard expires after 300 s.
- **The guard can lapse during a long upload or replace** (default 300 s, not renewed), letting a second system import start. → Its
  replace waits on the bucket locks and the system import is repeatable; the docs say to wait for the first answer.
- **A replace phase that fails midway leaves a partial system import.** → It is repeatable. The docs say to run
  the same archive again. A failure in the stage phase leaves the buckets untouched.
- **The replace phase has no all-or-nothing guarantee against readers.** Published files, prompts and Skills under
  `public/` are missing or partial while it runs. → The docs say to schedule a system import like an outage.
- **Writers that hold only a per-resource lock** can race with the invalidate. → Documented; system import is a maintenance operation.
- **A rolling deploy during a system import kills it.** → Documented. A leftover staging folder is reclaimed within about 48 hours.
- **Pod memory.** About 5 MiB for one multipart part, up to about 5 MiB queued in `InputStreamAdapter`, `ZipInputStream`
  buffers, one sidecar (at most 64 KiB), and the name-hash set (about 8 bytes per entry plus set overhead). Entries
  under 5 MiB are held whole for one `putBlob`.
- **Provider behaviour.** Content-encoding on multipart init and on copy, `SCAN` and `DEL` across slots in cluster mode, and
  ZIP64 in `ZipInputStream` (more than 65535 entries, entries over 4 GiB) are assumed, not seen. → Tests in tasks 1 to 4.
- **A client disconnect during the upload** is expected to reach the adapter's exception handler. If not, the stall timeout
  ends it after `stallTimeout`.
- **The per-pod CEK cache** still holds a CEK placed out of band. → The rolling restart that applies to any such change.
- **A forgotten or different CEK in the target** is not detected (decision 13). The imported secrets then do not decrypt, and
  the rebuild lists the entities as invalid. → The runbook step; the system import is repeatable once the CEK is right.

## Migration Plan

None. New endpoint and new settings with code defaults; nothing to migrate and nothing to roll back except the code. A
folder `.dial-system-import-tmp/` appears in the container during a system import, so cost reports and lifecycle rules that list the
container will see it.

## Implementation notes

Written while applying `tasks.md`; records drift from the plan above and from the assumption that nothing else had changed
since this design was written.

- **Decision 10 resolved.** `BlobStorageUploadTest.testCopyKeepsContentAndUserMetadata` (task 1.1) shows `BlobStorage.copy(from,
  to, null)` on the filesystem provider keeps content type, content encoding and user metadata. Promote (decision 2) uses that
  overload as planned; the `copy(from, to, userMetadata)` fallback was not needed. The cloud-provider check (task 1.4) is still
  deferred, as planned, for lack of S3/Azure access here.
- **A new `HttpStatus.REQUEST_TIMEOUT` (408)** was added to `storage/.../http/HttpStatus.java`; the enum had every status this
  change's design calls for except that one.
- **`TempFolderCleaner.FOLDER_FORMATTER`** (task 3.1) is `public`, not package-private as a first cut assumed, so
  `AdminSystemImportService` can format its own staging timestamp with the exact same pattern
  `ResourceService.TEMP_FOLDER_FORMATTER` uses, per decision 11.
- **`AnnotationEndpointCollector.CONTROLLER_CLASSES`** (`openapi-generator`), a hand-maintained list the OpenAPI generator
  scans for `@ApiOperation`, is not mentioned in `proposal.md`'s code-impact list. `AdminSystemImportController` had to be added
  to it for `./gradlew replaceSpec -Plint` to find the new route; this is a pre-existing project mechanism, not new surface
  this change adds.
- **Local Windows verification gap.** Staging paths are named `.dial-system-import-tmp/<yyyy-MM-dd HH:mm:ss>/<id>/...`, the same
  timestamp format `.dial-tmp` already uses (decision 11). The `HH:mm:ss` colons are not valid in a Windows path, and the
  filesystem blob storage provider maps a blob key onto a real OS path, so any test that actually stages a blob through a
  real filesystem-backed `BlobStorage` fails on a native Windows checkout — confirmed pre-existing and unrelated to this
  change: `ResourceServiceTest.testCleanupTempFolder` (untouched by this change) fails the same way, before and after this
  change, on this machine. It is expected to pass in CI (Linux). Three places inherit this. (`ResourceServiceTest` also fails
  four other, unrelated cases on this machine — `testGetResourceStreamToleratesNullContentLengthFromBlobMetadata`,
  `testGetResourceMetadataToleratesNullContentLengthFromBlobMetadata`, `testLoadEmpty`, `testLoadPropagatesBlobFailure` — from
  a pre-existing Windows file-handle-lock issue in that test's own helpers, order-dependent and already tracked outside this
  change in `docs/notes/windows-test-suite-failures.md`; unrelated to the colon-in-path issue below and to this change's diff,
  which only extracts `cleanupTempFolder`/`isExpiredTempFile` out of `ResourceService` and adds `invalidate()`.)
  - `TempFolderCleanerTest`: `testExpiredFolderIsDeletedAndRecentIsKept` and `testCleanupStopsAtTheFirstNonExpiredFolder` use
    real timestamp folder names and fail locally on Windows for this reason; `testUnparseableFolderIsReported` and
    `testDeleteFolderRemovesOnlyThatPrefix` avoid colons and pass everywhere.
  - `AdminSystemImportServiceTest` therefore uses a small in-memory fake `BlobStorage` (list/delete/copy/upload/load/exists
    backed by a `TreeMap`), not the real filesystem provider `AdminSystemExportServiceTest` uses, so the stage and replace
    phases can be verified on every machine; `BlobStorageUploadTest` and `TempFolderCleanerTest` already cover the real
    filesystem provider for the operations this fake stands in for.
  - `AdminSystemImportApiTest.testSuccessRoundTripViaExportThenImport` runs the full `AiDial` stack, which always uses a real
    filesystem `BlobStorage`, so it fails locally the same way and is expected to pass in CI; the other `AdminSystemImportApiTest`
    cases (forbidden, a non-ZIP body, the stall timeout) do not stage a blob and pass locally.
- **Checkstyle's `AbbreviationAsWordInName`** (at most one consecutive capital letter) rejected several task-list test names
  verbatim, so these were renamed one word short: `testSlowButSteadyDataIsNotAStall` → `...NotStalled`,
  `testWorksWithAStoragePrefix` → `testWorksWithStoragePrefix`, `testDevelopmentBuildAcceptsAReleaseArchive` →
  `...AcceptsReleaseArchive`, `testDevelopmentArchiveIsRejectedByAReleaseBuild` → `...ByReleaseBuild`,
  `testDevelopmentArchiveIsAcceptedByADevelopmentBuild` → `...ByDevelopmentBuild`,
  `testUnreadableVersionCountsAsADevelopmentBuild` → `...AsDevelopmentBuild`, `testNotAZipIsRejected` → `testNotZipIsRejected`.
- **`testDuplicateEntryIsRejected`** (`AdminSystemImportServiceTest`) builds its archive by splicing raw ZIP bytes:
  `java.util.zip.ZipOutputStream` refuses to write two entries with the same name, so the standard writer cannot produce the
  archive directly. The service reads sequentially off `ZipInputStream` and never consults the central directory, so
  appending a second copy of one entry's local record (writer output truncated before its own central directory) reaches the
  same code path a real duplicate would.
- **`testEntryWithWrongDeclaredSizeIsRejected`** corrupts a byte inside an entry's compressed data (breaking its CRC) rather
  than writing a literally wrong declared size, for the same reason: `ZipOutputStream` enforces a consistent size/CRC when
  writing. Reading the corrupted entry hits the same `ZipException` path a genuinely mismatched entry would.
- **Duplicate-name tracking** (decision 9) uses a `HashSet<Long>` of a 64-bit FNV-1a hash per entry name, not a primitive long
  set — no primitive-collections dependency was added for it. It is still bounded by entry count, as intended.
- **Deferred, as `tasks.md` already marked them:** 1.4 (cloud provider), 2.4 (Redis Cluster), 10.1 (dev-environment
  measurement) and 10.2 (manual check through a real ingress) all need an environment this machine does not have.
- **Not implemented in this pass:** `AdminSystemImportApiTest.testOldStagingIsReclaimedBySweeper` (task 8.1) and the broader
  per-scenario HTTP test enumeration in tasks 6.2-7.4 beyond what `AdminSystemImportApiTest` covers — the service-level suite
  (`AdminSystemImportServiceTest`, 44 cases) exercises the stage and replace logic those scenarios describe; the HTTP-level
  suite covers admission (admin check, a non-ZIP body, the stall timeout) and one full export-then-import round trip, but not
  every named scenario as a separate HTTP test. `tasks.md` leaves these unchecked rather than marking them done.
- **Review fixes.** Three findings from the review round:
  - `AdminSystemImportController`'s `onFailure` handler closed the connection for the `409` (busy) case but not for a
    stage-phase rejection (`400`/`408`) or the generic `500`, even though `InputStreamAdapter#close` never resumes a
    paused request stream: a rejection reached after the ~4 MiB high-water mark leaves the rest of the body unread in
    the socket, which keep-alive would then hand to the next request on that connection. All three branches now close
    the connection, like the existing `403`/`409` cases.
  - `AdminSystemImportService.stage()`'s own `catch` called `stagingCleaner.deleteFolder(stagingPrefix)` uninsulated; if
    that scan itself threw (e.g. a transient blob-store hiccup right after a long upload), the cleanup failure replaced
    the real rejection and the controller answered `500` instead of the actual `400`/`408`. The cleanup call is now
    wrapped in its own `try`/`catch` that logs and keeps the original exception, the same pattern `deleteStaging()`
    already used.
  - Added `AdminSystemImportServiceTest.testFolderListingEntriesAreSkippedDuringWipeAndPromote`: the fake `BlobStorage`
    only ever listed `StorageType.BLOB` entries, so the `meta.getType() == StorageType.BLOB` guards in `wipeBuckets` and
    `promoteStagedBlobs` had no test that would fail if either were deleted. The fake now takes a second, normally-empty
    set of paths to list as `StorageType.FOLDER`, and the new test asserts `delete`/`copy` are never called for one.
  - **Not fixed — rejected.** `BlobStorage.store()`/`buildBlobMetadata(String, ...)`/`buildContentMetadata(String)` (all
    pre-existing, used by `ResourceService` and `ComplexResourceService` well outside this change) duplicate the
    `BlobAttributes`-based `storeAttributes()`/`buildBlobMetadata(..., BlobAttributes, ...)`/`buildContentMetadata
    (BlobAttributes)` this change adds, the same split `initMultipartUpload`'s two overloads already had before this
    change. Collapsing them would mean routing `store()` through `upload()`, which declares `throws IOException` and
    switches to a multipart upload above `UPLOAD_PART_SIZE_BYTES` — a signature and behavior change for every existing
    `store()` caller, not something to do as a side effect of this change. Left as a follow-up for a dedicated
    `BlobStorage` cleanup.
