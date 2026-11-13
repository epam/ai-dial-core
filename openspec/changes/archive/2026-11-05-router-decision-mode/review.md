# Review: router-decision-mode

## Implementation

Tasks done: 26. Tasks deferred:

- 8.3 Full `:server:test` suite. It was not run locally because the workflow leaves the full suite to CI after the push. Only the targeted classes were run.
- 8.5 Perf measurement. It needs the perf environment and a router image that serves `/decide`.

Files changed:

- config: `InterfaceMode.java`, `DeploymentInterface.java`
- server main: `Proxy.java`, `ProxyContext.java`, `config/ConfigPostProcessor.java`, `controller/BaseDeploymentPostController.java`, `controller/DeploymentPostController.java`, `controller/ChatCompletionsController.java`, `controller/ResponsesController.java`, `controller/anthropic/MessagesBaseController.java`, `data/ApiKeyData.java`, `data/RouterDecision.java` (new), `service/RouterDecisionClient.java` (new), `log/AnalyticsLogContext.java`, `log/GfLogStore.java`. Fix rounds also changed `controller/BaseInterceptorController.java` and `controller/BaseChatCompletionController.java`.
- server test: `RouterDecisionApiTest.java` (new), `service/RouterDecisionClientTest.java` (new), `config/ConfigPostProcessorTest.java`, `config/FileConfigStoreTest.java`, `ConfigEntityWriteApiTest.java`, `log/GfLogStoreTest.java`, `resources/dial-config/router-decision.json` (new), `resources/dial-config/router-decision-interceptors.json` (new)
- docs: `docs/dynamic-settings/routers.md`, `docs/dynamic-settings/models.md`, `docs/open_api_core.yaml`
- change: `design.md`, `tasks.md`, `specs/router-deployment/spec.md`

Tests added:

- `RouterDecisionApiTest`, new, with 31 integration cases at first: headersAndEmptyBody, bodyOnRequest, decisionInterfaceIsNotProxied, shorthandAccepted, schemaMismatch, deploymentsPostServedByTheChosenModel, chatCompletionsApiServedByTheChosenModel, responsesApiServedByTheChosenModel, messagesBodyModelRewritten, countTokensDecidedAndCountedOnTheChosenModel, decisionForOneInterfaceProxyForAnother, chosenModelsLimitsCheckedAgainstTheCaller, candidateIsRouter, embeddingsDecidedAndServed, unknownLastCandidate, unparsableBodyAfterTheDecisionAnsweredOnce, routerReEnteredThroughAnApplication, forbiddenFirstCandidateSkipped (+OnMessages), failingFirstCandidateSkipped (+OnMessages), everyCandidateForbidden, nonRetriableAnswerRelayed, chosenDeploymentBehindAnInterceptorIsNotRetried, slowRouter, routerRejectsTheRequest, routerDown, rootKeyRecordAfterDecision, oneRowForDecidedRequest, globalInterceptorBeforeTheDecision (+OnTheChatCompletionsApi). The fix rounds added three more, for 34 in all: bodyFailureAfterTheAnswerIsNotRetried, chosenDeploymentBehindAnInterceptorRecordsTheDecision, chatCompletionsApiBodyModelIsTheCandidateWithoutOverrideName.
- `RouterDecisionClientTest`, new: verdict parsing, 4xx relay with content type, 502 for 5xx/invalid/unreachable, 504 on timeout, decide headers, body byte for byte.
- `ConfigPostProcessorTest`: decision on a model, application or interceptor is rejected; the entry needs its own base_url; translator is rejected; decisionTimeoutMs 0 is rejected; a valid entry is accepted; DECISION keeps subjectToLimits.
- `ConfigEntityWriteApiTest.testApplicationPutWithDecisionEntryReturns422`.
- `FileConfigStoreTest.testLoad_RouterInDecisionMode`. It also loads the routers.md example, which is the fixture's r1.
- `GfLogStoreTest.testRowOfDecidedRequest`, `testBackgroundJobRowHasNoDecision`.

Design drift. Each item is recorded in design.md under "## Implementation notes".

