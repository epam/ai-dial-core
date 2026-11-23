## Context

- `ComplexResourceService.java:listChildren` lists the marker blobs under a folder (`ResourceService.java:getFolderMetadata`,
  blob store only) and builds each item with `nodeMetadata`, which copies `createdAt` / `updatedAt` / `author` from the
  marker blob's metadata (`ResourceService.java:storageToResourceMetadata`).
- For every ITEM, `listChildren` already reads the marker document (`readMarker` → `ResourceService.java:getResource`,
  Redis first, then the blob) to drop `deleting` resources (`isActive`).
- Every write commits a new marker document with `updatedAt = now` (`ComplexResourceService.java:commitMarker`). A
  document up to `maxSizeToCache` goes to Redis only (`ResourceService.java:putResource`); the blob is written on
  creation (a stub) and then by `ResourceService.java:sync`, `syncDelay` after the last change.
- `GET /v2/metadata/` of an item takes both values from the marker document (`ComplexResourceService.java:itemMetadata`).

## Decisions

- `nodeMetadata` takes the marker document `listChildren` already read and sets `createdAt` / `updatedAt` from it,
  falling back to the marker blob's values when the document has none (`ObjectUtils.firstNonNull`). Folders have no
  document and keep the blob values.
- Rejected: syncing the marker blob on every write (`flushToBlobStore`). One more blob write per resource write, and it
  bypasses the Redis write-behind for a field only listings read.
- Rejected: taking `author` from the document too. The blob keeps the first writer (`putResource` keeps the existing
  author) and the document the last one (`commitMarker`), so it changes the field's meaning. Left to a separate change.

## Effect per request

None. The marker document is already read and parsed for each ITEM; the change only reads two more fields of it. No
extra Redis or blob round trips, and the response size is unchanged.

## Migration Plan

None. Old markers without timestamps keep the blob values.
