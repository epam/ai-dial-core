# Spec Delta

## Purpose

The HTTP contract for reading and changing single files, including files inside skills and projects, and for copying
or moving those files without bypassing the consistency rules of their owning resources.

## ADDED Requirements

### Requirement: Complex file routes use stored URLs
Core SHALL serve GET, PUT and DELETE at `/v1/{skills|projects}/{bucket}/{resource}/files/{filePath}`.
The first `/files/` after the bucket SHALL separate the resource root from the relative file path.
Resource roots, grouping folders and bucket roots SHALL have no route in this family.
Folder-shaped file targets SHALL return 400 for content operations.

#### Scenario: Stored URL and first delimiter
- **WHEN** a client reads `/v1/skills/<b>/team/s/files/refs/files/a.md` or the equivalent project URL
- **THEN** Core reads `refs/files/a.md` of resource `<group>/<b>/team/s`, without rewriting the stored URL
- **Test:** `UniversalFileApiTest.testStoredUrlAndFirstDelimiter`

#### Scenario: Non-file targets
- **WHEN** a client requests a resource root, grouping folder or bucket root under `/v1/skills` or `/v1/projects`
- **THEN** Core answers 404; GET, PUT and DELETE of `<resource>/files/` or `<resource>/files/refs/` answer 400
- **Test:** `UniversalFileApiTest.testNonFileTargets`

### Requirement: Complex file download matches plain file transport
GET SHALL stream the current file with its stored Content-Type, file ETag and `X-Resource-ETag` of the same resource
snapshot. Content-Length, negotiated compression and exposed response headers SHALL match the plain file API.
A missing file or absent resource SHALL return 404. Range SHALL not introduce partial responses where plain GET ignores it.

#### Scenario: File download and transport headers
- **WHEN** a client downloads a complex file written with an explicit MIME type, including requests with Range or gzip negotiation
- **THEN** content and transport behavior match plain file GET, and exposed headers include the file ETag and owning resource ETag
- **Test:** `UniversalFileApiTest.testDownloadTransport`

#### Scenario: Missing file or resource
- **WHEN** an authorized caller downloads a missing file, or a file in an absent or deleting resource
- **THEN** Core answers 404
- **Test:** `UniversalFileApiTest.testMissingDownload`

### Requirement: Complex file PUT uses multipart and returns metadata
PUT SHALL accept multipart/form-data containing one file part and preserve that part's content type.
Success SHALL return 200 with application/json file metadata, the new file ETag and `X-Resource-ETag` of the committed
resource. The body and headers SHALL describe the same committed version. Missing or invalid multipart Content-Type,
or zero or multiple file parts, SHALL return 400.

#### Scenario: Upload metadata and MIME type
- **WHEN** a client uploads one file part with a content type different from the filename's inferred type
- **THEN** PUT answers 200 with file metadata and matching file/resource ETags; metadata and download preserve the supplied content type
- **Test:** `UniversalFileApiTest.testMultipartMetadataAndContentType`

#### Scenario: Invalid multipart upload
- **WHEN** a client omits a valid multipart Content-Type or sends zero or multiple file parts
- **THEN** Core answers 400 and the resource is unchanged
- **Test:** `UniversalFileApiTest.testInvalidMultipart`

### Requirement: Complex file writes require an existing resource
PUT, copy and move into a complex file SHALL require an existing active resource at the destination root.
An absent or deleting destination resource SHALL return 404 with guidance to its whole-resource
`PUT /v2/{group}/{bucket}/{resource}` creation route. No file operation SHALL create a complex resource.

#### Scenario: Destination does not exist
- **WHEN** an authorized caller uploads, copies or moves a file into an absent or deleting complex resource
- **THEN** Core answers 404 naming the whole-resource creation API, creates nothing and leaves any source file intact
- **Test:** `UniversalFileApiTest.testExistingResourceRequired`; `ResourceOperationApiTest.testComplexFileDestinationMustExist`

