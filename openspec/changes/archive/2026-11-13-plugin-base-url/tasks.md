# Tasks

Run Gradle with a JDK 21 as `JAVA_HOME`. Integration tests bind the embedded Redis port 16370: run one test task at
a time across checkouts. A scenario whose text this change leaves as it is keeps its test; the tasks name the tests of
the scenarios it rewrites or adds.

No measurement task: design.md "Cost per request" claims no cost effect (one `startsWith` and one `indexOf` per
request where a field was read).

## 1. The plugin url is the reference

- [x] 1.1 Rewrite the tests first, `{"plugin": "<name>"}` on an interface to `{"base_url": "plugin://<name>"}` and a
  translator's `"plugin": "<name>"` to `"baseUrl": "plugin://<name>"`: the `embeddedChat` helper and every inline
  body of `EmbeddedOriginApiTest`, `EmbeddedCallApiTest`, `EmbeddedDecisionApiTest`, `RouterDecisionClientTest`,
  `ConfigPostProcessorTest`, `DeploymentEndpointUtilTest`, `TranslatorTest`, and
  `server/src/test/resources/dial-config/router-decision-embedded.json`. This covers the rewritten scenarios
  "Embedded interface goes to the plugin" (`EmbeddedOriginApiTest.testEmbeddedInterfaceGoesToThePlugin`), "Embedded
  interface never falls back" (`testEmbeddedInterfaceNeverFallsBack`), "Explicit pass-through mode with a plugin"
  (`testExplicitPassthroughModeWithPlugin`), "Embedded is not a mode" (`testEmbeddedIsNotMode`), "Embedded interface
  advertised" (`testEmbeddedInterfaceAdvertised`), "Override paths under an embedded entry"
  (`testOverridePathsUnderAnEmbeddedEntry`), "Embedded interceptor" (`testEmbeddedInterceptor`), "Interceptor with an
  inline translator plugin" (`ConfigPostProcessorTest.testInterceptorWithInlineTranslatorPluginRejected`), "Plugin url
  not registered yet" (`testPluginNotRegisteredYet`), "Application with a plugin but no embedded mode"
  (`testApplicationWithPluginButNoEmbeddedMode`, now a blank plugin url; `testRemovedPluginFieldRejected` pins the `400` for the removed field), the platform-only
  scenarios (`testCustomApplicationRefusedOnWrite`, `testCustomApplicationWithInlineTranslatorPluginRefusedOnWrite`,
  `testStoredCustomApplicationNamingTranslatorWithPlugin`, `testStoredCustomApplicationNotServedByPlugin`,
  `testCustomApplicationRefusedByValidate`), the "Embedded origin request" scenarios
  (`testChatCompletionHandedToThePlugin`, `testUpstreamHeadersForModel`, `testEmbeddingsHandedToThePlugin`,
  `testResponsesCreateHandedToThePlugin`, `testMessagesHandedToThePlugin`), "Missing plugin entry"
  (`testMissingPluginEntry`), "Origin plugins" (`testSettingsChangeTakesEffect`, `testEntryRemoved`), "Stored
  response read back from a plugin" and "Cancel reaches the plugin" (`testStoredResponseReadBackFromPlugin`,
  `testCancelReachesThePlugin`), "Translator with a plugin read and written back", "Named translator with a plugin"
  and "Messages request handed to the translator plugin" (`EmbeddedCallApiTest.testNamedTranslatorWithPlugin`,
  `testTranslatorPluginCallsTheOutputInterface`), "Translator's plugin missing" (`testTranslatorsPluginMissing`),
  "Translator plugin's call charged once" (`testTranslatorPluginsCallChargedOnce`), "Item operation reaches a
  translator's plugin" (`testItemOperationReachesTranslatorsPlugin`), "Interceptor naming a translator with a plugin"
  (`testInterceptorNamingTranslatorWithPlugin`), "Embedded interface next to an HTTP one", "Embedded interface on a
  decision router accepted" and "Plugin decides" (`EmbeddedDecisionApiTest.testEmbeddedInterfaceNextToHttpOne`,
  `testEmbeddedDecisionRouterListed`, `testPluginDecides`). Delete the tests of the removed rules:
  `ConfigPostProcessorTest.testPluginAndBaseUrlRejected`, `testFileConfigWithPluginAndBaseUrlRejected`,
  `testPluginOnTranslatedInterfaceRejected`, `testEmbeddedModelNeedsNoBaseUrl`, `TranslatorTest.blankPluginIsReadAsAbsent`
  and the "Model served by a plugin needs no url" comment; `DeploymentEndpointUtilTest.pluginOnTranslatedEntryIsIgnored`
  and `EmbeddedOriginApiTest.testEmbeddedModeWithoutPlugin` stay, ported (a translated entry ignores a plugin url;
  `embedded` is still no mode).
  Verify `grep -rn '"plugin"' server/src/test config/src/test` finds only the removed-field test of 1.2
