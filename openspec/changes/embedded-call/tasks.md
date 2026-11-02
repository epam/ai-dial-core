# Tasks

## 1. The contract additions

- [ ] 1.1 Add `OriginHost`, `OriginCall` and `OriginCaller` to `plugin-api`, change `OriginPlugin.handle` to take the
      host, and add `bodyTree` and `caller` to `OriginRequest` (design D1). The Javadoc says:
      - that `call` takes the body tree it is given;
      - which operations `uri` may name, and that `background: true` is refused;
      - which headers Core drops from a call;
      - that `bodyTree` is read-only unless it is handed to `call`.

      Verify `./gradlew :plugin-api:build` passes
- [ ] 1.2 Fill `caller` in `EmbeddedProxyRequest` (design D6), with `bodyTree` null and an `OriginHost` that fails every
      call until groups 2 and 4. Update `TestOriginPluginProvider`, `EmbeddedProxyRequestTest` and its plugin lambdas
      to the new signature, and make the test plugin record the caller. Verify:
      - `EmbeddedOriginApiTest` and `EmbeddedProxyRequestTest` pass;
      - deployment-origin "Caller with an API key" and "Caller with a JWT" pass in a new `EmbeddedCallApiTest`.
- [ ] 1.3 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.EmbeddedOriginApiTest"`
      - `--tests "com.epam.aidial.core.server.EmbeddedCallApiTest"`
      - `--tests "com.epam.aidial.core.server.upstream.EmbeddedProxyRequestTest"`

## 2. The shared body tree

- [ ] 2.1 Write the tests first:
      - `parseRequestBody` returns a child's tree once without parsing, and parses otherwise;
      - `getRequestBody()` serialises the shared tree when no body was set;
      - `requestTree` is kept for an embedded interface only.

      Then add `ProxyContext.parseRequestBody`, `requestTree` and the lazy `getRequestBody()` (design D3). Verify the
      tests pass
- [ ] 2.2 Call `parseRequestBody` from the six parse sites:
      - `DeploymentPostController.handleRequestBody`;
      - `ChatCompletionsController.parseBody`;
      - `ResponsesController.parseBody`;
      - `MessagesBaseController.parseBody`;
      - `ChatCompletionInterceptorController.parseRequest` and `ResponsesInterceptorController.parseRequest`.

      Have `EmbeddedProxyRequest` take the tree as `bodyTree`, re-parsing `body` on a retried attempt. Stub
      `parseRequestBody` in the controller tests that mock `ProxyContext`: `DeploymentPostControllerTest`,
      `ChatCompletionsControllerTest`, `ResponsesControllerTest` and `ChatCompletionInterceptorControllerTest`. Verify
      by 2.3
- [ ] 2.3 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - API tests (`com.epam.aidial.core.server.`): `DeploymentPostApiTest`, `ChatCompletionApiTest`,
        `EmbeddingsApiTest`, `ResponsesApiTest`, `MessagesApiTest`, `ConfigInterceptorTest`, `EmbeddedOriginApiTest`;
      - controller tests (`com.epam.aidial.core.server.controller.`): `DeploymentPostControllerTest`,
        `ChatCompletionsControllerTest`, `ResponsesControllerTest`, `ChatCompletionInterceptorControllerTest`.

## 3. The in-memory request and response

- [ ] 3.1 Write `ChildServerResponseTest` and `ChildServerRequestTest` first.
      - Response:
        - the head freezes on the first `write` or `end`;
        - `writeQueueFull` and `drainHandler` follow the plugin's reads;
        - chunks and the end reach the plugin from tasks of their own on the parent's context;
        - a write from a virtual thread runs on the child's context;
        - after `cancel`, a piped source's next write fails and the pipe fails.
      - Request: the path, the query, the method `POST`, and the headers Core drops.

      Then implement both (design D4), with the response's body an `InboundBuffer`. Verify both test classes pass
- [ ] 3.2 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.upstream.ChildServerResponseTest"`
      - `--tests "com.epam.aidial.core.server.upstream.ChildServerRequestTest"`

## 4. The child pass

