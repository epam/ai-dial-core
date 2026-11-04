# Tasks

Spec scenario to test. `router-deployment`: "Decision mode on a model or application rejected" (file and `PUT`), "Decision entry needs its own url":
`ConfigPostProcessorTest` and `ConfigEntityWriteApiTest`; "Decision for one interface, proxy for another", "Headers and empty body", "Body on
request", "Shorthand accepted", "Schema mismatch", "Deployments-POST served by the chosen model", "Messages body
model rewritten", "Chosen model's limits checked against the caller", "Candidate is a router", "Embeddings decided
and served", "Global interceptor before the decision", "Unknown last candidate", "Router re-entered through an
application", "Forbidden first
candidate skipped", "Failing first candidate skipped", "Every candidate forbidden", "Non-retriable answer relayed",
"Slow router", "Router rejects the request", "Router down": `RouterDecisionApiTest` (new, integration); "Row of a
decided request": `GfLogStoreTest` plus the row assertion in `RouterDecisionApiTest` "Failing first candidate
skipped". `deployment-interfaces-config` "Decision interface is not proxied": `RouterDecisionApiTest`.
`app-to-model-accounting` "Root key record after a decision": `RouterDecisionApiTest`; "One row for a decided
request": `RouterDecisionApiTest`.

Prerequisite: change `router-entity` applied on the same branch (its `Router`, fixture and helpers are reused).

## 1. Config and validation

- [x] 1.1 `InterfaceMode.DECISION("decision", false)` with Javadoc on why it is not subject to limits (implemented
  as `true`, per D1 and 1.3; design.md, Implementation notes);
  `DeploymentInterface.context` (one-field class, `Boolean body`) and `decisionTimeoutMs`; verify
  `./gradlew :config:compileJava`.
- [x] 1.2 Fixture `server/src/test/resources/dial-config/router-decision.json`: router `r1` with
  `anthropicMessages`, `openaiChatCompletions` and `openaiEmbeddings` in decision mode on the test server's
  `/decide`, `openaiResponses` proxied, `defaultHeaders: {"X-DIAL-ROUTER-TIER": "medium"}`; router `r2`; models
  `gpt-4` (with `overrideName: gpt-4-real`), `claude`, `m1`, `m2`, `private-a`, `private-b` (roles the test key
  lacks); a role with `limits.gpt-4.requestHour: 1`; verify it loads in a `FileConfigStoreTest` case.
- [x] 1.3 Tests first in `ConfigPostProcessorTest` (file) and `ConfigEntityWriteApiTest` (`PUT` of an application
  with a decision entry answers `422`): "Decision mode on a model or application rejected", "Decision entry needs
  its own url", decision with a translator rejected, `decisionTimeoutMs: 0` rejected, an interceptor with a
  decision entry rejected, a valid router entry accepted; then the checks in the per-entry loop (design D1).
  `InterfaceMode.DECISION` keeps `subjectToLimits` true: a unit test asserts it.
- [x] 1.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.config.ConfigPostProcessorTest" --tests
  "com.epam.aidial.core.server.config.FileConfigStoreTest" --tests
  "com.epam.aidial.core.server.ConfigEntityWriteApiTest"` pass.

## 2. Decide client

- [x] 2.1 Write the failing `RouterDecisionApiTest` cases (new class, `extends ResourceBaseTest`,
  `@DialConfigLocation("dial-config/router-decision.json")`, one `TestWebServer` serving `/decide` and the model
  paths, capturing the decide request): "Headers and empty body" and "Body on request" (asserting the decide request
  only; the served answer is group 3's), "Schema mismatch" (three bodies), "Slow router" (`decisionTimeoutMs: 200`, handler sleeps 2 s, assert `504` and
  elapsed under 1 s), "Router rejects the request" (`400` body relayed byte for byte), "Router down" (decide URL on
  a closed port, `502`).
- [x] 2.2 `RouterDecisionClient` with `Verdict` and `DecisionRejectedException` (both folded into `HttpException`,
  design.md, Implementation notes; design D2: router default headers
  and the three exclusions written on the decide request, no shared helper), `ProxyContext.decision` and
  `originalRequestBody`; `decideIfRouter` on `BaseDeploymentPostController` with its two guards, wired into
  `DeploymentPostController.handleDeployment` only; verify the failure cases of 2.1 pass (the two request-shape
  cases pass once group 3 serves the answer).
- [x] 2.3 Unit test for the verdict parser and the status mapping (timeout `504`, `5xx` `502`, `4xx` relayed, bad
  JSON `502`) against a `MockWebServer`, in a new `RouterDecisionClientTest`; verify it passes.
- [x] 2.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest"` pass.

