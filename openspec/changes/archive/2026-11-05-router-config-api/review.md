# Review: router-config-api

Implemented on `feat/router-config-api`, branched from `feat/router-deployment-type` at 4869f3e7 (PR #2232). The
plan was reviewed before apply by an independent agent with no context of the design discussion; its findings
(14) were folded into the artifacts before coding.

## What changed

- Type and routes (D1): `ResourceTypes.of("routers")`; `routers` in `RouteTemplate.CONFIG_RESOURCE`,
  `CONFIG_RESOURCE_METADATA` and `ADMIN_FILE_CONFIG`; `EntityBucketBinding` binds `routers` to `platform` only;
  `PlatformEntityLocationStrategy` accepts `ROUTER`. Tests: `ControllerSelectorTest` (config resource, metadata and
  file config selection), `EntityBucketBindingTest`.
- Validator (D3): `ConfigPostProcessor.validateRouterInvariants` (names the router, empties a null interceptor
  list, the three rule checks) shared by `processRouters`, the new `validateSingleRouter`, the PUT check, validate
  and apply. Tests: `ConfigPostProcessorTest` (three new cases; the existing router cases unchanged).
- Merged store (D2): `ROUTER` in `MANAGED_TYPES` and every per-type switch of `MergedConfigStore`. Tests:
  `MergedConfigStorePartialUpdateTest` (write, null interceptors, delete, skip records, abort throws),
  `MergedConfigStoreReplicaUpdateTest` (create and delete events, event filter).
- Entity API (D4): `ConfigResourceController` arms, `checkRouter`, the deployment-id check for routers, OpenAPI
  blocks; metadata and file config OpenAPI blocks; `FileConfigController` source arm. Tests: `ConfigRouterTest`
  (15 cases, every config-store and router-deployment scenario of the change at the HTTP boundary).
- Manifests (D5): `AdminRouterManifest`, `Router` at order 4, `newScratch` copies the routers map, validate and
  apply arms. Tests: `AdminValidateApiTest` (6 cases, among them a Model manifest named after a file router, which
  passed before this change), `AdminApplyApiTest` (in-batch translator with the processing order asserted, failure
  without precheck, atomic rejection with precheck).
- Migration (D6): `ConfigFileMigrateController` type `routers`. Test: `ConfigFileMigrateApiTest.testMigrateFileRouters`.
- Docs (D7): `docs/dynamic-settings/routers.md` "Configuration API" section and the rollback note;
  `docs/open_api_core.yaml` regenerated (additions only).

## Verification

`./gradlew checkstyleMain checkstyleTest` clean. Targeted run of `Router*`, `Config*`, `Admin*`, `config.*`,
`InvalidEntityApiTest`, `ControllerSelectorTest`, `EntityBucketBindingTest`: 687 tests, all pass. Full `:server:test`:
3259 tests, 2 failures in `HttpServerInstancesApiTest.testConcurrentStreamedRequests` (a token limit hit under
concurrency) and `ResponseItemControllerTest.testDeploymentInterceptorForwardsToInterceptor` (a Mockito default
answer); neither touches the config API, and both classes pass on two isolated reruns. `checkOpenApiDiff` (lint and
diff) passes; its ambiguous-path warnings are pre-existing and none concerns a router path.
`validate router-config-api --strict` valid.

## Drift from the plan, recorded in design.md and tasks.md

- The file load (`FileConfigStore.load` calling `ConfigPostProcessor.process` with no `onSkip`) is strict whatever
  `config.onInvalidEntity` says, so a file router that breaks its own rules stops startup as before and is never
  recorded. The skip scenario uses a stored router instead.
- `getRouter` is the operation id of the router listing; the config operations are `getPlatformRouter`,
  `savePlatformRouter`, `deletePlatformRouter`, as for platform applications.
- The migration test uses `dial-config/routers.json` rather than adding a router to the shared migrate fixture.

## Notes

- No class on the request path changed; the effect per request is none.
- `routers.md` warns that a router's `defaultHeaders` are stored in plain text, as an interceptor's are.
