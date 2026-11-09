## 1. Management API

- [x] 1.1 Routes `ALIAS_RESOURCE`/`ALIAS_RESOURCE_METADATA` before `RESOURCE`; `EntityBucketBinding` (`aliases` → `public`); `AdminRoleAuthorizationService` admin for every method; OpenAPI annotations (CI generates `docs/open_api_core.yaml`)
- [x] 1.2 `StoredAliases` (fields, body `name`); `ConfigResourceController.handleAliasPut` (blob only), `checkAliasTarget`, `handleAliasDelete`, GET projection; `AliasService.findTarget`
- [x] 1.3 Tests: `AliasPublicApiTest` ("Create, update and delete", "Not an admin", "Not directly in the public bucket on write", "Invalid bodies", "Resource target of the wrong kind", "Target in a user bucket"); `StoredAliasesTest` (incl. the public-target rule); `EntityBucketBindingTest`
- [x] 1.4 `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`

## 2. Calling and access

- [x] 2.1 `AliasService.findStoredAlias`/`listStoredAliases`/`isStoredAliasLocation`; `DeploymentService.findDeployment` `ALIAS` case (`404` outside `aliases/public/`, `userRoles` `403`)
- [x] 2.2 Tests: `AliasPublicApiTest` ("Called by its url", "Short name", "Responses and Messages", "Toolset by url", "Not directly in the public bucket", "Caller with a role of the alias", "No userRoles"); `DeploymentServiceTest` (found by url, `403`, `404` when missing or nested, short name); `AliasServiceTest` (read on demand, flat listing, invalid blob)
- [x] 2.3 `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`

## 3. Listings, health and rejections

- [x] 3.1 `DeploymentService.listUsableAliases`/`findAliasView`/`isStoredAliasId`; `AliasListings` always on the task executor, `isAliasId` for the single-item endpoints of `ModelController`, `AnthropicModelController`, `DeploymentController`; `AliasService.toView` without `inMemory`
- [x] 3.2 `AdminHealthConfigController` lists the stored aliases on the executor; `listStoredAliasesOrNone` and `storedAliasesOnce`; `handleAliasGet`; stored alias name rule; `ResourceOperationController.rejectAlias`; `ConfigManifestSupport.newScratch` copies `aliases`
- [x] 3.3 Tests: `AliasPublicApiTest` ("Name the url carries unchanged", "Blob that calls ignore", "Listings follow userRoles", "Stored alias to a resource target", "Anthropic listing", "Missing target", "Refused operations", "Manifest named like a config alias"); `DeploymentServiceTest` (listing by `userRoles`, "Storage fails", one stored-alias listing per request); `StoredAliasesTest` (name rule, parse); regression `AliasApiTest` ("Broken alias not named", "Toolset entry")
- [x] 3.4 `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`

## 4. Docs and checks

- [x] 4.1 `docs/dynamic-settings/aliases.md` (aliases in public storage, calling by url, management API, access, what is refused)
- [x] 4.2 `npx -y @fission-ai/openspec@1.14.3 validate deployment-alias-public --strict`
- [ ] 4.3 Latency of the model listings with the stored-alias listing on the task executor (deferred: needs a load-test environment)
