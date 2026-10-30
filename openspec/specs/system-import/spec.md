# system-import Specification

## Purpose
What `POST /v1/admin/system-import` accepts and rejects, and what it does to the `public` and `platform` buckets, to the
Redis cache of their resources and to the merged config, when given the archive produced by `POST /v1/admin/system-export`.
The layout of that archive (manifest, metadata entries, what is left out) is defined by the `system-export` capability and
is not repeated here.

## Requirements

### Requirement: Admin only, answered before the body is read
Core SHALL accept `POST /v1/admin/system-import` only from an admin, the same check as `/v1/admin/apply`. A caller that is not
an admin gets `403` before any of the request body is read, and the connection is closed so the rest of the body is not
consumed.

#### Scenario: Non-admin is refused
- **WHEN** a non-admin sends `POST /v1/admin/system-import` with a valid archive
- **THEN** the response is `403`, the connection is closed, and no blob in `public/`, `platform/` or `.dial-system-import-tmp/`
  has changed

### Requirement: One system import at a time
Core SHALL run one system import at a time across all pods. A system import that starts while another holds the system import guard is
answered with `409` and the body is not read. The guard is released when the system import ends, whether it succeeded or failed,
and expires on its own after the default lock period (5 minutes) if the pod dies.

#### Scenario: Second system import is refused
- **WHEN** a system import is streaming its upload and a second admin request `POST /v1/admin/system-import` arrives
- **THEN** the second response is `409` and the first system import is not disturbed

#### Scenario: Guard is released after a failure
- **WHEN** a system import is rejected because its archive is invalid
- **THEN** the next system import is admitted without waiting for the lock period

### Requirement: Archive manifest checked first
Core SHALL read `.dial-system-export/manifest.json` as the first entry and answer `400` if it is missing, is not first, is not
valid JSON, or has a `formatVersion` other than 1.

#### Scenario: Manifest is not the first entry
- **WHEN** the archive's first entry is a blob and the manifest comes later
- **THEN** the response is `400` and no blob in `public/` or `platform/` has changed

#### Scenario: Unsupported format version
- **WHEN** the manifest says `formatVersion: 2`
- **THEN** the response is `400` naming the supported version

### Requirement: Archive not newer than the running Core
Core SHALL answer `400` when the manifest's `coreVersion` is newer than the running Core's version, comparing the leading
dotted numbers (`0.50.0-SNAPSHOT` counts as `0.50.0`). A build whose version is `0.0.0` or has no leading number, such
as `undefined`, counts as newer than every release: it accepts any archive, and an archive from such a build is accepted
only by such a build.

#### Scenario: Archive from a newer release
- **WHEN** the manifest `coreVersion` is `0.51.0` and the running Core is `0.50.0`
- **THEN** the response is `400` and no blob has changed

#### Scenario: Archive from the same or an older release
- **WHEN** the manifest `coreVersion` is `0.50.0`, or `0.49.2`, and the running Core is `0.50.0`
- **THEN** the archive is accepted

#### Scenario: Development build accepts a release archive
- **WHEN** the running Core is `0.0.0` and the manifest `coreVersion` is `0.50.0`
- **THEN** the archive is accepted

#### Scenario: Development archive into a release
- **WHEN** the manifest `coreVersion` is `0.0.0` and the running Core is `0.50.0`
- **THEN** the response is `400` and no blob has changed

#### Scenario: Development archive into a development build
- **WHEN** the manifest `coreVersion` is `0.0.0` and the running Core is `0.0.0`
- **THEN** the archive is accepted

#### Scenario: Unreadable version counts as a development build
- **WHEN** the running Core's version is `undefined` and the manifest `coreVersion` is `0.50.0`
- **THEN** the archive is accepted, and an archive whose `coreVersion` is `undefined` is rejected by a Core running `0.50.0`

### Requirement: Entry names confined to the two buckets
Core SHALL answer `400` and change nothing if any entry name is empty, contains a backslash, a control character or NUL,
starts with `/`, has an empty, `.` or `..` segment, is longer than 1024 bytes in UTF-8, or does not start with
exactly `public/` or `platform/`. Names under `.dial-system-export/` are archive metadata, not blobs. Names are rejected, not
rewritten.

