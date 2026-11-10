# Tasks

Checks for every group: `./gradlew checkstyleMain checkstyleTest` and the group's test classes with `--tests` (JDK 21
as `JAVA_HOME`; check that port 16370 is free before an integration run).

## 1. Test plugin and fixtures

- [x] 1.1 Teach `TestOriginPluginProvider` the decide protocol, steered by client headers as its other cases are.
  - On `POST /decide`:
    - `X-Test-Decide: <name>[,<name>]` answers `{"candidates": [...]}`.
    - `X-Test-Redecide: <name>[,<name>]` answers instead when `X-DIAL-ROUTER-ATTEMPTS` is present.
    - `X-Test-Decide-Status: <code>` with `X-Test-Decide-Body: <json>` answers that status and body.
    - `X-Test-Decide-Stall-Body` sends status `200` and never ends the body.
    - `X-Test-Decide-Delay-Ms: <ms>` answers after that delay.
    - `X-Test-Decide-Strip` removes `messages` from its tree before answering.
    - `X-Test-Decide-Call: <path>` calls that path through the host with a minimal chat body of its own, waits for the
      answer, then answers.
    - `X-Test-Decide-Call-Stream: <path>` starts a streamed call and answers without waiting.
  - On a notice (`X-DIAL-ROUTER-NOTICE`): `X-Test-Notice-Call: <path>` calls that path through the host.
  - The existing `X-Test-Fail: call` and `X-Test-Await-Cancel` apply.
  - For the API tests it records:
    - each decide or notice request it receives: path, headers, caller, body bytes, body tree, whether `cancelled()`
      completed, and when its future settled;
    - the answers of its own calls;
    - its hosts, in the static `HOSTS` that `EmbeddedCallApiTest` already reads, so a test can call through a host
      after the decide call ended.

  Verify: the API tests of groups 3 and 4 drive every header. The provider gets no tests of its own.
- [x] 1.2 Add `dial-config/router-decision-embedded.json`.
  - Plugins `p1` and `p2` of type `test-origin`.
  - Decision routers embedded with `p1`:
    - `r1`, plain;
    - `rb`, with `decision.body: true` on chat completions, Messages and Responses, and on deployments-POST;
    - `rt`, with `timeoutMs: 200`;
    - `r9`, naming the missing `p9`;
    - `r4`, embedded chat next to an HTTP Responses interface;
    - `re`, with an embedded embeddings interface.
  - HTTP decision routers: `r2` with `decision.body: true`, and `rf` without.
  - An application `app` served by `p2`.
  - Candidate models on MockWebServer: `m1`, with `defaults: {"temperature": 0.5}`, and `m2`, streamed slowly through
    the server's throttle for the open-call test.

  Verify: the file is valid JSON. It loads in a `ConfigPostProcessorTest` case once 3.1 lifts the rule.
- [x] 1.3 Run checkstyle (`checkstyleTest` covers the provider).

## 2. A router entry in the origin seam

- [x] 2.1 `ChildPass` takes the key record its calls carry, and can be created closed. With no key, it is created
  closed, so every call is answered `401`. Verify: `ChildPassTest` cases for an explicit key and for a closed host.
- [x] 2.2 `OriginClient` gets the router entry of design D2, served by `EmbeddedProxyRequest`:
  - no platform check;
  - an explicit call key and body tree;
  - the context's request tree left alone;
  - no client watch;
  - its future always settles;
  - it closes its own attempt as D3 states, and stays registered with `addChildPass` as a backstop;
  - a notice's host is created closed.

  The origin entry is unchanged. Verify: `EmbeddedProxyRequestTest` cases:
  - the plugin gets the given tree and the context's tree stays;
  - a context whose deployment is a custom application does not refuse the router entry;
  - a client gone before a plugin failure still fails the future;
  - no close handler is set;
  - after a read answer, a call still open fails and a later call answers `401`, `cancelled()` stays incomplete, and
    the lease is released once the plugin's future settled;
  - `reset()` completes `cancelled()`;
  - `finalizeRequest`'s `closeChildPasses` closes an attempt nothing else closed.
