# system-export Specification

## Purpose
What `POST /v1/admin/system-export` (a preview endpoint, `x-preview`) answers and what the ZIP archive it streams holds:
the `public` and `platform` buckets of blob storage, for backups and migrations. It covers who may call it, the layout
of the archive and its manifest, what is left out, how the export excludes writers while it reads, and how it ends
when something goes wrong. How an archive is brought back (system import) and the `.dial-tmp` or staging prefixes
are other concerns.

## Requirements

### Requirement: Admin only
Core SHALL answer `POST /v1/admin/system-export` with `403` and no archive unless the caller is an admin. A request
authenticated only by an API key gets `403` too.

#### Scenario: Non-admin caller
- **WHEN** a user without the admin role, or a client that sends only an `Api-key`, sends `POST /v1/admin/system-export`
- **THEN** Core answers `403` and sends no archive

#### Scenario: Admin caller
- **WHEN** an admin sends `POST /v1/admin/system-export`
- **THEN** Core answers `200` and streams the archive

### Requirement: Response of an archive
Core SHALL answer a started export with `200`, `Content-Type: application/zip` and
`Content-Disposition: attachment; filename="dial-system-export-<yyyyMMdd'T'HHmmss'Z'>.zip"`, where the time is the
export's start time in UTC, the same instant as `createdAt` in the manifest. The body SHALL be streamed while the archive is
built.

#### Scenario: Headers of the archive
- **WHEN** an admin exports and the manifest has `createdAt: 2026-01-02T03:04:05Z`
- **THEN** the response is `200` with `Content-Type: application/zip` and
  `Content-Disposition: attachment; filename="dial-system-export-20260102T030405Z.zip"`

### Requirement: Archive layout
The archive SHALL hold the entry `.dial-system-export/manifest.json` first. Then, for every exported blob in `public/` and
then `platform/`, it SHALL hold the entry `.dial-system-export/metadata/<path>` immediately followed by the entry `<path>`,
where `<path>` is the blob's path in blob storage, for example `public/files/<bucket>/report.pdf`. Entry names SHALL be the
paths as UTF-8, never escaped or rewritten.

#### Scenario: Manifest first, metadata before content
- **WHEN** `public/files/user1/a.txt` and `platform/misc/b.bin` exist and an admin exports
- **THEN** the first entry is `.dial-system-export/manifest.json`, and the entry directly before `public/files/user1/a.txt`
  is `.dial-system-export/metadata/public/files/user1/a.txt`, and the same holds for `platform/misc/b.bin`

#### Scenario: Names with spaces and non-Latin letters
- **WHEN** the blob `public/files/user1/my folder/файл ü.txt` exists
- **THEN** the archive has the entries `public/files/user1/my folder/файл ü.txt` and
  `.dial-system-export/metadata/public/files/user1/my folder/файл ü.txt` under exactly those names

### Requirement: Every blob is exported
Core SHALL export every blob, however many there are, so that a bucket with more blobs than one listing page (1000), or
an archive with more than 65535 entries, is complete and can be read by a standard ZIP reader.

#### Scenario: More blobs than one listing page
- **WHEN** `public/` holds 1001 blobs
- **THEN** the archive holds a metadata entry and a content entry for each of the 1001

#### Scenario: More than 65535 entries
- **WHEN** the buckets hold 33000 blobs, which makes 66001 entries
- **THEN** the archive can be read by a standard ZIP reader and holds all entries

### Requirement: Content exactly as stored
The entry `<path>` SHALL hold the blob's bytes exactly as stored, so a blob stored with `contentEncoding: gzip` stays
gzip-compressed in the archive. Core SHALL NOT compress such an entry again in the ZIP; a blob with no content encoding or
with `identity` SHALL be compressed in the ZIP.

#### Scenario: Gzip blob
- **WHEN** a blob is stored with `contentEncoding: gzip`
- **THEN** the entry's bytes equal the stored bytes and the entry is stored without ZIP compression

#### Scenario: Plain and identity blobs
- **WHEN** a blob has no content encoding, or `contentEncoding: identity`
- **THEN** its entry is deflated in the ZIP

### Requirement: Blob metadata entry
The entry `.dial-system-export/metadata/<path>` SHALL be a JSON object with the blob's `contentType`, `contentEncoding`,
`contentDisposition`, `contentLanguage`, `cacheControl`, `creationDate`, `lastModified` and `userMetadata`. A field the blob
has no value for SHALL be omitted. The two dates SHALL be ISO-8601 UTC instants as blob storage reports them. They are for
reference: blob storage assigns them when a blob is written, so they cannot be restored.

#### Scenario: Content headers and dates
- **WHEN** a blob has content type `text/plain`, disposition `attachment; filename=a.txt`, language `en`, cache control
  `no-cache`, creation date `2025-03-04T05:06:07.123Z` and last modified `2025-04-05T06:07:08.456Z`
- **THEN** its metadata entry carries each of those values, with the dates as `2025-03-04T05:06:07.123Z` and
  `2025-04-05T06:07:08.456Z`

#### Scenario: User metadata and absent encoding
- **WHEN** a blob has user metadata `{"etag": "abc", "author": "user1"}` and no content encoding
- **THEN** its metadata entry has `userMetadata` equal to that object and has no `contentEncoding` field