#### Scenario: Path traversal
- **WHEN** an entry is named `public/../Users/alice/files/x`
- **THEN** the response is `400` and no blob outside the staging prefix has been written

#### Scenario: Other bucket
- **WHEN** an entry is named `Users/alice/files/x`
- **THEN** the response is `400`

#### Scenario: Backslash
- **WHEN** an entry is named `public\files\x`
- **THEN** the response is `400`

### Requirement: Encryption keys and credentials are not importable
Core SHALL answer `400` if an entry is a blob under `encryption_keys/` or `credentials/` of either bucket, compared
without regard to case. The match is on the resource type position only: `public/files/alice/encryption_keys/notes.txt`
is an ordinary file and SHALL be accepted.

#### Scenario: CEK in the archive
- **WHEN** an entry is named `platform/encryption_keys/cek`
- **THEN** the response is `400` and the CEK blob is unchanged

#### Scenario: Credentials in the archive
- **WHEN** an entry is named `public/credentials/x`
- **THEN** the response is `400`

#### Scenario: Same words deeper in the path
- **WHEN** an entry is named `public/files/alice/encryption_keys/notes.txt` with its metadata entry
- **THEN** the archive is accepted and the file is imported

### Requirement: Every blob has its metadata entry
Core SHALL require each blob entry to be immediately preceded by its metadata entry
`.dial-system-export/metadata/<blob path>`, and each metadata entry to be immediately followed by its blob. It SHALL answer `400`
for a blob without metadata, a metadata entry without its blob, a duplicate entry name, or a metadata entry that is not
valid JSON, is larger than 64 KiB, or has a control character in a string value.

#### Scenario: Blob without metadata
- **WHEN** an entry `platform/models/m1` is not preceded by `.dial-system-export/metadata/platform/models/m1`
- **THEN** the response is `400` and no blob has changed

#### Scenario: Metadata without a blob
- **WHEN** the last entry is `.dial-system-export/metadata/public/files/x` with no blob after it
- **THEN** the response is `400`

#### Scenario: Duplicate entry
- **WHEN** two entries have the name `platform/roles/r1`
- **THEN** the response is `400`

### Requirement: A usable archive
Core SHALL answer `400` and change nothing when the body is not a ZIP, is corrupt (bad entry, failed checksum, an entry
whose size does not match its header), has no blob entry, or ends before the central directory. A request whose connection
fails before the whole body is received SHALL be treated the same way: nothing in `public/` or `platform/` changes.

#### Scenario: Not a ZIP
- **WHEN** the body is `hello`
- **THEN** the response is `400`

#### Scenario: Only the manifest
- **WHEN** the archive holds the manifest and no blob
- **THEN** the response is `400` and the buckets are unchanged

#### Scenario: Entry whose size does not match
- **WHEN** an entry's header declares 10 bytes but it inflates to more
- **THEN** the response is `400` and the buckets are unchanged

#### Scenario: Truncated upload
- **WHEN** the client closes the connection after half of a valid archive
- **THEN** no blob in `public/` or `platform/` has changed and the guard is released

### Requirement: Stalled upload
Core SHALL fail a system import with `408` when it receives no bytes of the body for `adminSystemImport.stallTimeout` (60 seconds by
default), leave the buckets unchanged and release the guard. The time the system import itself spends writing is not counted, and
there is no limit on the total upload time: a client that keeps sending is not cut off.

#### Scenario: Client stops sending
- **WHEN** `adminSystemImport.stallTimeout` is 1 second and the client has sent half of the archive and then sends nothing
- **THEN** the response is `408`, the buckets are unchanged and the guard is released

#### Scenario: Slow but alive upload
- **WHEN** `adminSystemImport.stallTimeout` is 1 second and the client sends the archive in pieces with pauses of 300 ms, so
  that the whole upload takes longer than 1 second
- **THEN** the archive is accepted

### Requirement: Nothing destroyed before the archive is complete
Core SHALL write the upload only to the staging prefix `.dial-system-import-tmp/{timestamp}/{importId}/` until the whole
archive has been received and every check above has passed. Until then no blob in `public/` or `platform/`, no Redis
entry of those buckets and no merged config is changed.

#### Scenario: Rejected archive leaves no trace in the buckets
- **WHEN** an archive is rejected after half of its entries were staged
- **THEN** the buckets, their Redis entries and the merged config are as before the request

