## MODIFIED Requirements

### Requirement: Encryption keys and credentials are not importable
Core SHALL answer `400` if an entry is a blob under `credentials/` of either bucket, compared without regard to case. It
SHALL answer `400` for a blob under `encryption_keys/` of either bucket when no KMS provider (`aws`, `azure` or `gcp`) is
configured; with one, such an entry is accepted, as the requirement "Encryption keys from the archive" specifies. The
match is on the resource type position only: `public/files/alice/encryption_keys/notes.txt` is an ordinary file and SHALL
be accepted.

#### Scenario: CEK in the archive
- **WHEN** no KMS provider is configured and an entry is named `platform/encryption_keys/cek`
- **THEN** the response is `400` and the CEK blob is unchanged

#### Scenario: Credentials in the archive
- **WHEN** an entry is named `public/credentials/x`, with or without a KMS provider
- **THEN** the response is `400`

#### Scenario: Same words deeper in the path
- **WHEN** an entry is named `public/files/alice/encryption_keys/notes.txt` with its metadata entry
- **THEN** the archive is accepted and the file is imported

### Requirement: The buckets are replaced
After the upload is accepted, Core SHALL, holding the locks of both buckets, delete every blob under the replaced buckets
except those under `encryption_keys/`, then write each archive blob at its path with the bytes, content type, content
encoding, content disposition, content language, cache control and user metadata from its metadata entry. Creation and
modification times are assigned by blob storage. Blobs under `Users/` and `Keys/` are not touched.

#### Scenario: Blob absent from the archive is removed
- **WHEN** `platform/models/old` exists and the archive has no entry for it but has other entries under `platform/`
- **THEN** after the system import `platform/models/old` does not exist

#### Scenario: Blobs equal the archive
- **WHEN** an archive holding a gzip-stored model, a small JSON role and an uploaded PNG is imported
- **THEN** each blob has the archive's bytes, and the content type, content encoding, disposition, language,
  cache control and user metadata of its metadata entry, and a gzip blob stays gzip

#### Scenario: Credentials are wiped
- **WHEN** `public/credentials/t1` and `platform/credentials/t2` exist and the archive has entries under both buckets
- **THEN** neither exists after the system import

#### Scenario: Encryption keys survive
- **WHEN** `platform/encryption_keys/cek` and `public/encryption_keys/cek` exist, and the archive has entries under both
  buckets but no encryption key
- **THEN** both are unchanged after the system import

#### Scenario: Other buckets untouched
- **WHEN** blobs exist under `Users/alice/` and `Keys/`
- **THEN** they are unchanged after the system import

### Requirement: Imported content is what Core serves
After a successful system import, Core SHALL serve the imported content for every resource under each replaced bucket, and no
write that was queued before the system import SHALL later replace an imported blob. Core SHALL drop the queued and the
cached Redis entries of each replaced bucket, including the cached copy of `encryption_keys/cek`, and the in-memory copy of
its unwrapped key on the importing pod, while it still holds the locks.

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

#### Scenario: Cached key of a replaced bucket is forgotten
- **WHEN** the system import replaces `platform/` and the importing pod has the key of `platform/` cached
- **THEN** the importing pod reads the key again from blob storage before it rebuilds the merged config, and keeps its
  cached key of `public/`

## ADDED Requirements

### Requirement: Only the buckets in the archive are replaced
Core SHALL replace a bucket only when the archive has at least one blob entry under it, and SHALL NOT touch a bucket
without one, so an archive of a single bucket, such as one exported with `scope`, leaves the other bucket as it is.

#### Scenario: Bucket without entries is left alone
- **WHEN** `public/models/old` and `platform/models/old` exist and the archive has entries only under `platform/`
- **THEN** after the system import `public/models/old` still exists and `platform/models/old` does not

### Requirement: Encryption keys are unwrapped first
With a KMS provider configured, Core SHALL, before it changes anything, unwrap each `encryption_keys/` entry with the KMS of
this environment, after decoding its gzip content encoding as it does when it reads a key. A key that the KMS cannot
decrypt, or that is empty, over 64 KiB or encoded in any other way, SHALL be answered `400`; any other KMS error, `500`,
with nothing changed.

#### Scenario: Key stored with gzip
- **WHEN** a KMS provider is configured and the archive's `platform/encryption_keys/cek` has `contentEncoding: gzip`, its
  bytes being the gzip of a key that the KMS unwraps
- **THEN** the KMS is asked to unwrap the decoded key, and the system import imports the blob as stored, still gzip

#### Scenario: Key the KMS cannot unwrap
- **WHEN** a KMS provider is configured and the KMS cannot decrypt the archive's `platform/encryption_keys/cek`
- **THEN** the response is `400`, and no blob in `public/` or `platform/`, including the key, has changed

#### Scenario: Empty key
- **WHEN** a KMS provider is configured and the archive's `public/encryption_keys/cek` is empty
- **THEN** the response is `400`

#### Scenario: KMS unavailable
- **WHEN** a KMS provider is configured and the KMS fails with an error that does not say the key is invalid
- **THEN** the response is `500` and no blob in `public/` or `platform/` has changed

### Requirement: Keys of the archive replace the keys of the buckets
A key in the archive SHALL replace the key of its bucket, and a replaced bucket whose key is not in the archive SHALL keep
its own key.

#### Scenario: Key the KMS can unwrap
- **WHEN** a KMS provider is configured, `platform/encryption_keys/cek` exists with other bytes, and the archive holds a
  `platform/encryption_keys/cek` that the KMS unwraps, with a model under `platform/`
- **THEN** after the system import the blob has the archive's bytes, and `public/encryption_keys/cek` is unchanged
