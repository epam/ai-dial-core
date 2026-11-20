# Review: listing-complex-timestamps

## What shipped

- `ComplexResourceService.nodeMetadata`: `PROJECT` and `SKILL` items in a metadata listing take `createdAt` /
  `updatedAt` from the marker document `listChildren` already reads, falling back to the marker blob's values. Folders
  and `author` are unchanged.
- Tests, in `ProjectResourceApiTest` and `SkillResourceApiTest`:
  - `testMetadataListingTimestampsFollowWrites`: a file write a millisecond after the upload, then the listing
    compared with `GET /v2/metadata/` of the item. Asserts the preconditions: `updatedAt` is past `createdAt`, and
    the marker blob (read past Redis with `ResourceBaseTest.storedBlobMetadata`) is older than the document.
  - `testRecursiveMetadataListingTimestampsFollowWrites`: the same for a nested item in a recursive listing; the
    grouping folder node in it keeps its folder marker blob's values.
  - `testMetadataListingTimestampsFallBackToMarkerBlob`: a marker document without timestamps
    (`ResourceBaseTest.stripMarkerTimestamps`) is listed with the marker blob's values.
- `docs/open_api_core.yaml`: no change.

## Drift from design.md

None.

## Not done

- Checkstyle and the test classes were not run locally (no JDK 21 and no GitHub Packages credentials on this machine);
  PR CI ran them (`style_checks`, `code_checks`, green at 09d73ff2).

## Known limits

- `author` in the listing still comes from the marker blob: the first writer, while `GET /v2/metadata/` of the item
  reports the last one.

## Verification outside the tests

On the dev environment, before the fix: right after `PUT .../files/{path}` the listing reported the creation time while
`GET /v2/metadata/` of the project reported the write time; the listing caught up within 3.5 minutes.

## Review findings

Reviewed on PR #2224 (issue #2223).

| Lens | Severity | File | Finding |
| --- | --- | --- | --- |
| naming | low | `ComplexResourceService.java` (`nodeMetadata`) | The parameter `itemMetadata` shares its name with the static `itemMetadata(resource, marker)`, which builds document-based metadata; the two `marker == null ? … : firstNonNull(…)` ternaries repeat the fallback. |
| correctness | low | `ComplexResourceService.java` (`nodeMetadata`) | Timestamps come from the marker document but `author` from the blob (the first writer), so after another user's write the listing pairs that write's `updatedAt` with the creator. |
| tests | medium | `ProjectResourceApiTest.java`, `SkillResourceApiTest.java` | The tests could pass without the fix: nothing asserted the blob was stale, and the upload and the write could land in the same millisecond; recursive listing, folder nodes and the fallback were untested. |
| process | low | `tasks.md`, `review.md` | Task 1.3 unchecked and the review not recorded although the change was archived. |

## Fix round 1

- Fixed: naming/low. The parameter is `blobMetadata`; the fallback is computed in one `marker != null` branch.
- Fixed: tests/medium. See "What shipped": the write waits for the next millisecond, both preconditions are asserted,
  and recursive, folder-node and fallback tests were added.
- Fixed: process/low. Task 1.3 records the CI run; this section records the review.
- Not addressed: correctness/low. Unchanged by design (design.md, rejected alternatives): taking `author` from the
  document changes the field's meaning from creator to last writer. It stays in "Known limits" for a separate change.

## Outcome

- High/medium findings open: none.
- Low findings left: the `author` mismatch, for a separate change.
- CI must still confirm: checkstyle and the review-round tests.
