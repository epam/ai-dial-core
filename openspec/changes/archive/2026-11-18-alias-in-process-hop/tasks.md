# Tasks

## 1. Tests first

The "fails on `development`" checks below ran the new tests on a scratch checkout of `development` (f61fff688): 9 of
them failed as stated and the rest passed.

- [x] 1.1 Add `AliasInProcessApiTest` (extends `ResourceBaseTest`; `additionalSettingsOverrides` sets
  `backgroundJob.localBaseUrl` to a port nothing listens on) with the deployment-aliases scenarios "Chat completion
  through an alias", "Responses and Messages through an alias" (both Responses paths), "Router in
  decision mode behind an alias" and "Requests that stay on HTTP" (tokenize, `count_tokens`, background Responses);
  verify the first three fail on `development` (502) and the last passes.
- [x] 1.2 Add "Call chain unchanged": `AliasApiTest.targetRowNamesTheAliasAsItsParent` (the two rows read with
  `awaitAnalyticsRows`, the target row with `parent_deployment: assistant`), next to the existing execution-path test
  `targetPassCarriesTheCallChain`; verify it passes on `development` too. The behaviour scenarios of 1.2 to 1.5 are in
  `AliasApiTest`, at the default `backgroundJob.localBaseUrl`, so that they also run against `development`.
- [x] 1.3 Add the scenarios of "Headers of an in-process alias call" to `AliasApiTest`
  (`targetOriginGetsNoOverrideNameFromTheAliasPass`, `answerThroughAnAliasCarriesCorsHeaderOnce`); verify both fail on
  `development` (`X-DIAL-OVERRIDE-NAME: gpt-4-internal`; `access-control-allow-origin=[*, *]`).
- [x] 1.4 Add "Application calls an alias" of "Attachments shared on through an alias" to `AliasApiTest`
  (`applicationCallingAnAliasMayReadTheFileTheTargetShares`: the model's origin stores a file in its key's bucket and
  attaches it; the application reads it with its own key); verify it fails on `development` (`403`).
- [x] 1.5 Add the analytics-log scenarios "Messages through an alias" and "Streamed chat completion through an alias"
  to `AliasApiTest` (`messagesThroughAnAliasLogBodiesInTheTargetRowOnly`,
  `streamedChatThroughAnAliasAssemblesInTheTargetRowOnly`); verify both fail on `development` (the alias row has
  bodies).
- [x] 1.6 Rewrite `EmbeddedCallApiTest.testBodiesLoggedOnce` as the scenario "Call from an origin plugin"
  (`testCallFromAnOriginPluginLogsBothBodies`: both rows have `request.body` and `response.body`, the child row is not
  `bodiesOmitted`); verify it fails on `development`.
- [x] 1.7 Add the router-deployment scenario "Alias over a decision router that wants the body" to
  `RouterDecisionApiTest` (`aliasOverDecisionRouterThatWantsTheBody`, with the new fixture alias `body-tier` over `r3`);
  verify it passes on `development` (the body reaches the router over HTTP today).
- [x] 1.8 Run `./gradlew checkstyleMain checkstyleTest`; verify it succeeds.

## 2. In-process hop

- [x] 2.1 Add the in-process method to `ProxyClient` (`aliasTarget`) with the built-in forward plugin and the
  `Request.body` comment (design D1); add `ProxyClientTest.testAliasTargetDoesNotCheckTheDeployment` (a stored alias's
  pass gets its request with no platform check); the answer through the child pass is covered by 1.1. Verify it passes.
- [x] 2.2 Add the one check of design D2 (`BaseDeploymentPostController.inProcessAliasBaseUrl`: alias pass, not a
  background job, path a route `VertxHostDelegate.servesCall` accepts) and use it in
  `createProxyRequest(InterfaceType)`; verify 1.1 passes in full.
- [x] 2.3 Use the same check in `setProcessedRequestBody`: keep the tree, skip the serialize, empty buffer when the
  pass has no bytes (design D3); verify 1.1 still passes and `count_tokens` through an alias answers `200` with the
  default `backgroundJob.localBaseUrl` (`AliasApiTest.countTokensThroughAnAlias`).