## 3. Serving the chosen deployment on deployments-POST

- [x] 3.1 Tests first in `RouterDecisionApiTest`: "Deployments-POST served by the chosen model" (origin path with
  `gpt-4-real`, per-request `API-KEY`, `X-DIAL-OVERRIDE-NAME`), "Chosen model's limits checked against the caller"
  (second request `429`, nothing sent), "Candidate is a router" (`502`), "Decision interface is not proxied" (the
  decide handler never sees a chat completion body), "Shorthand accepted", "Embeddings decided and served",
  "Unknown last candidate", and an unparsable body after the decision answers one `400` with no second decide call.
- [x] 3.2 Re-entry in `DeploymentPostController`: `handleDeployment(candidate)` after the decision,
  `handleRateLimitSuccess` reading the saved body when present, `nextCandidateOrFalse` at the top of
  `handleRequestError` and `handleRateLimitHit`, `resolving` cleared before `handleRateLimitSuccess` (design D3);
  verify the 3.1 cases pass.
- [x] 3.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest" --tests
  "com.epam.aidial.core.server.controller.DeploymentPostControllerTest"` pass.

## 4. Serving the chosen deployment on the body-addressed APIs

- [x] 4.1 Tests first in `RouterDecisionApiTest`: "Messages body model rewritten" (origin body `model` is the
  candidate's override name, `X-DIAL-DEPLOYMENT-ID` names the candidate), "Global interceptor before the decision"
  on deployments-POST, `/openai/v1/chat/completions` and `/openai/v1/responses` (a fixture variant with
  `globalInterceptors: ["i1"]`), "Decision for one interface, proxy for another"
  (Responses to `r1` lands on `/openai/v1/responses` of the router, no decide call), and `count_tokens` for `r1`
  decided and counted on the chosen model.
- [x] 4.2 The D3 sequence in `ChatCompletionsController.dispatch`, `ResponsesController.dispatch` and
  `MessagesBaseController.handle` (`originalRequestBody` in each `parseBody`, `decideIfRouter`,
  `setupDeployment(candidate)`, `setModel`, the interceptor branch for the candidate, the `deploymentId` field in
  Messages, `nextCandidateOrFalse` in each `handleRequestError`, `resolving` cleared before `proceedToDeployment`);
  verify the 4.1 cases pass.
- [x] 4.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest" --tests
  "com.epam.aidial.core.server.controller.ChatCompletionsControllerTest" --tests
  "com.epam.aidial.core.server.ResponsesApiTest" --tests "com.epam.aidial.core.server.MessagesApiTest"` pass.

## 5. Candidate walk

- [x] 5.1 Tests first in `RouterDecisionApiTest`, on deployments-POST, and the two skip cases also on
  `/anthropic/v1/messages` (re-entry and key invalidation run in the shared `decideIfRouter` and
  `retryWithNextCandidate`): "Forbidden first candidate skipped" (nothing sent to `private-model`), "Failing first candidate skipped" (`m1`'s
  upstream answers `503`, `m2` serves, exactly one `API-KEY` remains valid: the failed attempt's key answers `401`
  or `403` on a call back), "Every candidate forbidden" (`403`), "Non-retriable answer relayed" (`m1` answers
  `400`, nothing sent to `m2`), "Router re-entered through an application" (the application stub calls `r1` back
  with its per-request key and receives `502`; one decide request in total), and a chosen deployment with an
  interceptor whose model then fails is not retried, and a body read failing after the candidate's `200` headers is
  not retried.
