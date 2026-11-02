# Review: embedded-call

## What shipped

- **The contract** (`plugin-api`):
  - `OriginPlugin.handle(OriginRequest, OriginHost)`.
  - `OriginHost.call(OriginCall)` calls another deployment in process.
  - `OriginHost.cancelled()` says Core stopped waiting for the answer.
  - `OriginRequest` gains `bodyTree` and `caller` (`OriginCaller`: user id, roles, project).
  - `OriginPluginProvider.create(settings, vertx)` hands the plugin Core's Vert.x instance.
- **The child pass.**
  - `ChildPass`, one per attempt of an embedded request, refuses everything but the five create routes and a
    background Responses call.
  - It builds an in-memory `ChildServerRequest` and `ChildServerResponse` and runs `Proxy.dispatch` (extracted from
    `processAuthorizationResult`) on a duplicate of the plugin's Vert.x context that carries its OTel context.
  - The child is authenticated by the parent's in-memory per-request key record: no HTTP request into Core and no key
    lookup.
  - It closes on `finalizeRequest`, in `BaseDeploymentPostController` and in `ResponseItemController`. It is cancelled
    on `EmbeddedProxyRequest.reset`, on `EmbeddedProxyResponse.discard` and on a client close before the answer.
- **The in-memory response.**
  - The call's answer is its head and then its body, through an `InboundBuffer` on the plugin's context.
  - Backpressure drains on the child's context.
  - `reset` cuts the answer. `cancel` fails later writes, releases a paused pipe, and settles the call's future and an
    unread body.
- **The shared body tree.**
  - The six parse sites take the call's tree (`ProxyUtil.parseRequestBody`, `BaseInterceptorController.parseObject`).
  - The serialise points go through `setProcessedRequestBody`, which keeps the tree for an embedded interface's
    plugin; a retried attempt parses the body again.
- **The row and the span.** A child's row is `withoutBodies()`, and its assembled answer is not built. Neither
  `GenAiTraceAttributes` nor the log layout writes onto the plugin's span for a child.
- **Told before the answer.** `EmbeddedProxyRequest` watches the client while the plugin works. A cancelled plugin
  that fails ends the request quietly: no retry, no row.
- **The `forward` sample** (new module `plugin-samples`) and `ARG SAMPLE_PLUGINS=false` in the `Dockerfile`.
- **Docs.** `docs/dynamic-settings/plugins.md` (calls, the caller and tree, client gone first, the sample),
  `docs/tracing.md` (calls from an origin plugin), and the module table of `CLAUDE.md`.

## Drift from design.md

design.md "Apply notes" records each:
- **D4:** state changes in the calling thread, the head and a reset from tasks of their own, and the members.
- **D3:** the parse helpers, the body set when a pass has none, and `getTree()` kept out of the OpenAPI schema.
- **D2:** a call with no uri or body, `close` cancelling every response, the `401` body, and the `Runnable` close.
- **D10 and D11:** added during apply, from the open questions of a Java model-provider port.
- **The fixes of the review round** (below).

## Checks run

- `./gradlew checkstyleMain checkstyleTest`: clean.
- `npx -y @fission-ai/openspec@1.14.3 validate embedded-call --strict`: valid.
- `:openapi-generator:checkOpenApiDiff`: passes, and `docs/open_api_core.yaml` is unchanged.
- `./gradlew :plugin-samples:build`: passes. The jar holds `ForwardOriginPluginProvider` and its services file only.
- The final regression set, after the fix round: 40 classes, 476 tests, none failing. Among them:

  | Class | Tests |
  |---|---|
  | `EmbeddedCallApiTest` | 21 |
  | `EmbeddedOriginApiTest` | 31 |
  | `ChildPassTest` | 8 |
  | `ChildServerResponseTest` | 13 |
  | `ChildServerRequestTest` | 4 |
  | `EmbeddedProxyRequestTest` | 7 |
  | `EmbeddedOriginsTest` | 7 |
  | `ProxyContextTest` | 10 |
  | `AutoEnrichedOtelJsonLayoutTest` | 17 |
  | `GfLogStoreTest` | 50 |
  | `GenAiTraceAttributesTest` | 42 |
  | `DeploymentPostApiTest`, `ChatCompletionApiTest`, `EmbeddingsApiTest`, `ResponsesApiTest`, `MessagesApiTest`, `ConfigInterceptorTest` | 58 |
  | `ResponseItemControllerTest` | 17 |
  | The other `*ControllerTest` classes | all pass |
  | `ForwardOriginPluginProviderTest` | 3 |

- **Windows only, as before this change:**
  - `ResourceApiTest.testWorkflow` fails locally on the folder listing.
  - `ApplicationDeploymentApiTest` fails 14 of 21 locally, run alone too. The test's cleanup cannot delete `app.py`
    while Windows holds it open, and the deployments then never reach `DEPLOYED`. No frame of this change is in the
    stacks.
- The full suite was not run locally; CI runs it.

## Review findings

The proposal went through two review rounds before apply. After apply, one fresh reviewer read the uncommitted diff
for correctness under Vert.x, the specs and design, HTTP-path regressions, tests and style.

