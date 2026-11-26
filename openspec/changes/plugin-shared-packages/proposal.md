# Proposal

## Why

A plugin's class loader asks Core's class loader first for every class, so what a plugin shares with Core is
whatever is on Core's class path (the 263 jars in `/app/lib`). No rule decides it: it changes when Core adds, bumps
or drops a dependency, and a plugin author can learn it only by opening the image. A library Core carries in part
breaks a plugin that completes it: Core has `kotlin-stdlib` (through okio) and no `kotlin-reflect`, so a plugin
that bundles `kotlin-reflect` fails with a `ClassCastException` on its first Kotlin reflection call. PR #2383 fixes
that one case by putting `kotlin-reflect` on Core's class path. This change fixes the cause.

## What Changes

- **BREAKING** A plugin directory's class loader shares with Core only an explicit list of packages: the plugin API
  (`com.epam.aidial.core.plugin`), Vert.x core (`io.vertx.core`), Netty (`io.netty`), Jackson core, databind and
  annotations, SLF4J and the OpenTelemetry API and context. Every other class comes from the plugin's own jars, with
  no fallback to Core's class path: a library the plugin forgot to bundle fails at once, and a library it bundles is
  the one it runs.
- A class a plugin jar bundles under a shared package is still Core's. Core logs a warning at start naming the jar
  and the package, since the jar's copy is not used.
- The list is published with the plugin API (`SharedPackages`) and in `docs/dynamic-settings/plugins.md`, with the
  one-sentence rule for plugin authors: compile against the plugin API, and bundle everything but the shared
  packages.
- The plugin API module declares SLF4J and the OpenTelemetry API as `api` dependencies, so its compile class path
  has every shared family.

Out of scope: the thread context class loader of plugin code (design.md, Risks); a setting that adds shared
packages; sandboxing a plugin's code, files or network; plugin providers on Core's own class path.

## Capabilities

### Modified Capabilities
- `plugin-registry`: "Plugin directories loaded apart" says which classes a plugin sees of Core's.

## Impact

Fixes the general cause behind epam/ai-dial-core#2383, the interim fix. #2383 stays until this ships; this change
makes its `kotlin-reflect` rule unnecessary (tasks.md says what to remove if it has merged). Modules:
`aidial-core-plugin-api` and `server`; docs; the `plugin-samples` build comment. A plugin that relies on Core's copy
of a library outside the list stops working until it bundles that library. The `forward` sample uses only shared
packages.
