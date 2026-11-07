# Review: listing-complex-timestamps

## What shipped

- `ComplexResourceService.nodeMetadata`: `PROJECT` and `SKILL` items in a metadata listing take `createdAt` /
  `updatedAt` from the marker document `listChildren` already reads, falling back to the marker blob's values. Folders
  and `author` are unchanged.
- Tests: `ProjectResourceApiTest.testMetadataListingTimestampsFollowWrites` and
  `SkillResourceApiTest.testMetadataListingTimestampsFollowWrites` (a file write, then the listing compared with
  `GET /v2/metadata/` of the item, before the marker blob is synced).
- `docs/open_api_core.yaml`: no change.

## Drift from design.md

None.

## Not done

- Checkstyle and the test classes were not run locally (no JDK 21 and no GitHub Packages credentials on this machine);
  they run in PR CI.

## Known limits

- `author` in the listing still comes from the marker blob: the first writer, while `GET /v2/metadata/` of the item
  reports the last one.

## Verification outside the tests

On the dev environment, before the fix: right after `PUT .../files/{path}` the listing reported the creation time while
`GET /v2/metadata/` of the project reported the write time; the listing caught up within 3.5 minutes.

## Review

Not yet reviewed. Issue #2223.
