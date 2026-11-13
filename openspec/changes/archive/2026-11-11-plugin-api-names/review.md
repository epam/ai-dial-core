# Review: plugin-api-names

## What was built

- **Names** (design D1). `HostDelegate`, `Request`, `Response`, `Call`, `Caller` replace the `Origin`-prefixed types
  in `plugin-api`, `plugin-samples`, `server`, `docs/dynamic-settings/plugins.md` and `CLAUDE.md`. `OriginPlugin`
  keeps its name. Two test files that inherit `ResourceBaseTest.Response` write the plugin `Response` in full; the
  test provider's nested `Call` record is now `DecideCall`.
- **One provider interface** (D2). `PluginProvider.create` returns a `Plugin`; `OriginPluginProvider` is deleted. The
  forward sample and the test origin provider narrow the return type to `OriginPlugin`. `PluginManager` checks the
  instance's kind, and closes an instance of another kind before it fails the request.
- **Javadoc.** `PluginProvider.type()` tells a type from an entry's name; `Request.bodyTree` says it is the same JSON
  as `body`, and why both are given.

## Tests

- Checkstyle passes; `plugin-samples` builds and its tests pass against the new API.
- Server: `PluginManagerTest` (13), `EmbeddedOriginApiTest` (43), and `PluginRegistryTest`,
  `ConfigPostProcessorTest`, `ConfigPluginApiTest`, `EmbeddedCallApiTest`, `EmbeddedDecisionApiTest`,
  `EmbeddedProxyRequestTest`, `EmbeddedServerResponseTest`, `VertxHostDelegateTest`, `RouterDecisionClientTest` pass.
- Scenario "Plugin of another kind": `EmbeddedOriginApiTest.testPluginThatServesNoRequests` (502, instance closed)
  and `PluginManagerTest.testPluginThatServesNoRequestsIsClosed`.

## Not done

- The provider adapter plugin (`ai-dial-model-provider-java`) needs the same renames in its own repository.
