# Design

## Context

- The controller reads the body into `AdminSystemImportService.stage(InputStream)` and then runs `replace` under the locks of
  `MergedConfigStore.ADMIN_BUCKET_LOCATIONS` (`AdminSystemImportController.java:handle`, `onAccepted`;
  `AdminSystemImportService.java:stage`, `replace`, `replaceUnderLocks`).
- `replaceUnderLocks` runs wipe, copy, invalidate and rebuild in that order. `wipeBuckets` deletes every blob of each archive
  bucket except those under `encryption_keys/`; `promoteStagedBlobs` copies each staged blob over its real path;
  `resourceService.invalidate(bucketLocations)` and `invalidateEncryptionKeys` drop the Redis and key caches
  (`AdminSystemImportService.java:wipeBuckets`, `promoteStagedBlobs`, `invalidateEncryptionKeys`).
- `ResourceService.invalidate` removes every queued sync entry and every cached map whose key starts with a bucket location.
  The cached maps are found with a pattern `SCAN` over the whole keyspace and the queue is walked in full
  (`ResourceService.java:invalidate`, `removeQueuedEntries`, `removeCachedEntries`). That is right when a bucket is
  replaced, and wrong for a merge: it would drop an unsynced write to a resource that the archive does not hold.
- A resource's Redis key is `<TYPE>:<storage path>`; `ResourceTypes.of(group)` maps the group segment to the type and
  `blobKeyFromRedisKey` reverses the key (`ResourceService.java:redisKey`, `blobKeyFromRedisKey`;
  `ResourceTypes.java:of`). Batches of `BATCH_SIZE` (50) are already used for Redis work in this class.
- The KMS check runs while staging, on each `encryption_keys/` entry, through
  `AdminSystemImportValidationService.validateEncryptionKey` (`AdminSystemImportService.java:uploadStagedBlob`).
- `scope` of the export shows how a query parameter is validated before the body is touched
  (`AdminSystemExportController.java:handle`, `AdminSystemExportService.java:resolveBucketLocations`).

## Goals / Non-Goals

**Goals:**
- A request without `mode` never deletes a blob.
- `replace` behaves exactly as today, byte for byte and in the number of Redis and blob calls.
- `merge` does not lose a queued write of a resource that the archive does not hold.

**Non-Goals:**
- Importing a key that the KMS cannot unwrap (postponed), and modes per resource type.
- Changing the lock period or renewing the locks (#2169).
- Changing `adminSystemImport.parallelism` or the staging.

## Decisions

- **`mode` with `merge` as the default.** The parameter is validated by a small `ImportMode.parse` in the service and called in
  `handle` before the stage task is submitted, so a typo is `400` without uploading the archive. The admin check stays first.
  Rejected: `wipe=true|false`, which cannot grow a third value. Rejected: `replace` as the default, because a caller who
  forgets the parameter would wipe a bucket.
- **`merge` skips `wipeBuckets` and nothing else in the order.** Copy, invalidate and rebuild run as today. `deleted` is 0
  and the `wipe` phase is not set, so no `wipe` progress line appears.
- **Per-blob Redis invalidation in `merge`.** New `ResourceService.invalidateBlobs(Collection<String> blobPaths)`: for each
  path it builds the key `<TYPE>:<storage path>` (with the service prefix, as `redisKey` does), skipping a path whose group
  is not a resource type because it can have no cache entry, and then removes the cache maps with `RKeys.delete` and the
  queue members with `RScoredSortedSet.removeAll`, in batches of 50. The paths are the ones that `promoteStagedBlobs`
  copied. `replace` keeps the bucket-wide `invalidate`.
  - Cost: a batch is one command per 50 blobs for each of the two structures, about 40 commands for 1000 blobs, with no
    `SCAN` of the keyspace and no walk of the queue. Blob storage dominates (about 15 ms per copy at parallelism 4 in the
    measured run), so no cap on the number of entities is needed. Rejected: a cap of 16 entities, which would make `merge`
    unusable for a normal archive and protect nothing.
  - Rejected: bucket-wide `invalidate` in `merge`, which drops unsynced writes of unrelated resources.
  - To check when applying: that building the key from the path equals `RedisUtil.redisKey(descriptor, prefix)` for every
    resource type, with a test over all `ResourceTypes`.
- **Keys in `merge`: the target's key wins.** An `encryption_keys/` entry is staged and checked only if its bucket has no key
  when the entry is read (`BlobStorage.exists(bucketLocation + "encryption_keys/cek")`, one call per bucket that has a key
  entry, only with a KMS provider). When the bucket has one, the entry is not staged or checked, and the final line gets a
  warning that names it. The target is read again under the locks before the copy: a staged key is skipped, with the same
  warning, if the bucket has got a key since. `replace` keeps the check on every key and the archive's key overwriting.
  - `invalidateEncryptionKeys` runs only for a bucket whose key was written, and in `merge` that is the buckets whose
    staged key was copied.
  - Rejected: archive key always wins in `merge`, because the target's other entities, which stay, are encrypted with the
    target's key and would become unreadable. Rejected: reject an archive key in `merge`, which would block the common
    "add a model" archive exported with keys.
  - Accepted: a key that appears between the stage and the replace is skipped late, so `copy` `done` can end below `total`
    and `importedBlobs` counts only written blobs.
- **`Bounded parallel storage calls` stays as it is.** It says "wipes the buckets", which is `replace` mode; the scenario
  "Copy waits for the wipe" is about it. Restating the whole requirement for one word is not worth it.

## Risks / Trade-offs

- [The default changes from `replace` to `merge`] → The endpoint is `x-preview`; the docs and the proposal say that a restore or
  clone must pass `mode=replace`, and the review brief lists the callers (`docs/admin-system-import.md`, the README example).
- [A `merge` import leaves entities that the archive no longer holds, so the result is not an exact copy] → That is what the
  mode is for; `replace` is the way to get a copy.
- [Per-blob invalidation misses a Redis key that the bucket-wide pattern would catch] → A test builds the key for every
  resource type and compares it with `RedisUtil.redisKey`.
- [A key placed between stage and replace] → Re-checked under the locks, see Decisions.

## Migration Plan

The default of `mode` is `merge`, which is a behaviour change for a caller that sends no `mode`. Callers that rely on the wipe
send `mode=replace`. No data migration and no setting. To roll back, revert the change; nothing is stored.

## Drift recorded while applying

- The "bucket has a key" test asks `BlobStorage.exists` for the entry's own path (for example
  `platform/encryption_keys/cek`) rather than for a fixed `cek` name, so any entry under `encryption_keys/` is treated the
  same way. Today the only such entry is `cek`.
- A key kept at the stage is not counted in `blobCount`, but it counts as an entry of the archive, so an archive that holds
  only a kept key is accepted and imports nothing instead of answering `400 Archive has no blob entries`.
- `Progress` takes the mode: in `merge` it starts in the `copy` phase, so the first progress line, written before the locks
  are taken, is not a `wipe` line.
- The log lines `System import stage finished` and `System import replace finished` now carry `mode=`; the stage line also
  carries `keptKeys=`.

## Measurement

The replace phase already logs `invalidate=...ms`. A `merge` import of an archive of about 1000 blobs on a real Redis shows
the cost of `invalidateBlobs` in that figure; compare it with the same archive in `replace`. (Deferred: needs an environment
with Redis and a blob store of that size.)
