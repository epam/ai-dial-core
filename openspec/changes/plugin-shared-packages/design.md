# Design

## Context

Core finds plugin providers once at start. Each plugin directory gets a `URLClassLoader` over its jars whose parent
is Core's application class loader, asked first for every class. A class is therefore Core's if it is anywhere on
Core's class path, and the plugin's jars are read only for the rest. That set comes from Core's build: the Kotlin
stdlib is there only because okio brings it. The plugin API declares only `jackson-databind` and `vertx-core` as
`api` dependencies, so the contract does not say what a plugin shares. A library that Core carries in part, such as
the Kotlin stdlib without `kotlin-reflect`, is split between the two loaders: the part in Core's loader looks up the
rest by name from Core's loader, which cannot see a plugin's jar.

## Decisions

### D1. The plugin loader delegates to Core by an explicit list, and to the JDK, and to nothing else

A new `PluginClassLoader` extends `URLClassLoader` with the platform class loader as its parent (the JDK modules). In
`loadClass`, a name that `SharedPackages.isShared` accepts is loaded by Core's class loader and never from the jars;
any other name is looked up in the JDK, then in the plugin's jars, and is not found when neither has it. Resources
(`getResource`, the `META-INF/services` files) come from the plugin's jars and the JDK only, so a provider lookup
through the loader sees only the plugin's registrations.

