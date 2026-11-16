# Tasks

Run Gradle with a JDK 21 as `JAVA_HOME`. Integration tests bind the embedded Redis port 16370: run one test task at
a time across checkouts.

## 1. Depth limit for in-process calls

- [x] 1.1 Add `ChildPassTest.testCallAtTheDepthLimitAnswers400`: a parent key whose path holds 32 names, the call
  answers `400` and nothing is dispatched
- [x] 1.2 Add the `X-Test-Call-Again` header to `TestOriginPluginProvider` (the call keeps the `X-Test-Call*`
  headers), and `EmbeddedCallApiTest.testPluginCallingItsOwnDeployment` for the scenario "Plugin calling its own
  deployment": `400`, the message, 32 plugin requests
- [x] 1.3 Add `ChildPass.MAX_CALL_DEPTH` and the check in `ChildPass.call` (design D1, D2)
- [x] 1.4 Update `docs/dynamic-settings/plugins.md`: the limit in place of "there is no depth limit"
- [x] 1.5 `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "*ChildPassTest" --tests "*EmbeddedCallApiTest"`