1. Task 1.1 said DECISION has the limits flag set to false. D1 and task 1.3 say true, and the code uses true.
2. There are no Verdict or DecisionRejectedException classes. decide() returns the candidate list, and every failure is an HttpException. A router 4xx carries the router's body and Content-Type. DeploymentPostController.handleRequestError now uses respond(e) so those headers reach the client.
3. Deployments-POST: Proxy pauses the client request until the controller's future completes. A decision that waits for the body inside that future never ends. A decision outside it lets the body be dropped ("Request has already been read"). So a router registers one cached body read before the decision, and runs the decision and the re-entry outside the returned future.
4. The design's re-entry guard exception for the router's own interceptor callback is not needed. An interceptor's key path never contains the router, so path.contains(router) is enough.
5. D5 key path: an interceptor key is [i1], not [r1, i1]. The router is added whenever a decision is set: [r1, gpt-4] for a root call, [i1, r1, gpt-4] after a global interceptor. The spec scenario now says [i1, r1, gpt-4]. parent_deployment is taken from the decision, because on Messages the old path walk returned null.
6. Global interceptors would run a second time for the chosen deployment in the interceptor callback pass. resolveInterceptors drops the interceptors the chain already ran.
7. For a candidate skipped after its call, the attempt status is what Core would have answered the client: 502 for exhausted upstreams or a connect failure. The upstream's own 503 is not kept. The spec scenario changed from 503 to 502, and the requirement now says what the status means. The requirement was also shortened to pass strict validation.
8. Skipping a candidate before its call re-parses the saved body on the three body-addressed APIs. This costs the same as a fallback.
9. The saved body is kept until the request ends, not released at the first response byte.
10. The chat API serializes the body once after setting model. Responses serializes only on an interceptor hand-off. Round 2 changed how the chat API does this (see Fix round 2).
11. In validateDeploymentInterfaces, the generic translator warning now skips decision entries, so the error reads "Mode 'decision' excludes translator".
12. open_api_core.yaml was regenerated. The InterfaceMode enum gains decision, and DeploymentInterface gains context and decisionTimeoutMs. The resolveServingEndpoint callers are unchanged (task 8.1).

Fix round 1 added two more notes: "No fallback after an accepted answer" and "Decision row behind the chosen deployment's interceptors".

## Verification

### Implementer's checks

- `./gradlew checkstyleMain checkstyleTest` with JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64: passed.
- `:config:test` and targeted `:server:test` classes: 445 tests, 0 failures. Classes: RouterDecisionApiTest, service.RouterDecisionClientTest, RouterApiTest, config.ConfigPostProcessorTest, config.FileConfigStoreTest, ConfigEntityWriteApiTest, log.GfLogStoreTest, controller.DeploymentPostControllerTest, controller.ChatCompletionsControllerTest, controller.ChatCompletionInterceptorControllerTest, controller.ResponsesControllerTest, controller.ResponsesInterceptorControllerTest, controller.anthropic.*, ResponsesApiTest, MessagesApiTest, DeploymentApiTest, DeploymentPostApiTest, ChatCompletionApiTest, EmbeddingsApiTest, data.ApiKeyDataTest, OverridePathsWriteApiTest, ConsentApiTest.
- `openspec validate router-decision-mode --strict`: valid. The spec deltas still start from the current text of app-to-model-accounting and deployment-interfaces-config. router-entity is not archived yet, and router-deployment is ADDED-only.

### Run 1 (first independent verification): passed

