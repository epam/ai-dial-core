# Tasks

## 1. The origin seam, HTTP only (no behaviour change)

- [x] 1.1 Add `ProxyRequest` and `ProxyResponse` in `upstream/`, with `HttpProxyRequest` and `HttpProxyResponse`
      delegating to the Vert.x objects, and `HttpProxyResponse.discard()` doing nothing (design D1).
      - Move `ProxyContext.proxyRequest` and `proxyResponse` and every consumer listed in design Context to them,
        keeping each call's name. This includes `ChatCompletionInterceptorController.enrichProxyRequestHeaders`.
      - The log lines read `remoteAddress()`, and `McpProxyController` reads `absoluteURI()`.
      - `McpProxyController`, `DeploymentFeatureController` and `route/RouteRequestBodyHandler` wrap the Vert.x
        objects they create.

      Verify `./gradlew :server:compileJava :server:compileTestJava` passes
- [x] 1.2 Add `upstream/OriginClient` with `http(RequestOptions)` only. Route
      `BaseDeploymentPostController.createProxyRequest(String, @Nullable String)` and `ResponsesApiClient.send` through
      it, each still building its `RequestOptions` as today, with `createProxyRequest(String)` delegating with no
      plugin (design D2). Verify `UpstreamRouteTest` and `ProxyUtilTest` pass with their types updated, and that none
      of the consumers listed in design Context imports `HttpClientRequest` or `HttpClientResponse` any more
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest`, then run these classes with
      `./gradlew :server:test --tests "<class>"` and verify they pass with no test edit beyond types:
      - API tests (`com.epam.aidial.core.server.`): `DeploymentPostApiTest`, `ChatCompletionApiTest`,
        `EmbeddingsApiTest`, `ResponsesApiTest`, `MessagesApiTest`, `ConfigInterceptorTest`, `ConfigTranslatorTest`,
        `OverridePathsApiTest`, `FeaturesApiTest`, `RouteApiTest`, `ApplicationRouteApiTest`;
      - controller tests (`com.epam.aidial.core.server.controller.`): `DeploymentPostControllerTest`,
        `ResponsesControllerTest`, `ResponseItemControllerTest`, `ChatCompletionInterceptorControllerTest`,
        `ToolSetMcpProxyControllerTest`;
      - `com.epam.aidial.core.server.tracing.GenAiTraceAttributesTest` and `com.epam.aidial.core.server.upstream.*`.

## 2. The embedded mode in config

- [x] 2.1 Add `InterfaceMode.EMBEDDED("embedded", true)` and `DeploymentInterface.plugin` (design D7, D4). Verify
      `ConfigPostProcessorTest` reads a model whose `interfaces.openaiChatCompletions` is
      `{"mode": "embedded", "plugin": "p1"}` back with that mode and plugin
- [x] 2.2 Extend `DeploymentEndpointUtilTest` first, then `DeploymentEndpointUtil` (design D3):
      - an embedded entry resolves to `plugin://p1`, and the request uri to `plugin://p1/openai/deployments/<name>/chat/completions?<query>`;
      - an embedded entry without a plugin resolves to null;
      - no legacy fallback and no override path for an embedded entry;
      - `isInterfaceDeclared` is true with a plugin;
      - `resolvePlugin` returns the plugin in embedded mode only.

      Verify the test class passes
- [x] 2.3 Write the rule tests first in `ConfigPostProcessorTest`:
      - deployment-interfaces-config "Plugin decided by mode", each message for a model, an application and an
        interceptor, and the file-config scenario "Both plugin and base url";
      - "No embedded interceptors";
      - an unresolved plugin name accepted.

      Then implement design D8: the `EMBEDDED` branch in `validateDeploymentInterfaces`, `validateOverridePaths`
      renamed `validateInterfaces` with the plugin rules and the interceptor rule at all its call sites, and
      `overridePaths` under an embedded entry rejected. Verify the test class passes
- [x] 2.4 Add the write scenarios to a new `EmbeddedOriginApiTest` and verify they pass:
      - deployment-interfaces-config "Embedded mode without a plugin", "Plugin not registered yet", "Embedded
        interceptor", "Override paths under an embedded entry" and "Application with a plugin but no embedded mode";
      - "Embedded interface advertised", through `GET /v1/deployments`
- [x] 2.5 Document `mode: embedded` and `plugin` in `docs/dynamic-settings/models.md` and `applications.md`. Say
      that an embedded interface is served by the plugin only and is charged like pass-through, and add an example.
      State in `interceptors.md` that embedded mode is rejected. Verify each example parses as JSON and loads in
      `ConfigPostProcessorTest`
- [x] 2.6 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.config.ConfigPostProcessorTest"`
      - `--tests "com.epam.aidial.core.server.util.DeploymentEndpointUtilTest"`
      - `--tests "com.epam.aidial.core.server.EmbeddedOriginApiTest"`
      - `--tests "com.epam.aidial.core.server.OverridePathsWriteApiTest"`
      - `--tests "com.epam.aidial.core.server.ApplicationInterfacesApiTest"`

