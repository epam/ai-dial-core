# Review: router-type-schema

Implemented on `feat/router-deployment-type` at 59859f87 (PR #2232). The plan was reviewed before apply by an
independent agent with no context of the design discussion. It reported 19 findings, two of them blockers: a
request-time schema failure would hang the request, and null-valued router fields would break rollback. All 19 were
folded into the artifacts before coding.

## What changed

- Fields and pairing (D1, D2): `Router.applicationTypeSchemaId` and `applicationProperties`, application aliases,
  `@JsonInclude(NON_NULL)`. The pairing warning is in `ConfigPostProcessor.validateRouterInvariants`. Tests:
  `ConfigPostProcessorTest` (three cases), `ConfigRouterTest.untypedRouterStoredWithoutTheFields` and
  `routerPropertiesWithoutSchemaRejected`.
- File-load check (D3): `CustomApplicationsConformToTypeSchemasValidator` loops over applications and routers through
  one helper. Tests: `CustomApplicationsConformToTypeSchemasValidatorTest` (three router cases).
- Schema service (D3, D5): `build(String)` extracted from `resolveSchema`. `CachedSchema` keeps its source text, and
  `resolveSchema` rebuilds or drops the entry when the merged config's text differs or the schema is gone. Also added
  `consumeMetadataProperties(URI, Map, consumer)`, `validateProperties(Config, URI, Map, warnings)` and
  `routerPropertiesHeader(Router)`. Tests: `ApplicationSchemaServiceTest` (seven new cases, the 40 existing ones
  unchanged).
- Write paths (D3): `ConfigResourceController.checkRouter`, the `ConfigValidationService` router arm and the
  `ConfigApplyService` `ROUTER` block. Both services take `ApplicationSchemaService` (wired in `AiDial`). Tests:
  `ConfigRouterTest` (unknown schema, breaking properties, typed write and read back),
  `AdminValidateApiTest` (breaking manifest, and the soft-validation class rejecting on PUT),
  `AdminApplyApiTest.testApplyTypedRouterWithItsInBatchSchema`, `ConfigFileMigrateApiTest.testMigrateTypedFileRouter`.
- Header (D4): `ProxyContext.routerPropertiesHeader`, set in `decideIfRouter` (every router pass, both modes, before
  any connection; a failure is the `502`). It is sent by `sendProxyRequest` (not in translator mode), by
  `RouterDecisionClient.send`, and by `DeploymentFeatureController`, which builds it before connecting and answers the
  `502` itself. Tests: `RouterApiTest` (declared properties only and no `X-DIAL-APPLICATION-ID`, feature endpoint,
  header off and untyped router, translator gets none, schema changed under a stored router answers `502` with
  nothing sent), `RouterDecisionApiTest.typedRouterDecideRequestCarriesTheProperties`.
- Docs: `docs/dynamic-settings/routers.md` "Type schema and properties" section, field entries and rollback note.
  `docs/open_api_core.yaml` regenerated: five lines in the `Router` schema.

## Verification

`./gradlew checkstyleMain checkstyleTest` is clean. The `config` module tests pass (236). The targeted server run
of `Router*`, `Config*`, `Admin*`, `config.*`, `ApplicationSchemaServiceTest`, `Application*ApiTest`,
`CustomApplication*`, `DeploymentFeatureControllerTest` and `InvalidEntityApiTest` passes (745 tests, all green).
`checkOpenApiDiff` passes. `validate router-type-schema --strict` is valid. The full suite runs in CI after the
push: the user stopped the local full run.

## Drift from the plan, recorded in design.md and tasks.md

- The fixture gained router `r6` (translator mode) so that the claim "a translator gets no properties" has a test.
- The header builder is one method, `ApplicationSchemaService.routerPropertiesHeader`, shared by `decideIfRouter`
  and the feature controller.

## Notes

- Decision mode reaches the same `502` through the same `decideIfRouter` branch. The changed-schema test exercises it
  in proxy mode.
- The D5 cache change applies to applications too: a schema written through the Configuration API now takes effect
  on the next request instead of waiting for a force reload.
