# Spec Delta

## MODIFIED Requirements

### Requirement: Access check before existence
On the file, resource (`/v1/{files,conversations,prompts,applications,toolsets}/...`, `/v1/metadata/...`), new
`/v1/{skills,projects}/.../files/...` and existing `/v2/{skills,projects}/...` routes Core SHALL require READ for GET
and WRITE for PUT/DELETE. A caller lacking it SHALL receive 403 with body `You don't have an access to: <url>` whether
or not the resource exists; 404 SHALL only reach permitted callers. For complex files, url SHALL name the root.

#### Scenario: Missing resource in a foreign bucket
- **WHEN** `proxyKey2` sends `GET /v1/conversations/<b1>/folder/missing`
- **THEN** Core answers `403`, not `404`

#### Scenario: Missing resource the caller may read
- **WHEN** an admin sends `GET /v1/files/public/no-such-file.txt`
- **THEN** Core answers `404`

#### Scenario: Complex file denial does not reveal existence
- **WHEN** a caller without root access reads or changes a file in an existing or absent complex resource using the new v1 routes
- **THEN** Core answers 403 naming the logical resource root before revealing existence
- **Test:** `UniversalFileApiTest.testAccessBeforeExistence`

### Requirement: Permissions in metadata
When a metadata request or `POST /v1/ops/publication/resource/list` carries the query `permissions=true`, Core SHALL
set `permissions` on each returned item (and on nested folder items) to the caller's permissions on it.
For new v1 complex file metadata, the file and every folder/item in a listing SHALL carry the caller's permissions
on the owning resource root instead of independent per-file grants.

#### Scenario: Recipient sees READ
- **WHEN** `proxyKey2` accepted a READ share of `conversations/<b1>/folder/conversation` and sends
  `GET /v1/metadata/conversations/<b1>/folder/conversation?permissions=true`
- **THEN** Core answers `200` with `"permissions":["READ"]`

#### Scenario: Complex listing carries root permissions
- **WHEN** a recipient holds READ on a skill/project root and requests file or recursive folder metadata with permissions=true
- **THEN** every returned node carries READ from the root; without permissions=true the optional field remains omitted
- **Test:** `UniversalFileApiTest.testRootPermissionsInMetadata`

### Requirement: Move permissions and surviving access
Except for operations involving complex file URLs, on POST `/v1/ops/resource/move` Core SHALL require READ, WRITE and
SHARE on sourceUrl and WRITE on destinationUrl (else 403). After a move within one private bucket, shares and invitations
SHALL follow the resource to the destination; after a move to another bucket, shares and invitations of the source
SHALL be revoked. Complex file operations SHALL follow the added single-file permission and sharing rules below.

#### Scenario: Share follows a move
- **WHEN** `proxyKey2` holds READ on `conversations/<b1>/folder/conversation` and `proxyKey1` moves it to
  `conversations/<b1>/folder2/conversation2`
- **THEN** `proxyKey2` can `GET` the new URL and the invitation lists the new URL

#### Scenario: Admin moving a private resource
- **WHEN** an admin moves `conversations/<b1>/folder/conversation` to `conversations/public/folder/conversation`
- **THEN** Core answers `403` and the source is unchanged

### Requirement: Copy permissions
On POST `/v1/ops/resource/copy` Core SHALL require READ on sourceUrl (plus WRITE for applications and toolsets) and
WRITE on destinationUrl (else 403), and SHALL NOT copy shares to the destination. For move and copy, differing types,
equal URLs, folders or a missing source SHALL be answered 400, except that operations involving a complex file URL
SHALL allow the file-group combinations defined by file-content. Other operations SHALL retain the same-type restriction.

#### Scenario: Recipient copies a shared conversation
- **WHEN** `proxyKey2` holds READ on `conversations/<b1>/c` and copies it to `conversations/<b2>/c`
- **THEN** Core answers `200`, and `proxyKey1` gets `403` on `conversations/<b2>/c`

## ADDED Requirements

### Requirement: Complex files inherit resource-root access
For the new v1 routes and copy/move operations, Core SHALL evaluate access to complex files on their owning root.
An existing root or covering-folder share, publication or per-request grant SHALL apply without adding per-file sharing.
READ SHALL permit reading every file; WRITE SHALL permit file mutations subject to the resource's validation.

