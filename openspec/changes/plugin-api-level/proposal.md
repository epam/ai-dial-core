## Why

A plugin jar is compiled against one version of `aidial-core-plugin-api`, and Core checks nothing about it when it
loads the jar. A plugin built against a different API than the Core that loads it fails with a linkage error on a
live request. With the API published as an artifact, plugins are built in other repositories and reach Core through
an image or a mount, so the mismatch must be caught at start, with a message an operator can act on.

## What Changes

- The plugin API gets an **API level**: one integer, `PluginApi.LEVEL`, raised on every change of the public API.
  The published artifact keeps Core's version; the level is what compatibility is judged by.
- **BREAKING** `PluginProvider` gets `int apiLevel()`, which a provider implements as `return PluginApi.LEVEL;`. The
  compiler copies the constant into the plugin's class, so the jar carries the level it was compiled against.
- Core declares the oldest level it still serves. At start it refuses a provider whose level is above Core's or below
  the oldest served, and a provider compiled before `apiLevel()` existed, with a message naming the provider, its
  level and the range Core serves.
- A test fails the build when the public API changes and the level was not raised: the public signatures of each
  level are kept in a checked-in file, and the current API must match the file of the current level.
- Docs: how to build a plugin against a level, and what the refusal means.

Out of scope:

- The directory layout and the include and exclude settings (the separate change `plugin-directories`).
- A version of the artifact apart from Core's, and where the artifact is published.
- Compatibility of a plugin's own bundled libraries with Core's.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `plugin-registry`: a new requirement, "Plugin API level checked at start". The requirement "Plugin providers loaded
  at start" is not touched, so this change and `plugin-directories` do not edit the same block.

## Impact

- Code: `aidial-core-plugin-api` (`PluginApi`, `PluginProvider.apiLevel`), `PluginRegistry.load`,
  `ForwardOriginPluginProvider`, the test providers, a new API signature test and its `level-1.txt`.
- Plugin authors: every provider must implement `apiLevel()` and be rebuilt; a jar built before this change is
  refused at start. Plugins are in preview.
- Follows PR #2295 (issue #2261, merged), which publishes the artifact: this change covers the compatibility check
  that publishing makes necessary. It leaves the artifact's version and registry as #2295 set them.
- `plugin-directories` also changes `PluginRegistry.load`; whichever merges second merges `development` first.