#### Scenario: Staged entries are outside the buckets
- **WHEN** an upload is in progress
- **THEN** no listing of `public/` or `platform/` shows a staged entry

### Requirement: The buckets are replaced
After the upload is accepted, Core SHALL, holding the locks of both buckets, delete every blob under `public/` and
`platform/` except those under `encryption_keys/`, then write each archive blob at its path with the bytes, content type,
content encoding, content disposition, content language, cache control and user metadata from its metadata entry.
Creation and modification times are assigned by blob storage. Blobs under `Users/` and `Keys/` are not touched.

#### Scenario: Blob absent from the archive is removed
- **WHEN** `platform/models/old` exists and the archive has no entry for it
- **THEN** after the system import `platform/models/old` does not exist

#### Scenario: Blobs equal the archive
- **WHEN** an archive holding a gzip-stored model, a small JSON role and an uploaded PNG is imported
- **THEN** each blob has the archive's bytes, and the content type, content encoding, disposition, language,
  cache control and user metadata of its metadata entry, and a gzip blob stays gzip

#### Scenario: Credentials are wiped
- **WHEN** `public/credentials/t1` and `platform/credentials/t2` exist before the system import
- **THEN** neither exists after it

#### Scenario: Encryption keys survive
- **WHEN** `platform/encryption_keys/cek` and `public/encryption_keys/cek` exist before the system import
- **THEN** both are unchanged after it

#### Scenario: Other buckets untouched
- **WHEN** blobs exist under `Users/alice/` and `Keys/`
- **THEN** they are unchanged after the system import

### Requirement: Folder markers are restored
The replace SHALL treat the folder marker blobs `.dial-folder` and `.dial-resource` like any other blob, so that Skills
folders come back as they were exported. Listing entries that are not blobs (folders in blob storage) SHALL be neither
deleted nor written.

#### Scenario: Folder markers are restored
- **WHEN** an archive holds `public/skills/folder/.dial-folder` and `public/skills/folder/skill/.dial-resource` with their
  metadata entries
- **THEN** both blobs exist after the system import with the archive's bytes and metadata

#### Scenario: Folder listing entries are left alone
- **WHEN** blob storage lists a folder `public/files/user1/dir` next to the blobs of `public/files/user1/`
- **THEN** the replace neither deletes nor writes an entry for that folder

### Requirement: Imported content is what Core serves
After a successful system import, Core SHALL serve the imported content for every resource under both buckets, and no write
that was queued before the system import SHALL later replace an imported blob. Core SHALL drop the queued and the cached Redis
entries of both buckets, including the cached copy of `encryption_keys/cek`, while it still holds the locks.

#### Scenario: Cached copy does not mask the system import
- **WHEN** `platform/roles/r1` was read before the system import so that Redis holds it, and the archive holds a different
  `platform/roles/r1`
- **THEN** `GET` of that role after the system import returns the imported content

#### Scenario: Queued write does not overwrite the system import
- **WHEN** a write to `platform/roles/r1` is queued and not yet synced when the system import runs, and the sync period passes
  after it
- **THEN** the blob `platform/roles/r1` still has the imported content

#### Scenario: Keys not in the archive
- **WHEN** a model existed before the system import in Redis and blob storage and the archive has no such model
- **THEN** it is not served after the system import

### Requirement: Merged config rebuilt on the importing pod
After the replace, Core SHALL rebuild the merged config on the pod that ran the system import, while the bucket locks are held,
so that the models, roles, applications and other managed entities of the archive are in effect when the response ends.
If the rebuild fails, the blobs stay replaced and the last line carries a warning that this pod's config may be stale until
the next periodic rebuild. Other pods rebuild on their own schedule (`config.reload`).

#### Scenario: Imported model is listed
- **WHEN** the archive holds `platform/models/m1` that did not exist and the system import succeeds
- **THEN** the next `GET /openai/models` on that pod lists `m1`

#### Scenario: Rebuild failure is reported
- **WHEN** the rebuild throws after the blobs were replaced
- **THEN** the response is still `200`, its last line has a warning, and the blobs are the imported ones