### Requirement: Complex file conditional requests use file ETags
On the new routes ETag, If-Match and If-None-Match SHALL refer only to the target file and follow plain-file matching
rules. GET with matching If-None-Match SHALL return 304 and the file ETag; a failing If-Match SHALL return 412.
PUT and DELETE SHALL check conditions against the file in the version being changed; failed conditions SHALL not commit.

#### Scenario: Conditional read and write matrix
- **WHEN** a client uses omitted, exact, list or wildcard If-Match/If-None-Match headers on GET, PUT and DELETE
- **THEN** results match the plain-file conditional rules, including GET 304, failed-condition 412 and absent-file wildcard checks
- **Test:** `UniversalFileApiTest.testFileConditionalRequests`

#### Scenario: Different files do not conflict
- **WHEN** two writers replace different files of one resource using the original ETag of each file
- **THEN** both writes succeed and both changes survive; a stale ETag for the same file returns 412 without changing the resource
- **Test:** `UniversalFileApiTest.testDifferentFileWriters`; `UniversalFileApiTest.testStaleSameFileWriter`

### Requirement: Complex file deletion preserves the resource contract
DELETE SHALL return 200 with an empty body and the resulting `X-Resource-ETag`, without an aggregate ETag in the ETag
header. A missing file SHALL return 404 unless a conditional request fails first. A rejected deletion SHALL leave the
resource unchanged. Deleting the last file of a project SHALL leave an existing empty project.

#### Scenario: Delete file and empty project
- **WHEN** a client deletes an ordinary skill file or the last file of a project
- **THEN** Core answers empty 200 with X-Resource-ETag; the resource remains, and the deleted file answers 404
- **Test:** `UniversalFileApiTest.testDeleteFileAndLastProjectFile`

### Requirement: Complex file mutations preserve validation and limits
Every changed complex side SHALL validate its file mutation and any changed entrypoint content.
The final file set SHALL satisfy maxFiles, maxTotalBytes and maxFileSizeBytes. Invalid mutations or count excess SHALL
return 400; size excess SHALL return 413. Rejection before commit SHALL leave the previous committed resource and files
intact. Writes SHALL publish the existing logical events specified by resource-subscription, not internal storage URLs.

#### Scenario: Entrypoint validation cannot be bypassed
- **WHEN** a client deletes or moves SKILL.md, or uploads or copies invalid skill manifest content to SKILL.md
- **THEN** Core answers 400 and every affected resource and source file remains unchanged
- **Test:** `UniversalFileApiTest.testManifestValidation`; `ResourceOperationApiTest.testComplexFileMutationValidation`

#### Scenario: Mutation limits
- **WHEN** a PUT or copy would exceed a configured file count, total size or individual size limit
- **THEN** Core answers 400 for count or 413 for size, with no committed change; a same-resource rename at the limit succeeds if its final set fits
- **Test:** `UniversalFileApiTest.testFileLimits`; `ResourceOperationApiTest.testComplexFileCopyMoveLimits`

#### Scenario: Logical events
- **WHEN** a client uploads, deletes, copies or moves a complex file through the new API
- **THEN** subscribers receive existing resource and changed-file events with logical URLs, never markers or version paths
- **Test:** `UniversalFileApiTest.testLogicalEvents`; `ResourceOperationApiTest.testComplexFileOperationEvents`

### Requirement: Single-file operations extend resource copy and move
POST `/v1/ops/resource/copy` and `move` SHALL accept plain file URLs and complex file URLs in sourceUrl/destinationUrl.
When at least one side is a complex file, any combination of files, skills and projects SHALL be supported.
Both targets SHALL name distinct files; folder targets, equal canonical file URLs and whole-resource/file mixtures SHALL
return 400. Successful operations SHALL return 200 with an empty body.

#### Scenario: File copy and move matrix
- **WHEN** an authorized caller copies or moves files through each combination of files, skills and projects, including within one resource
- **THEN** Core answers empty 200, preserves content and content type, and deletes the source only for a successful move
- **Test:** `ResourceOperationApiTest.testComplexFileCopyMoveMatrix`

#### Scenario: Invalid operation targets
- **WHEN** an operation names a folder, identical canonical file URLs, or a whole-resource/file mixture
- **THEN** Core answers 400 without changing either side
- **Test:** `ResourceOperationApiTest.testComplexFileInvalidTargets`

