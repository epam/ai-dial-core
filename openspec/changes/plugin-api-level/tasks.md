## 1. The level and the guard

- [ ] 1.1 `aidial-core-plugin-api`: `PluginApi` with `public static final int LEVEL = 1` and its Javadoc (when to
      raise it); `PluginProvider.apiLevel()` with the Javadoc of design D2
- [ ] 1.2 `PluginApiLevelTest` in `aidial-core-plugin-api`: the public API as text equals
      `src/test/resources/api/level-<LEVEL>.txt`; the failure message tells to raise `LEVEL`, add the new file and
      decide on `OLDEST_API_LEVEL` (design D4). Add the test dependencies the module lacks
- [ ] 1.3 Generate and check in `level-1.txt`; confirm by hand that adding a method to `HostDelegate` fails the test,
      then revert
- [ ] 1.4 Implement `apiLevel()` as `return PluginApi.LEVEL;` in `ForwardOriginPluginProvider`,
      `JarEchoPluginProvider` and its nested providers, `TestEchoPluginProvider`, `TestOriginPluginProvider`
- [ ] 1.5 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :aidial-core-plugin-api:test --tests "com.epam.aidial.core.plugin.PluginApiLevelTest"`

## 2. The check at start

- [ ] 2.1 `PluginRegistryTest`: `testProviderOfCoreLevelIsLoaded` ("Level in range"),
      `testNewerApiLevelFailsTheLoad` ("Provider built against a newer API"),
      `testApiLevelNoLongerServedFailsTheLoad` ("Provider built against an API no longer served"),
      `testProviderWithoutApiLevelFailsTheLoad` ("Provider built before levels existed"), with the providers of
      design D5
- [ ] 2.2 `PluginRegistry`: `OLDEST_API_LEVEL = 1`, the range check and the `LinkageError` case with the messages of
      design D3; one start log line with the levels Core serves
- [ ] 2.3 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "com.epam.aidial.core.server.plugin.PluginRegistryTest" --tests "com.epam.aidial.core.server.ConfigPluginApiTest"`

## 3. Docs

- [ ] 3.1 `docs/dynamic-settings/plugins.md`: in "Building a plugin", `apiLevel()` and why it returns the constant;
      in "Plugin code", the refusal at start and what to do; replace "A plugin jar is built for one DIAL Core version"
      in the preview note with the level rule
- [ ] 3.2 `CLAUDE.md`: the `aidial-core-plugin-api` row (the level, and that an API change raises it)
- [ ] 3.3 `docs/CODE_STYLE.md` or the PR template, where API changes are described: a public API change raises
      `PluginApi.LEVEL`, a breaking one also `OLDEST_API_LEVEL`
- [ ] 3.4 `./gradlew checkstyleMain checkstyleTest` and
      `npx -y @fission-ai/openspec@1.14.3 validate plugin-api-level --strict`
