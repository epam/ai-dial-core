# Universal File API

Files inside skills and projects can use the same `/v1` request shape as plain files. Keep the stored DIAL URL,
including its reserved `files` segment, and add `/v1/` to read or change content, or `/v1/metadata/` for metadata.

## Routes

| Method | Request | Result |
|---|---|---|
| GET | `/v1/{skills|projects}/{bucket}/{resource}/files/{filePath}` | Current file content |
| PUT | Same path, multipart/form-data with one file part | 200 JSON file metadata |
| DELETE | Same path | 200 empty body |
| GET | `/v1/metadata/{skills|projects}/{bucket}/{resource}/files/{filePath}` | File metadata, or an in-resource folder listing |
| POST | `/v1/ops/resource/copy` or `/v1/ops/resource/move` | Copy or move a single file, 200 empty body |

The first `files` segment after the bucket separates the resource root from the file path. For
`skills/{bucket}/team/my-skill/files/refs/files/a.md`, the root is `skills/{bucket}/team/my-skill` and the file is
`refs/files/a.md`. No marker walk or link conversion is needed.

Only the file-shaped paths are added. Resource roots, grouping folders and bucket roots have no new v1 route.
Use the existing v2 APIs to create a resource, replace or download its whole file set, delete it, or list resources.
GET, PUT and DELETE of an in-resource folder return 400; metadata can list `/files/` and nested folders ending in `/`.

## Content and ETags

PUT requires `multipart/form-data` with exactly one file part. Its part Content-Type is stored as the file's MIME type,
even when it differs from the filename extension. Invalid multipart input returns 400. File, count and aggregate-size
limits remain those of the owning complex resource; count excess returns 400 and size excess returns 413.

A file mutation can only target an existing active resource. An absent or deleting resource returns 404, with guidance
to its whole-resource `PUT /v2/{group}/{bucket}/{resource}` creation API. No file operation creates a complex resource.

On the new content routes:

- `ETag`, `If-Match` and `If-None-Match` refer to the **file**, never the aggregate resource ETag.
- GET and PUT return the file ETag and `X-Resource-ETag`, the aggregate ETag of the same resource version.
- A successful DELETE returns the resulting `X-Resource-ETag` and no aggregate ETag in the `ETag` header.
- Conditional requests follow the plain-file rules: matching GET `If-None-Match` returns 304 with the file ETag;
  failed conditions return 412. Use PUT `If-None-Match: *` to create only an absent file.
- Two writers using the original ETags of different files do not conflict. A stale ETag for the same file is rejected.
- GET uses the existing plain-file Content-Length/compression behavior. Range does not add partial-download support.

Both ETag headers are exposed for browser clients. A write still commits a new resource version under the resource lock.
The resource handler validates mutations and entrypoint contents; for example, deleting or moving a skill's `SKILL.md`
is rejected. A project can remain as an empty resource after its last file is deleted.

## Metadata

File metadata has the existing file fields plus an optional owning-resource reference. Example:

```json
{
  "name": "a.md",
  "parentPath": "team/my-skill/files/refs",
  "bucket": "<bucket>",
  "url": "skills/<bucket>/team/my-skill/files/refs/a.md",
  "nodeType": "ITEM",
  "resourceType": "SKILL",
  "etag": "<file ETag>",
  "createdAt": 0,
  "updatedAt": 0,
  "author": "<author>",
  "contentLength": 123,
  "contentType": "text/markdown",
  "resource": {
    "url": "skills/<bucket>/team/my-skill",
    "etag": "<aggregate ETag>"
  }
}
```

URLs have no API-version prefix or internal storage version. Plain file metadata omits `resource`.
Nested folder responses name the requested logical folder, not always the resource's top-level files folder.

Metadata accepts the existing file query parameters: `token`, `limit` (default 100, range 0..1000), `recursive` and
`permissions`. Replay a returned continuation token on the same resource, folder and recursion mode; its page size can
change. Tokens describe logical paths, not storage versions. With `permissions=true`, every node carries the caller's
permissions on the owning root. Metadata uses application/json unless the existing
`application/vnd.dial.metadata+json` MIME type is requested through Accept. It does not add conditional-header handling.

## Copy and move

The operation request body is unchanged:

```json
{
  "sourceUrl": "skills/<bucket>/team/my-skill/files/refs/a.md",
  "destinationUrl": "projects/<bucket>/p/files/docs/a.md",
  "overwrite": false
}
```

When at least one side is a complex file URL, single-file operations support every combination of `files`, `skills`
and `projects`, including two paths in the same resource. Both URLs must name distinct files. Folders, identical
canonical URLs and mixed whole-resource/file operations return 400; a missing source returns 400. A complex destination
must already exist, otherwise 404 points to its whole-resource creation API.

`overwrite` defaults to false. For these extended operations, an existing destination returns 409 with false;
true replaces it after validation. Existing plain-file-only and whole-resource conflict statuses do not change.

Copy requires source READ and destination WRITE. Move requires source READ and WRITE and destination WRITE;
SHARE is not additionally required in the extended file branch. Complex sides are authorized at their resource roots,
plain sides at their ordinary file URLs, before storage existence is read. Legacy plain-file-only and whole-resource
move permissions are unchanged.

A same-resource copy or rename takes one resource lock and commits one version. Rename is atomic: a resource snapshot
contains the old or final file set, not an intermediate copy-only state. Limits apply to the final file set.

A cross-resource or cross-group move is **not atomic**. Core validates source deletion before writing, commits the
destination first, then deletes the source. A failed destination write leaves the source unchanged. A failed source
deletion leaves the committed destination; transient deletion failures retain or restore the source, so a duplicate
can remain. The response is an error and the client can retry with `overwrite: true`. Persistent storage or Redis
failure can prevent recovery or reads; the committed destination is retained and Core does not delete the only copy.

Complex root shares, invitations and publications stay at their roots when an individual file moves. Destination
access comes from the destination root. A plain file moved into a complex resource has its source shares/invitations
revoked only after source deletion succeeds; no per-file complex grant is created.

Public-space moves retain the org-unit ownership restriction: moves into, out of or between unit owners return 409.
An authorized copy can enter another unit instead.

## Access, events and compatibility

Read/write access to a complex file is evaluated on the owning resource root. Existing root/folder shares, publications
and per-request permissions therefore work without a new permission model. Unauthorized requests return 403 before
resource existence is revealed. The new complex paths hide `.dial-resource`, `.dial-folder` and `v` segments with 404,
including encoded or nested forms. Encoded separators, traversal and backslash aliases cannot reach internal storage.

The engine publishes the existing logical resource and changed-file events, never marker or storage-version URLs.
No new event type, storage format, setting or dependency is added.

`/v1/files`, `/v1/metadata/files` and all existing v2 routes remain unchanged. In particular, existing v2 file GET
already returns a file ETag and ignores conditional headers; existing v2 file PUT/DELETE keep aggregate conditions,
aggregate ETags and empty bodies. There are no redirects or deprecation headers.

Complete the deployment rollout before clients rely on these new routes or extended operations. Older pods return
404 for the new routes and reject the extended file operations; stored links and old APIs require no migration.