- [x] 2.3 Document in the javadoc of `OriginHost.call`, `OriginHost.cancelled`, `OriginRequest.headers`,
  `OriginRequest.body` and `OriginRequest.bodyTree`:
  - a decide request's calls carry the decide key, end with the decide call, and see `cancelled()` only at the
    deadline;
  - a notice has no `API-KEY`, and its calls get `401`;
  - in process only the create operations can be called; `GET /v1/user/info` and attachment reads go over HTTP with
    `API-KEY`;
  - a decide's `bodyTree` is the plugin's own copy;
  - a decide's `body` is the client's buffer, which Core reuses for later candidates, so it is read-only;
  - an in-process decide has no `traceparent`.

  Verify: `./gradlew :plugin-api:javadoc` builds.
- [x] 2.4 Run checkstyle and `ChildPassTest`, `EmbeddedProxyRequestTest`, `EmbeddedCallApiTest`,
  `EmbeddedOriginApiTest`.

## 3. Decision by an embedded plugin

- [x] 3.1 Drop the embedded rule in `ConfigPostProcessor.validateDecision`; keep the translator rule. In
  `ConfigPostProcessorTest`, replace `testDecisionRouterWithEmbeddedInterfaceRejected` with
  `testDecisionRouterWithEmbeddedInterfaceAccepted` (scenario "Embedded interface on a decision router accepted").
  `testDecisionSettingsNeedDecisionMode` and `testDecisionRouterWithTranslatorRejected` keep covering the other two
  scenarios of "Decision router settings". Verify: `ConfigPostProcessorTest` passes and the group 1 fixture loads.
- [x] 3.2 `RouterDecisionClient.decide` and `notice` send through `proxy.getOriginClient()`:
  - the router entry when `DeploymentEndpointUtil.resolvePlugin(router, type)` names a plugin, with the decide key for
    a decide and no key and no tree for a notice; `http` otherwise;
  - retyped to `ProxyRequest`/`ProxyResponse`;
  - the reset is registered when the request is created, as today, but fires only once the deadline has fired: the
    timer sets a flag. A request created after the deadline is reset at once.

  Verify: `RouterDecisionClientTest` passes with `getOriginClient()` mocked around the real HTTP client, plus
  mock-level cases:
  - an embedded decide read from a stub plugin;
  - no reset after a `4xx` answer or a failed future;
  - a reset at the deadline, also when the deadline passes during the connect;
  - a reset when the answer's status arrived but its body did not end.
- [x] 3.3 New `EmbeddedDecisionApiTest` with the scenarios that need no body, one test each.
  - Decision mode: `testEmbeddedInterfaceNextToHttpOne`.
  - Decision router settings: `testEmbeddedDecisionRouterListed`, the `GET /v1/routers/r1` half.
  - Router identity through user info: `testEmbeddedRouterGetsTheCaller`.
  - Decision by an embedded plugin: `testPluginDecides`, `testPluginRedecides` (`m1` answers `503`),
    `testNoticeToThePlugin`, `testClientGoneWhilePluginDecides`, and `testEmbeddingsDecidedByThePlugin` on `re`.
  - Embedded decision failures: `testPluginFails`, which also asserts the plugin is not cancelled;
    `testPluginWithoutAnEntry`; `testPluginTooSlow`, plus a stalled-body case; `testPluginRefusesTheRequest`.
  - Calls of an embedded router while it decides: `testPluginAsksModelWhileDeciding`, `testCallAfterTheDecision`
    (through `HOSTS`), `testCallOpenWhenTheDecisionEnds`, `testCallWhileAnsweringNotice`. These also cover
    deployment-origin's "Calls end with the plugin's pass" for the decide.
  - "Not cancelled" checks are made once the client's answer has completed and the plugin's recorded future has
    settled. They never wait on a router row, which a failure does not write.
  - The existing `EmbeddedCallApiTest` tests keep covering the deployment-origin requirements for a plugin that serves
    a deployment's interface.

  Verify: the class passes.
