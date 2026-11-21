# Tasks

Run Gradle with a JDK 21 as `JAVA_HOME`. Integration tests bind the embedded Redis port 16370: run one test task at
a time across checkouts.

No measurement task: the change claims no cost effect.

## 1. The scheme is dial-plugin://

- [x] 1.1 Rewrite every `plugin://` in the tests and test resources to `dial-plugin://`: `EmbeddedOriginApiTest`,
  `EmbeddedCallApiTest`, `AdminValidateApiTest`, `ConfigTranslatorTest`, `ConfigPostProcessorTest`, `ProxyClientTest`,
  `EmbeddedProxyRequestTest`, `RouterDecisionClientTest`, `DeploymentEndpointUtilTest`, `TranslatorTest` and
  `server/src/test/resources/dial-config/router-decision-embedded.json`. The scenarios this change rewrites keep the
  tests named in `plugin-base-url`'s tasks; only the scheme in their bodies changes.
- [x] 1.2 In `DeploymentEndpointUtilTest.pluginUrlNamesThePlugin`, assert that `pluginName("plugin://p1")` is null.
- [x] 1.3 Set `DeploymentEndpointUtil.PLUGIN_URL_PREFIX` to `dial-plugin://`, and rewrite `plugin://` in the Javadoc
  of `InterfaceMode`, `Translator`, `PluginProvider`, `Request`, `ProxyClient`, `EmbeddedProxyRequest` and
  `DeploymentEndpointUtil`.
- [x] 1.4 Docs: rewrite `plugin://` in `docs/dynamic-settings/plugins.md`, `models.md`, `applications.md`,
  `routers.md`, `translators.md` and `interceptors.md`; verify `git grep -n 'plugin://' -- ':!openspec/changes/archive'`
  finds only `dial-plugin://`.
- [x] 1.5 `./gradlew checkstyleMain checkstyleTest`, `:config:test --tests TranslatorTest`, and the server classes of
  1.1, two or three per run.