### Requirement: Complex file operation overwrite conflicts are explicit
For operations involving a complex file, overwrite SHALL default to false. An existing destination file with overwrite
false SHALL return 409; overwrite true SHALL replace it after validation. A missing source SHALL return 400, as existing
resource operations do. This SHALL NOT change conflict statuses for plain-file-only or whole-resource operations.

#### Scenario: Destination conflict and overwrite
- **WHEN** a caller copies or moves onto an existing file with omitted or false overwrite, then retries with true
- **THEN** the first request answers 409 with both files unchanged, and the retry succeeds if validation allows it
- **Test:** `ResourceOperationApiTest.testComplexFileOverwrite`

#### Scenario: Source is missing
- **WHEN** an authorized caller copies or moves a missing complex file
- **THEN** Core answers 400 without writing the destination
- **Test:** `ResourceOperationApiTest.testComplexFileMissingSource`

### Requirement: Same-resource file moves commit atomically
A copy or move inside one complex resource SHALL create one committed resource version. For move, readers SHALL observe
both old paths or both resulting paths from one version, never an intermediate copy-only version. A successful rename
SHALL preserve the copied file content and ETag while recomputing the resource ETag for the new file set.

#### Scenario: Rename is one commit
- **WHEN** a client renames a file inside one resource while another client reads its metadata
- **THEN** each observed resource snapshot contains either the original or final file set, and the move produces one resource update
- **Test:** `ResourceOperationApiTest.testSameResourceFileMoveIsAtomic`

### Requirement: Cross-resource moves never delete the only committed copy
A move across resources or groups SHALL validate the source deletion before writing the destination, commit the
destination before deleting the source, and not lose a concurrent source replacement. A failed destination write SHALL
leave the source intact. A failed source deletion after destination commit SHALL return an error and retain both copies;
retry with overwrite true SHALL be supported. Post-commit cleanup SHALL not erase a committed file version.

#### Scenario: Destination write fails
- **WHEN** destination storage fails before its commit during a cross-resource move
- **THEN** the response is an error and the source remains readable with its original content
- **Test:** `ResourceOperationApiTest.testComplexFileMoveDestinationFailure`

#### Scenario: Source delete fails after destination commit
- **WHEN** source deletion fails after the destination has committed
- **THEN** the response is an error, both copies remain readable, and retry with overwrite true completes the move
- **Test:** `ResourceOperationApiTest.testComplexFileMoveSourceDeleteFailure`

#### Scenario: Concurrent upload and reverse-direction moves
- **WHEN** a plain source is replaced concurrently with a move, or opposite-direction file operations run together
- **THEN** no successful concurrent replacement is lost and operations finish without a lock-order deadlock
- **Test:** `ResourceOperationApiTest.testComplexFileMoveConcurrentReplacement`; `ResourceOperationApiTest.testReverseFileOperations`

### Requirement: Existing file and resource APIs keep their behavior
The existing `/v1/files`, `/v1/metadata/files`, `/v2/{group}` and `/v2/metadata/{group}` routes SHALL retain their paths,
status codes, headers, bodies and ETag behavior. Plain-file-only and whole-resource copy/move SHALL retain their existing
behavior. No redirect or deprecation header SHALL be added.

#### Scenario: Legacy ETags and response bodies
- **WHEN** clients use existing plain-file and v2 complex file APIs
- **THEN** plain PUT still returns file metadata, v2 PUT/DELETE still return empty bodies with aggregate ETags, and v2 file GET still returns a file ETag and ignores conditional headers
- **Test:** `UniversalFileApiTest.testLegacyRouteCompatibility`; existing `FileApiTest`, `SkillResourceApiTest`, `ProjectResourceApiTest`

#### Scenario: Legacy operation rules
- **WHEN** a client uses plain-file-only or whole-resource operations, including a destination conflict
- **THEN** existing status codes, same-type checks, permission requirements and share handling remain unchanged
- **Test:** existing `ResourceOperationApiTest`; `ProjectResourceApiTest.testMoveSharedProjectKeepsAccess`
