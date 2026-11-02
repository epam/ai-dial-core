## 1. Timestamps for complex resources in the share list

- [x] 1.1 `ComplexResourceService.loadMarkers`: active markers of the resources in one `ResourceService.load` call
- [x] 1.2 `ShareService.populateTimestamps`: complex items take `createdAt` / `updatedAt` from the marker; `AiDial` passes `ComplexResourceService` to `ShareService`
- [x] 1.3 Tests for scenario "Complex items carry timestamps": `ProjectResourceApiTest.testSharedProjectCarriesTimestamps`, `SkillResourceApiTest.testSharedSkillCarriesTimestamps`
- [ ] 1.4 `./gradlew checkstyleMain checkstyleTest` and `--tests` `ProjectResourceApiTest`, `SkillResourceApiTest`, `ShareApiTest`, `ResourceOperationApiTest` (deferred: needs JDK 21 and GitHub Packages credentials; runs in PR CI)

## 2. Docs

- [x] 2.1 `docs/open_api_core.yaml`: no change, `ResourceItemMetadata` already declares both fields
- [x] 2.2 `npx -y @fission-ai/openspec@1.14.3 validate share-list-complex-timestamps --strict`
