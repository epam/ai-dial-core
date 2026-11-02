## 1. Timestamps of complex items in metadata listings

- [x] 1.1 `ComplexResourceService.nodeMetadata`: `createdAt` / `updatedAt` from the marker document `listChildren` already read, the marker blob's values as fallback
- [x] 1.2 Tests for scenario "Listing after a write": `ProjectResourceApiTest.testMetadataListingTimestampsFollowWrites`, `SkillResourceApiTest.testMetadataListingTimestampsFollowWrites`
- [ ] 1.3 `./gradlew checkstyleMain checkstyleTest` and `--tests` `ProjectResourceApiTest`, `SkillResourceApiTest` (deferred: needs JDK 21 and GitHub Packages credentials; runs in PR CI)

## 2. Docs

- [x] 2.1 `docs/open_api_core.yaml`: no change, the listing item schema already declares both fields
- [x] 2.2 `npx -y @fission-ai/openspec@1.14.3 validate listing-complex-timestamps --strict`
