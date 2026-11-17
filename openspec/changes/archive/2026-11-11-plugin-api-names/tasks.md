# Tasks

Run Gradle with a JDK 21 as `JAVA_HOME`.

## 1. Names

- [x] 1.1 Rename `OriginHost`, `OriginRequest`, `OriginResponse`, `OriginCall`, `OriginCaller` to `HostDelegate`,
  `Request`, `Response`, `Call`, `Caller` in `plugin-api`, `plugin-samples` and `server` (design D1)
- [x] 1.2 Rename the test provider's nested `Call` record to `DecideCall`; write the plugin `Response` with its full
  name in the API tests that inherit `ResourceBaseTest.Response`

## 2. One provider interface

- [x] 2.1 Move `create` to `PluginProvider` returning `Plugin`; delete `OriginPluginProvider`; the forward sample and
  `TestOriginPluginProvider` return `OriginPlugin` (design D2)
- [x] 2.2 `PluginManager`: create through `PluginProvider`, close an instance that is not an `OriginPlugin` and fail
  with "serves no requests"
- [x] 2.3 Test providers without an origin plugin (`TestEchoPluginProvider`, `JarEchoPluginProvider`, the one in
  `ConfigPostProcessorTest`) implement `create`; `TestEchoPluginProvider` counts its closes
- [x] 2.4 `PluginManagerTest` and `EmbeddedOriginApiTest` check that the instance of another kind is closed (scenario
  "Plugin of another kind")

## 3. Javadoc and docs

- [x] 3.1 `PluginProvider.type()`: a type names the code, an entry's name one configured instance of it
- [x] 3.2 `Request.bodyTree`: the same JSON as `body`, and why both are given
- [x] 3.3 `docs/dynamic-settings/plugins.md` and `CLAUDE.md` use the new names

## 4. Checks

- [x] 4.1 `./gradlew checkstyleMain checkstyleTest :plugin-samples:test` and the server classes `PluginManagerTest`,
  `PluginRegistryTest`, `ConfigPostProcessorTest`, `ConfigPluginApiTest`, `EmbeddedOriginApiTest`,
  `EmbeddedCallApiTest`, `EmbeddedDecisionApiTest`, `EmbeddedProxyRequestTest`, `EmbeddedServerResponseTest`,
  `VertxHostDelegateTest`, `RouterDecisionClientTest`
