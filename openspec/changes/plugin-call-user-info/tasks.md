# Tasks

## 1. Plugin API

- [ ] 1.1 `Call` gains `HttpMethod method` as its first component, and a three-argument constructor
  `(uri, headers, body)` that means `POST` (design D1). Its javadoc: a `POST` to a create operation with a body, or a
  `GET` of `/v1/user/info` with none. `HostDelegate`'s javadoc of `call`: a create operation is checked, limited,
  charged and logged as today, and a user info read is answered as Core answers it over HTTP with the call's key (no
  deployment checks, limits or row); the failures add a call without a method, another method or path, and a `GET`
  with a body; the user info is served in process, while file reads and the other APIs still go over HTTP. The
  three-argument callers (`ProxyClient.java:FORWARD`, the `forward` sample, the tests) stay unchanged.
- [ ] 1.2 Run `./gradlew checkstyleMain checkstyleTest :aidial-core-plugin-api:compileJava :plugin-samples:compileJava`
  and `./gradlew :server:test --tests "com.epam.aidial.core.server.EmbeddedCallApiTest" --tests
  "*.AliasInProcessApiTest"`; verify both succeed.

## 2. User info through the host

- [ ] 2.1 `config/TestOriginPluginProvider`: `X-Test-Call-Method: <method>` makes the call with that method, with no
  body for a method other than `POST` unless `X-Test-Call-With-Body` is set; the plugin relays the call's answer as
  today. Document both headers in its javadoc. Verify `EmbeddedCallApiTest` passes unchanged.
- [ ] 2.2 Add the deployment-origin scenarios of "Caller's user info through the host" to `EmbeddedCallApiTest`:
  `testUserInfoOfCallerWithApiKey` ("Caller with an API key") and `testUserInfoOfCallerWithJwt` ("Caller with a
  JWT"), each asserting the exact body `UserInfoApiTest` asserts for that caller; `testUserInfoAfterThePluginsPassEnded`
  ("User info after the plugin's pass ended", through `HOSTS`); `testOtherReadsMethodsAndGetWithBodyRefused` ("Other
  reads, other methods and a GET with a body"). Verify the first three fail before 2.5 (the call fails: `502`, or no
  `401`) and the fourth passes.
- [ ] 2.3 Unit tests in `VertxHostDelegateTest`: `testUserInfoCallDispatchedAsChildPass` (a `GET` without a body is
  dispatched with the method `GET`, `isChildPass()` true and no call tree) and `testUserInfoCallPastTheDepthLimit`
  (`400`, never dispatched), which fail before 2.5; `testCallWithoutAMethodFails`, which fails before 2.5 (the host
  dispatches it as a `POST` today); `testOtherReadsAndMethodsFail` (`GET /v1/user/limits`, `DELETE /v1/user/info`,
  `POST /v1/user/info`: never dispatched) and `testGetWithBodyFails`, which pass after 1.1 already and must keep
  passing. `ProxyContextTest.testChildPassWithoutCallTree` (a context whose request is an `EmbeddedServerRequest` and
  which has no tree is a child pass) and a `GenAiTraceAttributesTest` case (such a pass writes nothing on the current
  span), which fail before 2.4.
- [ ] 2.4 The child-pass mark (design D3): `ProxyContext.isChildPass` answers whether the pass's request is an
  `EmbeddedServerRequest`. Give an `EmbeddedServerRequest` to the tests that mark a child pass by `setCallTree` alone:
  `ProxyContextTest.testCallTreeUntilTheBodyIsSet` and `GenAiTraceAttributesTest.childContext`. Verify
  `ProxyContextTest`, `GenAiTraceAttributesTest` and `AutoEnrichedOtelJsonLayoutTest` pass.
- [ ] 2.5 The host (design D2, D4, D5), in today's order: the call's own fields (a path; a method; a body for `POST`
  and none for `GET`) fail the call; then a closed host answers `401`; then `POST` reaches the create routes and `GET`
  `RouteTemplate.USER_INFO`, and any other call fails; then the background rule and the depth check. Compare methods
  with `equals` (`HttpMethod` is a class). `EmbeddedServerRequest` takes the call's method and sets
  `Content-Type: application/json` only for a call with a body; its class javadoc says so. Update
  `EmbeddedServerRequestTest` (the `POST` and `Content-Type` assertions, the constructor calls) and add a `GET` case.
  `servesCall(path)` keeps meaning the create routes for `BaseDeploymentPostController.isInProcessAliasHop`. Verify
  `VertxHostDelegateTest`, `EmbeddedServerRequestTest`, `EmbeddedCallApiTest`, `AliasInProcessApiTest` and
  `AliasApiTest` pass.
- [ ] 2.6 Docs, `docs/dynamic-settings/plugins.md`, "Calling another deployment": the paragraph that sends user info
  over HTTP keeps files and the resource and sharing APIs only; "What a call reaches" gains `GET /v1/user/info`
  without a body; "What the plugin passes" says a `GET` passes no body; "How the call is checked, charged and logged"
  says a user info call is answered as over HTTP with the call's key, with no deployment checks, limits, per-request
  key or row. "Translating for a deployment", "Responses": the item operations are not reachable through the host
  (instead of "create operations only"). "The forward sample": it forwards the create operations only, as the host
  serves no other write. `docs/tracing.md` "Calls from an origin plugin": a user info call has no server span either
  and writes nothing on the plugin's span. Verify each statement against the deployment-origin delta and design D3.
- [ ] 2.7 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.EmbeddedCallApiTest" --tests "*.VertxHostDelegateTest" --tests
  "*.EmbeddedServerRequestTest" --tests "*.ProxyContextTest" --tests "*.GenAiTraceAttributesTest" --tests
  "*.AutoEnrichedOtelJsonLayoutTest" --tests "*.AliasInProcessApiTest" --tests "*.AliasApiTest"`; verify both succeed.

## 3. User info while deciding

- [ ] 3.1 `config/TestOriginPluginProvider`: `X-Test-Decide-Call-Method` and `X-Test-Notice-Call-Method` set the method
  of the decide and notice calls, and each recorded call in `DECIDED` keeps its answer's body. Add the router-deployment
  scenarios to `EmbeddedDecisionApiTest`: `testPluginReadsTheCallerWhileDeciding` ("Plugin reads the caller while
  deciding", asserting the exact body) and a `GET /v1/user/info` case in `testCallWhileAnsweringNotice` ("Call while
  answering a notice"). Verify both pass, and the other tests of the class pass unchanged.
- [ ] 3.2 Docs: `docs/dynamic-settings/plugins.md` "Deciding for a router", "The key": the plugin reads the user info
  through its host with the decide request's key; file reads still go over HTTP with that `API-KEY`.
  `docs/dynamic-settings/routers.md` "Decision mode", the plugin paragraph: a plugin answering a decide request may
  call the create operations and read the caller's user info through its host. Verify both against the modified
  requirement.
- [ ] 3.3 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.EmbeddedDecisionApiTest"`; verify both succeed.

## 4. Measurement

- [ ] 4.1 A/B with the model-provider plugin and a `UserInfo.*` session tag: the user info read over HTTP against the
  read through the host, at the same load; compare Core CPU per request, Redis GETs per request and server spans per
  request, and record the result in review.md. (deferred: needs the perf environment and the plugin's user info read
  through the host)

## Workflow follow-up

- Open a draft PR with proposal.md and the spec deltas for review before applying (not a small change).
- After apply: review.md, archive, `npx -y @fission-ai/openspec@1.14.3 validate --specs --strict`, one commit staging
  explicit paths.
