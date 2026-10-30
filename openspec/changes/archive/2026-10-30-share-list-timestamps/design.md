## Context

`ShareService.listSharedWithMe` and `listSharedByMe` (through `toMetadata`) create `new ResourceItemMetadata(descriptor)`.
`ResourceItemMetadata.updatedAt` stays null and `@JsonInclude(NON_NULL)` drops it. The metadata endpoints fill it in
`ResourceService.getResourceMetadata` (item) and `ResourceService.storageToResourceMetadata` (folder listing).

## Decisions

- `ShareService.populateTimestamps` calls `ResourceService.getResourceMetadata` for each item and copies `createdAt`
  and `updatedAt`. It reuses the one existing reader, so the share list and the metadata endpoints agree: Redis
  first, then blob user metadata, then the blob's creation and last-modified dates.
- When `getResourceMetadata` returns null, which can happen only if the resource is deleted between reading the
  share record and the lookup (Core removes the share on delete), the item keeps no timestamps instead of failing
  the listing.
- `etag` is not copied: no client asked for it, and it would widen the response.
- Rejected: store the timestamps in the share record. They would go stale on every edit of the resource.

## Risks

- One Redis read per listed item, plus a blob head request when Redis has no entry. The set is bounded by the
  resources the caller shares or received. Not measured.

## Migration Plan

None. The two fields are additive.
