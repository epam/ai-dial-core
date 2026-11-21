# Tasks

Run Gradle with a JDK 21 as `JAVA_HOME`.

## 1. One check

- [x] 1.1 `ApplicationService.pluginOutsidePlatformError(bucket, application)`; `requireValidInterfaces` throws it as
  `422` (design D1)
- [x] 1.2 `ConfigValidationService.validateOnly`, arm `AdminApplicationManifest`: return it as `FAILED` after the
  interface warnings

## 2. Checks

- [x] 2.1 `EmbeddedOriginApiTest.testCustomApplicationRefusedByValidate` (scenario "Custom application refused by
  validate")
- [x] 2.2 `./gradlew checkstyleMain checkstyleTest` and `:server:test` for `EmbeddedOriginApiTest`,
  `AdminValidateApiTest`
