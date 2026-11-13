# Review: plugin-not-a-mode

## What was built

- **No `embedded` mode** (design D1). `InterfaceMode` has two values, `passthrough` and `translator`. An interface
  entry is served in process when it names a `plugin` in place of a `base_url`; `"mode": "embedded"` no longer
  parses.
  - `DeploymentEndpointUtil.resolvePlugin`, `resolveBaseUrl`, `isInterfaceDeclared` and `findOverridePath` test
    `getPlugin() != null` instead of the mode.
  - `ConfigPostProcessor.validatePluginReference`, `validateDeploymentInterfaces` and `validateOverridePaths` keep
    the rules of the removed mode under the new shape: no plugin together with a `base_url`, no blank name, no plugin
    on an interceptor, no `overridePaths` for an interface with a plugin.
  - The platform-only rule in `ApplicationService.requireValidInterfaces` and `OriginClient.embedded` reads "A plugin
    is reserved for platform deployments".
- **A translator served by a plugin** (D2 to D5). `Translator` takes a `plugin` in place of `baseUrl`, exactly one of
  the two. The interface stays `mode: translator`.
  - `resolvePlugin` takes the translator registry and returns the definition's plugin; `resolveBaseUrl` returns
    `plugin://<name>` for it. The callers in `BaseDeploymentPostController`, `ResponsesApiClient`,
    `RouterDecisionFlow` and `RouterDecisionClient` pass `config.getTranslators()`.
  - The plugin's `host.call` to the `out` interface is the translator's call back: the outer pass is not limit-checked
    or charged, the call is.
  - An interceptor cannot be served this way. An inline translator plugin on an interceptor is rejected at validation;
    a named translator that resolves to a plugin fails the hop in `BaseInterceptorController.sendRequest`.
  - The platform-only rule covers an inline translator plugin on a custom application
    (`ConfigPostProcessor.hasInlineTranslatorPlugin`).
- **Alias view** (D6). `AliasPasses.copyOperational` copies `plugin`, so an alias of a deployment served by a plugin is
  listed and served like its target.
- **Docs.** `plugins.md` (the new shape, "Translating for a deployment"), `translators.md` (the `plugin` field,
  "Translators served by a plugin"), `models.md`, `applications.md`, `routers.md`, `interceptors.md`; Javadoc of
  `InterfaceMode`, `DeploymentInterface`, `OriginPluginProvider` and `OriginClient`.

## Deviations from design.md and tasks.md

They are listed in design.md, "Drift recorded during apply":
- The group 2 tests were written with the code and first ran in CI, so they were not seen failing before it.
- A translator with neither `baseUrl` nor `plugin` fails to parse with `Translator needs exactly one of baseUrl or
  plugin`, not Jackson's missing-property message.
- A translator plugin's call refused `429` reaches the caller as `503 Service is not available`, the existing rule
  for an only origin answering `429`.

Also:
- The alias test is in `EmbeddedOriginApiTest`, not `AliasApiTest`, which runs on a file config without the plugin
  helpers. tasks.md is updated.
- The custom-application tests of group 2 are in `EmbeddedOriginApiTest`, next to the other platform-rule tests.

## Tests per scenario

deployment-interfaces-config:
- Plugin or base url, Pass-through entry needs a url, override paths: `ConfigPostProcessorTest`, and
  `EmbeddedOriginApiTest.testEmbeddedIsNotMode`, `testBlankPluginName`, `testExplicitPassthroughModeWithPlugin`.
- Plugins are for platform deployments: the existing `EmbeddedOriginApiTest` tests under the new shape, plus
  `testCustomApplicationWithInlineTranslatorPluginRefusedOnWrite` and
  `testStoredCustomApplicationNamingTranslatorWithPlugin`.
- Interceptors: `ConfigPostProcessorTest` (inline translator plugin), and
  `EmbeddedCallApiTest.testInterceptorNamingTranslatorWithPlugin`.
- Translator definition: `TranslatorTest.translatorNamesExactlyOneOfBaseUrlAndPlugin`,
  `ConfigEntityCodecTest.missingRequiredFieldNamesTheField`.

deployment-origin:
- Resolution: `DeploymentEndpointUtilTest`.
- Translator plugin request: `EmbeddedCallApiTest.testTranslatorPluginCallsTheOutputInterface`,
  `testNamedTranslatorWithPlugin`, `testTranslatorsPluginMissing`.
- Alias of a deployment served by a plugin: `EmbeddedOriginApiTest.testAliasOfModelServedByPlugin`.

app-to-model-accounting: `EmbeddedCallApiTest.testTranslatorPluginsCallChargedOnce`,
`testTranslatorPluginsCallRefusedOverTheLimit`.

responses-api: `EmbeddedCallApiTest.testItemOperationReachesTranslatorsPlugin`.

router-deployment: `RouterDecisionClientTest`, `EmbeddedDecisionApiTest` and the
`router-decision-embedded.json` fixture under the new shape.

plugin-registry: wording only; the existing tests.

## Review

- The proposal, design, tasks and spec deltas were reviewed by a separate agent before apply. Its findings were
  checked in the code and fixed in the artifacts: the interceptor with a translator plugin, the "Pass-through entry
  needs a url" delta, a custom application naming a registry translator with a plugin, Responses item operations, and
  the test classes named in the tasks. One finding was skipped: dropping a scenario from a MODIFIED requirement, which
  the validator forbids.
- The implementation has had no separate review yet; it goes through the PR review.

## Runs

- Checkstyle (`checkstyleMain checkstyleTest`): clean, locally and in CI.
- Group 1 locally: `AliasApiTest` 24, `EmbeddedCallApiTest` 21, `EmbeddedDecisionApiTest` 25, `EmbeddedOriginApiTest`
  41, `ConfigPostProcessorTest` 92, `RouterDecisionClientTest` 17, `OriginClientTest` 2, `DeploymentEndpointUtilTest`
  52. The group's unit tests failed before the code change (14 failures).
- Group 2 locally: `TranslatorTest` 10 and `ConfigEntityCodecTest`. The local run of the server classes was stopped
  by the host for low memory, so they ran in CI.
- CI, full suite: the first run (`a56fd0eca`) failed 2 of 3752 tests, both wrong expectations in tests
  (`ConfigEntityCodecTest.missingRequiredFieldNamesTheField`,
  `EmbeddedCallApiTest.testTranslatorPluginsCallRefusedOverTheLimit`). The second run (`24cd0dee6`, PR Workflow run
  42917804268) passed: build, tests, code and style checks, OpenAPI generation.
- `./gradlew :openapi-generator:test`: passes.
- OpenSpec `validate --all --strict`: the change is valid. The only failures are `resource-subscription` and
  `user-limits`, whose placeholder Purpose comes from `development`. Task 3.1 stays open for that reason.

## Measurement

Tasks 3.3 and 3.4 are deferred: they need the perf environment. The overlay configs there still say
`mode: embedded` and must change before the next run on an image with this change. A translator served by a plugin
against the same translator over HTTP is not measured.

## Open

- No compatibility alias for `"mode": "embedded"`: plugins are in preview, and a stored entry with it fails to parse.
