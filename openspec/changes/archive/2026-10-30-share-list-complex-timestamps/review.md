# Review: share-list-complex-timestamps

## What shipped

- `ComplexResourceService.loadMarkers`: reads the `.dial-resource` markers of many complex resources with one
  `ResourceService.load` call and returns the active ones by resource URL.
- `ShareService.populateTimestamps`: `PROJECT` and `SKILL` items take `createdAt` / `updatedAt` from the marker; other
  items keep the `loadMetadata` path from #2210. `AiDial` passes `ComplexResourceService` to `ShareService`.
- Tests: `ProjectResourceApiTest.testSharedProjectCarriesTimestamps` and
  `SkillResourceApiTest.testSharedSkillCarriesTimestamps` (both directions, equal to the `/v2/metadata` values).
- `docs/open_api_core.yaml`: no change.

## Drift from design.md

None.

## Not done

- Checkstyle and the test classes were not run locally (no JDK 21 and no GitHub Packages credentials on this machine);
  they run in PR CI.
- The extra marker read is not measured.

## Known limits

- One Redis round trip per 50 complex items, plus a blob read for each Redis miss.

## Review

Not yet reviewed. Issue #2214.
