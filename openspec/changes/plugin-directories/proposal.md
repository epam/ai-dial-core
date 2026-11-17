## Why

Core loads plugin jars from one flat directory, all of them, into one class loader. That blocks the delivery model
planned for production: common plugins inside the default Core image, beside plugins an environment mounts itself
(for example one Kubernetes image volume per plugin, each of which needs its own mount point). It also gives an
operator no way to keep one plugin from loading, and lets two plugins that bundle different versions of one library
break each other without a word.

## What Changes

- **BREAKING** Plugin code is laid out as `<plugins.dir>/<group>/<plugin>/*.jar`. A plugin directory is identified by
  `<group>/<plugin>`. By convention the Core image build fills `builtin/`, and `custom/` is where an environment
  mounts its own plugins; Core treats every group alike.
- **BREAKING** A jar directly in `plugins.dir` or directly in a group directory stops the start, with a message that
  names the jar and the expected layout. Today such a jar is loaded.
- New static settings `plugins.include` (default `["*"]`) and `plugins.exclude` (default `[]`): patterns matched
  against `<group>/<plugin>`. A plugin directory is loaded when it matches `include` and does not match `exclude`.
- Each plugin directory gets its own class loader, whose parent is Core's. Two plugins no longer see each other's
  classes; Core's classes still win over a plugin's copy.
- An included plugin directory that registers no provider stops the start.
- Core logs each plugin directory at start as loaded (with its types) or excluded.
- The Docker build argument `SAMPLE_PLUGINS=true` puts the sample jar in `/app/plugins/samples/forward/`.

Out of scope:

- Checking which plugin API a jar was built against (the separate change `plugin-api-level`).
- Putting any plugin into the default image, the Helm chart, and how a plugin is packaged as an OCI image.
- Reloading plugin code while Core runs.
- Child-first class loading, or any way for a plugin to replace a library Core carries.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `plugin-registry`: the requirement "Plugin providers loaded at start" changes (layout, isolation, the misplaced
  jar, the directory without a provider), and a requirement for `plugins.include` / `plugins.exclude` is added.

## Impact

- Code: `PluginRegistry.load` and its call in `AiDial.start`; the built-in `aidial.settings.json`; `Dockerfile`;
  `PluginRegistryTest`, `ConfigPluginApiTest` and the test settings.
- Docs: `docs/dynamic-settings/plugins.md` ("Plugin code", "The forward sample"), the `plugins.*` rows of
  `README.md`, the `plugin-samples` row of `CLAUDE.md`.
- Operators: an environment that keeps jars directly in `plugins.dir` must move each plugin into
  `<group>/<plugin>/`; until then Core does not start. Plugins are in preview, so no compatibility mode is kept.
- Related: #2260 (isolating plugin jars). This change gives each plugin its own class loader, so two plugins no
  longer share one namespace; a plugin still cannot carry its own version of a library Core ships. The motivation is the production delivery of plugins: built-in plugins in the default image
  beside plugins an environment mounts, with a per-plugin off switch for its operators. This change covers the
  directory layout, the switch and the isolation; it leaves out the API level check (`plugin-api-level`, a separate
  PR that also changes `PluginRegistry.load`; whichever merges second merges `development` first).
