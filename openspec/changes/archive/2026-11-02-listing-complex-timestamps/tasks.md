## 1. Timestamps of complex items in metadata listings

- [x] 1.1 `ComplexResourceService.nodeMetadata`: `createdAt` / `updatedAt` from the marker document `listChildren` already read, the marker blob's values as fallback
- [x] 1.2 Tests for scenario "Listing after a write", in `ProjectResourceApiTest` and `SkillResourceApiTest`: `testMetadataListingTimestampsFollowWrites` (asserts the marker blob is stale), `testRecursiveMetadataListingTimestampsFollowWrites` (recursive listing, and a folder node keeps its blob values), `testMetadataListingTimestampsFallBackToMarkerBlob` (a marker document without timestamps)
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest` and `--tests` `ProjectResourceApiTest`, `SkillResourceApiTest`: in PR CI (`style_checks`, `code_checks`), green at 09d73ff2; not runnable locally (needs JDK 21 and GitHub Packages credentials). The review-round tests run on the next CI run

## 2. Docs

- [x] 2.1 `docs/open_api_core.yaml`: no change, the listing item schema already declares both fields
- [x] 2.2 `npx -y @fission-ai/openspec@1.14.3 validate listing-complex-timestamps --strict`
