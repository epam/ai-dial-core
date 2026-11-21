# Tasks

Spec scenario to test (`router-deployment`, "Router editor URL"): "Blob router editor URL read back",
"Snake-case name accepted", "Router without an editor URL stored without the key" (extends
`untypedRouterStoredWithoutTheFields`), "File router editor URL read back": `ConfigRouterTest`. "Editor URL not
listed": `RouterApiTest`.

## 1. The field, its read paths and the listings

- [x] 1.1 Tests first. Fixture `dial-config/routers.json`: `r2` gains `editorUrl: "https://router-ui.example"`.
  `ConfigRouterTest`: `editorUrlReadBack` (PUT camel case, GET returns it), `editorUrlSnakeCaseAlias` (PUT
  `editor_url`, the stored blob read through `ResourceService` has `editorUrl` and no `editor_url`),
  `fileRouterEditorUrl` (`GET /v1/admin/config/file/routers/r2`), and `untypedRouterStoredWithoutTheFields` also
  asserts no `editorUrl` key. `RouterApiTest`: `editorUrlNotListed` (`GET /v1/routers`, `/v1/routers/r2`, `/v1/deployments` and
  `/v1/deployments/r2` with `proxyKey2`: no `editor_url` or `editorUrl` in the `r2` entry). Verify that the first
  three `ConfigRouterTest` cases fail before 1.2 (a `400`, or the file load rejecting `r2`).
- [x] 1.2 `Router`: `String editorUrl` with `@JsonAlias({"editorUrl", "editor_url"})` and
  `@JsonInclude(NON_NULL)`, and the class javadoc names it (design D1). `RouterData` unchanged (D2). Verify with 1.3.
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.ConfigRouterTest" --tests "com.epam.aidial.core.server.RouterApiTest" --tests
  "com.epam.aidial.core.server.ConfigFileMigrateApiTest" --tests "com.epam.aidial.core.server.AdminValidateApiTest"
  --tests "com.epam.aidial.core.server.config.FileConfigStoreTest"` pass (the last three also load
  `routers.json`).

## 2. Docs and OpenAPI

- [x] 2.1 `./gradlew :openapi-generator:replaceSpec`; verify the `Router` schema in `docs/open_api_core.yaml` gains
  `editorUrl` and nothing else changes (`git diff docs/open_api_core.yaml` adds only that property);
  `./gradlew :openapi-generator:checkOpenApiDiff` passes.
- [x] 2.2 `docs/dynamic-settings/routers.md`: `editorUrl` in `routers.<router_name>` (the URL of the router's admin
  UI, which DIAL Admin can embed; not validated; returned by the Configuration API reads, not by the listings), and
  the rollback note (remove the field before a downgrade). Verify each statement against a test of group 1.
- [x] 2.3 `npx -y @fission-ai/openspec@1.14.3 validate router-editor-url --strict` passes.

## Workflow follow-up

- Write `review.md`, archive, `validate --specs --strict`, one commit staging explicit paths.
