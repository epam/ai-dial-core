# Tasks

Spec scenario to test: `config-store` "Blob router serves at once", "Deleted router gone at once", "Rejected write
leaves nothing behind", "Blob router survives a reload", "Blob router shadows the file router", "Router name taken by
a model", "Model name taken by a router", "Router with interceptors rejected on write", "Router with a translator
mismatch rejected on write": `ConfigRouterTest` (new, integration, fixture `dial-config/routers.json`). "Invalid
router recorded under skip": `ConfigRouterTest` with a `skip` settings override. "Router manifest with a legacy
endpoint fails", "Model manifest named after a file router fails", "Router manifest in the public bucket":
`AdminValidateApiTest` (apply half of the first: `AdminApplyApiTest`). "Router applied with its in-batch translator":
`AdminApplyApiTest`. "File router migrated": `ConfigFileMigrateApiTest`. `router-deployment` "Router with upstreams
rejected on write", "Blob router read back": `ConfigRouterTest`. `request-routing` "Router entity", "File router
entry": `ControllerSelectorTest`.

## 1. Type resolution and routes

- [x] 1.1 `ResourceTypes.of`: `case "routers" -> ROUTER`; drop the "not in of() until then" comment. Verified end to
  end by the tests of 1.2 and 3.2 (`of("routers")` is on their path).
- [x] 1.2 `RouteTemplate`: `routers` in `CONFIG_RESOURCE`, `CONFIG_RESOURCE_METADATA` and `ADMIN_FILE_CONFIG`;
  `EntityBucketBinding`: `routers -> platform`; `PlatformEntityLocationStrategy.resolveBucket`: `ROUTER`. Tests
  first in `ControllerSelectorTest` ("Router entity": `GET /v1/routers/platform/r1` selects the config resource
  controller; "File router entry") and `EntityBucketBindingTest` (`routers` binds `platform` only). The one-segment
  router routes are already covered by `testRouterRouteTakesOneSegment`.
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.controller.ControllerSelectorTest" --tests
  "com.epam.aidial.core.server.security.EntityBucketBindingTest"` pass.

## 2. Validator and merged store

- [x] 2.1 `ConfigPostProcessor`: `public static validateRouterInvariants(name, router, translators, warnings)`
  factored out of `processRouters` with the `setName` and the null-interceptors normalization (design D3), and
  `validateSingleRouter(Config, mapKey, onSkip)` shaped like `validateSingleInterceptor`. Tests in
  `ConfigPostProcessorTest`: the existing router cases (including `testRouterWithNullInterceptorsLoadsWithEmptyList`)
  still pass; `validateSingleRouter` removes an invalid router under `skip` and throws under `abort`; a direct call
  with `"interceptors": null` leaves an empty list and no warning.
- [x] 2.2 `MergedConfigStore` (design D2): `ROUTER` in `MANAGED_TYPES`, `deserializeReplicaEntity`,
  `addBlobEntity`, `removeAddedEntity`, `isShortNameKeyed`, `cloneTypeMap`, `peekEntity`, `putEntityInPlace`,
  `removeEntityInPlace`, and the `applyChangeInPlace` switch; remove the "file-only for now" comment. Tests first in
  `MergedConfigStorePartialUpdateTest` (a router write is visible in `get()`, a write with `"interceptors": null`
  holds an empty list, a delete removes it, an invalid router write under `skip` is recorded as invalid) and
  `MergedConfigStoreReplicaUpdateTest` (a router create and delete event from another pod is applied).
- [x] 2.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.config.ConfigPostProcessorTest" --tests
  "com.epam.aidial.core.server.config.MergedConfigStorePartialUpdateTest" --tests
  "com.epam.aidial.core.server.config.MergedConfigStoreReplicaUpdateTest"` pass.

## 3. Config entity API

- [x] 3.1 `ConfigResourceController` (design D4): `ROUTER` arms in `handleGet` (`handleSingleGetFromBlob` with
  `projectItem`), `descriptorFor`, `prepareWrite` (`WriteSpec(descriptor, Router.class, false, false)`),
  `entityClassFor`; `checkRouter` in the `PUT` flow with the merged translators; `rejectDuplicateDeploymentId`
  for `ROUTER`; the `getRouter` / `saveRouter` / `deleteRouter` OpenAPI blocks; the class javadoc lists routers.
  `ConfigResourceMetadataController`: the `getRouterMetadata` block.
