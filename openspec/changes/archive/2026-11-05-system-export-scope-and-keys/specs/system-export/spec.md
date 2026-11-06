# Spec Delta

## ADDED Requirements

### Requirement: Scope parameter
`POST /v1/admin/system-export` SHALL accept the optional query parameter `scope`, a comma-separated list of the buckets
to export (`public`, `platform`). Names are compared case-insensitively and surrounding spaces are ignored. The parameter MAY be
repeated, and the lists are combined. Core SHALL export exactly the listed buckets, each once however often it is named. Without `scope`, Core SHALL export every
exportable bucket.

#### Scenario: No scope
- **WHEN** an admin exports without `scope` and blobs exist in `public/` and `platform/`
- **THEN** the archive holds the blobs of both buckets

#### Scenario: One bucket
- **WHEN** an admin exports with `scope=platform`
- **THEN** the archive holds the manifest and the blobs of `platform/`, and no entry under `public/`

#### Scenario: Case, spaces and repeats
- **WHEN** an admin exports with `scope= PUBLIC , public`
- **THEN** the archive holds the blobs of `public/` once each, and none of `platform/`

#### Scenario: Order follows the buckets, not the request
- **WHEN** an admin exports with `scope=platform,public`
- **THEN** the entries of `public/` come before those of `platform/`, as without `scope`

#### Scenario: Repeated parameter
- **WHEN** an admin exports with `scope=public&scope=platform`
- **THEN** the archive is the same as with `scope=public,platform`

### Requirement: Invalid scope
Core SHALL answer `400` when `scope`, or any repetition of it, is empty, has an empty element, or names a bucket that
is not exportable (for example `org` or `all`). It SHALL send no archive and no `Content-Disposition`, and take no bucket lock. A caller that
is not an admin SHALL get `403` whatever `scope` holds.

#### Scenario: Unknown, empty and blank values
- **WHEN** an admin exports with `scope=org`, `scope=all`, `scope=`, `scope=public,`, `scope=,platform`,
  `scope=public&scope=` or `scope=public&scope=org`
- **THEN** Core answers `400` with no `Content-Disposition` and no archive bytes, and the next valid export answers `200`

#### Scenario: Invalid scope from a non-admin
- **WHEN** a user without the admin role exports with `scope=org`
- **THEN** Core answers `403`

### Requirement: Encryption keys with KMS
When a KMS provider is configured (`toolsets.security.kms.provider` is `aws`, `azure` or `gcp`), Core SHALL export the
`encryption_keys` blobs of the selected buckets like any other blob, except one that cannot be a wrapped key: no content
encoding and no more than `toolsets.security.encryption.keySize` / 8 bytes (32 by default), empty included. Core SHALL skip
it and log a warning with its path. Without a KMS provider Core SHALL NOT export them, because the stored key is plaintext.

#### Scenario: KMS on
- **WHEN** a KMS provider (`aws`, `azure` or `gcp`) is configured, `public/encryption_keys/cek` and
  `platform/encryption_keys/cek` hold 60 bytes each, `platform/credentials/cred1` exists and an admin exports
- **THEN** the archive holds `public/encryption_keys/cek` and `platform/encryption_keys/cek` with their metadata entries,
  and no entry of `platform/credentials/cred1`

#### Scenario: KMS on with one bucket
- **WHEN** a KMS provider is configured, both `cek` blobs exist and an admin exports with `scope=platform`
- **THEN** the archive holds `platform/encryption_keys/cek` and not `public/encryption_keys/cek`

#### Scenario: Key as short as a plaintext key
- **WHEN** a KMS provider is configured, `public/encryption_keys/cek` has no content encoding and 32 bytes (the default
  key size), `public/encryption_keys/longer` has 33 bytes and an admin exports
- **THEN** the archive holds `public/encryption_keys/longer` and not `public/encryption_keys/cek`, and Core logs a warning
  with the path of the skipped key

#### Scenario: Empty placeholder key
- **WHEN** a KMS provider is configured, `platform/encryption_keys/cek` is empty and an admin exports
- **THEN** the archive has no entry for it

#### Scenario: Encoded key
- **WHEN** a KMS provider is configured and `platform/encryption_keys/encoded` is stored with `contentEncoding: gzip` and
  5 bytes
- **THEN** the archive holds it

#### Scenario: KMS off
- **WHEN** the provider is `unencrypted` or no provider is set, both `cek` blobs exist and an admin exports
- **THEN** the archive holds neither of them

## MODIFIED Requirements

