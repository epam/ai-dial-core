# Review: plugin-close-at-once

## What was built

- `PluginManager` maps each entry to its declaration and plugin; the plugin of a new or changed entry is created
  inside `ConcurrentHashMap.compute`, and the instance it replaces, or that of a removed entry, is closed at once off
  the event loop (design D1, D2). Leases, use counts and `VertxHostDelegate.whenClosed` are gone.
- `EmbeddedProxyRequest.send` is split into `watchClient`, `onAnswer`, `toProxyResponse` and `fail`, with one name,
  `host`, for the attempt's host delegate.
- The Javadoc of `Plugin.close` and `OriginPlugin`, and `docs/dynamic-settings/plugins.md`, say that Core does not
  wait for the requests in flight, and that a plugin that wants them to end waits for them in `close()`.

## Tests

- Checkstyle passes.
- `PluginManagerTest`, `PluginRegistryTest`, the `proxy.embedded` tests, `RouterDecisionClientTest`,
  `EmbeddedOriginApiTest`, `EmbeddedCallApiTest`, `EmbeddedDecisionApiTest` and `ConfigPluginApiTest` pass:
  193 tests.
- Mutation check: with `compute` replaced by a get and a put, `testRacingRequestsShareOnePlugin` fails.

## Not done

- Creating plugins when the config loads, off the event loop (#2268).
