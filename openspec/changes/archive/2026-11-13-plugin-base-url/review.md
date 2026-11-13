# Review: plugin-base-url

## What was built

- `DeploymentInterface.plugin` and `Translator.plugin` are removed. A plugin is named by a base url
  `plugin://<name>[/<path>]` in an interface's `base_url`, the deployment-level `baseUrl` or a translator's `baseUrl`
  (design 1, 2). `DeploymentEndpointUtil.resolvePlugin` reads the name from the url the entry resolves to, so every
  dispatch path (deployments, Responses items, translators, decide) takes it from one place.
- `ConfigPostProcessor` rejects a plugin url with a blank name, one in `endpoint` or `responsesEndpoint` (routers
  excepted: they reject both fields already), and any on an interceptor; the checks run first in
  `validateInterfaces` and `validateDeploymentInterfaces`, so every write path and the reload run them (design 3).
- `ApplicationService.pluginOutsidePlatformError` covers the application's `baseUrl`, interface `base_url`s and inline
  translators (design 4). `overridePaths` stay without effect on a plugin-served entry (design 5).
- Docs: `plugins.md`, `models.md`, `applications.md`, `routers.md`, `translators.md`, `interceptors.md`; the OpenAPI
  drops the `plugin` properties (the merge keeps hand-written properties, so they were removed by hand;
  `checkOpenApiDiff` passes).

## Reviews

- Proposal review: the `features` endpoints were dropped from the rejection (never served by a plugin, like
  toolsets and upstreams); routers are skipped by the new check; "Rejection outcome" names routers; design records
  the rejected alternatives and the interceptor case.
- Code review: no blocker or major finding. Fixed: a stale Javadoc in `ConfigResourceController.checkInterfaces`, one
  public `DeploymentEndpointUtil.isPluginUrl` in place of two private copies, a `404` check in
  `testInterceptorWithPluginBaseUrl`. Not taken: a separate message for a plugin url in an interceptor's `endpoint`
  (the spec gives every deployment one message there); rejecting names with `?` or spaces (a request fails `502`, as
  for a misspelled scheme, design Risks); renaming `testApplicationWithPluginButNoEmbeddedMode` (it carries its
  scenario's name).

## Deviations found while applying

- An entry still carrying `plugin` fails to parse with `400`, not `422`: "Application with a plugin but no embedded
  mode" now uses a blank plugin url, which a rule rejects with `422`; `testRemovedPluginFieldRejected` pins the `400`.
- An `Application` manifest does not accept `responsesEndpoint`, so "Plugin url in responsesEndpoint refused by
  validate and apply" uses a `Model` manifest. Apply with `"precheck": false` answers `200` with the same `FAILED`
  result.
- The test router is `rp` (`r2` was taken); the deployment-level scenario uses an HTTP embeddings entry next to the
  plugin-served one. The `test-origin` plugin answers a decide request by a path ending in `/decide`.

## Tests

- Checkstyle passes. `:config` `TranslatorTest`; `:server` `ConfigPostProcessorTest`, `DeploymentEndpointUtilTest`,
  `RouterDecisionClientTest`, `AliasPassesTest`, `EmbeddedProxyRequestTest`, `MergedConfigStoreTest`,
  `MergedConfigStorePartialUpdateTest`, `EmbeddedOriginApiTest`, `EmbeddedDecisionApiTest`, `EmbeddedCallApiTest`,
  `AdminValidateApiTest`, `AdminApplyApiTest`, `ConfigTranslatorTest` and `MergedConfigStoreApiTest` pass, run in
  batches. `CustomApplicationApiTest.testApplicationListing` fails on Windows on plain `development` too (a `gpt\`
  folder name); its other tests pass.

## Not done

- The performance-environment overlay configs still name `plugin` (task 3.4, deferred).
- The provider adapter's README moves to the plugin url on its own branch.