#### Scenario: Shared and published roots cover files
- **WHEN** a caller can read a shared or published skill/project root
- **THEN** the caller can read its new v1 file content and metadata; a READ-only recipient cannot PUT or DELETE its files
- **Test:** `UniversalFileApiTest.testSharedAndPublishedRootAccess`

#### Scenario: Per-request root grant covers files
- **WHEN** an application key has READ on a complex root or its covering folder
- **THEN** it can read the new file route but cannot write without root WRITE
- **Test:** `UniversalFileApiTest.testPerRequestRootAccess`

### Requirement: New complex file APIs hide internal paths
After root authorization, the new complex file routes and file-operation targets SHALL return 404 for decoded path
segments `.dial-resource`, `.dial-folder` or `v` anywhere in the addressed complex path. Encoded names SHALL not bypass
this guard. Path decoding SHALL not let encoded separators, traversal or backslash aliases reach internal storage.
The existing v2 path-validation behavior SHALL remain unchanged.

#### Scenario: Internal paths and encoded variants
- **WHEN** an authorized caller uses any new content, metadata or ops path naming reserved segments at root or nested depth, including encoded variants
- **THEN** Core answers 404 for hidden segments and rejects invalid separator/traversal aliases without accessing internal storage
- **Test:** `UniversalFileApiTest.testHiddenPaths`; `ResourceOperationApiTest.testComplexFileHiddenPaths`

### Requirement: Extended single-file operations use root permissions
For operations involving a complex file, copy SHALL require source READ and destination WRITE; move SHALL require
source READ and WRITE and destination WRITE, without requiring SHARE. Complex sides SHALL be checked at their roots;
plain sides SHALL be checked at their file URLs. Permission checks SHALL precede resource/file existence reads.
Plain-file-only and whole-resource operations SHALL retain their existing permissions.

#### Scenario: READ and WRITE are enough for a complex file move
- **WHEN** a caller holds READ and WRITE but not SHARE on the complex source root and WRITE on the destination root
- **THEN** a valid file move succeeds, while a whole-resource move remains subject to its existing SHARE requirement
- **Test:** `ResourceOperationApiTest.testComplexFileMovePermissions`

#### Scenario: Denied destination is not inspected
- **WHEN** a caller lacks WRITE on the destination root of a complex file copy/move, whether the destination exists or not
- **THEN** Core answers 403 and changes neither source nor destination
- **Test:** `ResourceOperationApiTest.testComplexFileOperationAccessBeforeExistence`

### Requirement: File operations do not relocate complex root shares
Copying or moving one complex file SHALL NOT move or copy its owning root's shares, invitations or publications.
Destination access SHALL come from the destination root. When a plain file is moved into a complex resource, its
source shares and invitations SHALL be revoked after source deletion, not translated into per-file complex grants.
Failed source deletion SHALL retain source shares with the surviving source.

#### Scenario: Complex root shares remain at their roots
- **WHEN** a caller copies or moves a file between two shared complex resources
- **THEN** each root's recipients and invitations stay unchanged; destination readers gain the file only through destination-root access
- **Test:** `ResourceOperationApiTest.testComplexFileRootSharesStayPut`

#### Scenario: Plain source shares are not converted
- **WHEN** a caller successfully moves a shared plain file into a complex resource
- **THEN** source shares/invitations are removed and no per-file complex share is created
- **Test:** `ResourceOperationApiTest.testPlainFileMoveIntoComplexShareCleanup`

### Requirement: Extended file moves preserve org-unit ownership
A file move within the public shared space SHALL retain the existing prohibition on changing the owning org unit.
For a complex file, ownership SHALL be evaluated on its root. A prohibited move SHALL answer 409 before any mutation;
a copy SHALL remain possible when source and destination permissions permit it.

#### Scenario: Public file move across unit owners
- **WHEN** a caller moves a complex file within public space into, out of or between org units
- **THEN** Core answers 409 and keeps both sides unchanged; an authorized copy of the same file can succeed
- **Test:** `OrgUnitSkillApiTest.testComplexFileMoveAcrossUnitOwners`