#### Scenario: Gzip encoding
- **WHEN** a blob is stored with `contentEncoding: gzip`, `contentType: application/json` and user metadata `etag: abc`
- **THEN** its metadata entry has `contentEncoding: gzip`, `contentType: application/json` and `userMetadata.etag: abc`

### Requirement: What is left out
Core SHALL NOT export a blob whose resource type, the path segment right after `public/` or `platform/`, is
`encryption_keys` or `credentials`. A blob with those names deeper in its path SHALL be exported. Folders in blob storage
have no entry of their own; the folder markers `.dial-folder` and `.dial-resource` are blobs and SHALL be exported
with their metadata. A blob that is deleted after it was listed and before it was read SHALL be skipped.

#### Scenario: Keys and credentials
- **WHEN** `public/encryption_keys/key1`, `public/credentials/cred1`, `platform/encryption_keys/key2`,
  `platform/credentials/cred2`, `public/files/owner/encryption_keys/notes.txt`, `public/files/owner/credentials/notes.txt`
  and `public/models/m1` exist
- **THEN** the archive holds content entries for exactly `public/files/owner/credentials/notes.txt`,
  `public/files/owner/encryption_keys/notes.txt` and `public/models/m1`

#### Scenario: Markers
- **WHEN** `public/skills/folder/.dial-folder` and `public/skills/folder/skill/.dial-resource` exist
- **THEN** the archive holds each of them, with its metadata entry

#### Scenario: Folder listing entries and a blob deleted meanwhile
- **WHEN** blob storage lists a folder `public/files/user1/dir` and the blobs `kept.txt` and `gone.txt` under
  `public/files/user1/`, and `gone.txt` is deleted before the export reads it
- **THEN** the archive holds the manifest, and the metadata and content entries of `kept.txt` only

### Requirement: Manifest
`.dial-system-export/manifest.json` SHALL be a JSON object with `formatVersion` (the integer `1`), `coreVersion` (the
version of the Core that exported) and `createdAt` (the start time of the export as an ISO-8601 UTC instant truncated to
seconds).

#### Scenario: Version and time
- **WHEN** Core `1.2.3` exports at `2026-01-02T03:04:05.678Z`
- **THEN** the manifest has `formatVersion: 1`, `coreVersion: "1.2.3"` and `createdAt: "2026-01-02T03:04:05Z"`

### Requirement: Buckets are locked while exporting
Core SHALL hold the bucket locks of `public/` and `platform/` from before the first blob is read until the export
ends, however it ends, and release both locks at the end. A write to either bucket that needs a bucket lock SHALL wait
for the export. Core SHALL answer `409` and send no archive and no `Content-Disposition` when it cannot take both locks
because a writer or another export holds one, and SHALL release the lock it did take.

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

### Requirement: Export ends at the lock period
The locks last 5 minutes and are not renewed. Core SHALL fail an export that tries to write after its start time plus
that period, instead of finishing an archive from blobs it read without the locks. An export whose last write is exactly
at the period SHALL succeed.

#### Scenario: Export outlives the locks
- **WHEN** an export is still writing more than 5 minutes after its start
- **THEN** the export fails with an error that names the lock period, the archive has no central directory, the locks are
  released, and the failure is logged at `ERROR`

#### Scenario: Export ends exactly at the period
- **WHEN** an export's last write is exactly 5 minutes after its start
- **THEN** the archive is complete and readable

### Requirement: A failure after the archive started
Once Core has sent `200`, the status cannot change. When the export then fails (blob storage, the client's connection, the
lock period or a client too slow to take the data), Core SHALL NOT finish the archive, SHALL reset the connection, SHALL
release the bucket locks and SHALL log the failure at `ERROR`, so that the client sees a transport error and a file without
the ZIP central directory and never a complete archive.

#### Scenario: Output fails midway
- **WHEN** writing the archive fails after part of it was written
- **THEN** the export throws, the end-of-central-directory record is not written, and both bucket locks are released

#### Scenario: Client disconnects
- **WHEN** the client closes the connection while the archive is streaming
- **THEN** both bucket locks are released and the next export answers `200`

#### Scenario: Client too slow
- **WHEN** the client stops reading for longer than `adminSystemExport.stallTimeout`
- **THEN** the export fails, the locks are released, and the client's download ends with an error

### Requirement: Stall timeout setting
`adminSystemExport.stallTimeout` SHALL be the milliseconds the export waits for a slow client to take the buffered data before
it fails, with the default `60000`. Core SHALL NOT start with a value that is not positive or is not below the lock period
(`300000`).

#### Scenario: Valid values
- **WHEN** the setting is absent, or is any value from `1` to `299999`
- **THEN** Core starts

#### Scenario: Invalid values
- **WHEN** the setting is `0`, negative, `300000` or more
- **THEN** Core does not start and the error names `adminSystemExport.stallTimeout`

### Requirement: The archive reflects blob storage
Core SHALL read the archive from blob storage, not from the resource cache or the write queue. A resource written within
the resource sync delay (`resources.syncDelay`) may therefore not be in the archive yet.

#### Scenario: Not yet synced
- **WHEN** a resource was written to Core less than the sync delay ago and has not reached blob storage
- **THEN** it is not in the archive
