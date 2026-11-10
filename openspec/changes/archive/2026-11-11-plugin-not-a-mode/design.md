# Design

## Context

See proposal.md for the motivation. What the code on this branch does today:

- `InterfaceMode.java` has `PASSTHROUGH`, `TRANSLATOR` and `EMBEDDED`, each with `subjectToLimits`. `EMBEDDED` is
  subject to limits, as `PASSTHROUGH` is.
- `DeploymentInterface.java` has `baseUrl`, `mode`, `translator` and `plugin`.
- `Translator.java:Translator` requires `out` and a non-empty `baseUrl`.
- `DeploymentEndpointUtil.java:resolvePlugin` returns the entry's `plugin` when its mode is `EMBEDDED`. It is the only
  question the request path asks about plugins. Its callers:
  `BaseDeploymentPostController.java:createProxyRequest`, `ResponsesApiClient.java` (item operations),
  `RouterDecisionFlow.java` and `RouterDecisionClient.java` (decide requests).
- `DeploymentEndpointUtil.java:resolveBaseUrl` returns the translator's `baseUrl` for `TRANSLATOR`, `plugin://<name>`
  (`PLUGIN_URL_PREFIX`) for `EMBEDDED`, and `passthroughBaseUrl` otherwise. `isInterfaceDeclared` and
  `findOverridePath` branch on the mode the same way.
- `BaseDeploymentPostController.java:createProxyRequest` passes the plugin name to `OriginClient.java:embedded`, which
  builds an `EmbeddedProxyRequest`. Nothing after that point reads the mode.
- `BaseDeploymentPostController.java:isSubjectToLimits` reads `resolveMode(...).isSubjectToLimits()`.
  `setProcessedRequestBody` keeps the parsed tree for the plugin when the mode is `EMBEDDED`.
- `ConfigPostProcessor.java:validateDeploymentInterfaces`, `validatePluginReference`, `validateOverridePaths`,
  `validateTranslatedInterface` and `validateTranslatorOutput` hold the config rules.
  `ApplicationService.java:requireValidInterfaces` and `hasEmbeddedInterface` hold the platform rule on write;
  `OriginClient.java:embedded` holds it on the request.
- `AliasPasses.java:copyOperational` copies `baseUrl`, `mode` and `translator` of an interface into the alias view.

Constraint: a mode decides what a request is charged for (`subjectToLimits`). A translated request must stay in
`translator` mode so that the client's pass is exempt and the callback is charged.

## Goals / Non-Goals

**Goals:**

- One meaning per field: `mode` says forwarded or translated; `plugin` or a url says in process or over HTTP.
- A translator served by a plugin with every existing translator rule, by changing where the plugin name is read
  and nothing in the dispatch after it.
- No new plugin contract: a translator plugin is an origin plugin (`OriginPlugin.handle`, `OriginHost.call`).

**Non-Goals:**

- A plugin kind that only converts bodies while Core makes the second call. The plugin makes the call itself.
- Reading old `mode: embedded` configs. There is no alias and no migration code.
- Renaming `EmbeddedOrigins`, `EmbeddedProxyRequest`, `OriginClient.embedded`, the `Embedded*ApiTest` classes or the
  spec headings that say "embedded". The word stays the name of in-process serving.

## Decisions

### D1. The plugin of an interface is resolved in one place, with the translators

`DeploymentEndpointUtil.resolvePlugin(deployment, type)` becomes
`resolvePlugin(deployment, type, translators)`:

- entry in `TRANSLATOR` mode: the resolved translator's `plugin`, or null (the translator has a `baseUrl`, or its
  name resolves to nothing);
- any other entry: the entry's `plugin`, or null.

`resolveBaseUrl` returns `PLUGIN_URL_PREFIX + plugin` in both cases, so `resolveRequestUri`,
`resolveResponseItemUri` and the `upstream_uri` of the analytics row keep working unchanged. `isInterfaceDeclared`
and `findOverridePath` test `getPlugin() != null` in place of the mode. `resolveLegacyEndpoint` needs no change:
an entry with a plugin resolves to `plugin://<name>` before the legacy field is looked at; only its Javadoc is
reworded. There are five call sites in four files (`RouterDecisionFlow` has two), and `setProcessedRequestBody`
becomes a sixth (D5). Every caller already has the config: `context.getConfig().getTranslators()`; the
router-decision callers pass it too, although a decision router never has a translated interface
(`ConfigPostProcessor.java:validateDecision`).

Rejected: **a second helper for the translator's plugin.** Each of the four call sites would need both calls and
could forget one, which is how the alias view lost `plugin` (D6).

