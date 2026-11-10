# Proposal

## Why

`interfaces.<type>.mode` answers two questions with one field. `translator` says what serves the interface: a
converter that calls Core back on another interface of the same deployment. `embedded` says where the serving code
runs: in Core's process and not behind HTTP. In every other respect an `embedded` interface is a pass-through one:
the same limits, the same charging, the same "a plugin or a `base_url`, never both" rule.

So a translator that runs in process cannot be configured: it needs both values and the field holds one. A plugin
can translate behind an `embedded` interface today, but then Core does not know it is a translator. The client's
request and the plugin's call to the same model are both limit-checked and charged, none of the translator rules is
checked when the config is written, and the config does not say which API the interface converts to.

The same thing was done for `decision`, which moved from the interface to the router. Plugins are documented as a
preview, so the config can change with no compatibility alias.

## What Changes

- **BREAKING** `embedded` is no longer a value of `interfaces.<type>.mode`. An entry that names a `plugin` is a
  pass-through entry served in process: `{"plugin": "p1"}` replaces `{"mode": "embedded", "plugin": "p1"}`. A config
  that still says `mode: embedded` is refused as any unknown mode is.
- The rules of an entry with a `plugin` are the ones `embedded` mode had: no `base_url` next to it, no
  `overridePaths`, no fallback to `baseUrl`, `endpoint` or `responsesEndpoint`, not on an interceptor, platform
  deployments only, `502` when the plugin cannot serve. Limits, charging, records and the plugin's request are
  unchanged.
- A `plugin` on an entry in `translator` mode is refused: the translator serves that entry.
- A router in decision mode decides an interface in process when the entry names a `plugin`.
- A translator definition, in `translators.<name>` or inline, names exactly one of `baseUrl` and `plugin`. With a
  `plugin`, Core hands the plugin the request it would send to the translator service, and the plugin calls the
  deployment's `out` interface through its host in place of the HTTP callback.
- Every translator rule holds for a translator served by a plugin: the interface stays in `translator` mode, the
  client's pass is neither limit-checked nor charged and the plugin's call is, `out` must be served pass-through by
  the deployment, an unresolved translator name answers `503`. A translator plugin is for platform deployments only.

Out of scope:

- Plugins as request and response filters, and embedded interceptors (the later `embedded-filter` change).
- A depth or cycle guard for in-process calls. A translator plugin must call `out`; one that calls its own `in`
  loops, as any plugin calling its own deployment does today.
- The built-in translators of the unmerged `feat/builtin-translator-main-path` branch. That branch makes a
  translator name exactly one of `baseUrl` and `builtin`; after both merge the rule is one of three. Neither branch
  depends on the other.
- Renaming classes, tests and spec headings that use "embedded" as the name of in-process serving
  (`EmbeddedOrigins`, "Embedded origin request"). Only the mode value goes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-interfaces-config`: the `mode` values, how an entry names a plugin and what is refused with it, and
  the translator definition with a `plugin`.
- `deployment-origin`: which interfaces are served by a plugin, and the request a translator plugin receives.
- `app-to-model-accounting`: a translated request served by a plugin is charged once, on the plugin's call.
- `router-deployment`: how an interface of a decision router names the plugin that decides it.
- `responses-api`: item operations of a deployment whose Responses interface names a plugin.
- `plugin-registry`: what an origin plugin can serve.

## Impact

- Motivation: the design discussion on PR #2216 (`feat/plugin-system`) of 2026-11-10 about a translator inside a
  plugin. It covers both points of that discussion: the mode no longer says where code runs, and a translator can
  run in process. There is no GitHub issue.
- Config: `InterfaceMode`, `DeploymentInterface`, `Translator` in the `config` module. Every stored or file config
  with `mode: embedded` must be rewritten; the feature is unreleased, so these are test and perf-environment configs.
- Server: `DeploymentEndpointUtil` (the plugin of an interface), `ConfigPostProcessor` (interface, translator and
  plugin validation), `ApplicationService` (the platform rule), `BaseDeploymentPostController`. The plugin dispatch in
  `OriginClient`, `EmbeddedProxyRequest`, `ResponsesApiClient` and the router decision classes does not change.
- `plugin-api`: no change to the contract; Javadoc that says "`embedded` mode" is reworded.
- Docs: `docs/dynamic-settings/plugins.md`, `models.md`, `applications.md`, `routers.md`, `translators.md`,
  `interceptors.md`; `docs/open_api_core.yaml` is regenerated by CI (the `InterfaceMode` enum loses a value, the
  translator schema gains `plugin`).
- This change ships in PR #2216, after the archived plugin changes on that branch.
