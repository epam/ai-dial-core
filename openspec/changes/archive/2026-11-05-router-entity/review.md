# Review: router-entity

Record of the sdd-apply run on branch `feat/router-deployment-type`.

## Implementation

Tasks done: 22. Deferred: 5.2 (full `:server:test` suite) was not run locally, because the full suite runs in CI after the push. The task is left unticked in tasks.md and is not marked deferred there.

Files changed:

- config: `Router.java` (new), `Config.java`
- storage: `ResourceTypes.java`
- server main: `ConfigPostProcessor`, `MergedConfigStore`, `UpstreamRouteProvider`, `BaseDeploymentPostController`, `DeploymentFeatureController`, `DeploymentController`, `ControllerSelector`, `RouterController` (new), `RouterData` (new), `RouteTemplate`, `Proxy`, `ProxyUtil`; in fix round 1, `ResponsesApiClient` and `ResponseItemController`
- openapi-generator: `AnnotationEndpointCollector`; `docs/open_api_core.yaml` regenerated
- docs: `docs/dynamic-settings/routers.md` (new), `README.md`, `CLAUDE.md`
- tests: `RouterApiTest` (new), `dial-config/routers.json` fixture (new), `ConfigPostProcessorTest`, `FileConfigStoreTest`, `MergedConfigStorePartialUpdateTest`, `ControllerSelectorTest`; in fix round 1, `ResponseItemControllerTest`
- change artifacts: `design.md`, `tasks.md`, `specs/router-deployment/spec.md`

Tests added:

- `RouterApiTest`: 16 integration cases, plus 1 added in fix round 1. They cover chat, messages and embeddings proxied; an undeclared interface answers 503; an exhausted request limit does not refuse; Messages is not checked; a model called back by the router is charged 15 tokens; a JWT caller; a non-ASCII user id escaped; a project-key caller; a feature endpoint carries the header; the header is not added for an application; a client header is dropped for a model; the listing is filtered by role; a single router answers 403/200/404; routers are hidden from 6 listings and `/v1/deployments/r1` answers 404. Fix round 1 added `responseItemCallCarriesTheIdentityHeader`.
- `ConfigPostProcessorTest`: router interceptors rejected; legacy endpoint rejected; pass-through with no url (exact message `Router 'r1': ... the router declares no baseUrl`); translator without mode; skip mode plus namespace map; router/model duplicate; slash-keyed router dropped; model message unchanged.
- `FileConfigStoreTest`: fixture router loads with interfaces; router with upstreams rejected; router name taken by a model rejected.
- `MergedConfigStorePartialUpdateTest.modelWriteKeepsFileRouters`.
- `ControllerSelectorTest`: `GET /v1/routers`; `GET /v1/routers/my%20router` decodes the id; the ROUTER pattern rejects `/v1/routers/platform/x`.

Design drift (recorded in design.md "## Implementation notes"):

1. D2: the widened interface validators take no kind-name parameter. They use `deployment.getClass().getSimpleName()` as the prefix, as `validateOverridePaths` in the same class already does. `validateTranslatorOutput` is widened too. Model messages are byte-identical.
2. `Limit` has no `requestMinute` field and the mapper rejects unknown fields. The fixture uses `requestHour`/`requestDay`, and the router-deployment scenario "Exhausted request limit on the router does not refuse" now says requestHour / within one hour. The delta was rebased on the current spec and still validates.
3. D6: the header is built in `ProxyUtil.buildUserInfoHeader` and also used by `DeploymentFeatureController` (`/v1/{id}/rate` and the other feature calls), which forwards to a router on its own header-copy path. That path now drops a client `X-DIAL-USER-INFO` for every deployment and adds Core's value for a router. After fix round 1, response-item calls also carry it (see Fix round 1).
4. The 2.5 sweep found only `UpstreamRouteProvider.getKey`. `DeploymentController.toDeploymentData` already has a default that answers 404; only its comment was updated.
5. The JWT test uses roles `[analysts, b]` because r1 admits only `analysts`.
6. The regenerated `open_api_core.yaml` also gains `routers`/`MapStringRouter`/`Router` under the Config schema and `ROUTER` in the resource-type enum, beyond the two paths and `RouterData`.