- [x] 3.4 Update `docs/dynamic-settings/routers.md` (Decision mode section) and `docs/dynamic-settings/plugins.md`:
  - a decision router may serve an interface in `embedded` mode;
  - its plugin answers `POST /decide` in the same protocol;
  - the plugin's calls carry the decide key and are cancelled when the decide ends;
  - a notice has no key.

  Verify: the docs no longer say an embedded interface is rejected, and they name each other.
- [x] 3.5 Run checkstyle and `ConfigPostProcessorTest`, `ConfigRouterTest`, `RouterDecisionClientTest`,
  `RouterDecisionApiTest` (the HTTP decision tests must pass unchanged), `EmbeddedDecisionApiTest`.

## 4. The body, the grants and the in-process call

- [x] 4.1 `RouterDecisionFlow.decideIfRouter` takes the parsed request tree (nullable).
  - The three body-addressed controllers pass theirs: `MessagesBaseController.handle`,
    `ChatCompletionsController.dispatch` and `ResponsesController.dispatch`.
  - `DeploymentPostController.handleDeployment` passes none.
  - `mintDecideKey` collects the attachments from that tree, and on deployments-POST keeps parsing the bytes (design
    D5).

  Verify:
  - `RouterDecisionApiTest.decideKeyReadsTheAttachmentsOfTheBodyItCarries` passes on deployments-POST;
  - new `RouterDecisionApiTest` cases with `decision.body` on `/openai/v1/chat/completions` (`custom_content`
    attachments) and `/openai/v1/responses` (`input_file.file_url`) check that the decide key grants the attachment.
- [x] 4.2 An embedded decide with body (design D4): the plugin gets the original bytes buffer and a copy of the tree,
  taken in the virtual-thread task that mints the key; a redecide parses the bytes for its copy. Verify with
  `EmbeddedDecisionApiTest`:
  - `testBodyAndTree`, on chat completions, Messages, Responses and deployments-POST;
  - `testPluginChangesItsTree`;
  - `testNoBodyWithoutTheSetting`.
- [x] 4.3 An in-process call (design D6). The serialization of the call's tree, made once, becomes the original bytes:
  - in `decideWith` for the body-addressed APIs, when the pass is a child pass with empty original bytes;
  - in `DeploymentPostController.readRequestBody` for a router on deployments-POST.

  Verify with `EmbeddedDecisionApiTest`, scenarios of "Decide request of an in-process call":
  - `testPluginCallsDecisionRouterThatWantsTheBody`;
  - `testFallbackInsideAnInProcessCall` (on `rf`, without `decision.body`; `m2`'s body has none of `m1`'s defaults);
  - `testInProcessCallOnDeploymentsPost`;
  - plus `testInProcessCallToAnEmbeddedRouter`.

  The existing fallback tests in `RouterDecisionApiTest` pass unchanged.
- [x] 4.4 Extend the routers.md section of 3.4 with:
  - the `decision.body` hand-off: the bytes, and the body tree as the plugin's own copy;
  - an in-process call's serialized tree.

  Verify: the section names `bodyTree`.
- [x] 4.5 Run checkstyle and `RouterDecisionApiTest`, `RouterApiTest`, `MessagesApiTest`, `ResponsesApiTest`,
  `EmbeddedDecisionApiTest`, `EmbeddedCallApiTest`.

## 5. Integration and measurement

- [x] 5.1 Run `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`, then the classes of groups 1 to 4
  together. Verify: no new validation issue, all green.
- [ ] 5.2 Measure on the perf environment, on one install: the direct path; decision over HTTP and in process, each
  with and without `decision.body`.
  - Report per variant: Core CPU per user request, router CPU, Redis commands per request and JVM allocation per
    request.
  - Compare with the effect table in design.md, including the expected cost of the tree copy.
  - The in-process variants need the router plugin answering `POST /decide`, which is in the router's repository.

  (deferred: needs the perf environment and the router plugin with `/decide`)
- [x] 5.3 Write `review.md`: what was built, deviations from design.md, the test of each scenario, and the
  measurement or its deferral.

## Workflow follow-up

- Archive the change once `review.md` exists and every non-deferred task is checked; then run
  `validate --specs --strict`.
