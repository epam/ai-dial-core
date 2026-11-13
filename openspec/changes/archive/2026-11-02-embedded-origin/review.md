# Review: embedded-origin

## What shipped

- **The origin seam.** Every call to an origin goes through Core-owned `upstream/ProxyRequest` and `ProxyResponse`.
  `HttpProxyRequest` and `HttpProxyResponse` wrap the Vert.x objects, so what an HTTP origin receives, and what Core
  relays and records, does not change.
  - The consumers in the deployment controllers, `UpstreamRoute`, `ProxyUtil`, `ProxyContext`, `ResponseItemController`
    and `ResponsesApiClient` changed type only.
  - The MCP, feature and route controllers keep their Vert.x objects and wrap them where they meet shared code.
- **`upstream/OriginClient`** decides between the two. `http(RequestOptions)` is the HTTP path. `embedded(...)` builds
  an `EmbeddedProxyRequest` for the plugin an interface names, and returns every failure as a failed future.
- **Config.** `interfaces.<type>` takes `"mode": "embedded"` and `"plugin": "<name>"`.
  - `DeploymentEndpointUtil` resolves an embedded entry to the base url `plugin://<name>`. The 503 gate, the synthetic
    upstream, the Responses mapping, the analytics row and the logs work unchanged.
  - `ConfigPostProcessor` checks the plugin rules for models (`validateDeploymentInterfaces`) and for applications and
    interceptors (`validateInterfaces`, renamed from `validateOverridePaths`, on all eight write surfaces).
- **The plugin contract** is in `plugin-api`: `OriginPluginProvider.create(settings)` makes an `OriginPlugin`, whose
  `handle(OriginRequest)` returns an `OriginResponse`, in Vert.x types. `config/EmbeddedOrigins` keeps one plugin per
  `plugins` entry and recreates it when the entry changes.
- **Platform only.** An `embedded` interface is refused with `422` on a write or a copy outside the `platform` bucket
  (`ApplicationService.requireValidInterfaces`). At request time `OriginClient.embedded` refuses any application that
  is not of the platform config.
- **Docs.** `docs/dynamic-settings/models.md`, `applications.md`, `interceptors.md` and `plugins.md`, the `plugin-api`
  row of `CLAUDE.md`, and `docs/open_api_core.yaml`, regenerated: 3 lines for `plugin` and `embedded`.

## Drift from design.md

design.md "Apply notes" records each change:
- **D1:** a no-body `send()`, and the accessor named `absoluteUri()`.
- **D2:** `OriginClient` holds the `EmbeddedOrigins`; `embedded` reads the deployment from the context; the
  two-argument `createProxyRequest` arrived with task 3.5.
- **D6:** `LinkageError` caught at the calls into plugin code, and the stream rule.
- **D8:** the interceptor plugin rule.
- **D9:** the request check and copies.
- **D10:** the test plugin's hold stream.
- The connect-failure body.

## Checks run

- `./gradlew checkstyleMain checkstyleTest`: clean after each group and after the fix round.
- `npx -y @fission-ai/openspec@1.14.3 validate embedded-origin --strict`: valid.
- `:openapi-generator:replaceSpec` and `checkOpenApiDiff`: the document now carries `plugin` and `embedded`, and is
  valid.
- **Group 1, the refactor, with no test edits beyond types.** Unit tests, 165:
  - `DeploymentPostControllerTest`, `ResponsesControllerTest`, `ResponseItemControllerTest`,
    `ChatCompletionInterceptorControllerTest` and `ToolSetMcpProxyControllerTest`;
  - `GenAiTraceAttributesTest`, the `upstream` tests and `ProxyUtilTest`.

  Integration tests, 111:
  - `DeploymentPostApiTest`, `ChatCompletionApiTest`, `EmbeddingsApiTest`, `ResponsesApiTest` and `MessagesApiTest`;
  - `ConfigInterceptorTest`, `ConfigTranslatorTest` and `OverridePathsApiTest`;
  - `FeaturesApiTest`, `RouteApiTest` and `ApplicationRouteApiTest`.