## 3. The origin plugin contract and the embedded dispatch

- [x] 3.1 Add `OriginPluginProvider`, `OriginPlugin`, `OriginRequest` and `OriginResponse` to `plugin-api`, with
      `vertx-core` at `${vertx_version}` (design D4). The Javadoc carries:
      - the meaning of `uri`, `headers` and `cancel`;
      - that `body` and `settings` are read-only;
      - that `create` must be quick;
      - the two stream rules of design D6.

      Update the `plugin-api` row in `CLAUDE.md`. Verify `./gradlew :plugin-api:build` passes
- [x] 3.2 Add `TestOriginPluginProvider` (type `test-origin`, design D10) and register it in
      `server/src/test/resources/META-INF/services/com.epam.aidial.core.plugin.PluginProvider`. Verify
      `PluginRegistryTest.testMissingDirectoryLoadsOnlyTheClasspathProviders` finds both test providers
- [x] 3.3 Add the protected `AiDial.createLogStore(AnalyticsSettings)` and the recording override in
      `ResourceBaseTest` (design D10). Verify `ChatCompletionApiTest` passes and that a chat completion in it records
      one row
- [x] 3.4 Write `EmbeddedOriginsTest` first:
      - the same plugin instance for the same and for equal declarations, and a new one after a settings change;
      - a failure for a missing entry, a provider that is not loaded, a provider that only validates, and a
        throwing `create`.

      Then implement `config/EmbeddedOrigins` with `get` and `put`, no lock (design D5), and wire it in `AiDial` and
      `Proxy`. Verify the test class passes
- [x] 3.5 Implement `EmbeddedProxyRequest`, `EmbeddedProxyResponse` and `OriginClient.embedded` (design D6, D9):
      - `ctx.succeededFuture`;
      - every failure as a failed future;
      - the name check for applications.

      Branch on `resolvePlugin` in `createProxyRequest(InterfaceType)` and in `ResponsesApiClient.send`, called from
      `ResponseItemController.forwardToUpstream` (design D2), and call `discard()` in the three retriable branches.
      Verify by 3.7
- [x] 3.6 Refuse an `embedded` entry outside the `platform` bucket in `ApplicationService.prepareApplication`
      (design D9). Verify by 3.7, scenario "Custom application refused on write"
- [x] 3.7 Add the request scenarios to `EmbeddedOriginApiTest` and verify each passes:
      - deployment-origin "Embedded origin request", all six scenarios;
      - deployment-origin "Embedded origin answer": "Streamed answer from a plugin", asserting the recorded row's
        `response.upstream_uri`; "Model usage charged"; "Retriable status from a plugin";
      - deployment-origin "Embedded origin failure": "Missing plugin entry", "Provider that does not serve
        requests", "Plugin call fails";
      - deployment-origin "Client gone from an embedded origin": "Client disconnects mid-stream from an embedded
        application", "Retried answer cancelled";
      - deployment-interfaces-config "Embedded interface goes to the plugin", "Embedded interface never falls back",
        "Custom application refused on write", "Stored custom application not served by a plugin";
      - responses-api "Stored response read back from a plugin", "Cancel reaches the plugin", "Plugin gone before the
        item operation";
      - plugin-registry "Settings change takes effect", "Entry removed".
- [x] 3.8 Document origin plugins in `docs/dynamic-settings/plugins.md`, replacing the note that nothing uses an
      entry yet. Cover:
      - what an origin plugin receives;
      - the `502` and item-operation `500` cases;
      - the event-loop and stream rules;
      - that a settings change applies on the next request;
      - the platform-only rule;
      - the rollout order.

      Verify the doc names no class beyond the `plugin-api` contract
- [x] 3.9 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.EmbeddedOriginApiTest"`
      - `--tests "com.epam.aidial.core.server.config.EmbeddedOriginsTest"`
      - `--tests "com.epam.aidial.core.server.config.PluginRegistryTest"`
      - `--tests "com.epam.aidial.core.server.ConfigPluginApiTest*"`
      - `--tests "com.epam.aidial.core.server.CustomApplicationApiTest"`
      - `--tests "com.epam.aidial.core.server.ResponsesApiTest"`
      - `--tests "com.epam.aidial.core.server.MessagesApiTest"`
      - `--tests "com.epam.aidial.core.server.DeploymentPostApiTest"`

## 4. Measurement

- [x] 4.1 Run a same-day A/B of the perf scenario in which clients call a model directly, `development` against this
      change. Expect Core CPU per request within run-to-run noise (design D11) and record both numbers in review.md

## Workflow follow-up

- Write review.md in the change folder, then `npx -y @fission-ai/openspec@1.14.3 archive embedded-origin -y` and
  `validate --specs --strict`.
- Propose `embedded-call` next. It builds on the contract and dispatch of this change.
