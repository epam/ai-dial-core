# Tasks

Spec scenario to test. `router-deployment`: "Decision mode on a model rejected", "Decision entry needs its own url":
`ConfigPostProcessorTest`; "Decision for one interface, proxy for another", "Headers and empty body", "Body on
request", "Shorthand accepted", "Schema mismatch", "Deployments-POST served by the chosen model", "Messages body
model rewritten", "Chosen model's limits checked against the caller", "Candidate is a router", "Forbidden first
candidate skipped", "Failing first candidate skipped", "Every candidate forbidden", "Non-retriable answer relayed",
"Slow router", "Router rejects the request", "Router down": `RouterDecisionApiTest` (new, integration); "Row of a
decided request": `GfLogStoreTest` plus the row assertion in `RouterDecisionApiTest` "Failing first candidate
skipped". `deployment-interfaces-config` "Decision interface is not proxied": `RouterDecisionApiTest`.
`app-to-model-accounting` "Root key record after a decision": `RouterDecisionApiTest`; "One row for a decided
request": `RouterDecisionApiTest`.

Prerequisite: change `router-entity` applied on the same branch (its `Router`, fixture and helpers are reused).

## 1. Config and validation

- [ ] 1.1 `InterfaceMode.DECISION("decision", false)` with Javadoc on why it is not subject to limits;
  `DeploymentInterface.context` (one-field class, `Boolean body`) and `decisionTimeoutMs`; verify
  `./gradlew :config:compileJava`.
- [ ] 1.2 Fixture `server/src/test/resources/dial-config/router-decision.json`: router `r1` with
  `anthropicMessages`, `openaiChatCompletions` and `openaiEmbeddings` in decision mode on the test server's
  `/decide`, `openaiResponses` proxied, `defaultHeaders: {"X-DIAL-ROUTER-TIER": "medium"}`; router `r2`; models
  `gpt-4` (with `overrideName: gpt-4-real`), `claude`, `m1`, `m2`, `private-a`, `private-b` (roles the test key
  lacks); a role with `limits.gpt-4.requestMinute: 1`; verify it loads in a `FileConfigStoreTest` case.
- [ ] 1.3 Tests first in `ConfigPostProcessorTest`: "Decision mode on a model rejected", "Decision entry needs its
  own url", decision with a translator rejected, `decisionTimeoutMs: 0` rejected, a valid router entry accepted;
  then the `DECISION` branch in `validateDeploymentInterfaces` (design D1).
- [ ] 1.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.config.ConfigPostProcessorTest" --tests
  "com.epam.aidial.core.server.config.FileConfigStoreTest"` pass.

## 2. Decide client

- [ ] 2.1 Write the failing `RouterDecisionApiTest` cases (new class, `extends ResourceBaseTest`,
  `@DialConfigLocation("dial-config/router-decision.json")`, one `TestWebServer` serving `/decide` and the model
  paths, capturing the decide request): "Headers and empty body", "Body on request", "Shorthand accepted",
  "Schema mismatch" (three bodies), "Slow router" (`decisionTimeoutMs: 200`, handler sleeps 2 s, assert `504` and
  elapsed under 1 s), "Router rejects the request" (`400` body relayed byte for byte), "Router down" (decide URL on
  a closed port, `502`).
- [ ] 2.2 Extract the shared helpers design D2 names: the exclude-list builder out of `sendProxyRequest` and the
  default-headers loop out of `ApplyDefaultDeploymentSettingsFn`; verify `./gradlew :server:test --tests
  "com.epam.aidial.core.server.DeploymentApiTest" --tests "com.epam.aidial.core.server.RouterApiTest"` still pass.
- [ ] 2.3 `RouterDecisionClient` with `Verdict` and `DecisionRejectedException` (design D2), `ProxyContext.decision`
  and `originalRequestBody`; `decideIfRouter` on `BaseDeploymentPostController` wired into
  `DeploymentPostController.handleDeployment` only, so the 2.1 cases run on deployments-POST; verify they pass.
- [ ] 2.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest"` pass.

## 3. Serving the chosen deployment on deployments-POST

- [ ] 3.1 Tests first in `RouterDecisionApiTest`: "Deployments-POST served by the chosen model" (origin path with
  `gpt-4-real`, per-request `API-KEY`, `X-DIAL-OVERRIDE-NAME`), "Chosen model's limits checked against the caller"
  (second request `429`, nothing sent), "Candidate is a router" (`502`), "Decision interface is not proxied" (the
  decide handler never sees a chat completion body), and an embeddings call to `r1` decided and served.
- [ ] 3.2 Re-entry in `DeploymentPostController`: `handleDeployment(candidate)` after the decision, the router
  guard, `handleRateLimitSuccess` reading the saved body when present, `nextCandidateOrFalse` at the top of
  `handleRequestError` and `handleRateLimitHit` (design D3); verify the 3.1 cases pass.