- Checkstyle: passed, no violations.
- `:config:test`: all 23 suites passed. Gradle reported the task up to date, from a run on unchanged inputs after the last config edit.
- `:server:test`: RouterDecisionApiTest 31/0, service.RouterDecisionClientTest 9/0, ConfigEntityWriteApiTest 52/0, config.ConfigPostProcessorTest 66/0, config.FileConfigStoreTest 13/0, log.GfLogStoreTest 52/0, controller.DeploymentPostControllerTest 40/0 (2 skipped; both are existing @Disabled tests in a file unchanged from HEAD), controller.ChatCompletionsControllerTest 4/0, ResponsesApiTest 8/0, MessagesApiTest 20/0. BUILD SUCCESSFUL.
- `openspec validate router-decision-mode --strict`: valid.
- git status: the six untracked files belong to tasks 1.2, 2.1, 2.2, 2.3 and 4.1. playground-unified-config.html is also untracked. It predates the change, was already untracked at session start, and was left alone.
- Cost-claim check: matched design.md. The decide call is non-blocking: it uses the async Vert.x HttpClient with one setTimer deadline. The happy path makes no Redis or blob call. Redis calls happen only on a fallback (invalidatePerRequestApiKey with onFailure, and startSpan on re-entry). On deployments-POST the body is read once into a cached Future<Buffer> and not parsed. On the body-addressed APIs, setOriginalRequestBody only keeps a reference. Every new Future chain has a failure handler. Minor note: when the decide timer fires, the in-flight decide request is not reset.

### Run 2 (after fix round 1): failed

- Checkstyle: passed. `:config:test`: passed (up to date).
- `:server:test`: RouterDecisionApiTest 33/0, RouterDecisionClientTest 9/0, ConfigEntityWriteApiTest 52/0, ConfigPostProcessorTest 66/0, FileConfigStoreTest 13/0, GfLogStoreTest 52/0, DeploymentPostControllerTest 40/0 (2 skipped), ChatCompletionsControllerTest 4/0, ResponsesApiTest 8/0, MessagesApiTest 20/0, RouterApiTest 17/0.
- `openspec validate router-decision-mode --strict`: valid. git status: same as run 1.
- Cost-claim check: failed. design.md claimed one serialization per decided chat request. ChatCompletionsController.serveCandidate always called `writeRequestBody` after setting model. BaseChatCompletionController.processChatCompletionsRequestBody:104 then serialized again whenever the enhancement chain changed the request. A candidate with an overrideName always changes it (EnhanceDeploymentRequestFn), so the request was serialized twice.

### Run 3 (after fix round 2) and final verification: passed

- Checkstyle: passed.
- `:config:test`: 24 classes, 233 tests, 0 failures.
- `:server:test`: 11 classes, 315 tests, 0 failures, 2 skipped, BUILD SUCCESSFUL. RouterDecisionApiTest 34/0, service.RouterDecisionClientTest 9/0, ConfigEntityWriteApiTest 52/0, config.ConfigPostProcessorTest 66/0, config.FileConfigStoreTest 13/0, log.GfLogStoreTest 52/0, controller.DeploymentPostControllerTest 40/0, controller.ChatCompletionsControllerTest 4/0, ResponsesApiTest 8/0, MessagesApiTest 20/0, RouterApiTest 17/0.
- `openspec validate router-decision-mode --strict`: valid.
- git status: each new untracked file belongs to a task, with no probe or build output outside build/. playground-unified-config.html is the user's earlier file and is not part of this change.
- Cost-claim check: matched design.md, including the documented exceptions. The chat API serializes once per decided request: with changed=true on the direct path, or writeRequestBody when the candidate has interceptors. A pre-flight skip on the body-addressed APIs re-parses. The saved body stays referenced until the request ends. ApiKeyData.decision rides on the existing interceptor key write. Two minor points that are not failures: the in-flight decide request is not reset when the deadline timer fires (it is bounded by its idle timeout, also decisionTimeoutMs), and reenterWithCandidate's parseBody moves the request-body timestamp to the time of the fallback.

## Review findings

