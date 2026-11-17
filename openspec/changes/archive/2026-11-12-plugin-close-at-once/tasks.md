# Tasks

Run Gradle with a JDK 21 as `JAVA_HOME`.

## 1. Plugin manager

- [x] 1.1 `PluginManager`: one map of `(declaration, plugin)`, the create inside `compute`, a replaced or removed
  instance closed at once; `Lease`, `acquire` and `Instance` removed (design D1, D2, D3)
- [x] 1.2 `ProxyClient`: the attempt takes the plugin with `resolve`
- [x] 1.3 `EmbeddedProxyRequest`: no lease; `send` split into short methods with one name for the host delegate;
  `VertxHostDelegate.whenClosed` removed

## 2. Docs

- [x] 2.1 `Plugin.close` and `OriginPlugin` Javadoc, `docs/dynamic-settings/plugins.md`

## 3. Checks

- [x] 3.1 `PluginManagerTest`: `testReplacedPluginClosedAtOnceOffTheEventLoop`, `testRacingRequestsShareOnePlugin`,
  `testCloseClosesEveryPluginOnce`; the lease tests removed
- [x] 3.2 `EmbeddedOriginApiTest.testBusyInstanceClosedAtOnce` (scenario "Busy instance closed at once")
- [x] 3.3 `./gradlew checkstyleMain checkstyleTest` and `:server:test` for the plugin, embedded proxy and plugin API
  classes