### D2. `EMBEDDED` is removed from the enum, with no alias

`InterfaceMode` keeps `PASSTHROUGH` and `TRANSLATOR`. A config with `mode: embedded` fails Jackson's enum read, as
`mode: decision` does today (router-deployment "Decision mode"): a `PUT` answers `400`
(`ConfigResourceController.java:handleWriteError`), a config file fails to load, and a blob already in the
`platform` bucket is recorded as an invalid entity with reason `parse_error` and skipped, while the rest of the
config loads (`MergedConfigStore.java`, `REASON_PARSE`).

Rejected: **accept `embedded` as a deprecated alias of pass-through.** Plugins are unreleased and documented as a
preview; an alias is code and a spec scenario kept for configs that exist only in tests and the perf environment.

### D3. An entry with a plugin is pass-through; the rules move from the mode to the field

`ConfigPostProcessor.validatePluginReference` checks the `plugin` field, whatever the mode:

| Case | Message |
|---|---|
| `plugin` blank | `A plugin name cannot be blank` |
| `plugin` and `base_url` | `An interface is served either by a plugin or by a base_url, not by both` (unchanged) |
| `plugin` on an interceptor, or in its inline translator | `A plugin is not supported for an interceptor` |
| `plugin` and `mode: translator` | `A translated interface is served by its translator, not by a plugin` |
| `plugin` and `overridePaths` | `An interface with a plugin is served by its plugin: overridePaths has no effect` |

`Mode 'embedded' requires a plugin` and `A plugin requires mode 'embedded'` have no case left and are deleted.
`validateDeploymentInterfaces` skips the "declares no base_url" rule when the entry names a plugin, as it does for
`EMBEDDED` today. The platform rule's message becomes `A plugin is reserved for platform deployments`, in
`ApplicationService.requireValidInterfaces` and `OriginClient.embedded`.

These rules run for models, applications, interceptors and routers, as `validatePluginReference` does today; the
translator rules stay model-only.

An interceptor hop is always sent over HTTP (`BaseInterceptorController.java:sendRequest` uses the HTTP-only
`createProxyRequest(String)`). A named translator cannot be checked when the interceptor is written, so the hop
fails the request as a connect failure when the resolved url starts with `PLUGIN_URL_PREFIX`, in place of handing
`plugin://...` to the HTTP client. Rejected: **serve an interceptor's translator plugin in process.** That is the
embedded-interceptor work of the later `embedded-filter` change.

An entry with a `plugin` on a deployment that also declares a deployment-level `baseUrl` is served by the plugin:
the entry is more specific than the deployment. This is today's `EMBEDDED` behaviour.

### D4. A translator names exactly one of `baseUrl` and `plugin`

`Translator` gets a nullable `plugin` and its constructor requires exactly one of a non-empty `baseUrl` and a
non-empty `plugin` (`Translator needs exactly one of baseUrl or plugin`). The three-argument constructor used by
tests stays. Like an interface's plugin, the name is not checked against `plugins`: a missing entry answers `502` on
the request (`OriginClient.embedded`), which is the deliberate difference from a missing translator name (`503`)
recorded in the embedded-origin design.

The unmerged `feat/builtin-translator-main-path` branch changes the same constructor to "exactly one of `baseUrl`
or `builtin`". Whichever merges second makes it one of three; the conflict is in that one method.

Rejected: **`mode: translator` plus `plugin` on the interface entry, with `out` on the entry.** `out` and the
"served pass-through" check belong to the translator definition, which a registry entry shares between models.

### D5. A translator plugin is an origin plugin on the translated pass

With D1 the translated pass reaches `OriginClient.embedded` and `EmbeddedProxyRequest` as an interface's own plugin
does. What follows from the existing code, with no change:

- The pass is in `TRANSLATOR` mode, so `isSubjectToLimits` is false: no limit check, no charge
  (`BaseDeploymentPostController.java:checkLimits`, `increaseLimits`); `/anthropic/v1/messages` and the Responses
  create still read the spend caps (`SpendCapCheck.java:verify`, called from `MessagesController.java:verifyLimit`
  and `ResponsesController.java:verifyLimit`).
- The plugin's `host.call` to the `out` interface is a child pass (`ChildPass.java:call`) with the per-request key
  of the translated pass. It is limit-checked and charged as a request the deployment sends itself, and writes its
  own analytics row with `parent_deployment`.
- The plugin gets the headers a translator service gets, `X-DIAL-DEPLOYMENT-ID` included
  (`BaseDeploymentPostController.java:sendProxyRequest`), and the caller.
