# Tasks

## 1. The contract additions

- [x] 1.1 Add `OriginHost`, `OriginCall` and `OriginCaller` to `plugin-api`, change `OriginPlugin.handle` to take the
      host, and add `bodyTree` and `caller` to `OriginRequest` (design D1). The Javadoc says:
      - that `call` takes the body tree it is given;
      - which operations `uri` may name, and that `background: true` is refused;
      - which headers Core drops from a call;
      - that `bodyTree` is read-only unless it is handed to `call`.

      Verify `./gradlew :plugin-api:build` passes
- [x] 1.2 Have `OriginClient.embedded` pass the pass's `ProxyContext` to `EmbeddedProxyRequest` (design D2), and fill
      `caller` from it (design D6), with `bodyTree` null and an `OriginHost` that fails every call until groups 2
      and 4. Update `TestOriginPluginProvider`, `EmbeddedProxyRequestTest` and its plugin lambdas to the new signature,
      and make the test plugin record the caller. Verify:
      - `EmbeddedOriginApiTest` and `EmbeddedProxyRequestTest` pass;
      - deployment-origin "Caller with an API key" and "Caller with a JWT" pass in a new `EmbeddedCallApiTest`.
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.EmbeddedOriginApiTest"`
      - `--tests "com.epam.aidial.core.server.EmbeddedCallApiTest"`
      - `--tests "com.epam.aidial.core.server.upstream.EmbeddedProxyRequestTest"`

## 2. The shared body tree

- [x] 2.1 Write a `ProxyContextTest` case first: `getCallTree()` returns the call's tree until a body is set, and null
      after that and without a call tree. Then add the call tree to `ProxyContext` (design D3). Verify the test passes
- [x] 2.2 Use the call tree at the six parse sites in place of `ProxyUtil.parseObject` (design D3):
      - `DeploymentPostController.handleRequestBody`;
      - `ChatCompletionsController.parseBody`;
      - `ResponsesController.parseBody`;
      - `MessagesBaseController.parseBody`;
      - `ChatCompletionInterceptorController.parseRequest` and `ResponsesInterceptorController.parseRequest`.

      Have the four controller sites set only the body timestamp when they use it, and
      `BaseInterceptorController.handle` read the request's body only when neither a body nor a call tree is set.
      Route the four serialise points through one `BaseDeploymentPostController` helper that keeps the tree for an
      embedded interface, and have `EmbeddedProxyRequest` hand that tree to the plugin as `bodyTree`, parsing `body`
      when none is kept. Add to `EmbeddedOriginApiTest` that the plugin's `bodyTree` equals its `body` on each
      interface and on a retried attempt. Verify by 2.3
- [x] 2.3 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - API tests (`com.epam.aidial.core.server.`): `DeploymentPostApiTest`, `ChatCompletionApiTest`,
        `EmbeddingsApiTest`, `ResponsesApiTest`, `MessagesApiTest`, `ConfigInterceptorTest`, `EmbeddedOriginApiTest`;
      - controller tests (`com.epam.aidial.core.server.controller.`): `DeploymentPostControllerTest`,
        `ChatCompletionsControllerTest`, `ResponsesControllerTest`, `ChatCompletionInterceptorControllerTest`,
        `ResponsesInterceptorControllerTest`;
      - `com.epam.aidial.core.server.ProxyContextTest`.

## 3. The in-memory request and response

- [x] 3.1 Write `ChildServerResponseTest` and `ChildServerRequestTest` first.
      - Response:
        - the head freezes on the first `write` or `end`;
        - nothing written before the plugin sets a handler is dropped;
        - chunks reach the plugin with the parent's context current;
        - `writeQueueFull` follows the plugin's reads, and `drainHandler` fires on the child's context;
        - a write from a virtual thread runs on the child's context;
        - `reset` before the head fails the call, and after the head fails the body;
        - after `cancel`, a piped source's next write fails and the pipe fails.
      - Request: the path, the query, the method `POST`, and the headers Core drops.

      Then implement both (design D4), with the answer's body an `InboundBuffer`. Verify both test classes pass
- [x] 3.2 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.upstream.ChildServerResponseTest"`
      - `--tests "com.epam.aidial.core.server.upstream.ChildServerRequestTest"`

## 4. The child pass

- [x] 4.1 Extract `Proxy.dispatch(ProxyContext)` from `processAuthorizationResult`, with no behaviour change. Verify
      `DeploymentPostApiTest` and `ResourceApiTest` pass
- [x] 4.2 Write `ChildPassTest` first: a path outside the five create routes fails the call; a background Responses
      call fails; a call after close answers `401`; a head after close is cancelled. Then implement `ChildPass`
      (design D2):
      - the route check against the `RouteTemplate` patterns;
      - the child context on a duplicated Vert.x context carrying the parent's `ACTIVE_CONTEXT`, the parent's key
        record and a minted span id;
      - dispatch failures answered through `Proxy.handleError`;
      - one `ChildPass` per attempt, registered on the pass's `ProxyContext`;
      - closing, on the parent's context, from `finalizeRequest` (every registered one), `EmbeddedProxyRequest.reset`
        and `EmbeddedProxyResponse.discard`.

      Skip the span writes of `GenAiTraceAttributes` for a child pass. Give `TestOriginPluginProvider` its call mode,
      the relay that counts a cancel, and the kept host and context (design D8). Verify `ChildPassTest` passes
