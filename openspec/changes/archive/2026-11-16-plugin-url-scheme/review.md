# Review: plugin-url-scheme

## What was built

- `DeploymentEndpointUtil.PLUGIN_URL_PREFIX` is `dial-plugin://`. Every reader of a plugin url goes through
  `pluginName`, which uses the constant, so dispatch, validation, the platform-only check and the analytics
  `upstream_uri` follow it without other code changes.
- `plugin://` in the Javadoc, the docs (`plugins.md`, `models.md`, `applications.md`, `routers.md`, `translators.md`,
  `interceptors.md`), the tests and `router-decision-embedded.json` is now `dial-plugin://`.
  `git grep 'plugin://'` outside the archived changes finds only `dial-plugin://`, and one test line asserting the old
  scheme is no plugin url.

## Decisions

- `dial-plugin`, not `dial_plugin` as written in the review: RFC 3986 allows no `_` in a scheme, so URL-parsing
  clients of the Configuration API (`new URL()`, Java `URI`, Python `urlsplit`) would reject or misread it.
- No fallback for `plugin://`: plugins are in preview and no release shipped the old scheme.

## Checks

- `checkstyleMain checkstyleTest`: pass.
- `TranslatorTest`, `DeploymentEndpointUtilTest`, `ConfigPostProcessorTest`, `ProxyClientTest`,
  `EmbeddedProxyRequestTest`, `RouterDecisionClientTest`, `EmbeddedOriginApiTest`, `AdminValidateApiTest`,
  `EmbeddedCallApiTest`, `ConfigTranslatorTest`, `EmbeddedDecisionApiTest`: pass.
- The full suite runs in CI.