The cited design line numbers still held. The other spec deltas (app-to-model-accounting, deployment-origin, request-routing) matched the current requirement text unchanged.

Implementer's own checks:

- `./gradlew checkstyleMain checkstyleTest` (JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64): passed after two violations were fixed (a misplaced Javadoc in `ProxyUtil`, an abbreviation in a test name).
- Tests: 0 failures, 706 tests in the result files. Ran `:config:test`, `:storage:test`, `:openapi-generator:test`, and `:server:test --tests` for RouterApiTest, ControllerSelectorTest, ConfigPostProcessorTest, FileConfigStoreTest, MergedConfigStorePartialUpdateTest, DeploymentApiTest, ApplicationInterfacesApiTest, ListingTest, LimitApiTest, OverridePathsApiTest, OverridePathsWriteApiTest, ConfigTranslatorTest, UserInfoApiTest, MessagesSpendLimitApiTest, ResponsesSpendLimitApiTest.
- `./gradlew :openapi-generator:replaceSpec` regenerated `docs/open_api_core.yaml`. The implementer did not run `checkOpenApiDiff` (it needs the npx redocly lint step); the first verification ran it.
- `npx -y @fission-ai/openspec@1.14.3 validate router-entity --strict`: valid.
- `git status --short`: every untracked file belongs to the change except `playground-unified-config.html`, which was already untracked at session start. Nothing was committed.

## Verification

### Run 1 (after implementation): passed

JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64. Every server test task ran with `--rerun`; all 22 fresh server and storage result XMLs show 0 failures and 0 errors.

| Check | Result |
|---|---|
| `checkstyleMain checkstyleTest` | exit 0 |
| RouterApiTest | 16 tests, 0 failures |
| config.ConfigPostProcessorTest | 58, 0 failures |
| config.FileConfigStoreTest | 12, 0 failures |
| config.MergedConfigStorePartialUpdateTest | 13, 0 failures |
| controller.ControllerSelectorTest | 58, 0 failures |
| DeploymentApiTest | 9, 0 failures |
| ApplicationInterfacesApiTest | 5, 0 failures |
| ListingTest | 20, 0 failures |
| `:config:test --rerun` (whole module) | passed |
| `:storage:test --rerun` (whole module) | passed |
| `:openapi-generator:checkOpenApiDiff` | passed; committed spec is current |
| `openspec validate router-entity --strict` | valid |

Cost-claim check: design.md "Per-request cost" claims a router request costs the same as an application request (one Core pass, one body read and parse, the same key write, token-stats span and analytics row), plus one small JSON serialization for `X-DIAL-USER-INFO`, and removes the router's own `GET /v1/user/info` call (one Core request with a Redis read). For other deployments the only cost is one more name in the header exclude list. No measured cost effect is claimed. The diff matches:

- `BaseDeploymentPostController.sendProxyRequest` and `DeploymentFeatureController` add `HEADER_USER_INFO` to the exclude list and, under `instanceof Router`, call `ProxyUtil.buildUserInfoHeader`, which builds an `ObjectNode` from in-memory context values and serializes it with a cached `ObjectWriter` (ESCAPE_NON_ASCII). No I/O, Redis, blocking call or new Future.
- The header logic also runs on the `DeploymentFeatureController` path; recorded in design.md.
- `isSubjectToLimits` short-circuits for a Router, so it does less work.
- `UpstreamRouteProvider.getKey` gains one switch case.
- No request body is newly parsed.
- `RouterController.getRouters`/`getRouter` iterate the in-memory config map synchronously, as `ModelController` does.
- `ConfigPostProcessor` and `MergedConfigStore` changes run only at config load or rebuild. Routers are file-only; `MANAGED_TYPES` is untouched; no blob or Redis access was added.

