# Proposal

## Why

Every system import wipes each bucket that the archive has entries for, then copies the archive in. An archive with a
single model therefore deletes the whole `platform/` bucket, and a caller who forgets that cannot undo it. A caller who only
wants to add or update some entities has no way to say so.

## What Changes

- New query parameter `mode` on `POST /v1/admin/system-import` with two values, matched without regard to case:
  - `merge` (the default): write the archive's blobs over the bucket and delete nothing, so blobs that the archive does not
    hold, including `credentials/`, stay.
  - `replace`: what the endpoint does today.
- **BREAKING**: a caller that sends no `mode` used to get `replace` and now gets `merge`. Restoring or cloning a bucket
  must pass `mode=replace`. The endpoint is `x-preview`.
- An unknown or empty `mode` is `400`, answered before the body is read.
- In `merge` mode the key of a bucket is never overwritten: the target's `encryption_keys/cek` wins if the bucket has one,
  and the archive's is taken only if the bucket has none. The KMS check runs only on a key that will be taken, and a key
  that is skipped adds a warning to the final status line. `replace` keeps today's key rules.
- In `merge` mode Core drops from Redis only the queued and cached entries of the blobs it imported, not of the whole
  bucket, so an unsynced write to a resource that the archive does not hold is not lost. `replace` still drops the whole
  bucket.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `system-import`: new requirements "Import mode", "Merge keeps what the archive does not hold", "Merge drops only the Redis
  entries of the imported blobs" and "Merge keeps the key of a bucket that has one"; the requirements that describe the
  wipe, the keys, the progress lines and the final status line now say that they are about `replace` mode.

## Impact

- Issue [#2204](https://github.com/epam/ai-dial-core/issues/2204), pull request #2213.
- Code: `AdminSystemImportController`, `AdminSystemImportService`, `ResourceService` (a per-blob invalidation next to
  `invalidate`). Docs: `docs/admin-system-import.md`.
- Out of scope: a flag to import keys that the KMS cannot unwrap (postponed), modes per resource type, renewing the bucket
  locks (#2169), the GCS 32-part compose limit.
