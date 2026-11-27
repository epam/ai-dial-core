# resource-listing Specification

## Purpose
What the metadata listing APIs (`GET /v2/metadata/...`) report for the resources under a folder. It covers the fields
of listed items that the item's own metadata endpoint also reports.

## Requirements

### Requirement: Timestamps of complex items in metadata listings
On `GET /v2/metadata/{projects|skills}/{bucket}/{folder}/`, recursive or not, each `PROJECT` or `SKILL` item SHALL
carry the `createdAt` and `updatedAt` that `GET /v2/metadata/` of that item returns, including right after a write to
the resource.

#### Scenario: Listing after a write
- **WHEN** `proxyKey1` creates a project (or a skill) under `listed/`, writes a file into it with `PUT .../files/{path}`, and lists `GET /v2/metadata/projects/<b1>/listed` (or `skills`)
- **THEN** the item has the same `createdAt` and `updatedAt` as `GET /v2/metadata/` of the project (or skill)

### Requirement: Complex file metadata uses the v1 file shape
GET `/v1/metadata/{skills|projects}/{bucket}/{resource}/files/{filePath}` SHALL return one file metadata item for a
file, or the v1 folder shape for a folder inside the resource. File metadata SHALL include the file ETag, contentLength,
stored contentType, timestamps, author and optional `resource: {url, etag}` of the same resource snapshot.
Plain files SHALL omit resource. Missing files or absent/deleting resources SHALL return 404.

#### Scenario: File metadata and owning resource
- **WHEN** a client requests metadata for `skills/<b>/team/s/files/refs/a.md` or the equivalent project file
- **THEN** Core answers 200 with nodeType ITEM, the complex resourceType, file fields and `resource: {url: '<group>/<b>/team/s', etag: '<aggregate ETag>'}`
- **Test:** `UniversalFileApiTest.testFileMetadata`

#### Scenario: Plain file serialization is unchanged
- **WHEN** a client uploads or requests metadata for a plain file
- **THEN** the response omits resource and retains the existing file metadata fields
- **Test:** `FileApiTest.testFileUpload`; `UniversalFileApiTest.testPlainFileMetadataHasNoResource`

#### Scenario: Missing metadata target
- **WHEN** an authorized caller requests metadata for a missing file or a file/folder of an absent or deleting resource
- **THEN** Core answers 404
- **Test:** `UniversalFileApiTest.testMissingMetadata`

### Requirement: Complex metadata URLs keep the segment form
Every metadata URL SHALL be a logical DIAL URL with the reserved files segment and no API-version or storage-version
prefix. name, parentPath and bucket SHALL correspond to that logical URL. A nested folder response SHALL name the
requested folder, not always the resource's top-level files folder. All returned files SHALL carry the owning resource
reference. Bucket, resource and grouping-folder listing SHALL remain on `/v2/metadata` only.

#### Scenario: Top-level and nested folder URLs
- **WHEN** a client lists `<resource>/files/` and `<resource>/files/refs/`, recursively or not
- **THEN** each response names its requested folder, items have logical file/folder URLs and file parentPath includes the resource/files prefix
- **Test:** `UniversalFileApiTest.testFolderMetadataUrls`

#### Scenario: Metadata has no whole-resource route
- **WHEN** a client requests a bucket, grouping-folder or resource-root listing at `/v1/metadata/skills/...` or `/v1/metadata/projects/...` without files
- **THEN** Core answers 404 and the existing v2 metadata routes remain available
- **Test:** `UniversalFileApiTest.testMetadataOnlyInsideResources`

### Requirement: Complex file metadata uses existing query parameters
Metadata SHALL accept token, limit, recursive and permissions with the v1 file meanings.
limit SHALL default to 100 and accept 0 through 1000; values outside that range SHALL return 400.
Continuation tokens SHALL be reusable on the same folder request. Accept negotiation SHALL return the existing
metadata MIME type when requested, otherwise application/json. Metadata SHALL not add conditional-request handling
that plain metadata does not have.

#### Scenario: Pagination and recursion
- **WHEN** a caller lists a complex folder using a small limit and its returned token, or recursive=true
- **THEN** pages enumerate the folder without omissions in an unchanged resource, and recursion uses the v1 folder/item shape
- **Test:** `UniversalFileApiTest.testMetadataPaginationAndRecursion`

#### Scenario: Limit boundaries
- **WHEN** a caller omits limit, sets it to 0 or 1000, or sets it outside 0..1000
- **THEN** Core uses 100 by default, accepts both boundaries, and answers 400 with `Limit is out of allowed range: [0, 1000]` outside the range
- **Test:** `UniversalFileApiTest.testMetadataLimits`

#### Scenario: Metadata content negotiation
- **WHEN** a caller supplies the plain-file metadata MIME type in Accept or requests metadata without it
- **THEN** Core returns the requested metadata MIME type or application/json respectively
- **Test:** `UniversalFileApiTest.testMetadataContentNegotiation`