| Lens | Severity | File:line | Finding |
|------|----------|-----------|---------|
| conformance | medium | server/src/main/java/com/epam/aidial/core/server/controller/BaseDeploymentPostController.java:154 | The fallback hook sits in handleProxyConnectionError, which the response-body readers also call. So Core falls back to the next candidate after the current one has already answered (BaseChatCompletionController:184, ResponsesController:444, MessagesController:190, MessagesCountTokensController:69). The spec allows no fallback after a non-retriable answer. |
| conformance | medium | server/src/main/java/com/epam/aidial/core/server/log/AnalyticsLogContext.java:115 | When the chosen deployment has its own interceptors, the row of the deployment that served has no `decision` object. The decision lives only on the deciding pass's ProxyContext. |
| conformance | low | openspec/changes/router-decision-mode/tasks.md:75 | Ticked tasks 4.1 and 5.1 list tests that do not exist: global interceptor before the decision on /openai/v1/responses, and the Messages variants of "Router re-entered through an application" and of the failed-key invalidation check. |
| correctness | medium | server/src/main/java/com/epam/aidial/core/server/controller/BaseDeploymentPostController.java:604 | retryWithNextCandidate is guarded only by headWritten(). After the upstream's response was accepted, a failed body read or a failed saveMapping still walks to m2. The client gets m2's answer, and m1's tokens are not charged. |
| correctness | low | server/src/main/java/com/epam/aidial/core/server/service/RouterDecisionClient.java:120 | A router 4xx that HttpStatus does not list (408, 410, 418, 451, 499) reaches the client as 500, because fromStatusCode falls back to INTERNAL_SERVER_ERROR. |
| correctness | low | server/src/main/java/com/epam/aidial/core/server/service/RouterDecisionClient.java:68 | When the deadline timer fires, the decide HttpClientRequest is not reset. A router that trickles its body keeps the connection open after the client got 504. |
| correctness | low | server/src/main/java/com/epam/aidial/core/server/token/TokenStatsTracker.java:162 | On a fallback, startSpan replaces the span's TokenStats (spans.put). Child usage rolled up by the failed candidate is lost, and late child usage is attributed to the next candidate. |
| simplicity | low | server/src/main/java/com/epam/aidial/core/server/config/ConfigPostProcessor.java:645 | The DECISION exception in the translator branch and the "Mode 'decision' excludes translator" check (line 735) only change the wording of a rejection the existing branch already makes. |
| simplicity | low | server/src/main/java/com/epam/aidial/core/server/controller/BaseDeploymentPostController.java:514 | decideIfRouter takes a Supplier<Future<Buffer>>, but no caller uses the laziness. |
| simplicity | low | server/src/main/java/com/epam/aidial/core/server/controller/ChatCompletionsController.java:157 | The "candidate resolved" state change is repeated by hand at six sites (setResolving(false), and setResolving(false) with close()). A site that misses it leaves resolving true. |
| simplicity | low | server/src/test/java/com/epam/aidial/core/server/service/RouterDecisionClientTest.java:162 | bodySentByteForByte repeats RouterDecisionApiTest.bodyOnRequest. |

## Fix round 1

Open at the start: the three medium findings (conformance BaseDeploymentPostController.java:154, conformance AnalyticsLogContext.java:115, correctness BaseDeploymentPostController.java:604).

Fixed:

- Fallback after an accepted answer (conformance :154 and correctness :604). retryWithNextCandidate now returns false once context.getProxyResponse() is set. The guard is in the shared function, so it covers every caller that reaches it after the headers were accepted. The retriable-status and connect-failure paths return before setProxyResponse, so they still fall back. New test RouterDecisionApiTest.bodyFailureAfterTheAnswerIsNotRetried: m1 sends 200 headers and then disconnects during the body; the client gets 502 and m2 receives nothing. The test fails with the guard reverted.
- No decision on the row behind the chosen deployment's interceptors (AnalyticsLogContext.java:115). ApiKeyData gains a `decision` field (RouterDecision.Row, @JsonInclude NON_NULL). BaseInterceptorController sets it from context.getDecision(), or from the previous interceptor's key in later passes of the chain. AnalyticsLogContext.decisionRow() falls back to the carried row when the pass's deployment is the one the row names. New test RouterDecisionApiTest.chosenDeploymentBehindAnInterceptorRecordsTheDecision asserts a single row: deployment m-int, execution_path [r1, i2, m-int], parent r1, decision.deployment m-int, attempts [{private-model, 403, forbidden}].
- design.md Implementation notes gained "No fallback after an accepted answer" and "Decision row behind the chosen deployment's interceptors". tasks.md 5.1 and 6.2 now name the new tests and the carried decision.
- Checks: checkstyle passed. RouterDecisionApiTest, log.GfLogStoreTest, controller.ChatCompletionInterceptorControllerTest, controller.ResponsesInterceptorControllerTest, controller.DeploymentPostControllerTest, security.ApiKeyStoreTest and CustomApplicationApiTest passed. openspec validate --strict: valid.

