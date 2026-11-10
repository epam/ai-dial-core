# Tasks

Run Gradle with a JDK 21 as `JAVA_HOME`. Integration tests bind the embedded Redis port 16370: run one test task at
a time across checkouts.

## 1. A plugin is a field of a pass-through entry

- [x] 1.1 Rewrite the tests first, replacing `{"mode": "embedded", "plugin": ...}` with `{"plugin": ...}` and the
  three reworded messages (design D3), in `ConfigPostProcessorTest`, `DeploymentEndpointUtilTest`,
  `EmbeddedOriginApiTest`, `EmbeddedCallApiTest`, `EmbeddedDecisionApiTest`, `RouterDecisionClientTest`,
  `OriginClientTest` and `server/src/test/resources/dial-config/router-decision-embedded.json`; verify they fail to
  compile or fail only for the mode
- [x] 1.2 Add tests, verify each fails before 1.4: in `EmbeddedOriginApiTest` "Explicit pass-through mode with a
  plugin", "Embedded is not a mode" (`400`), "Embedded mode without a plugin" (`400`), "Model served by a plugin needs
  no url", "Blank plugin name"; in `ConfigPostProcessorTest` "Plugin on a translated interface" and that "Entry with
  no url anywhere" still rejects
- [x] 1.3 Add to `EmbeddedOriginApiTest` (it has the plugin helpers; `AliasApiTest` runs on a file config) a test
  that an alias of a model with `interfaces.openaiChatCompletions: {"plugin": "p1"}` is listed with
  `interface_configs.openaiChatCompletions` and is served by the plugin; verify it fails before 1.5
- [x] 1.4 Remove `InterfaceMode.EMBEDDED`; change `DeploymentEndpointUtil.resolvePlugin`, `resolveBaseUrl`,
  `isInterfaceDeclared` and `findOverridePath`, `ConfigPostProcessor.validateDeploymentInterfaces`,
  `validatePluginReference` and `validateOverridePaths`, `ApplicationService.hasEmbeddedInterface` and its message,
  `OriginClient.embedded`'s message and `BaseDeploymentPostController.setProcessedRequestBody` as in design D1 to
  D3; verify `grep -rn "EMBEDDED" --include=*.java config/src server/src` finds nothing
- [x] 1.5 Copy `plugin` in `AliasPasses.copyOperational` (design D6); verify the 1.3 test passes
- [x] 1.6 Reword the Javadoc that says "`embedded` mode" in `DeploymentInterface`, `InterfaceMode`,
  `DeploymentEndpointUtil` (`resolveLegacyEndpoint` included), `BaseDeploymentPostController.createProxyRequest` and
  `plugin-api` `OriginPluginProvider`; verify
  `grep -rn "embedded.. mode\|mode.*embedded" --include=*.java config/src server/src/main plugin-api/src` finds nothing
- [x] 1.7 Update `docs/dynamic-settings/plugins.md`, `models.md` (mode values, base URL resolution order, `plugin`
  field, example, override paths), `applications.md`, `routers.md` and `interceptors.md` to the new shape, keeping
  the preview notes; verify `grep -rn '"embedded"\|mode: embedded' docs README.md` finds nothing outside
  `docs/open_api_core.yaml`
- [x] 1.8 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "*ConfigPostProcessorTest" --tests "*DeploymentEndpointUtilTest"
  --tests "*EmbeddedOriginApiTest" --tests "*EmbeddedCallApiTest" --tests "*EmbeddedDecisionApiTest"
  --tests "*RouterDecisionClientTest" --tests "*OriginClientTest" --tests "*AliasApiTest"`; verify both pass

## 2. A translator served by a plugin

- [x] 2.1 Add to `TranslatorTest` (config module): a translator with `plugin` and no `baseUrl` reads and writes
  back, neither is rejected, both are rejected (spec "Translator definition"); add to `DeploymentEndpointUtilTest`
  `resolvePlugin` for an inline translator plugin, a named one, an unresolved name (null) and a translator with a
  `baseUrl` (null); verify they fail before 2.4
- [x] 2.2 Add to `EmbeddedCallApiTest`, verify each fails before 2.4: "Messages request handed to the translator
  plugin", "Translator plugin calls the output interface", "Named translator with a plugin", "Translator's plugin
  missing" (`502`), "Translator plugin's call charged once", "Translator plugin's call refused over the limit",
  "Item operation reaches a translator's plugin", "Interceptor naming a translator with a plugin". The
  test plugin converts by calling `/openai/deployments/<X-DIAL-DEPLOYMENT-ID>/chat/completions` through its host
- [x] 2.3 Add "Translator with a plugin read and written back" (`PUT` and `GET /v1/translators/platform/a2c`) to
  the named-translator test of `EmbeddedCallApiTest`, "Custom application with an inline translator plugin refused on
  write" and "Custom application naming a translator with a plugin" to `EmbeddedOriginApiTest` (next to the other
  platform-rule tests), and "Interceptor with an inline translator plugin" to `ConfigPostProcessorTest`; verify they
  fail before 2.4
- [x] 2.4 Add `plugin` to `Translator` with the exactly-one rule, resolve a translator's plugin in
  `DeploymentEndpointUtil.resolvePlugin` and `resolveBaseUrl` and pass the translators from its four callers, and
  extend `ApplicationService.hasEmbeddedInterface` and the interceptor rule of `validatePluginReference` to an inline
  translator's plugin, and fail an interceptor hop whose resolved url starts with `PLUGIN_URL_PREFIX` as a connect
  failure in `BaseInterceptorController` (design D1, D3, D4, D5); verify the tests of 2.1 to 2.3 pass
- [x] 2.5 Update `docs/dynamic-settings/translators.md` (the `plugin` field, an example, the rules, a note in
  "Limits and a translated request" that the plugin's call is the charged request and that a plugin answering by
  itself is charged nothing) and `plugins.md` (a "Translating for a deployment" section: the plugin must call `out`,
  never its own interface, and one translating `openaiResponses` answers item operations and background jobs
  itself); verify both examples parse as JSON
- [x] 2.6 Run `./gradlew checkstyleMain checkstyleTest`,
  `./gradlew :config:test --tests "*TranslatorTest"` and
  `./gradlew :server:test --tests "*EmbeddedCallApiTest" --tests "*ConfigTranslatorTest"
  --tests "*ConfigPostProcessorTest" --tests "*DeploymentEndpointUtilTest" --tests "*MergedConfigStore*"
  --tests "*RouterDecisionClientTest" --tests "*EmbeddedDecisionApiTest" --tests "*EmbeddedOriginApiTest"`; verify
  all pass

## 3. Integration checks

- [ ] 3.1 Run `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`; verify it passes
- [x] 3.2 Run `./gradlew :openapi-generator:test`; verify it passes with `InterfaceMode` reduced to two values and
  `plugin` in the translator schema (CI regenerates `docs/open_api_core.yaml`)
- [ ] 3.3 Update the perf-environment overlay configs that say `mode: embedded` (deferred: needs the perf
  environment and its config repository)
- [ ] 3.4 A/B a translator served by a plugin against the same translator over HTTP: Core CPU per request and
  Redis commands per request (deferred: needs a real translator plugin and the perf environment)

## Workflow follow-up

- Write `review.md` in the change folder, then archive the change and run `validate --specs --strict`.
- One commit on `feat/plugin-system`, staging explicit paths; the earlier uncommitted preview-note edits in `docs/`
  and `ConfigResourceMetadataController.java` belong to a separate commit.