Untracked files each map to a task (1.1, 1.3, 2.1, 3.2, 4.1), except `playground-unified-config.html` (dated 2026-06-08, earlier unified-config work). It was not counted as a failure and must not be staged in this change's commit.

### Run 2 (after fix round 1): passed

Repo `/home/siarhei_fedziukovich/projects/dial/github/ai-dial-core`, branch `feat/router-deployment-type`, JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64. BUILD SUCCESSFUL in 1m 9s.

| Check | Result |
|---|---|
| `checkstyleMain checkstyleTest` | exit 0 |
| RouterApiTest | 17 tests, 0 failures, 0 errors |
| config.ConfigPostProcessorTest | 58, 0 failures |
| config.FileConfigStoreTest | 12, 0 failures |
| config.MergedConfigStorePartialUpdateTest | 13, 0 failures |
| controller.ControllerSelectorTest | 58, 0 failures |
| controller.ResponseItemControllerTest | 17, 0 failures (a `RejectedExecutionException 'event executor terminated'` in the log is teardown noise) |
| DeploymentApiTest | 9, 0 failures |
| ApplicationInterfacesApiTest | 5, 0 failures |
| ListingTest | 20, 0 failures |
| `:config:test` (whole module) | passed; Gradle reported up-to-date from an earlier passing run on the same inputs |
| `:storage:test` (whole module) | passed; Gradle reported up-to-date from an earlier passing run on the same inputs |
| `openspec validate router-entity --strict` | valid |

Cost-claim check: the code still matches design.md "Per-request cost". `ResponseItemController.sendRequest` computes the header value only for a Router and passes it to `ResponsesApiClient.send`, which puts the header inside the existing compose chain; that chain's failure handling is unchanged. `buildUserInfoHeader` cannot null-dereference, because `ProxyContext` guarantees either a key or claims. One addition the design does not mention: `Config.selectDeployment` now does one more in-memory HashMap lookup per request for every deployment. It does no I/O and its cost is negligible. No new blocking call on the event loop, no body parsed where it was only forwarded, no added Redis call, no new Future chain without a failure handler.

Untracked files: the same set as run 1, each mapped to a task; `playground-unified-config.html` again excluded.

The final verification is run 2; no further rounds ran.

## Review findings

| Lens | Severity | File:line | Finding |
|---|---|---|---|
| correctness | medium | server/src/main/java/com/epam/aidial/core/server/controller/ResponseItemController.java:222 | Response-item operations (GET, DELETE, cancel, input_items on `/openai/v1/responses/{id}`) forwarded to a router did not carry `X-DIAL-USER-INFO`; `ResponsesApiClient.send` set only API-KEY and upstream headers. The spec says Core SHALL add the header to every request it forwards to a router. |
| conformance | low | docs/dynamic-settings/routers.md:64 | The docs and proposal say a client-sent `X-DIAL-USER-INFO` is dropped from every request forwarded to a model, application or router. The MCP proxy path (`ApplicationMcpProxyController.injectProxyRequestHeaders`, line 95, via `McpProxyController.handleProxyRequest`, line 218) still copies it to the origin. The spec delta is not violated, because deployment-origin covers only the deployments-POST family. No test covers this path. |
| correctness | low | server/src/main/java/com/epam/aidial/core/server/config/ConfigPostProcessor.java:910 | `processRouters` lets an explicit `"interceptors": null` through. `DeploymentService.getInterceptors` then iterates it without a null check, so every request to that router fails with an NPE (500 "Failed to process deployment: r1"). Models have the same latent issue. |
| simplicity | low | server/src/main/java/com/epam/aidial/core/server/util/ProxyUtil.java:505 | `buildUserInfoHeader` re-implements the roles / project-or-userId branch of `UserInfoController.handle` instead of sharing it, although the spec requires the header to carry the same values `GET /v1/user/info` returns. No test compares the two. |
| simplicity | low | server/src/test/java/com/epam/aidial/core/server/config/ConfigPostProcessorTest.java:824 | `testRouterNameTakenByModelIsDuplicate` duplicates `FileConfigStoreTest.testLoad_RouterNameTakenByModelRejected`; tasks.md maps the scenario to FileConfigStoreTest only. |