- [ ] 4.1 Extract `Proxy.dispatch(ProxyContext)` from `processAuthorizationResult`, with no behaviour change. Verify
      `DeploymentPostApiTest` and `ResourceApiTest` pass
- [ ] 4.2 Write `ChildPassTest` first: a path outside the create routes fails the call; a background Responses call
      fails; a call after close answers `401`; a head after close is cancelled. Then implement `ChildPass` (design D2):
      - the route check;
      - the child context on a duplicated Vert.x context carrying the parent's `ACTIVE_CONTEXT`, the parent's key
        record and a minted span id;
      - dispatch failures answered through `Proxy.handleError`;
      - closing from `finalizeRequest` and `EmbeddedProxyRequest.reset`.

      Skip the span writes of `GenAiTraceAttributes` for a child pass. Give `TestOriginPluginProvider` its call mode
      (design D8). Verify `ChildPassTest` passes
- [ ] 4.3 Add to `EmbeddedCallApiTest`, and verify they pass:
      - deployment-origin "Calling a deployment from an origin plugin", all seven scenarios;
      - "Calls end with the plugin's pass";
      - "Cancelling a call", both scenarios.
- [ ] 4.4 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.EmbeddedCallApiTest"`
      - `--tests "com.epam.aidial.core.server.ChildPassTest"`
      - `--tests "com.epam.aidial.core.server.EmbeddedOriginApiTest"`
      - `--tests "com.epam.aidial.core.server.DeploymentPostApiTest"`
      - `--tests "com.epam.aidial.core.server.tracing.GenAiTraceAttributesTest"`

## 5. Accounting and the row

- [ ] 5.1 Return `withoutBodies()` from `AnalyticsLogContext.from` for a child pass, and skip the assembled answer for
      one in `BaseChatCompletionController.completeProxyResponse` (design D5). Verify by 5.2
- [ ] 5.2 Add to `EmbeddedCallApiTest`, and verify they pass:
      - app-to-model-accounting "Target limits charged", "Usage rolled up to the plugin's pass" and "Child row";
      - analytics-log "Bodies logged once".
- [ ] 5.3 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.EmbeddedCallApiTest"`
      - `--tests "com.epam.aidial.core.server.log.GfLogStoreTest"`

## 6. The forward sample plugin and the docs

- [ ] 6.1 Add the module `plugin-samples` to `settings.gradle`, with `ForwardOriginPluginProvider` (type `forward`,
      design D7), its `META-INF/services` entry, and unit tests of `validate` and the path mapping. Verify
      `./gradlew :plugin-samples:build` passes and the jar holds only the provider's classes and the services file
- [ ] 6.2 Add `ARG SAMPLE_PLUGINS=false` to the `Dockerfile` builder stage, copying the sample jar into `/build/plugins`
      when it is `true` (design D7). Verify by reading the `RUN` line against the builder stage's paths that it copies
      `plugin-samples/build/libs/*.jar` and does nothing by default (deferred: a Docker build needs the GitHub Packages
      credentials)
- [ ] 6.3 Document the call in `docs/dynamic-settings/plugins.md`:
      - what a call is, and which operations it reaches;
      - that the child pass is checked, charged and logged with no bodies in its row, and ends with the plugin's pass;
      - the caller and `bodyTree` the plugin gets;
      - the `forward` sample and the build argument.

      In `docs/tracing.md`, state that a child pass has no server span and its origin calls keep their client spans.
      Add `plugin-samples` to the module table in `CLAUDE.md`. Verify the docs name no class beyond `plugin-api`
- [ ] 6.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :plugin-samples:test`

## 7. Measurement

- [ ] 7.1 Run a same-day A/B of the routing-application perf scenario. Compare an application served by the HTTP router
      against one embedded with the `forward` plugin, on an image built with `SAMPLE_PLUGINS=true`. Record Core CPU per
      user request for both in review.md, with the HTTP router's `/v1/user/info` call reported apart, against the
      estimate of design D9 (deferred: needs the perf env)

## Workflow follow-up

- Write review.md, then `npx -y @fission-ai/openspec@1.14.3 archive embedded-call -y` and `validate --specs --strict`.