- **Group 2, config:** `ConfigPostProcessorTest` (67), `DeploymentEndpointUtilTest` (53), `EmbeddedOriginApiTest`,
  `OverridePathsWriteApiTest` (15) and `ApplicationInterfacesApiTest` (5).
- **Group 3 and the fix round, all passing:**

  | Class | Tests |
  |---|---|
  | `EmbeddedOriginApiTest` | 29 |
  | `EmbeddedOriginsTest` | 6 |
  | `EmbeddedProxyRequestTest` | 6 |
  | `PluginRegistryTest` | 7 |
  | `ConfigPluginApiTest*` | 13 |
  | `ResponsesApiTest` | 8 |
  | `MessagesApiTest` | 20 |
  | `DeploymentPostApiTest` | 12 |
  | `ChatCompletionApiTest` | 3 |
  | `ResponseItemControllerTest` | 17 |
  | `ConfigInterceptorTest` | 6 |
  | `OverridePathsWriteApiTest` | 15 |
- `./gradlew :config:test`: passes.
- `CustomApplicationApiTest.testApplicationListing` fails locally on Windows only: the filesystem blob store lists a
  folder as `gpt\`, as `ResourceApiTest.testWorkflow` does. The change does not touch folder listing; the other 20
  tests of the class pass.
- Task 2.5's check that the doc examples load: both new examples parse as JSON, and their shapes are what
  `ConfigPostProcessorTest.testEmbeddedModelNeedsNoBaseUrl` and `testUnresolvedPluginNameAccepted` load. No test
  reads the doc files themselves.
- The full suite was not run locally; CI runs it. The perf A/B (task 4.1) ran later: "Perf A/B" below.

## Review findings

One independent reviewer, three lenses (spec conformance, correctness and security, simplicity), on the
uncommitted diff.

| Lens | Severity | File:line | Finding |
|---|---|---|---|
| correctness | medium | `EmbeddedProxyRequest.java:75`, `EmbeddedOrigins.java:56` | An `Error` from plugin code, such as a `NoClassDefFoundError` from a jar missing a class, escaped the catch of `RuntimeException`. On the event loop that left the request unanswered and the per-request key alive. |
| correctness | medium | `OriginResponse.java:16-20`, `EmbeddedProxyResponse.body()` | `BufferingReadStream` sets `handler` before `endHandler`, so a stream that emits from inside `handler()` loses its end. A stream handed back paused was never resumed by `body()`. Either hangs the request. |
| spec | low | `ApplicationService.copyApplication` | A copy, move or publication of an embedded platform application into another bucket was stored, then answered `502`. |
| spec | low | `OriginClient.java:46` | A platform-bucket application called by its url (`applications/platform/a`) carries that url as its name, and was refused. |
| design | low | `EmbeddedProxyRequest`, `EmbeddedProxyResponse` | The code comment D6 asks for, naming the thread rule and its upgrade path, was missing. |
| tests | low | `EmbeddedOriginApiTest` | Gaps: "cancelled before the second call" was not checked; "one row" was not checked; the row of a model with upstreams was not asserted; no test covered a reset before the answer or a throw from `handle`. |
| security | low | `plugins.md`, `OriginRequest` Javadoc | `X-UPSTREAM-*` was described as Core's. Core forwards client headers, so it is Core's only for a model with upstreams. |
| simplicity | low | `EmbeddedOrigins`, `ResponsesApiClient`, two method names, poll helpers | A redundant identity check before Lombok `equals`; a `plugin` argument the client can derive; stale names `checkOverridePaths` and `requireRenderableOverridePaths`; two copies of one poll loop. |
| spec | low | `tasks.md` 2.5 | Nothing showed the verification step of task 2.5. |

Found during the fix round, not by the reviewer: `docs/open_api_core.yaml` lacked `plugin` and `embedded`.

## Fix round 1

Fixed:
- **Errors from plugin code.** `handle` and `create` are wrapped in `catch (RuntimeException | LinkageError e)`.
  `EmbeddedProxyRequestTest.testPluginMissingClassFailsTheSend` and `testThrowingPluginFailsTheSend` cover it.
- **The stream rule.** `EmbeddedProxyResponse.body()` resumes the stream after setting its handlers. The
  `OriginResponse` Javadoc says a body emits only from a task of its own, never from inside a call Core makes to it.
  `EmbeddedProxyRequestTest.testBodyReadsStreamHandedBackPaused` covers it.
- **Copies.** `ApplicationService.requireValidInterfaces(resource, application)` runs on every write and on copies,
  against the destination bucket.
- **Platform-bucket applications by url.** `OriginClient.isPlatform` also accepts a name under
  `applications/platform/`.
- **Code comment.** The comment on the thread rule and its upgrade path is on `EmbeddedProxyRequest`.
- **Tests.**
  - The test plugin records the cancel count at each call; `testRetriedAnswerCancelled` asserts `[0, 1]`.
  - `testStreamedAnswerFromPlugin` asserts one row.
  - `testRetriableStatusFromPlugin` asserts the row's upstream `http://cloud-b`.
  - `testResetBeforeTheAnswerCancelsIt` and `testResetAfterTheAnswerCancelsItOnce` cover reset.
