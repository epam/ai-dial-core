# Tasks

Spec scenario to test: `router-deployment` "Typed router loads", "File router that breaks its schema":
`FileConfigStoreTest` or `CustomApplicationsConformToTypeSchemasValidatorTest`. "Properties without a schema
rejected", "Untyped router stored without the fields", "Unknown schema", "Properties that break the schema":
`ConfigRouterTest`. "Schema and router in one batch": `AdminApplyApiTest`. "Proxied request", "Feature endpoint",
"Header turned off", "Router without a schema", "Schema changed under a stored router": `RouterApiTest`. "Decide
request": `RouterDecisionApiTest`. `config-store` "Typed router properties rejected on write": `ConfigRouterTest`
(soft-validation nested class) or `AdminValidateApiTest.SoftValidation`. `deployment-origin` "Typed router gets no
application id": `RouterApiTest`.

## 1. Fields, the pairing rule and the file-load check

- [x] 1.1 `Router`: the two fields with the aliases and `@JsonInclude(NON_NULL)` (design D1).
  `validateRouterInvariants`: the pairing warning (D2). Tests first in `ConfigPostProcessorTest`: properties
  without a schema and a schema without properties are rejected with the message, `{}` counts as set, a router with
  both passes.
- [x] 1.2 `CustomApplicationsConformToTypeSchemasValidator` covers `getRouters()` (D3). Tests first in its test
  class: a router whose properties break a file schema fails, a valid one passes, an untyped one is skipped.
- [x] 1.3 Fixture `dial-config/routers-typed.json`. The schemas carry `dial:applicationTypeDisplayName`, every
  top-level property carries `dial:meta` with `dial:propertyOrder` and `dial:propertyKind`, there is no
  `additionalProperties: false`, and there is no endpoint.
  - `https://dial.test/router-type`: `tier` a required server string, `preferredModels` a client string array.
  - `https://dial.test/router-quiet`: the same with `dial:appendApplicationPropertiesHeader: false`.
  - `r3`: proxy mode, `router-type`, properties `{"tier": "medium", "preferredModels": ["gpt-4"], "undeclared": 1}`,
    `features.rateEndpoint`.
  - `r4`: `router-type`, `openaiChatCompletions` in decision mode.
  - `r5`: `router-quiet`.
  - `r6`: `router-type`, `anthropicMessages` in translator mode (added during apply, to test that a translator gets
    no properties).
  - `r1`: untyped.
  - Model `gpt-4`, key `proxyKey1`.

  Verify it loads in a `RouterApiTest` case ("Typed router loads").
- [x] 1.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :config:test --tests
  "*CustomApplicationsConformToTypeSchemasValidatorTest"` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.config.ConfigPostProcessorTest"` pass.

## 2. Schema service, cache and write-time validation

- [x] 2.1 `ApplicationSchemaService`: extract `build(String schemaText)` from `resolveSchema`; `CachedSchema` keeps
  its source text and `resolveSchema` rebuilds or drops on a mismatch (D5); `consumeMetadataProperties(URI, Map,
  consumer)` with the `Application` overload delegating (D4); `validateProperties(Config, URI, Map, warnings)` with
  the warnings of D3. Tests first in `ApplicationSchemaServiceTest`: unknown schema, invalid properties (joined
  messages), valid properties, `validateProperties` leaves the cache untouched, the overload returns the declared
  properties only, and a changed schema text in the config rebuilds the cached entry while unchanged text with a
  download endpoint does not download again.
- [x] 2.2 Wire `ApplicationSchemaService` into `ConfigValidationService` and `ConfigApplyService` (`AiDial`);
  `ConfigResourceController.checkRouter`, the validate arm and the apply block call `validateProperties` when the
  schema id is set (D3). Tests first: `ConfigRouterTest` "Properties without a schema rejected", "Untyped router
  stored without the fields" (read the blob through `ResourceService`), "Unknown schema", "Properties that break the
  schema", a valid typed router `PUT` answering `200` and reading back both fields, and "Typed router properties
  rejected on write" under soft validation; `AdminValidateApiTest` a router manifest with breaking properties
  `FAILED`; `AdminApplyApiTest` "Schema and router in one batch" (router listed first); `ConfigFileMigrateApiTest`
  a typed file router migrates.
- [x] 2.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.service.ApplicationSchemaServiceTest" --tests
  "com.epam.aidial.core.server.ConfigRouterTest" --tests "com.epam.aidial.core.server.AdminValidateApiTest" --tests
  "com.epam.aidial.core.server.AdminApplyApiTest" --tests "com.epam.aidial.core.server.ConfigFileMigrateApiTest"`
  pass.

## 3. The header

- [x] 3.1 `ProxyContext.routerPropertiesHeader`; `decideIfRouter` builds it or fails with the `502` (D4);
  `sendProxyRequest` puts it for a non-translator router interface; `RouterDecisionClient.send` puts it;
  `DeploymentFeatureController` builds and puts it, `502` before connecting on failure. Tests first: `RouterApiTest`
  "Proxied request" (parse the header JSON and compare as a tree), "Typed router gets no application id", "Feature
  endpoint" (`/v1/r3/rate`), "Header turned off" (`r5`), "Router without a schema" (`r1`), "Schema changed under a
  stored router" (store the schema and a typed router through the API, replace the schema with one requiring a new
  property, call the router: `502`, the mock server receives nothing); `RouterDecisionApiTest` "Decide request"
  (header on the decide request, absent on the request `gpt-4` receives).
- [x] 3.2 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterApiTest" --tests "com.epam.aidial.core.server.RouterDecisionApiTest"` pass.

## 4. Docs and OpenAPI

- [x] 4.1 `./gradlew :openapi-generator:replaceSpec`; verify the `Router` schema gains the two properties and
  nothing else changes; `./gradlew :openapi-generator:checkOpenApiDiff` passes.
- [x] 4.2 `docs/dynamic-settings/routers.md`: the two fields in `routers.<router_name>`, the pairing rule, where
  properties are validated, the header and where it goes and does not go, the `502`, that the schema contributes
  nothing else, that a router schema is listed among application types, that properties are plain text (stored,
  exported, read back, logged at debug) so no secrets, and the rollback note (D6, Risks). Verify each statement
  against a test of groups 1 to 3.
- [x] 4.3 `./gradlew checkstyleMain checkstyleTest` passes and `npx -y @fission-ai/openspec@1.14.3 validate
  router-type-schema --strict` passes.

## 5. Integration check

- [x] 5.1 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.Router*" --tests "com.epam.aidial.core.server.Config*" --tests
  "com.epam.aidial.core.server.Admin*" --tests "com.epam.aidial.core.server.config.*" --tests
  "com.epam.aidial.core.server.service.ApplicationSchemaServiceTest" --tests
  "com.epam.aidial.core.server.Application*ApiTest" --tests "com.epam.aidial.core.server.CustomApplication*"` pass
  (the last two cover the D5 cache change for applications); the full suite runs in CI after the push.

## Workflow follow-up

- Write `review.md`, archive, `validate --specs --strict`, one commit staging explicit paths, push to PR #2232.
