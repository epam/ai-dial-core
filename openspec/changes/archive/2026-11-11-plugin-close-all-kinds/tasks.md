# Tasks

Run Gradle with a JDK 21 as `JAVA_HOME`.

## 1. Close contract for every plugin kind

- [x] 1.1 Add `plugin-api` `Plugin` with `default void close()` and the kind-neutral contract (design D1)
- [x] 1.2 Make `OriginPlugin` extend `Plugin`; remove its `close()`; move the origin-only sentence to its class
  Javadoc
- [x] 1.3 Update `docs/dynamic-settings/plugins.md`, "Settings changes": `close()` is declared on `Plugin`, which every
  kind extends
- [x] 1.4 `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "*EmbeddedOriginsTest" --tests "*EmbeddedOriginApiTest"` (the scenarios of
  "Closing a plugin" are tested there, unchanged)
