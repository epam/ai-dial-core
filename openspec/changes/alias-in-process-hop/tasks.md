# Tasks

## 1. Tests first

- [ ] 1.1 Add `AliasInProcessApiTest` (extends `ResourceBaseTest`; `additionalSettingsOverrides` sets
  `backgroundJob.localBaseUrl` to a port nothing listens on) with the scenarios "Chat completion without Core's own
  address", "Responses and Messages without Core's own address", "Router in decision mode behind an alias" and
  "Requests that stay on HTTP"; verify the first three fail on `development` behaviour (502) and the last passes.
- [ ] 1.2 Add the scenario "Messages through an alias" of "Request body in the alias pass's row" to
  `AliasInProcessApiTest`, reading the rows with `awaitAnalyticsRows`; verify it fails before the change (the alias row
  has the target's name in `model`).
- [ ] 1.3 Name the test of the scenario "Call chain unchanged": the existing execution-path and row tests in
  `AliasApiTest`; verify they pass unchanged after group 2.

## 2. In-process hop

- [ ] 2.1 Add the in-process method to `ProxyClient` (design D1): an `EmbeddedProxyRequest` with the built-in forward
  plugin, no platform check; unit-test in `ProxyClientTest` (or the existing origin client test) that it answers with
  the child pass's answer.
- [ ] 2.2 Send in process from `BaseDeploymentPostController.createProxyRequest(InterfaceType)` for an `AliasPass` that is
  not a background job (design D2); verify 1.1's first three scenarios pass and "Requests that stay on HTTP" still
  passes.
- [ ] 2.3 Keep the tree and skip the serialize in `setProcessedRequestBody` for such a pass when it holds its caller's
  bytes (design D3); verify 1.2 passes, and that an origin plugin's call to an alias (no bytes) still writes a row with
  a body (a case in `EmbeddedCallApiTest`).
- [ ] 2.4 Add a test in `AliasInProcessApiTest` that asserts the headers the model's MockWebServer receives through
  `assistant` (`API-KEY`, `X-DIAL-DEPLOYMENT-ID`, the deployment features header, the alias's `defaultHeaders`, no
  override name of the alias pass) match a direct call's, apart from the key value (design D6); verify it passes.
- [ ] 2.5 Add a test that a client gone during a streamed chat completion through an alias cancels the target pass's
  origin request, and one that a decision fallback from an alias candidate reaches the next candidate (design D5);
  verify both pass.
- [ ] 2.6 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.AliasInProcessApiTest" --tests "com.epam.aidial.core.server.AliasApiTest" --tests "com.epam.aidial.core.server.AliasPublicApiTest" --tests "com.epam.aidial.core.server.AliasPlatformApiTest" --tests "com.epam.aidial.core.server.AliasReloadApiTest" --tests "com.epam.aidial.core.server.RouterDecisionApiTest" --tests "com.epam.aidial.core.server.EmbeddedCallApiTest" --tests "com.epam.aidial.core.server.EmbeddedDecisionApiTest"`;
  verify both succeed (exit code 0, read from the Gradle output, not a tail).

## 3. Docs

- [ ] 3.1 `docs/dynamic-settings/aliases.md` (the "second pass" section, lines 106 to 108): the target pass of a
  create operation runs in process; MCP, features, background Responses and stored-response operations go over
  `backgroundJob.localBaseUrl`; one server span fewer per hop. Verify the section matches the spec delta.
- [ ] 3.2 `README.md` row `backgroundJob.localBaseUrl`: say which alias requests still use it. Verify against design D2.
- [ ] 3.3 Run `./gradlew checkstyleMain checkstyleTest` and
  `npx -y @fission-ai/openspec@1.14.3 validate alias-in-process-hop --strict`; verify both succeed.

## 4. Measurement

- [ ] 4.1 Perf run: Core CPU per user request and Valkey commands per request through the alias chain (alias, router
  in decision mode, alias, model) against direct, on the `development` image and on this branch's image, same day
  (deferred: needs the perf environment). Record the result in review.md against the design's expectation.

## Workflow follow-up

- Write review.md, archive with `npx -y @fission-ai/openspec@1.14.3 archive alias-in-process-hop -y`, then
  `npx -y @fission-ai/openspec@1.14.3 validate --specs --strict`.