| Severity | File | Finding |
|---|---|---|
| major | `ChildServerResponse.cancel` | A cancel or close settled neither the call's future nor an answer body. A plugin waiting on a call's head, or holding an unread answer, waited forever, and its pass never ended. |
| minor | `EmbeddedProxyRequest.send` | A close handler set on a closed connection never fires. A client gone during the parse or the chain, or before a retried attempt, was never told to the plugin. |
| minor | `ResponseItemController.finalizeRequest` | Item operations have their own finalize, which did not close their child passes. A call after an item operation ended was still served. |
| minor | `AutoEnrichedOtelJsonLayout` | A child's log lines wrote their attributes, `error` and `exception.*` among them, onto the plugin's server span. |
| minor | `ChildServerRequest.DROPPED_HEADERS` | `X-DIAL-APPLICATION-ID`, `X-DIAL-APPLICATION-PROPERTIES` and `X-DIAL-DEPLOYMENT-FEATURES` of the plugin's own request reached the target's interceptor, which copies every header. |
| minor | tests | A wrong comment in `ChildPassTest`; no test for a pending call on close, a cancel after the head, a client gone before the send, the item-operation path, or the signal down a call chain. |

Found during the fix round, not by the reviewer: `ResponseItemControllerTest.testInterceptorReentryDispatchesToNextInterceptor`
failed one run in three. It called the deep-stubbed `proxy` mock in its last `verify` while the controller still used
that mock on another thread.

## Fix round 1

Fixed:
- **Settling on cancel.** `cancel` fails the call's future and an unread body from a task on the plugin's context. A
  body read to its end is left alone, and `writeHead()` refuses a closed response.
  - `ChildServerResponseTest.testCancelBeforeTheHeadFailsTheCall`, `testCancelFailsAnUnreadBody` and
    `testCancelLeavesBodyReadToItsEnd` cover it, and so does `ChildPassTest.testPendingCallFailsOnClose`.
- **A client gone before the send.** `send` checks `closed()` after setting the handler.
  `EmbeddedProxyRequestTest.testClientGoneBeforeTheSendCancelsAtOnce` covers it.
- **Item operations.** `ResponseItemController.finalizeRequest` closes the child passes.
  `EmbeddedCallApiTest.testItemOperationEndsItsCalls` covers it.
- **The span.** The log layout skips its span enrichment for a child pass.
- **Headers.** The three headers are dropped from a call. `ChildServerRequestTest` and the `OriginCall` Javadoc,
  design D1 and `plugins.md` list them.
- **Tests.** `EmbeddedCallApiTest.testClientGoneReachesTheTargetOfCall` covers the signal down a call chain. The comment
  is corrected. The test plugin checks `X-Test-Call` before `X-Test-Await-Cancel`.
- **The flaky test.** It takes the `ResponsesApiClient` stub before the request starts. It then passed five runs in a
  row.

Recorded, not changed:
- `EmbeddedCallApiTest.testNoRetryAfterCancelledPluginFails` checks for an absence: a second call within 500 ms.
- A plugin that stops on `cancelled()` and fails is not charged, while an HTTP model origin whose client went away
  is. design.md "Risks" records it.

## Spec-to-test map

| Scenario | Test |
|---|---|
| Call reaches a model | `EmbeddedCallApiTest.testCallReachesModel` |
| Call to a model behind an HTTP origin | `EmbeddedCallApiTest.testCallToModelBehindHttpOrigin` |
| Unknown target | `EmbeddedCallApiTest.testUnknownTarget` |
| Target the caller may not use | `EmbeddedCallApiTest.testTargetTheCallerMayNotUse` |
| Target with an interceptor | `EmbeddedCallApiTest.testTargetWithInterceptor` |
| Responses and Messages calls | `EmbeddedCallApiTest.testResponsesAndMessagesCalls` |
| Target fails mid-stream | `EmbeddedCallApiTest.testTargetFailsMidStream`, `ChildServerResponseTest.testResetAfterTheHeadFailsTheBody` |
| Call outside the create operations | `EmbeddedCallApiTest.testCallOutsideTheCreateOperations`, `ChildPassTest.testCallOutsideTheCreateRoutesFails`, `testBackgroundResponsesCallFails` |
| Call after the plugin's pass ended | `EmbeddedCallApiTest.testCallAfterThePluginsPassEnded`, `testItemOperationEndsItsCalls`, `ChildPassTest.testCallAfterCloseAnswers401` |
| Caller with an API key | `EmbeddedCallApiTest.testCallerWithApiKey` |
| Caller with a JWT | `EmbeddedCallApiTest.testCallerWithJwt` |
| Plugin told | `EmbeddedCallApiTest.testPluginToldClientGone`, `testClientGoneReachesTheTargetOfCall`, `EmbeddedProxyRequestTest.testClientGoneBeforeTheSendCancelsAtOnce` |
| No retry after the plugin fails | `EmbeddedCallApiTest.testNoRetryAfterCancelledPluginFails` |
| Client gone while a call streams | `EmbeddedCallApiTest.testClientGoneWhileCallStreams` |
| Plugin cancels a call | `EmbeddedCallApiTest.testPluginCancelsCall`, `ChildServerResponseTest.testCancelFailsThePipe` |
| Target limits charged | `EmbeddedCallApiTest.testTargetLimitsCharged` |
| Usage rolled up to the plugin's pass | `EmbeddedCallApiTest.testUsageRolledUpToThePluginsPass` |
| Child row | `EmbeddedCallApiTest.testChildRow` |
| Bodies logged once | `EmbeddedCallApiTest.testBodiesLoggedOnce` |

The two scenarios of the MODIFIED analytics-log requirement keep their existing `GfLogStoreTest` tests.

## Open

- **Task 7.1.** The same-day perf A/B of the routing-application scenario, the HTTP router against the `forward`
  plugin on an image built with `SAMPLE_PLUGINS=true`, needs the perf env.
- **Task 6.2** was checked by reading the `Dockerfile`; no image was built here.
- **Closing a replaced plugin instance** is left to a follow-up change: design.md "Open Questions".
- CI must still confirm the full suite.