Rejected: none.

Still open after the round: none of the round's findings. The verification that followed (run 2) failed on the cost-claim check.

## Fix round 2

Open at the start: the failure from verification run 2, two body serializations per decided chat completions request.

Fixed:

- ChatCompletionsController.serveCandidate now calls writeRequestBody only on the interceptor branch. On the direct path it passes modelChanged=true through proceedToDeployment and handleRequestBody to BaseChatCompletionController.processChatCompletionsRequestBody(request, changed). That method serializes once when the chain changes the request or when changed is true. DeploymentPostController passes false, so its behaviour is unchanged.
- design.md, "Chat body model (D3)" note rewritten to match.
- New test RouterDecisionApiTest.chatCompletionsApiBodyModelIsTheCandidateWithoutOverrideName covers a candidate the chain does not change (no overrideName). It asserts that the upstream body's model is m1.
- Checks: checkstyle passed. RouterDecisionApiTest (34), controller.ChatCompletionsControllerTest (4), controller.DeploymentPostControllerTest (40, 2 skipped), ResponsesApiTest (8) and MessagesApiTest (20) passed with 0 failures. openspec validate --strict: valid.

Rejected: none.

Still open after the round: none. Verification run 3 passed.

## Outcome

High or medium findings still open: none.

Low findings left. None of these went into a fix round, so all eight stand as reported:

- conformance, tasks.md:75: tests that ticked tasks 4.1 and 5.1 name are missing (the Responses variant of the global-interceptor case; the Messages variants of re-entry and key invalidation). Round 1 edited 5.1 to name the new tests but did not add these.
- correctness, RouterDecisionClient.java:120: unlisted router 4xx codes are relayed as 500.
- correctness, RouterDecisionClient.java:68: the decide request is not reset on deadline.
- correctness, TokenStatsTracker.java:162: a fallback overwrites the span and loses the failed candidate's child usage.
- simplicity, ConfigPostProcessor.java:645: redundant decision-specific translator rejection.
- simplicity, BaseDeploymentPostController.java:514: the Supplier parameter on decideIfRouter is not needed.
- simplicity, ChatCompletionsController.java:157: the setResolving(false)/close() pattern is repeated at six sites.
- simplicity, RouterDecisionClientTest.java:162: duplicate body-forwarding test.

Also noted during verification, not raised as findings: reenterWithCandidate moves the request-body timestamp to the time of the fallback.

CI must still confirm:

- The full `:server:test` suite (task 8.3). Only targeted classes ran locally.
- Task 8.5 (perf measurement) is still deferred. It needs the perf environment and a router image with `/decide`.

## After the workflow

Low findings handled by hand:

- conformance, tasks.md:75: added `RouterDecisionApiTest.globalInterceptorBeforeTheDecisionOnTheResponsesApi`. The interceptor fixture declares `openaiResponses` on `i1`, `r1` and `gpt-4` for it. Task 5.1 now says what is covered on Messages: the two skip cases. Re-entry and key invalidation run in the shared `decideIfRouter` and `retryWithNextCandidate`, so they have no Messages variants.
- correctness, RouterDecisionClient.java:120: a router 4xx that `HttpStatus` does not list is answered 400, not 500. New unit test `unlistedRouterClientErrorStaysClientError`.
- correctness, RouterDecisionClient.java:68: the decide request is reset once the verdict fails, the deadline included.
- simplicity, BaseDeploymentPostController.java:514: `decideIfRouter` takes a `Future<Buffer>`.
- simplicity, RouterDecisionClientTest.java:162: removed `bodySentByteForByte`. `RouterDecisionApiTest.bodyOnRequest` asserts the same body and content type.

Left as reported:

