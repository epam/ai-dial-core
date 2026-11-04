## 1. Timestamps in the share list

- [x] 1.1 `ShareService.populateTimestamps`, called from `listSharedWithMe` and `toMetadata`
- [x] 1.2 Tests: `ShareApiTest` and `ResourceOperationApiTest` expect `createdAt` and `updatedAt`; `ShareApiTest` scenario "Items carry timestamps" (`testSharedItemsCarryTimestamps`)
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest` and `--tests` `ShareApiTest`, `ResourceOperationApiTest`, `CustomApplicationApiTest`

## 2. Docs

- [x] 2.1 Regenerate `docs/open_api_core.yaml` (`:openapi-generator:replaceSpec`): no diff, the schema already has both fields
- [x] 2.2 `npx -y @fission-ai/openspec@1.14.3 validate share-list-timestamps --strict`