- **`X-UPSTREAM-*`.** `plugins.md` and the `OriginRequest` Javadoc say it can be trusted only for a model with
  upstreams.
- **Simplicity.**
  - `EmbeddedOrigins` uses one `equals` branch.
  - `ResponsesApiClient.send` resolves the plugin from the context.
  - The two methods are renamed `checkInterfaces` and `requireValidInterfaces`.
  - One poll helper, `ResourceBaseTest.awaitCondition`.
- **OpenAPI.** Regenerated.

Recorded, not changed:
- **Task 2.5.** Its verification is the JSON parse and the two tests named in "Checks run".
- **Client-sent `X-UPSTREAM-*`.** Core forwards a client's `X-UPSTREAM-*` headers to an application's origin. That
  was true for HTTP origins before this change, and the change leaves it so.

Still open: none.

## Spec-to-test map

| Scenario | Test |
|---|---|
| Embedded interface goes to the plugin | `EmbeddedOriginApiTest.testEmbeddedInterfaceGoesToThePlugin` |
| Embedded interface never falls back | `EmbeddedOriginApiTest.testEmbeddedInterfaceNeverFallsBack` |
| Embedded interface advertised | `EmbeddedOriginApiTest.testEmbeddedInterfaceAdvertised` |
| Override paths under an embedded entry | `EmbeddedOriginApiTest.testOverridePathsUnderAnEmbeddedEntry`, `ConfigPostProcessorTest.testOverridePathsUnderEmbeddedEntryRejected` |
| Application with a plugin but no embedded mode | `EmbeddedOriginApiTest.testApplicationWithPluginButNoEmbeddedMode` |
| Embedded mode without a plugin | `EmbeddedOriginApiTest.testEmbeddedModeWithoutPlugin`, `ConfigPostProcessorTest.testEmbeddedModeRequiresPlugin` |
| Both plugin and base url | `ConfigPostProcessorTest.testFileConfigWithPluginAndBaseUrlRejected`, `testPluginAndBaseUrlRejected` |
| Plugin not registered yet | `EmbeddedOriginApiTest.testPluginNotRegisteredYet`, `ConfigPostProcessorTest.testUnresolvedPluginNameAccepted` |
| Embedded interceptor | `EmbeddedOriginApiTest.testEmbeddedInterceptor`, `ConfigPostProcessorTest.testEmbeddedInterceptorRejected` |
| Custom application refused on write | `EmbeddedOriginApiTest.testCustomApplicationRefusedOnWrite` |
| Stored custom application not served by a plugin | `EmbeddedOriginApiTest.testStoredCustomApplicationNotServedByPlugin` |
| Chat completion handed to the plugin | `EmbeddedOriginApiTest.testChatCompletionHandedToThePlugin` |
| Unified chat endpoint handed to the plugin | `EmbeddedOriginApiTest.testUnifiedChatEndpointHandedToThePlugin` |
| Upstream headers for a model | `EmbeddedOriginApiTest.testUpstreamHeadersForModel` |
| Embeddings handed to the plugin | `EmbeddedOriginApiTest.testEmbeddingsHandedToThePlugin` |
| Responses create handed to the plugin | `EmbeddedOriginApiTest.testResponsesCreateHandedToThePlugin` |
| Messages handed to the plugin | `EmbeddedOriginApiTest.testMessagesHandedToThePlugin` |
| Streamed answer from a plugin | `EmbeddedOriginApiTest.testStreamedAnswerFromPlugin` |
| Model usage charged | `EmbeddedOriginApiTest.testModelUsageCharged` |
| Retriable status from a plugin | `EmbeddedOriginApiTest.testRetriableStatusFromPlugin` |
| Missing plugin entry | `EmbeddedOriginApiTest.testMissingPluginEntry`, `EmbeddedOriginsTest.testMissingEntry` |
| Provider that does not serve requests | `EmbeddedOriginApiTest.testProviderThatDoesNotServeRequests`, `EmbeddedOriginsTest.testProviderThatServesNoRequests` |
| Plugin call fails | `EmbeddedOriginApiTest.testPluginCallFails`, `EmbeddedProxyRequestTest.testThrowingPluginFailsTheSend` |
| Client disconnects mid-stream from an embedded application | `EmbeddedOriginApiTest.testClientDisconnectsMidStreamFromAnEmbeddedApplication` |
| Retried answer cancelled | `EmbeddedOriginApiTest.testRetriedAnswerCancelled` |
| Stored response read back from a plugin | `EmbeddedOriginApiTest.testStoredResponseReadBackFromPlugin` |
| Cancel reaches the plugin | `EmbeddedOriginApiTest.testCancelReachesThePlugin` |
| Plugin gone before the item operation | `EmbeddedOriginApiTest.testPluginGoneBeforeTheItemOperation` |
| Settings change takes effect | `EmbeddedOriginApiTest.testSettingsChangeTakesEffect`, `EmbeddedOriginsTest.testNewPluginAfterSettingsChange` |
| Entry removed | `EmbeddedOriginApiTest.testEntryRemoved` |

