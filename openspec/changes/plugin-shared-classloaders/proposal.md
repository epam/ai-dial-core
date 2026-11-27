# Proposal

## Why

A plugin currently uses Core's copy of every library on Core's classpath, even when the plugin bundles another
version; partial library families can also fail across the two loaders. The provider plugin's Kotlin reflection
failure required #2383's Core-side workaround, making dependency isolation a concrete need rather than a future one.

## What Changes

- **BREAKING:** load Core and plugin directories as siblings below a Shared loader instead of parenting plugins
  directly to Core. A minimal launcher starts Shared and Core even when no plugins are enabled.
- Publish a fixed plugin-visible package list: plugin API, Vert.x core, Netty, Jackson core/databind/annotations,
  SLF4J and OpenTelemetry API/context. Shared holds their runtime dependencies, but plugins cannot borrow those
  dependencies unless their packages are on the list.
- Put logging and telemetry implementations in Shared, hidden from plugins; Core owns their configuration.
- Keep service descriptors private to each plugin directory. Core discovers plugin providers explicitly through
  that directory's loader; Shared performs its own implementation discovery through Shared.
- Scope the thread context class loader to the originating plugin at Core-to-plugin calls and restore it on return.
  Plugin authors own propagation for asynchronous work that needs that loader.
- Ignore bundled shared classes with a bounded startup warning. Never fall back to a bundled shared class when
  Shared lacks it. Preserve the existing directory layout, include/exclude settings and startup error checks.
- Migrate the model-provider plugin's packaging and verify its provider/translator paths without #2383's workaround
  before removing the workaround. Changes in that separate repository need a coordinated, separate PR.

Out of scope: compatibility mode, runtime TCCL instrumentation or startup bytecode inventory, generic native-library
isolation/detection, plugin hot reload, and automatic propagation through arbitrary third-party asynchronous work.
Access to selected Shared service descriptors is an optional future feature, not part of this change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `plugin-registry`: revise the existing "Plugin directories loaded apart" requirement, which explicitly says
  plugins see Core's classes and Core's copy wins. Retain observable provider validation and request behaviour while
  describing the new dependency boundary through executable plugin fixtures. Keep classloader mechanics in design.md.

Spec deltas and tasks are not drafted yet. This proposal and design record the agreed approach; they are not a
complete, validated change ready for implementation.

## Impact

- Addresses [#2260](https://github.com/epam/ai-dial-core/issues/2260): plugin/Core version conflicts and incomplete
  library families; plugin-to-plugin isolation already exists and remains.
- Uses [#2385](https://github.com/epam/ai-dial-core/issues/2385)'s explicit shared boundary, with a physically separate
  Shared runtime rather than delegation into Core's application loader. #2385 was closed as a duplicate; its linked
  [proposal #2384](https://github.com/epam/ai-dial-core/pull/2384) was not merged and is not an approved baseline.
- Replaces [#2383](https://github.com/epam/ai-dial-core/pull/2383)'s workaround only after isolated Kotlin reflection
  succeeds. Native-library limitations remain documented rather than generically solved.
- Supersedes: plugin-directories D4 (2026-11-17 archive), specifically Core-parent, parent-first plugin loading;
  keeps one loader per directory. The older plugin-config-entity D4 (2026-11-02 archive) is historical context, already
  replaced in part by plugin-directories.
- Affects server startup, plugin registry/lifecycle/stream adapters, plugin API documentation, Gradle distributions,
  Docker packaging, logging/tracing initialization and integration fixtures. No new HTTP endpoint or stored record.
- Provider reference: [ai-dial-model-provider-plugin](https://github.com/epam/ai-dial-model-provider-plugin), its
  `ModelProviderPluginProvider`, `TranslatorPluginProvider`, `Buffers`, packaging and `PluginJarIsolationTest`.
- Native-library handling and TCCL responsibilities are operator/plugin-author documentation obligations. Existing
  logging/telemetry outputs and plugin HTTP semantics must be preserved by integration verification.