- [ ] 3.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest" --tests
  "com.epam.aidial.core.server.controller.DeploymentPostControllerTest"` pass.

## 4. Serving the chosen deployment on the body-addressed APIs

- [ ] 4.1 Tests first in `RouterDecisionApiTest`: "Messages body model rewritten" (origin body `model` is the
  candidate's override name, `X-DIAL-DEPLOYMENT-ID` names the candidate), the same shape for
  `/openai/v1/chat/completions` and `/openai/v1/responses`, "Decision for one interface, proxy for another"
  (Responses to `r1` lands on `/openai/v1/responses` of the router, no decide call), and `count_tokens` for `r1`
  decided and counted on the chosen model.
- [ ] 4.2 The D3 sequence in `ChatCompletionsController.dispatch`, `ResponsesController.dispatch` and
  `MessagesBaseController.handle` (`originalRequestBody` in each `parseBody`, `decideIfRouter`,
  `setupDeployment(candidate)`, `setModel`, the `deploymentId` field in Messages, `nextCandidateOrFalse` in each
  `handleRequestError`); verify the 4.1 cases pass.
- [ ] 4.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest" --tests
  "com.epam.aidial.core.server.controller.ChatCompletionsControllerTest" --tests
  "com.epam.aidial.core.server.ResponsesApiTest" --tests "com.epam.aidial.core.server.AnthropicMessagesApiTest"` pass
  (use the existing Responses and Messages API test class names if they differ).

## 5. Candidate walk

- [ ] 5.1 Tests first in `RouterDecisionApiTest`, each on deployments-POST and on `/anthropic/v1/messages`:
  "Forbidden first candidate skipped" (nothing sent to `private-model`), "Failing first candidate skipped" (`m1`'s
  upstream answers `503`, `m2` serves, exactly one `API-KEY` remains valid: the failed attempt's key answers `401`
  or `403` on a call back), "Every candidate forbidden" (`403`), "Non-retriable answer relayed" (`m1` answers
  `400`, nothing sent to `m2`), and a chosen deployment with an interceptor whose model then fails is not retried.
- [ ] 5.2 `retryWithNextCandidate` in `BaseDeploymentPostController` hooked into `nextUpstream()` exhaustion and
  `handleProxyConnectionError`, the per-controller `reenterWithCandidate`, key invalidation of the failed attempt,
  closing the decision when the chosen deployment has interceptors, releasing the saved body at the first response
  byte (design D4); verify the 5.1 cases pass.
- [ ] 5.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest"` pass.

## 6. Accounting

- [ ] 6.1 Tests first: `GfLogStoreTest` "Row of a decided request" (an `AnalyticsLogContext` with a decision writes
  `execution_path`, `parent_deployment` and the `decision` object as the scenario states); in
  `RouterDecisionApiTest` "Root key record after a decision" (read the key record the model received, as the
  existing per-request-key tests do) and "One row for a decided request" plus the row assertion of "Failing first
  candidate skipped" (through the analytics log capture the existing row tests use).
- [ ] 6.2 `ApiKeyData.initFromContext` adds the router before the receiver when a decision is set;
  `AnalyticsLogContext.decision` and the `GfLogStore` block (design D5); verify the 6.1 tests pass.
- [ ] 6.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.log.GfLogStoreTest" --tests "com.epam.aidial.core.server.RouterDecisionApiTest"` pass.

## 7. Docs

- [ ] 7.1 Extend `docs/dynamic-settings/routers.md`: the `decision` mode, `base_url` as the decide URL,
  `context.body`, `decisionTimeoutMs` and its default, the decide request (headers, body), the verdict, the candidate
  walk and what is not retried, the failure table, the one-row accounting with the `decision` object and
  `execution_path`; a JSON example that is the 1.2 fixture's `r1`; verify the example loads in a `FileConfigStoreTest`
  case.
- [ ] 7.2 `./gradlew :openapi-generator:replaceSpec` and the generator's check task: verify `docs/open_api_core.yaml`
  is unchanged (no schema of this change is exposed) or commit the regenerated file if it is.
- [ ] 7.3 `./gradlew checkstyleMain checkstyleTest` (this group's gate).

## 8. Integration checks

- [ ] 8.1 Grep `server/src/main` for every `resolveServingEndpoint` caller and every path that forwards a client
  body to an interface url; list each in design.md with whether the decision step guards it. Expected: the four
  flows of design D3.
- [ ] 8.2 `npx -y @fission-ai/openspec@1.14.3 validate router-decision-mode --strict` passes and every scenario at
  the top of this file names a test that exists and passes.
- [ ] 8.3 `./gradlew :server:test` (full module suite) passes locally, or the failures are shown unrelated with the
  output in review.md.
- [ ] 8.4 Compare design.md with the code as merged; record drift in design.md.
- [ ] 8.5 Measure on the perf improvement initiative's environment: same-day direct, proxy-router and decision-mode
  runs on the router path; Core CPU per user request, user-info calls per second, Redis commands per request, p50
  overhead; record in review.md (deferred: needs the perf environment and a router image with `/decide`).

## Workflow follow-up

- Write review.md in the change folder, then archive with
  `npx -y @fission-ai/openspec@1.14.3 archive router-decision-mode -y` after `router-entity` is archived, and run
  `validate --specs --strict`.
- Next change: conversation binding and outcome events (design.md, Follow-ups), after the measurement.
