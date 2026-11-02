# Tasks

## 1. Route invariants and PUT

- [x] 1.1 In `ConfigPostProcessor`, add `validateRouteInvariants(Route, List<ValidationWarning>)`, which calls
  `validateRouteUpstreams(route, warnings)` the way `validateModelInvariants` calls its sibling checks;
  `validateRouteUpstreams` drops its `entityLabel` and `entityName` parameters (the label is always "Route", the name is
  `route.getName()`) and is otherwise unchanged. Switch `processRoutes` and `validateSingleRoute` to the new
  method and update their Javadoc links. Verify that `./gradlew :server:test --tests
  "com.epam.aidial.core.server.config.ConfigPostProcessorTest" --tests
  "com.epam.aidial.core.server.config.MergedConfigStorePartialUpdateTest"` passes unchanged.
- [x] 1.2 In `ConfigResourceController`, add `checkRoute(Route)` beside `checkTranslator` and `checkOverridePaths`: it
  calls `ConfigPostProcessor.validateRouteInvariants` and rejects through `rejectWithValidationWarnings`; call it from
  the `instanceof` chain in `handlePut` so it runs on the merged entity, before `putResource`. Verify with 1.3 and 1.4.
- [x] 1.3 In `ConfigEntityWriteApiTest` add `testRoutePutUpstreamWithoutKeyRejected`: a `BEARER` and a `CUSTOM_HEADER`
  upstream without a key each answer `422` with field `upstreams[0].authType` and a message containing
  `requires a key`; `GET` answers `404`; a retry with a key and `If-None-Match: *` answers `200` (scenarios "Bearer
  route without a key", "Custom header route without a key"). Verify with
  `./gradlew :server:test --tests "com.epam.aidial.core.server.ConfigEntityWriteApiTest"`.
- [x] 1.4 In `ConfigEntityWriteApiTest` add `testRoutePutOmittedUpstreamKeyKeptOnUpdate`: store a `BEARER` route with a
  key, `PUT` it again without the key, expect `200` for the `PUT` and for `GET` (scenario "Update without the key").
  Same command as 1.3.
- [x] 1.5 Run `./gradlew checkstyleMain checkstyleTest` and the test classes of 1.1 and 1.3.

## 2. Refuse the route in validate and apply

- [x] 2.1 In `ConfigValidationService.validateOnly` replace the empty `AdminRouteManifest` arm with a call to
  `validateRouteInvariants`, returning `FAILED` with `ConfigManifestSupport.joinWarnings`. In
  `ConfigApplyService.applyManagedEntity` add a `ROUTE` branch before the encrypt and put, returning `FAILED` the same
  way. Verify with 2.2 and 2.3.
- [x] 2.2 In `AdminValidateApiTest` add `testValidateRouteUpstreamWithoutKey`: `precheck: false`, `valid: 0`,
  `failed: 1`, the result `FAILED` with an `error` containing `requires a key`, and `GET` `404` (scenario
  "Validate"). Verify with `./gradlew :server:test --tests "com.epam.aidial.core.server.AdminValidateApiTest"`.
- [x] 2.3 In `AdminApplyApiTest` add `testApplyRouteUpstreamWithoutKeyFails`: a valid `Interceptor` and the bad
  `Route` with `precheck: false` give `applied: 1`, `failed: 1`, the route `FAILED`, the interceptor `200` on `GET`
  and the route `404` (scenario "Apply"). Verify with `./gradlew :server:test --tests
  "com.epam.aidial.core.server.AdminApplyApiTest"`.
- [x] 2.4 Update `docs/dynamic-settings/routes.md` (the `authType` line, "`BEARER` requires `key` to be set") to say the
  configuration API and `/v1/admin/validate` and `/v1/admin/apply` refuse such a route with `422` or `FAILED`.
  Verify by reading the rendered paragraph against the spec requirement.
- [x] 2.5 Run `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`.

## 3. Integration checks

- [x] 3.1 Run `npx -y @fission-ai/openspec@1.14.3 validate validate-routes-on-write --strict`; it passes. The
  `validate --all --strict` failures on the `resource-subscription` and `user-limits` specs (placeholder `Purpose`) predate
  this change and are not part of it.
- [x] 3.2 Run `ConfigPostProcessorTest`, `MergedConfigStorePartialUpdateTest` and `ConfigRouteTest` with `--tests` to
  confirm the rebuild and request-time behaviour of routes is unchanged.

## Workflow follow-up

- Write `review.md` in the change folder, archive with `npx -y @fission-ai/openspec@1.14.3 archive
  validate-routes-on-write -y`, then `validate --specs --strict`; commit with explicit paths.