- The platform rule of `OriginClient.embedded` covers a custom application's translator, inline or named: the
  request is answered `502`. Only the inline case can also be refused on write.
- Responses item operations of the deployment go to the translator's plugin (`ResponsesApiClient.java:send`).

Two places test the mode for the plugin and change:

- `setProcessedRequestBody` keeps the parsed tree when `resolvePlugin(...) != null`, so a translator plugin gets
  `bodyTree` too.
- `ApplicationService.hasEmbeddedInterface` becomes "names a plugin, directly or in an inline translator". A named
  translator is platform config already, but the request-time rule still refuses a custom application that names
  one whose translator has a plugin.

Rejected: **Core converts through the plugin and makes the second call itself.** It needs a new plugin kind with a
request converter and a streaming response converter. The origin contract already covers it, and the builtin
translator branch is where conversion inside Core's own pass is being built.

### D6. The alias view copies the plugin

`AliasPasses.copyOperational` copies `plugin` next to `baseUrl`, `mode` and `translator`. Without it an alias of a
model with a plugin-served interface is dropped from the listings (review finding on PR #2216); with the mode gone
the view would also resolve to no plugin at all. A translator's plugin needs nothing here: the view copies the
translator reference.

### Per-request effect

- Pass-through interface with a plugin: none. The same pass, the same single parse, no extra Redis or blob call.
- Translator with a plugin, against the same translator over HTTP: the same two Core passes, minus one HTTP request
  from Core to the translator, one HTTP request back into Core, one API-key lookup in Redis and one parse of the
  converted body (the plugin passes a tree). Against today's workaround, a translating plugin behind an `embedded`
  interface: one limit check and one limit increase fewer per request, because the first pass is exempt.
- Not measured in this change. The claim is structural and is covered by the scenario "Translator plugin's call
  charged once"; a perf run belongs with the first real translator plugin.

## Risks / Trade-offs

- [A translator plugin that calls its own `in` interface loops] → Config validation checks only that the deployment
  serves `out` pass-through, not what the plugin calls. Documented in `plugins.md`; the depth guard is its own
  change (review finding on PR #2216).
- [A translator plugin that answers without calling `out` is charged nothing] → Same as a translator service that
  answers by itself today. Documented next to the limits table in `translators.md`.
- [`out` served by a plugin] → `validateTranslatorOutput` accepts it, since that entry is pass-through. Both plugins
  then run in one request; nothing forbids it and nothing depends on it.
- [Background Responses through a translator plugin] → `OriginHost.call` refuses `"background": true`, so a plugin
  translating `openaiResponses` cannot hand a background job to `out`. It answers such a request itself or fails it;
  "Translated job charged at finish" is unchanged.
- [Responses item operations through a translator plugin] → `ChildPass.java:call` accepts create routes only, so
  the plugin cannot pass `GET`, `DELETE` or `cancel` of a response on to `out`. A plugin translating
  `openaiResponses` answers item operations itself. Documented in `plugins.md`.
- [Configs with `mode: embedded` stop loading] → Deliberate (D2). The perf-environment overlay configs are updated
  with the image that carries this change.
- [Merge with the builtin translator branch] → One constructor and one docs table (D4).

## Migration Plan

Replace `{"mode": "embedded", "plugin": "<name>"}` with `{"plugin": "<name>"}` in every config file and platform
blob before the new image starts. An old entry in a config file makes the file fail to load; an old blob is
skipped as an invalid entity, so check `GET /v1/admin/health/config` after the upgrade. The feature is unreleased,
so this covers the test resources in this repository and the configs of the perf environment. Rollback is the
previous image with the previous config.

## Open Questions

None.

## Drift recorded during apply

- The group 2 tests were written together with the code and first ran in CI, so "verify it fails before 2.4" was
  not observed for them. The group 1 unit tests were observed failing before the code change.
- A translator definition with neither `baseUrl` nor `plugin` now fails to parse with `Translator needs exactly one of
  baseUrl or plugin` instead of Jackson's `Missing required creator property 'baseUrl'`
  (`ConfigEntityCodecTest.missingRequiredFieldNamesTheField`).
- "Translator plugin's call refused over the limit": the plugin's call is answered `429`; a plugin that answers with
  that status makes Core answer the caller `503 Service is not available`, the existing rule for an only origin
  answering `429`.
- `validate --all --strict` passes for this change and fails on the placeholder Purpose of two specs this change does
  not touch, `resource-subscription` and `user-limits`; task 3.1 stays open for that reason.