- [x] 4.3 Add to `EmbeddedCallApiTest`, and verify they pass:
      - deployment-origin "Calling a deployment from an origin plugin", all eight scenarios;
      - "Calls end with the plugin's pass";
      - "Cancelling a call", both scenarios.
- [x] 4.4 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.EmbeddedCallApiTest"`
      - `--tests "com.epam.aidial.core.server.ChildPassTest"`
      - `--tests "com.epam.aidial.core.server.EmbeddedOriginApiTest"`
      - `--tests "com.epam.aidial.core.server.DeploymentPostApiTest"`
      - `--tests "com.epam.aidial.core.server.tracing.GenAiTraceAttributesTest"`

## 5. Accounting and the row

- [x] 5.1 Return `withoutBodies()` from `AnalyticsLogContext.from` for a child pass, and skip the assembled answer for
      one in `BaseChatCompletionController.completeProxyResponse` (design D5). Verify by 5.2
- [x] 5.2 Add to `EmbeddedCallApiTest`, and verify they pass:
      - app-to-model-accounting "Target limits charged", "Usage rolled up to the plugin's pass" and "Child row";
      - analytics-log "Bodies logged once".
- [x] 5.3 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.EmbeddedCallApiTest"`
      - `--tests "com.epam.aidial.core.server.log.GfLogStoreTest"`

## 6. The forward sample plugin and the docs

- [x] 6.1 Add the module `plugin-samples` to `settings.gradle`, with `ForwardOriginPluginProvider` (type `forward`,
      design D7), its `META-INF/services` entry, and unit tests of `validate` and the path mapping. Verify
      `./gradlew :plugin-samples:build` passes and the jar holds only the provider's classes and the services file
- [x] 6.2 Add `ARG SAMPLE_PLUGINS=false` to the `Dockerfile` builder stage, copying the sample jar into `/build/plugins`
      when it is `true` (design D7). Verify by reading the `RUN` line against the builder stage's paths that it copies
      `plugin-samples/build/libs/*.jar` and does nothing by default (deferred: a Docker build needs the GitHub Packages
      credentials)
- [x] 6.3 Document the call in `docs/dynamic-settings/plugins.md`:
      - what a call is, and which operations it reaches;
      - that the child pass is checked, charged and logged with no bodies in its row, and ends with the plugin's pass;
      - the caller and `bodyTree` the plugin gets;
      - the `forward` sample and the build argument.

      In `docs/tracing.md`, state that a child pass has no server span and its origin calls keep their client spans.
      Add `plugin-samples` to the module table in `CLAUDE.md`. Verify the docs name no class beyond `plugin-api`
- [x] 6.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :plugin-samples:test`

## 7. Measurement

- [x] 7.1 Run a same-day A/B of the routing-application perf scenario. Compare an application served by the HTTP router
      against one embedded with the `forward` plugin, on an image built with `SAMPLE_PLUGINS=true`. Record Core CPU per
      user request for both in review.md, with the HTTP router's `/v1/user/info` call reported apart, against the
      estimate of design D9

## 8. Cancellation before the answer and Core's Vert.x

- [x] 8.1 Change `OriginPluginProvider.create` to take Core's `Vertx`, passed by `EmbeddedOrigins` from `AiDial`
      (design D11), and update `TestOriginPluginProvider` and `ForwardOriginPluginProvider`. Verify an
      `EmbeddedOriginsTest` case that the plugin is created with Core's `Vertx`, and `./gradlew :plugin-samples:build`
- [x] 8.2 Write `ChildPassTest` cases first: `cancel` completes `cancelled()` and a later call answers `401`; `close`
      leaves `cancelled()` pending. Then add `OriginHost.cancelled()`, `ChildPass.cancel`, cancel from
      `EmbeddedProxyRequest.reset` and `EmbeddedProxyResponse.discard`, and the client-close watch in
      `EmbeddedProxyRequest.send` that ends the attempt quietly when a cancelled plugin fails (design D10). Give
      `TestOriginPluginProvider` `X-Test-Await-Cancel`. Verify deployment-origin "Client gone before the plugin
      answers", both scenarios, in `EmbeddedCallApiTest`
- [x] 8.3 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.EmbeddedCallApiTest"`
      - `--tests "com.epam.aidial.core.server.ChildPassTest"`
      - `--tests "com.epam.aidial.core.server.EmbeddedOriginApiTest"`
      - `--tests "com.epam.aidial.core.server.upstream.EmbeddedProxyRequestTest"`
      - `--tests "com.epam.aidial.core.server.config.EmbeddedOriginsTest"`

## Workflow follow-up

- Write review.md, then `npx -y @fission-ai/openspec@1.14.3 archive embedded-call -y` and `validate --specs --strict`.