The baseline scenarios carried in the MODIFIED requirements keep their existing tests.

## Perf A/B (task 4.1)

Run on 2026-11-03 in the perf environment, both runs on one install, two Core pods: clients call the model
`llm-d-sim-via-openai` directly (Core, then the OpenAI adapter, then the mock model). Anthropic Messages, streamed,
1,000 users, 383 KB requests, 343 max tokens, 900 s of steady load per run, measured over 12 minutes of it. The
`development` image was two commits past this branch's last merge of it, neither on the request path. This branch's
image is the one `embedded-call` task 7.1 used, so it carries that change too.

| Per request | `development` | This branch | Change |
|---|---|---|---|
| Requests per second | 120.1 | 120.2 | |
| Core CPU | 36.7 ms | 37.0 ms | +0.4 ms (+1.0%) |
| Valkey commands | 76.8 | 76.7 | |
| Core's mean server time | 7.32 s | 7.32 s | |
| OpenAI adapter CPU | 24.8 ms | 25.7 ms | +3.7% |

No change, as D11 expected: Core moved by 1.0%, less than the OpenAI adapter, which neither image changes, moved
between the same two runs. No failed request in either run; no throttling, scale-down, eviction or restart.

## Open

- **Baseline spec drift.** The `deployment-origin` requirement "Connect failure" quotes `"code":"502"`; Core writes
  `"BAD_GATEWAY"`. That is a separate fix to the baseline spec.
- **The next change.** `embedded-call` adds the in-process child call, its cheaper child pass and the CPU
  measurement on the path through a routing application.
- CI passed the full suite.
