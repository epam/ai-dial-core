## MODIFIED Requirements

### Requirement: The buckets are replaced
In `replace` mode, after the upload is accepted, Core SHALL, holding the locks of both buckets, delete every blob under the replaced buckets
except those under `encryption_keys/`, then write each archive blob at its path with the bytes, content type, content
encoding, content disposition, content language, cache control and user metadata from its metadata entry. Creation and
modification times are assigned by blob storage. Blobs under `Users/` and `Keys/` are not touched.

#### Scenario: Blob absent from the archive is removed
- **WHEN** `mode=replace`, `platform/models/old` exists and the archive has no entry for it but has other entries under `platform/`
- **THEN** after the system import `platform/models/old` does not exist

#### Scenario: Blobs equal the archive
- **WHEN** an archive holding a gzip-stored model, a small JSON role and an uploaded PNG is imported
- **THEN** each blob has the archive's bytes, and the content type, content encoding, disposition, language,
  cache control and user metadata of its metadata entry, and a gzip blob stays gzip

#### Scenario: Credentials are wiped
- **WHEN** `mode=replace`, `public/credentials/t1` and `platform/credentials/t2` exist and the archive has entries under both buckets
- **THEN** neither exists after the system import

#### Scenario: Encryption keys survive
- **WHEN** `mode=replace`, `platform/encryption_keys/cek` and `public/encryption_keys/cek` exist, and the archive has entries under both
  buckets but no encryption key
- **THEN** both are unchanged after the system import

#### Scenario: Other buckets untouched
- **WHEN** blobs exist under `Users/alice/` and `Keys/`
- **THEN** they are unchanged after the system import

### Requirement: Only the buckets in the archive are replaced
Core SHALL touch a bucket only when the archive has at least one blob entry under it, and in `replace` mode SHALL replace it, so an
archive of a single bucket, such as one exported with `scope`, leaves the other bucket as it is.

#### Scenario: Bucket without entries is left alone
- **WHEN** `mode=replace`, `public/models/old` and `platform/models/old` exist and the archive has entries only under `platform/`
- **THEN** after the system import `public/models/old` still exists and `platform/models/old` does not

### Requirement: Imported content is what Core serves
After a successful system import, Core SHALL serve the imported content for every imported resource, and no write that was
queued before the system import SHALL later replace an imported blob. While it still holds the locks, Core SHALL drop the
queued and cached Redis entries of each replaced bucket in `replace` mode, and of the imported blobs only in `merge` mode,
and SHALL tell every pod to drop its in-memory copy of the key of each bucket whose key it wrote.

#### Scenario: Cached copy does not mask the system import
- **WHEN** `platform/roles/r1` was read before the system import so that Redis holds it, and the archive holds a different
  `platform/roles/r1`
- **THEN** `GET` of that role after the system import returns the imported content

#### Scenario: Queued write does not overwrite the system import
- **WHEN** a write to `platform/roles/r1` is queued and not yet synced when the system import runs, and the sync period passes
  after it
- **THEN** the blob `platform/roles/r1` still has the imported content

#### Scenario: Keys not in the archive
- **WHEN** `mode=replace`, a model existed before the system import in Redis and blob storage and the archive has no such model
- **THEN** it is not served after the system import

#### Scenario: Cached key of a replaced bucket is forgotten
- **WHEN** `mode=replace`, the system import replaces `platform/` and a pod has the key of `platform/` cached, the importing pod
  or another
- **THEN** that pod reads the key again from blob storage on its next use, and the importing pod does so before it
  rebuilds the merged config; a pod keeps its cached key of `public/`

### Requirement: Progress while the replace runs
Once the archive has been received and accepted, Core SHALL answer `200` with `Content-Type: application/x-ndjson` and
write one JSON object per line. While the replace runs it SHALL write a progress line at least every
`adminSystemImport.progressInterval` (15 seconds by default): `{"phase":"wipe","deleted":N}` (`replace` mode only),
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
- **WHEN** `mode=replace` and an archive of 3 blobs is imported over a bucket that held 5 other blobs
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
- **WHEN** `mode=replace` and a bucket is exported and then imported on the same environment
- **THEN** every exported blob has the same bytes and metadata afterwards, except the credentials, which are gone

### Requirement: Encryption keys are unwrapped first
With a KMS provider configured, Core SHALL, before it changes anything, unwrap each `encryption_keys/` entry that it will import
with the KMS of this environment, after decoding its gzip content encoding as it does when it reads a key. A key that the KMS
cannot decrypt, or that is empty, over 64 KiB or encoded in any other way, SHALL be answered `400`; any other KMS error, `500`,
with nothing changed.

#### Scenario: Key stored with gzip
- **WHEN** a KMS provider is configured and the archive's `platform/encryption_keys/cek` has `contentEncoding: gzip`, its
  bytes being the gzip of a key that the KMS unwraps
- **THEN** the KMS is asked to unwrap the decoded key, and the system import imports the blob as stored, still gzip

#### Scenario: Key the KMS cannot unwrap
- **WHEN** a KMS provider is configured and the KMS cannot decrypt the archive's `platform/encryption_keys/cek`, which the
  system import would import