**Rejected: keep the parent-first loader and add the missing library to Core (#2383)** - it fixes one library family;
the next library Core carries in part fails the same way, and the version of every other shared library is still an
accident of Core's build.
**Rejected: child-first loading with Core as the fallback** - a plugin that forgot a library would work until Core
drops it, and the Kotlin split remains for any class the plugin does not bundle.

### D2. What is shared is what crosses the plugin boundary, as packages

| Shared | Why |
| --- | --- |
| `com.epam.aidial.core.plugin` (this package only) | the contract; the sample's `com.epam.aidial.core.plugin.samples` is a plugin's own |
| `io.vertx.core.**` | `Future`, `Buffer`, `ReadStream` and `Vertx` appear in the API |
| `io.netty.**` | `Buffer` wraps a Netty `ByteBuf`; Netty's modules look up each other by name, so the family is not split |
| `com.fasterxml.jackson.core.**`, `.databind.**`, `.annotation.**` | `JsonNode` and `ObjectNode` appear in the API; the modules of Jackson (Kotlin, JSR-310) are the plugin's and run on the shared databind |
| `org.slf4j.**` | a plugin logs through Core's binding, so its lines get Core's format and level |
| `io.opentelemetry.api.**`, `io.opentelemetry.context.**` | the current span and context live in Core's copy; a second copy loses the trace |

The packages of other Vert.x modules (`io.vertx.ext.web`) are not shared: a plugin that needs one bundles it and it
runs on the shared `io.vertx.core`.

**Rejected: the whole Core class path (today)** - see D1.
**Rejected: the plugin API package alone** - `Future` and `Buffer` would exist twice, and the answer of a plugin
could not be read by Core.
**Rejected: all of `io.vertx` and `com.fasterxml.jackson`** - a plugin could not carry a newer module of its own,
and Core's choice of extra modules would again decide what a plugin may bundle.

### D3. The list is published as `SharedPackages` in the plugin API

`SharedPackages` in `aidial-core-plugin-api` holds the list and `isShared(String className)`. Core's loader reads
it, a test checks `docs/dynamic-settings/plugins.md` against it, and it is versioned with the contract. The module
adds SLF4J and the OpenTelemetry API as `api` dependencies, at Core's versions, so the compile class path of a
plugin has what the list names.

**Rejected: a list in Core's server module only** - a plugin author would again learn it from the code or the image.
**Rejected: a resource file in the API jar** - nothing reads it but Core, and the plugin author reads the docs.

### D4. No fallback to Core's class path, and no setting to widen the list

A class outside the list that the plugin's jars do not hold is not found, and the plugin fails where it first needs
it (a provider class the loader cannot define stops the start, naming the plugin directory). Plugins are a preview
feature, so the change is breaking in one step.

**Rejected: a setting `plugins.shared` with extra packages** - it brings back a per-installation answer to what
is shared, and the next plugin is written against one installation's list.
**Rejected: a compatibility mode that is parent-first for one release** - two behaviours to test, and the plugin
that needs it is the one that fails in Kotlin.

### D5. A shared class bundled in a plugin jar is ignored, with a warning at start

When Core opens a jar it already opens (`PluginRegistry.listJars`), it lists the entries. A `.class` of a shared
package, such as a Jackson or SLF4J copy in a fat jar, gets one `WARN` per jar naming the jar and the packages. The
copy is not used: the class is Core's, as it is today.

**Rejected: fail the start** - a shaded jar is the usual output of a build and works today.
**Rejected: stay silent** - the plugin author would test against a copy that does not run in Core.

## Differences from today

| What | Today | After | Requirement |
| --- | --- | --- | --- |
| A library the plugin bundles and Core carries, outside the list | Core's copy | the plugin's copy | Plugin directories loaded apart |
| A Core class outside the list that the plugin uses without bundling | found | not found, the plugin fails | Plugin directories loaded apart |
| A shared-package class a plugin jar bundles | Core's copy, silently | Core's copy, with a start warning | Plugin directories loaded apart |
| Providers a plugin's loader finds by `META-INF/services` | the plugin's and Core's, then filtered | the plugin's only | none |

## Effect per request

None: classes load once, on first use; a name check by prefix runs per class load, never per request. A plugin that
bundles a library holds its own copy in memory, a few MB per library.

## Risks / Trade-offs

- A plugin that relies on Core's copy of a library fails until it bundles it. The failure is a start error for a
  provider class and a `NoClassDefFoundError` at first use otherwise. The docs name the rule and list the shared
  packages; the release notes name the break.
- The thread context class loader of Core's threads is Core's application loader. Plugin code or a library that
  loads by it (`ServiceLoader.load(X.class)` with no loader, `Class.forName` through it) sees Core's class path, not
  the plugin's jars. Setting it around a plugin call does not hold: answers continue on other threads. The docs tell
  authors to pass the plugin's loader (`getClass().getClassLoader()`). Out of scope.
- A shared family still has one version, Core's. A plugin built against a newer Jackson than Core's can fail; the API
  version it compiles against pins the versions it was built with.
- A Core upgrade of a shared family (Jackson, Netty, Vert.x) can break a plugin as before; the list now says which.

## Migration Plan

Per plugin: bundle every library it uses outside the shared packages (a fat jar, or the libraries' jars beside the
plugin's in its directory), and drop any copy of a shared package. Roll the plugin and the Core version together; a
rollback of Core alone does not restore a plugin that bundles its libraries and would also run on the old Core.

## Code map

- `PluginRegistry.java:load`: builds the loader (`new URLClassLoader(..., parent)`) and filters the providers its
  parent finds; the filter goes with the parent.
- `PluginRegistry.java:listJars`: opens every jar once; the entry scan for D5 goes here.
- `PluginProvider.java`, `Plugin.java` in `aidial-core-plugin-api`: the packages the list covers; new
  `SharedPackages.java` next to them; `aidial-core-plugin-api/build.gradle` for the two `api` dependencies.
- `plugin-samples/build.gradle`: its comment says Core's loader provides the libraries.
- `docs/dynamic-settings/plugins.md`: "Plugin code", "Layout" (the paragraph on one loader per plugin directory) and
  "Building a plugin".
- Tests: `PluginRegistryTest.writeIsolatedPlugin` compiles and jars classes; `JarEchoPluginProvider.writeJar` writes
  only the registration and relies on the class on the test class path, which the plugin loader no longer sees, so
  it must copy the class files into the jar. `ConfigPluginApiTest` also uses it.