- [x] 5.2 `retryWithNextCandidate` in `BaseDeploymentPostController` hooked into `nextUpstream()` exhaustion and
  `handleProxyConnectionError`, the per-controller `reenterWithCandidate`, key invalidation of the failed attempt,
  closing the decision when the chosen deployment has interceptors, releasing the saved body at the first response
  byte (design D4); verify the 5.1 cases pass.
- [x] 5.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest"` pass.

## 6. Accounting

- [x] 6.1 Tests first: `GfLogStoreTest` "Row of a decided request" (an `AnalyticsLogContext` with a decision writes
  `execution_path`, `parent_deployment` and the `decision` object with exactly the snake_case fields the scenario
  states, and a background job row writes none); in
  `RouterDecisionApiTest` "Root key record after a decision" (read the key record the model received, as the
  existing per-request-key tests do) and "One row for a decided request" plus the row assertion of "Failing first
  candidate skipped" (through the analytics log capture the existing row tests use).
- [x] 6.2 `ApiKeyData.initFromContext` adds the router before the receiver when a decision is set and the pass is
  not an interceptor callback (added whenever a decision is set: design.md, Implementation notes);
  `AnalyticsLogContext.decision` and the `GfLogStore` block (design D5), the decision carried on the interceptor key
  to the row of a chosen deployment with interceptors (design.md, Implementation notes); verify the 6.1 tests pass.
- [x] 6.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.log.GfLogStoreTest" --tests "com.epam.aidial.core.server.RouterDecisionApiTest"` pass.

## 7. Docs

- [x] 7.1 Extend `docs/dynamic-settings/routers.md`: the `decision` mode, `base_url` as the decide URL,
  `context.body`, `decisionTimeoutMs` and its default, the decide request (headers, body), the verdict, the candidate
  walk and what is not retried, the failure table, the one-row accounting with the `decision` object and
  `execution_path`; a JSON example that is the 1.2 fixture's `r1`; verify the example loads in a `FileConfigStoreTest`
  case.
- [x] 7.2 `./gradlew :openapi-generator:replaceSpec` and the generator's check task: verify `docs/open_api_core.yaml`
  is unchanged (no schema of this change is exposed) or commit the regenerated file if it is.
- [x] 7.3 `./gradlew checkstyleMain checkstyleTest` (this group's gate).

## 8. Integration checks

- [x] 8.1 Re-check the `resolveServingEndpoint` caller list in design D6 against the code as merged; record any
  new caller and whether the step or the retarget covers it.
- [x] 8.2 `npx -y @fission-ai/openspec@1.14.3 validate router-decision-mode --strict` passes and every scenario at
  the top of this file names a test that exists and passes.
- [x] 8.3 `./gradlew :server:test` (full module suite) passes locally, or the failures are shown unrelated with the
  output in review.md.
- [x] 8.4 Compare design.md with the code as merged; record drift in design.md.
- [ ] 8.5 Measure on the perf improvement initiative's environment: same-day direct, proxy-router and decision-mode
  runs on the router path; Core CPU per user request, user-info calls per second, Redis commands per request, p50
  overhead; record in review.md (deferred: needs the perf environment and a router image with `/decide`).

## Workflow follow-up

- Write review.md in the change folder, then archive with
  `npx -y @fission-ai/openspec@1.14.3 archive router-decision-mode -y` after `router-entity` is archived, and run
  `validate --specs --strict`.
- Next change: conversation binding and outcome events (design.md, Follow-ups), after the measurement.
