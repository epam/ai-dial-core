# Tasks

## 1. Layout

- [x] 1.1 Tests in `AutoEnrichedOtelJsonLayoutTest`: "A plugin's record with its fields" (`shouldWriteKeyValuePairsAsAttributes`), "Core's attribute is kept" (`shouldKeepCoreAttributeOverKeyValuePair`), "Not on the span" (`shouldNotCopyKeyValuePairsToSpan`), and a value Jackson cannot serialize (`shouldWriteUnknownValueAsText`)
- [x] 1.2 `AutoEnrichedOtelJsonLayout.enrichKeyValuePairs`, after the tracing attributes
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "com.epam.aidial.core.server.log.layout.AutoEnrichedOtelJsonLayoutTest"`

## 2. Docs

- [x] 2.1 `docs/dynamic-settings/plugins.md`, origin plugins: a plugin's log records keep their key-value pairs
- [x] 2.2 `npx -y @fission-ai/openspec@1.14.3 validate plugin-log-fields --strict`