- [x] 3.2 New `ConfigRouterTest` (integration, config `dial-config/routers.json`, mirroring `ConfigTranslatorTest`
  and the write cases of `ConfigEntityWriteApiTest`): "Blob router read back" (PUT 200 with `name` and `ETag`, GET
  200 with `name` and `status: valid`), file router `r1` not addressable on per-entity GET, metadata listing shows
  the blob router only, non-admin 403, `POST` 405, "Router with interceptors rejected on write", "Router with a
  translator mismatch rejected on write", "Router with upstreams rejected on write" (400), "Rejected write leaves
  nothing behind", "Router name taken by a model" (`gpt-4`), "Model name taken by a router" (`r1`), "Blob router
  serves at once" (listing by role `analysts` with `proxyKey1` and a proxied chat completion to the mock server on
  4848), "Deleted router gone at once", "Blob router survives a reload", "Blob router shadows the file router"
  (`userRoles: ["default"]`, listed for `proxyKey2`, not for `proxyKey1`), "Invalid router recorded under skip" (the
  class runs with `config.onInvalidEntity: skip`; a router blob with `interceptors` written to storage directly, then
  a reload, asserting the health endpoint and `status: invalid`). Write the tests before 3.1 and watch them fail.
- [x] 3.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.ConfigRouterTest" --tests "com.epam.aidial.core.server.ConfigEntityWriteApiTest"
  --tests "com.epam.aidial.core.server.ConfigResourceConditionalHeaderTest"` pass.

## 4. Manifests, file inspection, migration

- [x] 4.1 `AdminRouterManifest` record; `AdminTypedManifest` and `AdminManifest` `@ApiSubType`;
  `ConfigManifestSupport` (`DEPENDENCY_ORDER` `Router` = 4 with the later kinds shifted, `KIND_URL_SEGMENT`,
  `newScratch` copying the map into a new `HashMap`, `mutateScratch`, `parseManifest`); `ConfigValidationService`
  and `ConfigApplyService.applyManagedEntity` arms calling `validateRouterInvariants` and
  `validateDeploymentIdUniqueness` (design D3, D5). Tests first: `AdminValidateApiTest` "Router manifest with a
  legacy endpoint fails", "Router manifest in the public bucket", "Model manifest named after a file router fails",
  a router manifest with `upstreams` fails, a router manifest named after model `gpt-4` fails with the uniqueness
  message; `AdminApplyApiTest` "Router applied with its in-batch translator" (input order model, router,
  translator; asserts the result order), apply of the legacy-endpoint router with `"precheck": false` answers
  `FAILED` and writes nothing, a mixed batch with a failing router is rejected atomically with precheck; update the
  "Model (7)" comment at `AdminApplyApiTest` line 208.
- [x] 4.2 `FileConfigController` (`entitySource` arm, the two OpenAPI blocks) and `ConfigFileMigrateController`
  (`ALL_TYPES`, `ManagedTypeSpec` for routers) (design D6). Tests first: `ConfigRouterTest` reads file router `r1`
  through `GET /v1/admin/config/file/routers/r1` and lists it under `/v1/admin/config/file/routers`;
  `ConfigFileMigrateApiTest` "File router migrated" (with `dial-config/routers.json`, which already has file
  routers, so the shared migrate fixture keeps its counts) and a second run answers `skipped`.
- [x] 4.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.AdminValidateApiTest" --tests "com.epam.aidial.core.server.AdminApplyApiTest" --tests
  "com.epam.aidial.core.server.ConfigFileMigrateApiTest" --tests "com.epam.aidial.core.server.ConfigRouterTest"`
  pass.

## 5. Docs and OpenAPI

- [x] 5.1 `./gradlew :openapi-generator:replaceSpec`; verify `docs/open_api_core.yaml` gains the router config
  entity, metadata and file-config operations and the `Router` manifest subtype, and nothing else changes.
- [x] 5.2 `docs/dynamic-settings/routers.md`: a "Configuration API" section (design D7) including the mixed-fleet
  and rollback notes of the Migration Plan and the plain-text `defaultHeaders` note. Verify every URL and the
  manifest shape in it against a request in `ConfigRouterTest` or `AdminApplyApiTest`.
- [x] 5.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.ConfigRouterTest"` pass; `npx -y @fission-ai/openspec@1.14.3 validate
  router-config-api --strict` passes.

## 6. Integration check

- [x] 6.1 `./gradlew :server:test --tests "com.epam.aidial.core.server.Router*" --tests
  "com.epam.aidial.core.server.Config*" --tests "com.epam.aidial.core.server.Admin*" --tests
  "com.epam.aidial.core.server.config.*"` pass; the full suite runs in CI after the push.

## Workflow follow-up

- Write `review.md`, archive with `npx -y @fission-ai/openspec@1.14.3 archive router-config-api -y`, run
  `validate --specs --strict`, one commit staging explicit paths.
