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

## Perf A/B (task 7.1)

Run on 2026-11-03 in the perf environment, both runs on one install: the Core image of this branch built with
`SAMPLE_PLUGINS=true`, two Core pods. Anthropic Messages, streamed, 1,000 users, 383 KB requests, 343 max tokens,
900 s of steady load per run, measured over 12 minutes of it. The environment declares its routing deployments as
models, so both sides are models:

- **A:** `auto-router-medium`, served by the HTTP router. Core, then the router, then Core again, then the OpenAI
  adapter and the mock model.
- **B:** `auto-router-medium-forward`, the same model with its three interfaces embedded in a `forward` plugin set
  to the router's medium-tier deployment. Core runs the plugin's pass and a child pass, then the OpenAI adapter and
  the mock model.

| Per user request | A: HTTP router | B: `forward` plugin | Change |
|---|---|---|---|
| User requests per second | 119.7 | 119.9 | |
| HTTP requests into Core | 3: two Messages, one `/v1/user/info` | 1 | |
| Core CPU | 63.6 ms | 47.0 ms | -16.6 ms (-26%) |
| Router CPU | 9.2 ms | 0 | |
| Core and router CPU | 72.8 ms | 47.0 ms | -25.7 ms (-35%) |
| Valkey commands | 141 | 139 | -2 |
| OpenAI adapter CPU | 25.9 ms | 25.3 ms | |

- **Against D9.** D9 expected 35-39 ms against 53.5 ms, a saving of 14.5-18.5 ms; the saving measured is 16.6 ms.
  The levels sit about 10 ms above D9's: path A cost 53.5 ms on 2026-10-20 and 63.6 ms here, so the two paths are
  compared within the day only.
- **`/v1/user/info`.** Path A makes one per user request, path B none. Without a profile its CPU is not split from
  the passes; the 2026-10-20 estimate is 3 ms, give or take 3. If the plugin's pass costs what the router's first
  pass does, the child pass costs about 11-17 ms less than the HTTP pass it replaces.
- **Latency.** Unchanged within what the run resolves: the mock model's streamed answer takes 6-7 s, the root
  span's p50 is 6.07 s on both paths, and Core's mean server time is 7.34 s on both.
- **Validity.** Equal throughput; no failed request on B, and 1 of 569,540 on A (a client timeout at 300 s, after
  the load ended); Core throttled under 0.4% of periods; no scale-down, eviction or restart; the same Core pods in
  both runs. The mock model's CPU per request differed by 8% between the runs, though neither path changes it;
  Core's change is three times that.

## Open

- **Task 6.2** was checked by reading the `Dockerfile`, and at run time by the image of task 7.1: its Core logged
  `Loaded plugin provider ...ForwardOriginPluginProvider for type 'forward'`.
- **Closing a replaced plugin instance** is left to a follow-up change: design.md "Open Questions".
- CI passed the full suite.