### Requirement: Archive layout
The archive SHALL hold the entry `.dial-system-export/manifest.json` first. Then, for every exported blob in the selected
buckets, `public/` before `platform/`, it SHALL hold the entry `.dial-system-export/metadata/<path>` immediately followed
by the entry `<path>`, where `<path>` is the blob's path in blob storage, for example `public/files/<bucket>/report.pdf`.
Entry names SHALL be the paths as UTF-8, never escaped or rewritten.

#### Scenario: Manifest first, metadata before content
- **WHEN** `public/files/user1/a.txt` and `platform/misc/b.bin` exist and an admin exports
- **THEN** the first entry is `.dial-system-export/manifest.json`, and the entry directly before `public/files/user1/a.txt`
  is `.dial-system-export/metadata/public/files/user1/a.txt`, and the same holds for `platform/misc/b.bin`

#### Scenario: Names with spaces and non-Latin letters
- **WHEN** the blob `public/files/user1/my folder/файл ü.txt` exists
- **THEN** the archive has the entries `public/files/user1/my folder/файл ü.txt` and
  `.dial-system-export/metadata/public/files/user1/my folder/файл ü.txt` under exactly those names

### Requirement: What is left out
Core SHALL NOT export a blob whose resource type, the path segment right after `public/` or `platform/`, is `credentials`,
or is `encryption_keys` while no KMS provider is configured. A blob with those names deeper in its path SHALL be exported.
Folders in blob storage have no entry of their own; the folder markers `.dial-folder` and `.dial-resource` are blobs and
SHALL be exported with their metadata. A blob that is deleted after it was listed and before it was read SHALL be skipped.

#### Scenario: Keys and credentials
- **WHEN** no KMS provider is configured and `public/encryption_keys/key1`, `public/credentials/cred1`,
  `platform/encryption_keys/key2`, `platform/credentials/cred2`, `public/files/owner/encryption_keys/notes.txt`,
  `public/files/owner/credentials/notes.txt` and `public/models/m1` exist
- **THEN** the archive holds content entries for exactly `public/files/owner/credentials/notes.txt`,
  `public/files/owner/encryption_keys/notes.txt` and `public/models/m1`

#### Scenario: Credentials with KMS
- **WHEN** a KMS provider is configured and `public/credentials/cred1` and `platform/credentials/cred2` exist
- **THEN** the archive holds neither of them

#### Scenario: Markers
- **WHEN** `public/skills/folder/.dial-folder` and `public/skills/folder/skill/.dial-resource` exist
- **THEN** the archive holds each of them, with its metadata entry

#### Scenario: Folder listing entries and a blob deleted meanwhile
- **WHEN** blob storage lists a folder `public/files/user1/dir` and the blobs `kept.txt` and `gone.txt` under
  `public/files/user1/`, and `gone.txt` is deleted before the export reads it
- **THEN** the archive holds the manifest, and the metadata and content entries of `kept.txt` only

### Requirement: Buckets are locked while exporting
Core SHALL hold the bucket lock of each selected bucket from before the first blob is read until the export ends, however
it ends, and release them at the end. A write to a selected bucket that needs a bucket lock SHALL wait for the export. Core
SHALL answer `409` and send no archive and no `Content-Disposition` when it cannot take every lock because a writer or
another export holds one, and SHALL release the locks it did take.

#### Scenario: Writer waits
- **WHEN** an export is running and another task asks for the lock of `public/`
- **THEN** the task gets it only after the export has ended

#### Scenario: Second export rejected
- **WHEN** an export is running and an admin sends `POST /v1/admin/system-export`
- **THEN** the second request is answered `409` with no archive bytes, and once the first export has ended a new export
  succeeds

#### Scenario: One lock held elsewhere
- **WHEN** the lock of `platform/` is held and an admin exports
- **THEN** Core answers `409` with no `Content-Disposition`, writes nothing, and the lock of `public/` is free
  afterwards; once the other lock is released an export answers `200`

#### Scenario: A bucket outside the scope is not locked
- **WHEN** the lock of `platform/` is held and an admin exports with `scope=public`, or an export with `scope=public` is
  running and another task asks for the lock of `platform/`
- **THEN** the export answers `200`, or the task gets the lock at once

### Requirement: The archive reflects blob storage
Core SHALL read the archive from blob storage, not from the resource cache or the write queue. A resource written within
the resource sync delay (`resources.syncDelay`) may therefore be in the archive with its previous content or, if it is
new, as the empty blob Core creates for listing until the first sync.

#### Scenario: Empty placeholder of a new resource
- **WHEN** the blob of a resource is empty because the resource has not reached blob storage yet and an admin exports
- **THEN** the archive holds an empty entry for it, with its metadata entry
