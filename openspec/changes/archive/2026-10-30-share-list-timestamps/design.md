## Context

`ShareService.listSharedWithMe` and `listSharedByMe` (through `toMetadata`) create `new ResourceItemMetadata(descriptor)`.
`ResourceItemMetadata.updatedAt` stays null and `@JsonInclude(NON_NULL)` drops it. The metadata endpoints fill it in
`ResourceService.getResourceMetadata` (item) and `ResourceService.storageToResourceMetadata` (folder listing).

## Decisions

- `ShareService.populateTimestamps` collects the ITEMs of a listing and calls `ResourceService.loadMetadata` once. It
  reuses the batching of `ResourceService.load` (Redis `RBatch` per `BATCH_SIZE` items, blob lookups in parallel only for
  Redis misses), with a `withBody` flag so that only the metadata fields are read and no body is downloaded or cached.
  The metadata is built by the same `toMetadata` as `getResourceMetadata`, so the share list and the metadata endpoints
  agree: Redis first, then blob user metadata, then the blob's creation and last-modified dates.
- It runs before the items go into the response's hash set, because the timestamps are part of an item's hash code.
- When the lookup finds no resource, which can happen only if the resource is deleted between reading the
  share record and the lookup (Core removes the share on delete), the item keeps no timestamps instead of failing
  the listing.
- `etag` is not copied: no client asked for it, and it would widen the response.
- Rejected: store the timestamps in the share record. They would go stale on every edit of the resource.

## Risks

- One Redis round trip per `BATCH_SIZE` (50) items, plus a blob head request for each Redis miss. Not measured.

## Migration Plan

None. The two fields are additive.
