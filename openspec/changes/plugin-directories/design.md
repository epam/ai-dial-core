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