### Requirement: Replace holds both bucket locks
Core SHALL hold the Redis locks of `public/` and `platform/` for the whole replace, each with the default lock period
(5 minutes) from the moment it is taken, and Core does not renew them. Admin writes and config rebuilds wait for
it, and a `system-export` started meanwhile is answered `409` as that capability specifies for a bucket lock held by a
writer. A replace that runs longer than the lock period logs a warning and keeps going: it does not stop half way.

#### Scenario: Admin write waits
- **WHEN** `/v1/admin/apply` is called while a system import is replacing the buckets
- **THEN** the apply does not start until the system import has released the locks

#### Scenario: Export during a system import
- **WHEN** `POST /v1/admin/system-export` is called while a system import is replacing the buckets
- **THEN** the export is answered `409`

#### Scenario: Slow replace is not aborted
- **WHEN** the replace has run for longer than the lock period
- **THEN** it completes and Core logs a warning

### Requirement: Progress while the replace runs
Once the archive has been received and accepted, Core SHALL answer `200` with `Content-Type: application/x-ndjson` and
write one JSON object per line. While the replace runs it SHALL write a progress line at least every
`adminSystemImport.progressInterval` (15 seconds by default): `{"phase":"wipe","deleted":N}`,
`{"phase":"copy","done":N,"total":M}`, `{"phase":"invalidate"}` or `{"phase":"rebuild"}`, in that order.

#### Scenario: Progress lines
- **WHEN** the replace takes longer than `adminSystemImport.progressInterval`
- **THEN** the client receives a progress line before the last line

#### Scenario: Copy progress
- **WHEN** an archive of 3 blobs is imported and `adminSystemImport.progressInterval` is short
- **THEN** every `copy` line has `total: 3` and `done` between 0 and 3, and the phases never go backwards

#### Scenario: Client goes away
- **WHEN** the client disconnects after the `200` was sent
- **THEN** the replace still runs to the end and Core logs its outcome

### Requirement: Final status line
When the replace has finished, Core SHALL write a last line `{"status":"success","importedBlobs":N,"deletedBlobs":N,
"warnings":[...]}` and end the response. If the replace fails after the `200` was sent, Core SHALL reset the connection
and not write that line, so a failure never looks like a success.

#### Scenario: Success
- **WHEN** an archive of 3 blobs is imported over a bucket that held 5 other blobs
- **THEN** the last line is a JSON object with `status: "success"`, `importedBlobs: 3`, `deletedBlobs: 5` and an empty
  `warnings`

#### Scenario: Replace fails
- **WHEN** a blob delete fails during the replace
- **THEN** the client sees a transport error, no last line is written, and Core logs the failure

### Requirement: Repeatable
Importing the same archive again SHALL leave `public/` and `platform/` in the same state, so an operator can run it again
after a failure in the replace.

#### Scenario: Run twice
- **WHEN** the same archive is imported twice in a row
- **THEN** the blobs and their metadata after the second system import equal those after the first

#### Scenario: Export then system import
- **WHEN** a bucket is exported and then imported on the same environment
- **THEN** every exported blob has the same bytes and metadata afterwards, except the credentials, which are gone

### Requirement: Staging is removed
Core SHALL delete the staging prefix of a system import after it has sent its response, whether the system import succeeded or was
rejected, without delaying the response. Core SHALL also delete, once every 24 hours, each staging folder under
`.dial-system-import-tmp/` whose timestamp is more than 24 hours old, to reclaim what a pod that died left behind. The
existing cleanup of `.dial-tmp/` SHALL behave as before.

#### Scenario: Staging is gone after success
- **WHEN** a system import succeeds
- **THEN** shortly after the response no blob exists under its `.dial-system-import-tmp/` folder

#### Scenario: Staging is gone after a rejection
- **WHEN** an archive is rejected as invalid
- **THEN** shortly after the response no blob exists under its `.dial-system-import-tmp/` folder

#### Scenario: Old staging is reclaimed
- **WHEN** a folder `.dial-system-import-tmp/{timestamp more than 24 hours ago}/{id}/` holds blobs and the cleanup runs
- **THEN** those blobs are deleted and a folder from the last hour is kept

#### Scenario: Temp folder cleanup unchanged
- **WHEN** the `.dial-tmp/` cleanup runs with an expired and a recent upload folder
- **THEN** only the expired one is deleted, as before
