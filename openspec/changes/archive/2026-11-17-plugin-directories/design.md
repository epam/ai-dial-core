## Context

Today (`development` at ccc370e47):

- `AiDial.java:start` calls `PluginRegistry.load(Path.of(settings("plugins").getString("dir", "plugins")))`.
- `PluginRegistry.java:listJars` lists the `*.jar` files directly in that directory (`Files.list`, no recursion),
  opens each one, and refuses a provider class registered by two jars.
- `PluginRegistry.java:load` builds one `URLClassLoader` over all the jars with Core's loader as parent, and takes
  every provider `ServiceLoader` finds through it: the jars' and, through the parent, the classpath's. It refuses a
  blank type and a type declared twice.
- Static settings merge the built-in `aidial.settings.json`, the `AIDIAL_SETTINGS` file and the `aidial.*`
  environment variables (`AiDial.java:envSettings`, through `JsonObjectHelper.from`).
- `Dockerfile` copies the sample jar to `/build/plugins/` when `SAMPLE_PLUGINS=true`.

Plugins are in preview (`docs/dynamic-settings/plugins.md`), so the layout may change without a compatibility mode.

## Goals / Non-Goals

**Goals:**

- A built-in plugin and a mounted plugin can sit under one `plugins.dir`, each in a directory of its own.
- An operator can keep any plugin directory from loading with a static setting, also through the environment.
- Two plugins cannot break each other through a shared library.

**Non-Goals:**

- The API level check, any plugin in the default image, the Helm chart.
- Child-first loading, hot reload, a class loader that is ever closed.

## Decisions

