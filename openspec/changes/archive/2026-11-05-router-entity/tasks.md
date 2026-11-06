# Tasks

Spec scenario to test: `router-deployment` "Router loads with interfaces", "Chat completion proxied to the router",
"Messages request proxied to the router", "Embeddings proxied to the router", "Interface the router does not declare", "Listing filtered by role",
"Single router by role", "Router hidden from the model and deployment listings", "Exhausted request limit on the
router does not refuse", "Model called back is still charged", "JWT caller", "Project-key caller", "Not added for
an application": `RouterApiTest` (new, integration). "Router with upstreams rejected", "Router name taken by a
model": `FileConfigStoreTest`. "Router with interceptors rejected", "Router with a legacy endpoint rejected", "Pass-through entry with no url
anywhere", "Translator without mode": `ConfigPostProcessorTest`. `request-routing` "Router listing", "Single router":
`ControllerSelectorTest`. `app-to-model-accounting` "Router not checked on the Messages API": `RouterApiTest`.
`deployment-origin` "Client identity header dropped for a model", "Router identity header": `RouterApiTest`.

## 1. Config entity

- [x] 1.1 Add `config/.../Router.java` (`extends Deployment`, design D1), `Config.routers` with the namespace comment,
  and `Config.selectDeployment` looking routers up before interceptors; verify `./gradlew :config:compileJava`.
- [x] 1.2 Add `ResourceTypes.ROUTER("routers", true, 30 days)` to the enum and `EVENT_TYPES`, not to `of()` (design D4);
  verify the existing `storage` tests still pass with `./gradlew :storage:test`.
- [x] 1.3 Add the test fixture `server/src/test/resources/dial-config/routers.json`: routers `r1` (roles
  `analysts`, `baseUrl` on the test server port, `openaiChatCompletions` and `anthropicMessages` interfaces) and
  `r2` (no roles, chat only), a model `gpt-4` on the same server, a project key and a role with
  `limits.r1.requestMinute: 1`, `limits.r1.requestHour: 1`; verify it loads in a `FileConfigStoreTest` case.
- [x] 1.4 `ConfigPostProcessor`: `rejectSlashKeyedNames` for routers in `processStructural`; `processRouters` in
  `processSemantic` after `processToolSets` (dedupe, `setName`, interfaces validation, `A router declares no
  interceptors`, `A router declares interfaces, not endpoint`); `ROUTER` in `isDeploymentIdTakenByAnotherDeploymentType`; widen `validateDeploymentInterfaces`
  and `validateTranslatedInterface` to `Deployment` with a kind name (design D2). Tests first in
  `ConfigPostProcessorTest`: "Router with interceptors rejected", "Router with a legacy endpoint rejected", "Pass-through entry with no
  url anywhere" (asserting the `Router 'r1'` prefix and the `router declares no baseUrl` tail), "Translator
  without mode", and a model case asserting the `Model 'm': ...` message is unchanged.
- [x] 1.5 `FileConfigStoreTest`: "Router with upstreams rejected" (config with `routers.r1.upstreams` fails to
  load) and "Router name taken by a model" (`models.x` plus `routers.x` fails under the default policy); verify
  both fail before 1.4 and pass after.
- [x] 1.6 `MergedConfigStore`: copy and set the router map in `rebuildMergedConfig`, copy it in `shallowClone`
  (design D3). Test in `MergedConfigStorePartialUpdateTest`: a partial update of a model keeps the file routers.