- **THEN** the response is `400`, and no blob in `public/` or `platform/`, including the key, has changed

#### Scenario: Empty key
- **WHEN** a KMS provider is configured and the archive's `public/encryption_keys/cek` is empty
- **THEN** the response is `400`

#### Scenario: KMS unavailable
- **WHEN** a KMS provider is configured and the KMS fails with an error that does not say the key is invalid
- **THEN** the response is `500` and no blob in `public/` or `platform/` has changed

### Requirement: Keys of the archive replace the keys of the buckets
In `replace` mode a key in the archive SHALL replace the key of its bucket, and a replaced bucket whose key is not in the archive
SHALL keep its own key.

#### Scenario: Key the KMS can unwrap
- **WHEN** `mode=replace`, a KMS provider is configured, `platform/encryption_keys/cek` exists with other bytes, and the archive
  holds a `platform/encryption_keys/cek` that the KMS unwraps, with a model under `platform/`
- **THEN** after the system import the blob has the archive's bytes, and `public/encryption_keys/cek` is unchanged

## ADDED Requirements

### Requirement: Import mode
Core SHALL read the query parameter `mode` of `POST /v1/admin/system-import`, without regard to case: `merge`, the default when it
is absent, or `replace`. Any other value, including an empty one, SHALL be answered `400` before the body is read, with
nothing changed. The admin check comes first.

#### Scenario: No mode means merge
- **WHEN** a system import is sent without `mode` over a bucket that holds a blob that the archive does not
- **THEN** that blob still exists afterwards

#### Scenario: Mode is case-insensitive
- **WHEN** a system import is sent with `mode=REPLACE`
- **THEN** it runs as `replace`

#### Scenario: Unknown mode
- **WHEN** a system import is sent with `mode=wipe`
- **THEN** the response is `400`, the connection is closed without reading the body, and no blob has changed

### Requirement: Merge keeps what the archive does not hold
In `merge` mode Core SHALL delete no blob of `public/` or `platform/`, including those under `credentials/`, and SHALL write each
archive blob over the blob at its path with the metadata of its entry. The final line SHALL have `deletedBlobs: 0` and
no `wipe` progress line SHALL be written.

#### Scenario: Absent blob stays
- **WHEN** `mode=merge`, `platform/models/old` exists and the archive holds only `platform/models/m1`
- **THEN** after the system import both exist, `m1` with the archive's bytes

#### Scenario: Credentials stay
- **WHEN** `mode=merge` and `platform/credentials/t2` exists
- **THEN** it is unchanged after the system import

#### Scenario: Existing blob is overwritten
- **WHEN** `mode=merge`, `platform/models/m1` exists with other bytes and metadata, and the archive holds it
- **THEN** it has the archive's bytes and metadata afterwards, and the last line has `deletedBlobs: 0`

### Requirement: Merge drops only the Redis entries of the imported blobs
In `merge` mode Core SHALL drop the queued and the cached Redis entries of the blobs it imported, while it holds the locks, and
SHALL NOT drop those of any other resource of the bucket. A write that is queued for a resource that the archive does not hold
is therefore still synced to blob storage.

#### Scenario: Write to another resource survives
- **WHEN** `mode=merge`, a write to `platform/roles/r2` is queued and not yet synced, and the archive holds only `platform/roles/r1`
- **THEN** after the sync period `platform/roles/r2` has the written content, and `r1` has the imported one

#### Scenario: Cached copy of another resource is kept
- **WHEN** `mode=merge` and Redis holds `platform/roles/r2`, which the archive does not hold
- **THEN** it is still in Redis after the system import

### Requirement: Merge keeps the key of a bucket that has one
In `merge` mode Core SHALL import the archive's `encryption_keys/cek` of a bucket only if that bucket has no key, and SHALL
keep the key of a bucket that has one, without asking the KMS about the archive's key and with a warning in the final line.
An archive without a key SHALL need no key check.

#### Scenario: Bucket has a key
- **WHEN** `mode=merge`, a KMS provider is configured, `platform/encryption_keys/cek` exists, and the archive holds one that the
  KMS cannot decrypt
- **THEN** the system import succeeds, the blob is unchanged, the KMS was not asked about the archive's key, and the last
  line has a warning that names it

#### Scenario: Bucket has no key
- **WHEN** `mode=merge`, a KMS provider is configured, `platform/encryption_keys/cek` does not exist, and the archive holds one that
  the KMS unwraps
- **THEN** the blob has the archive's bytes afterwards and every pod drops its cached key of `platform/`

#### Scenario: Bucket has no key and the KMS cannot unwrap the archive's
- **WHEN** `mode=merge`, a KMS provider is configured, `platform/encryption_keys/cek` does not exist, and the KMS cannot decrypt the
  archive's one
- **THEN** the response is `400` and nothing has changed

#### Scenario: Archive without a key
- **WHEN** `mode=merge` and the archive holds no `encryption_keys/` entry
- **THEN** the KMS is not asked anything and the keys of the buckets are unchanged