## Fix round 1

Sent to fix: the medium correctness finding (ResponseItemController.java:222).

Fixed:

- `ResponsesApiClient.send` takes a new nullable `userInfo` argument and sets `Proxy.HEADER_USER_INFO` when it is non-null. `ResponseItemController.sendRequest` passes `ProxyUtil.buildUserInfoHeader(context)` when `context.getDeployment()` is a Router, and null otherwise. `send` builds a fresh request and copies no client headers, so a forged client header cannot reach the router.
- `ResponseItemControllerTest`: `send` mocks updated for the new arity; non-router calls verified with `isNull()`.
- `RouterApiTest.responseItemCallCarriesTheIdentityHeader`: creates a stored response on r2, then sends `GET /openai/v1/responses/{id}`. The router must receive exactly one `X-DIAL-USER-INFO` header with project p1.
- `routers.json` fixture: `interfaces.openaiResponses: {}` added to r2. r1 stays without it, so `interfaceTheRouterDoesNotDeclare` still holds.
- tasks.md 2.4 and the D6 bullet in design.md "## Implementation notes" updated.

Rejected: none.

Still open: none of the findings sent to this round. The four low findings were not sent to fix.

## Outcome

- High/medium findings still open: none.
- Low findings left (not fixed):
  1. routers.md:64 / proposal: the claim that a client `X-DIAL-USER-INFO` is dropped on every forwarded request does not hold on the MCP proxy path. Either exclude the header in `ApplicationMcpProxyController.injectProxyRequestHeaders` (and the toolset equivalent) or narrow the docs and proposal wording.
  2. ConfigPostProcessor.java:910: an explicit `"interceptors": null` on a router loads and then causes an NPE on every request. Normalise null to `List.of()` in `processRouters`, or make `DeploymentService.mergeInterceptors` skip a null source.
  3. ProxyUtil.java:505: the identity logic is duplicated with `UserInfoController`.
  4. ConfigPostProcessorTest.java:824: duplicate test of the router/model name clash.
- CI must still confirm:
  - The full `:server:test` suite (task 5.2, unticked).
  - The whole-module `:config:test` and `:storage:test` results in run 2 were Gradle up-to-date results, not fresh runs.
  - `checkOpenApiDiff` with the redocly lint step, against the final tree.
- Before the commit: `playground-unified-config.html` is untracked and does not belong to this change. Do not stage it.

## After the workflow

Done by the orchestrating session after the run, before the commit:

- Low finding 2 fixed: `processRouters` sets an explicit `"interceptors": null` to `List.of()`, so the router loads
  and serves. Test: `ConfigPostProcessorTest.testRouterWithNullInterceptorsLoadsWithEmptyList`.
- Low finding 1 fixed by narrowing the wording in `routers.md` and proposal.md to the request paths that drop the
  header. The MCP proxy and interceptor paths are left as they are: neither forwards to a router.
- Low finding 4 fixed: the duplicate `ConfigPostProcessorTest.testRouterNameTakenByModelIsDuplicate` is removed; the
  scenario stays covered by `FileConfigStoreTest`.
- Low finding 3 left: the identity logic in `ProxyUtil.buildUserInfoHeader` and `UserInfoController.handle` is still
  two copies of a three-field branch. Sharing it means touching the user-info endpoint, which this change does not
  otherwise change.

Full suite, run locally after these fixes (task 5.2): `./gradlew :config:test :storage:test :openapi-generator:test
:server:test`, 3,580 tests, 2 skipped, 1 failure. The failure is
`HttpServerInstancesApiTest.testConcurrentStreamedRequests`, answered `429` by a model's minute token limit
(240 / 100 tokens): limit state left by earlier tests in the same run. It touches no router, and the class passes
on its own (`:server:test --rerun --tests HttpServerInstancesApiTest`: 2 tests, 0 failures). Unrelated to this change.