- [x] 1.7 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.config.ConfigPostProcessorTest" --tests
  "com.epam.aidial.core.server.config.FileConfigStoreTest" --tests
  "com.epam.aidial.core.server.config.MergedConfigStorePartialUpdateTest"` pass.

## 2. Request path

- [x] 2.1 Write the failing `RouterApiTest` cases (new class, `extends ResourceBaseTest`,
  `@DialConfigLocation("dial-config/routers.json")`, a `TestWebServer` as the router and the model): "Chat
  completion proxied to the router" (asserts the origin path, a per-request `API-KEY`, the features header),
  "Messages request proxied to the router", "Embeddings proxied to the router", "Interface the router does not
  declare" (`503`, no origin call).
- [x] 2.2 `UpstreamRouteProvider.getKey`: `case Router ignored -> "router"` (design D5); verify the three 2.1 cases pass.
- [x] 2.3 `BaseDeploymentPostController.isSubjectToLimits`: false for a `Router` (design D5). Tests first in
  `RouterApiTest`: "Exhausted request limit on the router does not refuse" (`/openai/v1/chat/completions`,
  second request still forwarded), "Router not checked on the Messages API", and "Model called back is still
  charged" (the router stub calls Core back inline with the received `API-KEY` for `gpt-4`, which answers with
  `usage.total_tokens: 15`; assert the limit record through `GET /v1/deployments/gpt-4/limits`).
- [x] 2.4 Identity header (design D6): `Proxy.HEADER_USER_INFO`, the exclude-list entry in `sendProxyRequest`, the
  `instanceof Router` branch building the JSON from the user-info values with non-ASCII escaped. Tests first in
  `RouterApiTest`: "JWT caller" (client sends a forged `X-DIAL-USER-INFO`, origin sees exactly one with
  `userId`, `roles`, no `project`), "Project-key caller", "Not added for an application", "Client identity header
  dropped for a model", and a `GET /openai/v1/responses/{id}` on a router carrying it
  (`responseItemCallCarriesTheIdentityHeader`).
- [x] 2.5 Grep `server/src/main` for `instanceof Model`, `instanceof Application`, `case Model`, `case Application`
  and `switch (deployment)`; record in design.md any switch without a default that a router can reach, and fix it
  with its test. Expected: none beyond D5.
- [x] 2.6 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterApiTest" --tests "com.epam.aidial.core.server.DeploymentApiTest" --tests
  "com.epam.aidial.core.server.ApplicationInterfacesApiTest"` pass.

## 3. Listing

- [x] 3.1 `RouteTemplate.ROUTERS` and `ROUTER` (one-segment id), registered in `ControllerSelector` next to
  `MODELS`/`MODEL` (design D7). Tests first in `ControllerSelectorTest`: "Router listing", "Single router"
  (percent-decoded id), and `/v1/routers/platform/x` does not match `ROUTER`.
- [x] 3.2 `RouterData` (cut from `ToolSetData.toData`, `object: "router"`, no url) and `RouterController`
  (`getRouters`, `getRouter`, cut from `ModelController`) with `@ApiOperation` annotations. Tests first in
  `RouterApiTest`: "Listing filtered by role" (asserts `object`, absence of `base_url` and `endpoint`), "Single
  router by role" (`403`, `200`, `404`), "Router hidden from the model and deployment listings" (`/openai/models`,
  `/openai/deployments`, `/openai/applications`, `/v1/deployments`, `/v1/deployment-names`, `/anthropic/v1/models`,
  and `GET /v1/deployments/r1` is `404`).
- [x] 3.3 Add `RouterController` to `AnnotationEndpointCollector.CONTROLLER_CLASSES`, run
  `./gradlew :openapi-generator:replaceSpec`, commit the regenerated `docs/open_api_core.yaml`; verify the
  generator's spec check task passes and the diff holds only the two router paths and `RouterData`.
- [x] 3.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterApiTest" --tests
  "com.epam.aidial.core.server.controller.ControllerSelectorTest" --tests
  "com.epam.aidial.core.server.ListingTest"` pass.

## 4. Docs

- [x] 4.1 Write `docs/dynamic-settings/routers.md` (design D8): the map and its fields, a link to the `interfaces`
  section of `models.md` (check the rendered anchor), no upstreams / limits / interceptors / legacy endpoint, what
  `GET /v1/deployments/<router>/limits` shows, `GET /v1/routers[/{id}]`, the `X-DIAL-USER-INFO` header with its JSON
  shape and the header-size ceiling, with a JSON example that is the 1.3 fixture's `r1`;
  verify the example loads by pasting it into a `FileConfigStoreTest` case or the fixture itself.
- [x] 4.2 Add the `routers` row to the README dynamic-settings table and the word `routers` to CLAUDE.md's
  dynamic-config line; verify both render with the links resolving.
- [x] 4.3 `./gradlew checkstyleMain checkstyleTest` (no Java changed in this group; the command is the group's gate).

## 5. Integration checks

- [x] 5.1 `npx -y @fission-ai/openspec@1.14.3 validate router-entity --strict` passes and every scenario listed at the
  top of this file names a test that exists and passes.
- [x] 5.2 `./gradlew :server:test` (full module suite) passes locally, or the failures are shown to be unrelated
  with the output in review.md.
- [x] 5.3 Compare design.md with the code as merged; record drift, if any, in design.md.

## Workflow follow-up

- Write review.md in the change folder, then archive with `npx -y @fission-ai/openspec@1.14.3 archive router-entity -y`
  and run `validate --specs --strict`.
- Next change: `router-decision-mode` (depends on this one); later: the Configuration API for routers (touch list in
  design.md).
