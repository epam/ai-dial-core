# Review: plugin-one-interface

## What was built

- `Plugin` is the one plugin interface: `handle` and a default `close()`. `OriginPlugin` and Core's kind check are
  gone (design D1).
- `PluginManager.resolve` closes a plugin it created while Core was stopping and fails the request (design D2).
- `ProxyClient.embedded` and `embeddedForRouter` share `embeddedRequest`.

## Tests

- Checkstyle passes.
- `:plugin-samples:test` and the server classes `PluginManagerTest`, `PluginRegistryTest`, the `proxy.embedded`
  tests, `RouterDecisionClientTest`, `ConfigPostProcessorTest`, `EmbeddedOriginApiTest`, `EmbeddedCallApiTest`,
  `EmbeddedDecisionApiTest` and `ConfigPluginApiTest` pass. `ConfigPluginApiTest.testPluginMetadata` failed once in
  the combined run, in `ResourceBaseTest.init` while Core started, and passed when its class ran again alone.
- Mutation check: without the re-check, `testPluginCreatedDuringShutdownIsClosed` fails.

## Not done

- The provider adapter plugin moves to `Plugin` on its own branch.
