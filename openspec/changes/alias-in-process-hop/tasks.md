# Tasks

## 1. Tests first

- [ ] 1.1 Add `AliasInProcessApiTest` (extends `ResourceBaseTest`; `additionalSettingsOverrides` sets
  `backgroundJob.localBaseUrl` to a port nothing listens on) with the deployment-aliases scenarios "Chat completion
  without Core's own address", "Responses and Messages without Core's own address" (both Responses paths), "Router in
  decision mode behind an alias" and "Requests that stay on HTTP" (tokenize, `count_tokens`, background Responses);
  verify the first three fail on `development` (502) and the last passes.
- [ ] 1.2 Add "Call chain unchanged" to `AliasInProcessApiTest`: the model's key `executionPath` and the two rows read
  with `awaitAnalyticsRows`, the target row with `parent_deployment: assistant`; verify it passes on `development` too.
- [ ] 1.3 Add the scenarios of "Headers of an in-process alias call" (no `X-DIAL-OVERRIDE-NAME` at the model's
  origin, one `Access-Control-Allow-Origin` in the answer) to `AliasInProcessApiTest`; verify both fail on
  `development`.
- [ ] 1.4 Add "Application calls an alias" of "Attachments shared on through an alias" to `AliasInProcessApiTest`
  (the application's per-request key taken from the request its origin receives, as the existing application tests
  do); verify it fails on `development`.
- [ ] 1.5 Add the analytics-log scenarios "Messages through an alias" and "Streamed chat completion through an alias"
  to `AliasInProcessApiTest`; verify both fail on `development` (the alias row has bodies).
- [ ] 1.6 Rewrite `EmbeddedCallApiTest.testBodiesLoggedOnce` as the scenario "Call from an origin plugin" (both rows
  have `request.body` and `response.body`, the child row is not `bodiesOmitted`); verify it fails on `development`.
- [ ] 1.7 Add the router-deployment scenario "Alias over a decision router that wants the body" to
  `RouterDecisionApiTest` (with a fixture router with `decision: {"body": true}` behind an alias if none exists);
  verify it passes on `development` (the body reaches the router over HTTP today).
- [ ] 1.8 Run `./gradlew checkstyleMain checkstyleTest`; verify it succeeds (the new tests fail as stated above until
  group 2).

## 2. In-process hop

- [ ] 2.1 Add the in-process method to `ProxyClient` with the built-in forward plugin and the `Request.body` comment
  (design D1); add a case to `ProxyClientTest` that the request answers with the child pass's answer; verify it
  passes.
- [ ] 2.2 Add the one check of design D2 (alias pass, not a background job, path in `CREATE_ROUTES`) and use it in
  `BaseDeploymentPostController.createProxyRequest(InterfaceType)`; verify 1.1 passes in full.
- [ ] 2.3 Use the same check in `setProcessedRequestBody`: keep the tree, skip the serialize, empty buffer when the
  pass has no bytes (design D3); verify 1.1 still passes and `count_tokens` through an alias answers `200` with the
  default `backgroundJob.localBaseUrl` (a case in `MessagesApiTest`).
- [ ] 2.4 Key the row's bodies and the assembled response on the alias pass instead of the child pass
  (`AnalyticsLogContext`, `BaseChatCompletionController`; design D4); verify 1.5 and 1.6 pass and the rest of
  `EmbeddedCallApiTest` still passes.
- [ ] 2.5 Mark the child passes the alias hop's host creates, and make the two span rules skip a marked pass
  (`GenAiTraceAttributes`, `AutoEnrichedOtelJsonLayout`; design D5, D6); add a test that the model's `gen_ai` usage
  attributes land on the alias pass's server span (span exporter as the existing tracing tests use, or the
  `tracingAttributes` the pass records if no exporter is wired in tests); verify it passes.
- [ ] 2.6 Verify 1.3 and 1.4 pass with no further code (design D7, D9); if not, record the drift in design.md before
  changing code.
- [ ] 2.7 Add a test that a client gone during a streamed chat completion through an alias cancels the target pass's
  origin request, and one that a decision fallback from an alias candidate reaches the next candidate (design D8);
  verify both pass.
- [ ] 2.8 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.AliasInProcessApiTest" --tests "com.epam.aidial.core.server.AliasApiTest" --tests "com.epam.aidial.core.server.AliasPublicApiTest" --tests "com.epam.aidial.core.server.AliasPlatformApiTest" --tests "com.epam.aidial.core.server.AliasReloadApiTest" --tests "com.epam.aidial.core.server.RouterDecisionApiTest" --tests "com.epam.aidial.core.server.EmbeddedCallApiTest" --tests "com.epam.aidial.core.server.EmbeddedDecisionApiTest" --tests "com.epam.aidial.core.server.EmbeddedOriginApiTest" --tests "com.epam.aidial.core.server.MessagesApiTest" --tests "com.epam.aidial.core.server.ResponsesApiTest" --tests "com.epam.aidial.core.server.proxy.ProxyClientTest" --tests "com.epam.aidial.core.server.log.GfLogStoreTest"`;
  verify both succeed (exit code 0, read from the Gradle output, not a tail).

## 3. Docs

- [ ] 3.1 `docs/dynamic-settings/aliases.md`, the "second pass" section (lines 106 to 108): the target pass of a
  create operation runs in process; MCP, features, `count_tokens`, background Responses and stored-response operations
  go over `backgroundJob.localBaseUrl`; the alias row has no bodies; the header and attachment differences. Verify
  against the deployment-aliases and analytics-log deltas.
- [ ] 3.2 `README.md` row `backgroundJob.localBaseUrl`: which alias requests still use it. Verify against design D2.
- [ ] 3.3 `docs/tracing.md`: a section next to "Calls from an origin plugin" for the target pass of an alias call: no
  server span, attributes and log enrichment on the alias pass's span, row span id without an OTel span. Verify
  against design D6.
- [ ] 3.4 `docs/dynamic-settings/plugins.md` line 81: the row of a plugin's call carries its bodies, as an HTTP call's
  does, so a plugin call logs them in two rows. Verify against the analytics-log delta.
- [ ] 3.5 Run `./gradlew checkstyleMain checkstyleTest` and
  `npx -y @fission-ai/openspec@1.14.3 validate alias-in-process-hop --strict`; verify both succeed.

## 4. Measurement

- [ ] 4.1 Perf run: Core CPU per user request and Valkey commands per request through the alias chain (alias, router
  in decision mode, alias, model) against direct, on the `development` image and on this branch's image, same day.
  Count user requests from the load generator, not from Core's server spans or HTTP metrics, which drop by design
  (deferred: needs the perf environment). Record the result in review.md against the design's expectation.

## Workflow follow-up

- Write review.md, archive with `npx -y @fission-ai/openspec@1.14.3 archive alias-in-process-hop -y`, then
  `npx -y @fission-ai/openspec@1.14.3 validate --specs --strict`.