**D1. Two levels, `<group>/<plugin>`, not one.** With one level a single volume mounted over `plugins.dir` (a bundle
of an environment's plugins) would hide the plugins the image carries. With groups, the bundle mounts over `custom`
and the image's `builtin` stays. Rejected: one level (`<plugins.dir>/<plugin>`), simpler but with that trap; a list of
root directories (`plugins.dirs`), which needs a second setting for the same effect. `builtin` and `custom` are names
the docs and the image use; the code knows no group name.

**D2. A misplaced jar stops the start.** A jar directly in `plugins.dir` or in a group directory is what every
existing setup has. Ignoring it would start Core without the plugin, and the first sign would be a `502` on a request.
Rejected: keep loading flat jars as one unnamed plugin, which keeps two layouts alive for a preview feature.
Files that are not jars, and directories below a plugin directory, are ignored as today.

**D3. A loaded plugin directory with no provider stops the start.** The likely cause is a volume mounted one level
off. Rejected: a warning, for the same reason as D2. A group directory with no plugin directory in it is fine: an
empty `custom` is the normal state of the default image.

**D4. One class loader per plugin directory, parent first.** `load` creates, per loaded plugin directory, a
`URLClassLoader` over its jars with `PluginRegistry.class.getClassLoader()` as parent, and asks
`ServiceLoader.load(PluginProvider.class, loader)`. That also returns the classpath's providers, once per plugin
directory, so `load` keeps from each loader only the providers whose `getClass().getClassLoader()` is that loader, and
loads the classpath's providers once, through the parent. Rejected: one shared loader (today), where the first copy of
a class wins for every plugin; child first, where a plugin could load its own Vert.x or Jackson and hand Core objects
of a class Core does not know. The loaders are never closed, as today.

The check "two jars register the same provider class" stays inside one plugin directory. Across two directories each
loader loads its own copy, and the duplicate-type check catches it; its message now names the two plugin directories.

**D5. `include` and `exclude`, patterns over the id.** `load(Path dir, List<String> include, List<String> exclude)`.
A pattern is `*` or `<group>/<plugin>` with `*` allowed for either part; anything else throws
`IllegalStateException` naming it. Loaded = matches some include and no exclude. Rejected: `exclude` alone, which
cannot express "nothing loads unless listed"; regular expressions or globs inside a name, which nobody asked for. A
pattern that matches no directory is not an error: one values file serves environments with different mounts.

An excluded directory is not listed or opened, so a broken jar in it does not stop the start: exclusion is how an
operator gets a pod up while a plugin is broken.

**D6. A list or a comma-separated string.** `AiDial` reads each setting with a small helper that accepts a
`JsonArray` or a `String` split on commas (blank parts dropped). The string form is for
`aidial.plugins.exclude=builtin/a,custom/b`; whether `JsonObjectHelper.from` can build an array from the environment
is not relied on. Code defaults are `["*"]` and `[]`, also when the key is absent (the test-resources settings file
replaces the built-in one).

**D7. Start log.** One `INFO` line per plugin directory: `Loaded plugin directory builtin/echo: types [jar-echo]`
or `Excluded plugin directory builtin/echo`. The existing per-provider line stays.

**D8. The sample jar goes to `samples/forward`.** `Dockerfile` copies it to `/build/plugins/samples/forward/`.

Differences from today's behaviour, all deliberate: the flat layout fails the start (D2); plugins are isolated from
each other (D4); a plugin directory without a provider fails the start (D3); a jar that registers no provider and
lies in a plugin directory is still fine (a library of that plugin).

## Per-request effect

None. The change runs once at start. No Core pass, body copy, parse, Redis or blob call is added or removed; nothing
to measure.

## Risks / Trade-offs

- A plugin that relied on a class from another plugin's jar stops working → none exists; the docs state the rule.
- More class loaders hold more metaspace when two plugins bundle the same library → accepted; it is the price of the
  isolation, and a plugin should not bundle what Core carries.
- An environment upgraded without moving its jars does not start → the message names the jar and the layout; the
  rollout order in the docs says to move the jars with the upgrade.

## Migration Plan

1. Move each plugin's jars to `<plugins.dir>/<group>/<plugin>/` in the same rollout that brings this Core version.
2. Rollback: the previous Core version reads the flat directory, so roll the layout back with it.

## Open Questions

- None for this change.

## Implementation notes

- **Code drift.** None: since ccc370e47 only 33d8e210a (CI and docs of the plugin API publish) landed, and it does not
  touch `PluginRegistry`, `AiDial.start`, the settings or the `Dockerfile`. The spec delta still starts from the
  current text of "Plugin providers loaded at start".
- **`load(JsonObject settings)`, not `load(Path, List, List)` plus a helper in `AiDial` (D5, D6).** `AiDial.start`
  passes `settings("plugins")`, as it does for `FileConfigStore` (`settings("config")`) and
  `ApplicationOperatorService` (`settings("applications")`). `PluginRegistry` reads `dir`, `include` and `exclude`
  with their code defaults (`plugins`, `*`, none) and parses the array or comma-separated string form, so
  `PluginRegistryTest` tests the string form through the real path, and `AiDial` gains no package-private helper. The
  two tests that loaded only the classpath providers (`ConfigPostProcessorTest`, `PluginManagerTest`) now pass
  `{"dir": "no-plugins-dir"}`.
- **A plugin directory keeps the providers its jars register, by class name, not by class loader (D4).** The test
  jars carry only the registration; the provider classes stay on the test classpath, so under parent-first loading
  Core's class loader defines them and a filter on `getClass().getClassLoader() == loader` would drop every one.
  `listJars` already reads each jar's registrations for the duplicate-class check; `load` keeps, from the plugin
  directory's `ServiceLoader`, the providers whose class name one of those jars registers, and filters before
  creating them, so the classpath's providers are not created again per plugin directory. In production the provider
  classes are in the plugin's jars, and both filters keep the same providers. One edge differs: a jar that registers a
  class of Core's classpath that the classpath also registers now fails the start with a duplicate type naming "the
  classpath" and the plugin directory, where the loader filter would drop it silently.
- **Duplicate type message.** It names both providers with their origin, `plugin directory <group>/<plugin>` or `the
  classpath`: "Plugin providers A of plugin directory builtin/echo and B of plugin directory custom/echo both declare
  type 'jar-echo'".
- **A misplaced jar fails the start whatever `exclude` says.** Exclusion applies to plugin directories; a jar directly
  in `plugins.dir` or in a group directory belongs to none, and the spec rule is unconditional.
- **Pattern check.** A pattern is `*` or `<name>/<name>`, where a name is `*` or has no `/`, `*`, whitespace,
  bracket, quote or comma: `builtin`, `builtin/`, `/echo`, `a/b/c`, `built*/echo`, `built in/echo` and
  `'builtin/echo'` stop the start with a message naming the pattern and the setting. The refused characters come from
  the review: `aidial.plugins.exclude=[builtin/echo]` is not JSON, reaches Core as a string, and with a looser name
  would split into `[builtin/echo` and match nothing, leaving the plugin loaded. Both forms strip each pattern and drop
  blank ones (PR review: the array form first took items as given, unlike the string form); a `null` or number item
  becomes `'null'` or `'5'` and stops the start as malformed.
- **An empty `include` stops the start (PR review).** `aidial.plugins.include=` or `[]` would load no plugin directory
  and log each as excluded, and the plugins' types would then fail later as unknown. Loading none on purpose is
  `plugins.exclude=*`. `testEmptyIncludeFailsTheLoad`.
- **Dot directories and `lost+found` are ignored (added after the review).** A formatted volume mounted at `custom`
  carries `custom/lost+found`, empty or unreadable; as a plugin directory it would stop the start ("registers no
  plugin provider" or a listing error), and at the top level no `exclude` could skip it, since a group is listed before
  any pattern applies. `listPluginDirs` skips a group or plugin directory whose name starts with `.` or is
  `lost+found`; the spec delta and the docs' Layout section say so, and `testDotAndLostAndFoundDirectoriesAreIgnored`
  covers it.
- **Isolation test.** "Two plugins with their own copy of a class" needs classes that are not on the test classpath,
  since a class there always wins under parent-first loading. `PluginRegistryTest.writeIsolatedPlugin` compiles two
  small classes per plugin directory at test time with the JDK compiler (`javax.tools.ToolProvider`) and packs them
  into the jar; `JarEchoPluginProvider.writeJar` stays as it is.
- **Test names.** Checkstyle's abbreviation rule refuses "APlugin" and "AMounted": the tests are
  `testJarOutsidePluginDirectoryFailsTheLoad` and `testExcludedBuiltInIsReplacedByMountedBuild`. One test beyond the
  tasks, `testCommaSeparatedPatternsAreEachApplied`, covers a string of several patterns with spaces and a trailing
  comma.