- [x] 2.4 Key the row's bodies and the assembled response on the alias pass instead of the child pass
  (`AnalyticsLogContext`, `BaseChatCompletionController`; design D4); verify 1.5 and 1.6 pass and the rest of
  `EmbeddedCallApiTest` still passes.
- [x] 2.5 Mark the child passes the alias hop's host creates (`VertxHostDelegate.forAliasHop`,
  `ProxyContext.aliasTargetPass`), and make the two span rules skip a marked pass (`GenAiTraceAttributes`,
  `AutoEnrichedOtelJsonLayout`; design D5, D6); add `GenAiTraceAttributesTest.aliasTargetPassWritesOnTheCurrentSpan`
  and `pluginCallPassWritesNoSpanAttribute` (no span exporter is wired in the API tests, so the current span is
  mocked as the other tests of the class do); verify they pass.
- [x] 2.6 Verify 1.3 and 1.4 pass with no further code (design D7, D9); if not, record the drift in design.md before
  changing code.
- [x] 2.7 Add `AliasApiTest.clientGoneMidStreamThroughAnAliasStillChargesTheTarget` (the client leaves mid-stream; the
  model's stream is read to its end and its row has the usage, as on a direct call; passes on `development` too). The
  decision fallback from an alias candidate is `RouterDecisionApiTest.failingAliasCandidateSkipped`, which now runs the
  candidate's target pass in process. Verify both pass (design D8).
- [x] 2.8 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.AliasInProcessApiTest" --tests "com.epam.aidial.core.server.AliasApiTest" --tests "com.epam.aidial.core.server.AliasPublicApiTest" --tests "com.epam.aidial.core.server.AliasPlatformApiTest" --tests "com.epam.aidial.core.server.AliasReloadApiTest" --tests "com.epam.aidial.core.server.RouterDecisionApiTest" --tests "com.epam.aidial.core.server.EmbeddedCallApiTest" --tests "com.epam.aidial.core.server.EmbeddedDecisionApiTest" --tests "com.epam.aidial.core.server.EmbeddedOriginApiTest" --tests "com.epam.aidial.core.server.MessagesApiTest" --tests "com.epam.aidial.core.server.ResponsesApiTest" --tests "com.epam.aidial.core.server.proxy.ProxyClientTest" --tests "com.epam.aidial.core.server.log.GfLogStoreTest" --tests "com.epam.aidial.core.server.tracing.GenAiTraceAttributesTest" --tests "com.epam.aidial.core.server.log.layout.AutoEnrichedOtelJsonLayoutTest"`;
  verify both succeed (exit code 0, read from the Gradle output, not a tail).

## 3. Docs

- [x] 3.1 `docs/dynamic-settings/aliases.md`, the "second pass" section (lines 106 to 108): the target pass of a
  create operation runs in process; MCP, features, `count_tokens`, background Responses and stored-response operations
  go over `backgroundJob.localBaseUrl`; the alias row has no bodies; the header and attachment differences. Verify
  against the deployment-aliases and analytics-log deltas.
- [x] 3.2 `README.md` row `backgroundJob.localBaseUrl`: which alias requests still use it. Verify against design D2.
- [x] 3.3 `docs/tracing.md`: a section next to "Calls from an origin plugin" for the target pass of an alias call: no
  server span, attributes and log enrichment on the alias pass's span, row span id without an OTel span. Verify
  against design D6.
- [x] 3.4 `docs/dynamic-settings/plugins.md` line 81: the row of a plugin's call carries its bodies, as an HTTP call's
  does, so a plugin call logs them in two rows. Verify against the analytics-log delta.
- [x] 3.5 Run `./gradlew checkstyleMain checkstyleTest` and
  `npx -y @fission-ai/openspec@1.14.3 validate alias-in-process-hop --strict`; verify both succeed.

## 4. Measurement

- [ ] 4.1 Perf run: Core CPU per user request and Valkey commands per request through the alias chain (alias, router
  in decision mode, alias, model) against direct, on the `development` image and on this branch's image, same day.
  Count user requests from the load generator, not from Core's server spans or HTTP metrics, which drop by design
  (deferred: needs the perf environment). Record the result in review.md against the design's expectation.

## Workflow follow-up

- Write review.md, archive with `npx -y @fission-ai/openspec@1.14.3 archive alias-in-process-hop -y`, then
  `npx -y @fission-ai/openspec@1.14.3 validate --specs --strict`.