- [x] 1.2 Add tests, verify each fails before 1.3: `TranslatorTest.translatorRequiresBaseUrl` (no `baseUrl` rejected;
  a `plugin` field rejected on read, scenario "Both baseUrl and plugin rejected on read"; `baseUrl: plugin://p1`
  read and written back); `DeploymentEndpointUtilTest` `resolvePlugin` from an entry's `base_url`, from the
  deployment-level `baseUrl`, from a translator's `baseUrl`, `plugin://p1/v2` and `plugin://p_1` (names `p1`, `p_1`),
  an HTTP url and `PLUGIN://p1` (null), `resolveRequestUri` keeping `/v2` before the ingress path, and no override
  path for an entry served by the deployment's plugin url; `EmbeddedOriginApiTest.testPathAfterThePluginName`
  ("Path after the plugin name" and "Upstream uri with a path"),
  `testDeploymentLevelPluginUrlServesTheEntries` ("Deployment-level plugin url serves the entries without a base
  url"), `testOverridePathsUnderTheDeploymentsPluginUrl` ("Override paths under the deployment's plugin url");
  `EmbeddedDecisionApiTest.testRouterBaseUrlNamesThePlugin` ("Router baseUrl names the plugin", a router `rp`
  with `baseUrl: plugin://p1/v1` added to `router-decision-embedded.json`; the `test-origin` plugin answers a decide
  request by a path ending in `/decide`)
- [x] 1.3 Remove `plugin` from `DeploymentInterface` and `Translator` (`baseUrl` required and non-empty in the
  constructor, design 6); add one helper in `DeploymentEndpointUtil` that returns the name of a plugin url (the text
  after `plugin://` up to the next `/` or the end, design 2) and use it everywhere below; `resolvePlugin` reads the
  name from the url `resolveBaseUrl` returns, `resolveBaseUrl`, `isInterfaceDeclared` and `findOverridePath` lose
  their `plugin` branches (design 1, 5); `ProxyClient.embeddedRequest` is unchanged (it already passes
  `plugin://<name>` as the base url, so `EmbeddedProxyRequest` hands the plugin the path after the name); port the existing rules to the url in
  `ConfigPostProcessor` (`validateDeploymentInterfaces` drops `getPlugin() == null` from the needs-a-url check,
  `validatePluginReference` checks an entry's own `base_url`, `validateOverridePaths` reads the pass-through url,
  `hasInlineTranslatorPlugin` reads the inline `baseUrl`) and in `ApplicationService.pluginOutsidePlatformError`;
  drop the copy of `plugin` in `AliasPasses.copyOperational` (`baseUrl` is copied already). Verify the tests of 1.1
  and 1.2 pass, `EmbeddedOriginApiTest.testAliasOfModelServedByPlugin` included, and
  `grep -rn 'getPlugin()\|setPlugin(' --include=*.java config/src server/src` finds nothing
- [x] 1.4 Reword the Javadoc and comments that name the `plugin` field: `InterfaceMode`, `DeploymentInterface`,
  `Translator`, `DeploymentEndpointUtil` (`PLUGIN_URL_PREFIX`, `resolvePlugin`, `resolveBaseUrl`, `findOverridePath`),
  `ConfigPostProcessor`, `ApplicationService`, `ResponsesApiClient`, `RouterDecisionClient` and the comment in
  `BaseInterceptorController.sendRequest`, and `PluginProvider.getType` in `plugin-api`; verify
  `grep -rn 'getPlugin' --include=*.java config/src server/src/main` finds nothing
- [x] 1.5 Update `docs/dynamic-settings/plugins.md` (preview note, examples, `plugin://<name>[/<path>]`, the
  deployment-level `baseUrl`, the path the plugin receives, `upstream_uri`, the decide path), `models.md` (base URL
  resolution order, the `plugin` field and `translator` bullets, example, override paths), `applications.md`,
  `routers.md` and `translators.md` (`baseUrl` required, an HTTP or plugin url; the plugin example) to the plugin url;
  verify `grep -rn '"plugin"\|`plugin`' docs/dynamic-settings` finds nothing and every changed JSON example parses
- [x] 1.6 Run `./gradlew checkstyleMain checkstyleTest`, `./gradlew :config:test --tests "*TranslatorTest"` and
  `./gradlew :server:test --tests "*EmbeddedOriginApiTest" --tests "*EmbeddedCallApiTest"
  --tests "*EmbeddedDecisionApiTest" --tests "*RouterDecisionClientTest" --tests "*ConfigPostProcessorTest"
  --tests "*DeploymentEndpointUtilTest" --tests "*AliasPassesTest"`; verify all pass

## 2. A plugin url where no plugin serves

- [x] 2.1 Add tests, verify each fails before 2.2: `EmbeddedOriginApiTest.testPluginUrlInEndpoint` ("Plugin url in
  endpoint", a model with no `interfaces`); `AdminValidateApiTest.testPluginUrlInResponsesEndpointFails` ("Plugin url in
  responsesEndpoint refused by validate and apply", the apply call included);
  `EmbeddedOriginApiTest.testBlankPluginName` rewritten to `baseUrl: plugin:///v1` ("Plugin url with a blank name");
  `ConfigTranslatorTest.testTranslatorPluginUrlWithBlankName` ("Translator plugin url with a blank name"); `ConfigPostProcessorTest` a blank name in an inline translator's
  `baseUrl` rejected; `EmbeddedOriginApiTest.testInterceptorWithPluginBaseUrl` ("Interceptor with a plugin baseUrl");
  `EmbeddedOriginApiTest.testCustomApplicationWithPluginBaseUrlRefusedOnWrite` ("Custom application with a plugin
  baseUrl refused on write")
- [x] 2.2 In `ConfigPostProcessor` add the check of `endpoint` and `responsesEndpoint`, routers excepted (they reject
  both fields already) (`A plugin url is only accepted in a base url`, warning on the field) and the blank-name check
  of the deployment-level `baseUrl`, called from `validateInterfaces` and `validateDeploymentInterfaces` before their
  `interfaces == null` return, so a deployment without `interfaces` is checked too (design 3); reject an interceptor's
  plugin url in its `baseUrl` as well; check a blank name in an inline translator's `baseUrl` and in
  `validateTranslator`; extend `ApplicationService.pluginOutsidePlatformError` to the application's `baseUrl`
  (design 4). Verify the tests of 2.1 pass
- [x] 2.3 Update `docs/dynamic-settings/models.md` and `applications.md` (a plugin url rejected in `endpoint` and
  `responsesEndpoint`, and with a blank name; platform only, `baseUrl` included),
  `interceptors.md` (no plugin url in `baseUrl`, an interface `base_url` or an inline translator) and
  `translators.md` (a blank name rejected); verify the rules read as the spec states them
- [x] 2.4 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*EmbeddedOriginApiTest"
  --tests "*ConfigPostProcessorTest" --tests "*AdminValidateApiTest" --tests "*AdminApplyApiTest"
  --tests "*ConfigTranslatorTest" --tests "*CustomApplicationApiTest" --tests "*MergedConfigStore*"`; verify all pass

## 3. Integration checks

- [x] 3.1 Run `./gradlew :openapi-generator:replaceSpec` to regenerate `docs/open_api_core.yaml`; verify the
  `DeploymentInterface` and `Translator` schemas have no `plugin` property and
  `./gradlew :openapi-generator:checkOpenApiDiff` passes
- [x] 3.2 Confirm `README.md` and `CLAUDE.md` need no change: verify `grep -n '"plugin"\|`plugin`' README.md CLAUDE.md`
  finds nothing (they name only `plugins.dir`, the `plugins` entity and the `plugin-api` module)
- [x] 3.3 In the provider adapter's branch, change
  `C:\Data\Projects\DIAL\GitHub\adapters\ai-dial-model-provider-java-plugin\README.md` (lines 49, 61 and 74:
  "`embedded` interfaces", "name the entry in place of a `base_url`", `{"mode": "embedded", "plugin": ...}`) to
  `"base_url": "plugin://model-provider-openai"`; verify its JSON example parses. Committed in that repository, not
  with this change
- [ ] 3.4 Update the performance-environment overlay configs that name `plugin` on an interface or a translator
  (deferred: needs the performance environment and its config repository)
- [x] 3.5 Run `npx -y @fission-ai/openspec@1.14.3 validate plugin-base-url --strict` and
  `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`; verify both pass

## Workflow follow-up

- Write `review.md` in the change folder, then archive the change with
  `npx -y @fission-ai/openspec@1.14.3 archive plugin-base-url -y` and run `validate --specs --strict`.
- One commit on `feat/plugin-system`, staging explicit paths.
