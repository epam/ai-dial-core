# Tasks

Run Gradle with a JDK 21 as `JAVA_HOME`.

## 1. One plugin interface

- [x] 1.1 `Plugin` declares `handle` and `close()`; `OriginPlugin` removed; Javadoc links in `HostDelegate`,
  `Response`, `Call`, `Caller`, `PluginProvider` updated (design D1)
- [x] 1.2 `PluginManager`: no kind check; `resolve` returns `Plugin`; `EmbeddedProxyRequest` takes a `Supplier<Plugin>`
- [x] 1.3 `ForwardOriginPluginProvider`, `TestOriginPluginProvider`, `TestEchoPluginProvider`, `JarEchoPluginProvider`
  return `Plugin`

## 2. Review fixes

- [x] 2.1 `PluginManager.resolve` re-checks shutdown after `compute` (design D2)
- [x] 2.2 `ProxyClient.embeddedRequest` builds both embedded requests

## 3. Docs

- [x] 3.1 `docs/dynamic-settings/plugins.md`, the `plugin-api` row of `CLAUDE.md`

## 4. Checks

- [x] 4.1 `PluginManagerTest.testPluginCreatedDuringShutdownIsClosed` (scenario "Created while Core stops"); the
  "serves no requests" tests removed
- [x] 4.2 `./gradlew checkstyleMain checkstyleTest`, `:plugin-samples:test`, and `:server:test` for the plugin,
  embedded proxy, router decision, config and plugin API classes