- correctness, TokenStatsTracker.java:162: a fallback replaces the span's TokenStats. Only a candidate that already reported child usage before failing with a retriable status loses it: an application that called a model and then answered 5xx. That is rare enough to leave for the binding/hooks change.
- simplicity, ConfigPostProcessor.java:645: the decision-specific translator message is kept. It is the message routers.md documents, and it names the actual rule.
- simplicity, ChatCompletionsController.java:157: the `setResolving(false)` sites stay explicit. Each sits at a different point of its controller's flow, and a helper would hide which point clears it.

Full suite after these fixes: `./gradlew :config:test :storage:test :openapi-generator:test :server:test` passed, with 3206 server tests, 0 failures and 2 skipped (existing @Disabled tests). Task 8.3 is ticked. Task 8.5 (perf measurement) is still deferred.

## Perf measurement (task 8.5)

Environment of the perf improvement initiative, 2026-11-04. All four runs used the same Core image (this PR's head) and the same two Core pods. They also shared one router pod (dial-llm-router-app with `/decide` and `DIAL_USER_INFO_HEADER_TRUSTED=true`), the mock, the adapter and the Valkey pods. The load was Anthropic Messages, streamed, 1,000 users, a 100 s ramp and a 10-minute steady phase, with 2 minutes between runs. Only the deployment changed between runs:

- D: `llm-d-sim-via-openai`, the direct floor.
- R: `auto-router-medium`, a model on proxy interfaces to the router (today's setup).
- R2: `auto-router-medium-router`, the router type on proxy interfaces.
- B: `auto-router-medium-decision`, the router type in decision mode on all three APIs.

Before the runs, a 1-user smoke test of each deployment got 200s. B wrote one analytics row per request with the execution path `[auto-router-medium-decision, llm-d-sim-via-openai]` and a `decision` object (`router`, `api`, `candidates`, `deployment`, `decide_ms`, `attempts`). R and R2 wrote two rows per request.

Per user request, from the 5-minute window of steady load before each profile capture:

| | D | R | R2 | B |
|---|---|---|---|---|
| Core passes per user request | 1 | 3 (2 Messages, 1 user-info) | 2 | 1 |
| Core CPU per user request | 53.6 ms | 96.9 ms | 93.7 ms | 60.7 ms (55.6 ms on the pod without the housekeeping scan) |
| Core CPU over D | | +43.3 ms | +40.1 ms | +7.1 ms (+2.4 ms on that pod, against the same pod in D) |
| Router CPU per user request | 0 | 15.5 ms | 14.8 ms | 1.1 ms |
| `/v1/user/info` calls per second | 0 | 63.9 | 0 | 0 |
| Redis commands per user request | 80 | 144 | 110 | 80 |
| Client p50 / p95 | 9.12 / 34.0 s | 9.23 / 34.6 s | 9.07 / 33.8 s | 9.07 / 34.2 s |
| Mock mean response time | 14.42 s | 14.55 s | 14.37 s | 14.37 s |
| KO, Core non-2xx | 0 | 0 | 0 | 0 |

- Against R, decision mode saves about 50 ms of CPU per user request: 36 ms in Core, 14 ms in the router. It also saves 64 Redis commands, the user-info call and one analytics row.
- The router type on proxy interfaces (R2) saves only the user-info pass: 3.2 ms in Core and 0.7 ms in the router.
- B costs 2 to 7 ms of Core CPU over the direct path.
  - The range comes from one pod. During B's window it ran the daily response-mapping housekeeping (`ResponseMappingService.cleanExpiredMappings`, which lists the response-mappings bucket once a day per pod after a random start offset).
  - That scan took 18% of that pod's CPU samples, so the two-pod figure (+7.1 ms) is an upper bound.
  - On the other pod B was 2.4 ms above the same pod in D. That is within the 6% spread between the two pods of one run.
- Latency: the client p50 deltas over D (+113, -50, -56 ms) follow the mock's own mean deltas (+133, -43, -47 ms) within 20 ms. No router latency can be resolved at this shape.

Limits of the measurement:

- The load generator sent a mix of request sizes: body median 374 KB, mean 650 KB; max tokens median 343, mean 767. The deltas hold because all four runs had the same mix, but the absolute numbers do not compare with earlier fixed-size runs.
- The capture sat 7 minutes into steady load. Each variant ran once.
