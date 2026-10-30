## Context

- `ShareService.java:populateTimestamps` (from #2210) calls `ResourceService.java:loadMetadata` for every ITEM. That
  reads the Redis entry or blob at the item's URL.
- A complex resource (`ResourceType.isComplex()`: `SKILL`, `PROJECT`) has no entry at its URL
  (`ComplexResourceService.java:hasResource`). Its state is the `.dial-resource` marker under the resource
  (`ComplexResourceService.java:markerDescriptor`), a JSON `FolderResourceMarker` with `createdAt`, `updatedAt` and
  `state`.
- The metadata endpoint for a complex item takes both values from the active marker
  (`ComplexResourceService.java:getMetadata`, `itemMetadata`).

## Decisions

- `populateTimestamps` splits the ITEMs by `getDescriptor().getType().isComplex()`. Plain items keep the
  `loadMetadata` path unchanged. Complex items go through the new `ComplexResourceService.java:loadMarkers`.
- `loadMarkers` builds the marker descriptor of each resource and reads all markers with one
  `ResourceService.java:load` call (Redis `RBatch` per 50 items, a blob read only for Redis misses), then keeps only
  active markers (`isActive`), as `getMarker` does. A marker in the `deleting` state or a missing one leaves the item
  without timestamps, as a missing resource does for plain items.
- It lives in `ComplexResourceService` because the marker layout (`markerDescriptor`, `isActive`) is private there.
- Rejected: `getMarker` per item. One Redis round trip per shared project or skill instead of one per 50.
- Rejected: `loadMetadata` on the marker descriptors. It returns the marker blob's own timestamps, which differ from
  the logical `createdAt` in the marker JSON that the metadata endpoint reports.

## Effect per request

On `share/list` with complex items: one Redis round trip per 50 complex items plus a blob read per Redis miss, and a
JSON parse of each marker (a small document holding the file list). Plain items are unchanged. Not measured.

## Migration Plan

None. The two fields are additive.
