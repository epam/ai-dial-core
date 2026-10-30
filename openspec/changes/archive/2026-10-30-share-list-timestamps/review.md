# Review: share-list-timestamps

## What shipped

- `ShareService.populateTimestamps`: one `ResourceService.loadMetadata` call per listing (`listSharedWithMe` and
  `listSharedByMe`) copies `createdAt` and `updatedAt` onto the ITEMs. `loadMetadata` reuses `ResourceService.load`'s
  Redis batching through a `withBody` flag, so no body is read.
- Tests: `ShareApiTest.testSharedItemsCarryTimestamps` (both directions, equal to the metadata endpoint's values);
  the existing share-list expectations in `ShareApiTest` and `ResourceOperationApiTest` now ignore the two fields
  (`"@ignore"` with `verifyJsonNotExact`).
- `docs/open_api_core.yaml`: no change; `ResourceItemMetadata` already declares both fields and regenerating it gives no diff.

## Drift from design.md

- The proposal's "item whose resource no longer exists is listed without the fields" does not happen: Core removes the
  share when the resource is deleted, so the item is not listed. The scenario and the sentence were dropped; the null
  check stays as a guard for a delete that races the listing.

## Not done

- The extra read per item is not measured.

## Known limits

- One Redis round trip per 50 items, plus a blob head request for each Redis miss.
- `etag` is not added to share-list items.

## Review

Not yet reviewed; no PR is open. Issue #2210.
