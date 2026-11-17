## Context

Today (`development` at 33d8e210a, which includes PR #2295):

- `aidial-core-plugin-api` holds `PluginProvider`, `Plugin`, `HostDelegate`, `Request`, `Response`, `Call`, `Caller`.
  Its public signatures use Jackson (`ObjectNode`) and Vert.x (`Vertx`, `Future`, `Buffer`, `ReadStream`) types, which
  are `api` dependencies. The module is published with Core's version.
- `PluginRegistry.java:load` takes every provider `ServiceLoader` yields, checks `type()` for blank and duplicates,
  and wraps `ServiceConfigurationError` in an `IllegalStateException`. It checks nothing about the API the provider
  was compiled against.
- `docs/dynamic-settings/plugins.md` says a plugin jar is built for one DIAL Core version.
- Providers in the repo: `ForwardOriginPluginProvider` (plugin-samples), `JarEchoPluginProvider`,
  `TestEchoPluginProvider`, `TestOriginPluginProvider` (server tests).

## Goals / Non-Goals

**Goals:**

- A plugin built against an API Core cannot serve is refused at start, not on a request.
- A plugin built against an older Core keeps loading on a newer Core as long as the API it uses is unchanged, so the
  default Core image can carry a plugin released before that Core version.
- Nobody can change the public API without the level moving.

**Non-Goals:**

- A second version line for the artifact; checking a plugin's bundled libraries; the directory layout.

## Decisions

**D1. One integer, not major and minor.** `PluginApi.LEVEL` (a `public static final int`, starting at 1) is raised by
one on every change of the public API, additive or breaking. Core serves the levels from
`PluginRegistry.OLDEST_API_LEVEL` to `PluginApi.LEVEL`. An additive change raises `LEVEL` only; a breaking change
also sets `OLDEST_API_LEVEL` to the new `LEVEL`. This is the same information as "same major, minor not above
Core's" with one number to compare and print. Rejected: a semantic version of the artifact apart from Core's, which
needs the release to skip publishing an unchanged version (GitHub Packages refuses to republish) and a version the
shared release workflow does not set.

`OLDEST_API_LEVEL` lives in `server`, beside the check: it is a promise of the host, not part of the contract a
plugin compiles against.

**D2. The provider states its level through an abstract method.** `PluginProvider.apiLevel()` has no default body;
its Javadoc tells authors to write `return PluginApi.LEVEL;`. `LEVEL` is a compile-time constant, so `javac` copies
its value into the provider's class file: at run time the method returns the level of the API jar the plugin was
compiled against, whatever API jar Core carries. Rejected: a default method (it would return Core's level); a jar
manifest attribute (needs build configuration in every plugin repository and is easy to leave out). The Javadoc and
the docs say not to write a literal number.

**D3. The check.** In `PluginRegistry.load`, for each provider before `type()` is read:

- `apiLevel()` outside `[OLDEST_API_LEVEL, PluginApi.LEVEL]` throws `IllegalStateException`:
  `Plugin provider <class> was built against plugin API level <n>; this DIAL Core serves levels <oldest> to <level>`.
- A provider compiled before the method existed throws `AbstractMethodError` on the call. `load` catches
  `LinkageError` around the call and throws `Plugin provider <class> was built against a plugin API without a level;
  rebuild it against this DIAL Core's plugin API`.

Classpath providers go through the same check; they are compiled with Core and always pass.

**D4. The guard is a test over checked-in signature files.** A test in `aidial-core-plugin-api` writes the public
API as sorted text: for every public type of `com.epam.aidial.core.plugin`, its kind, name, supertypes, public and
protected methods, fields and record components with their generic signatures, plus one line each for the major
version of Vert.x and of Jackson on the classpath. It compares the text with
`aidial-core-plugin-api/src/test/resources/api/level-<LEVEL>.txt`. A change of the API therefore fails the test until
`LEVEL` is raised and a new file is added; the files of older levels stay as the record of what each level was.
Editing the file of an existing level instead is visible in review as exactly that. The failure message says what to
do. Rejected: comparing against the last published jar (the PR build would need registry credentials and a published
baseline); the `binary-compatibility-validator` Gradle plugin (a new build dependency, and it does not tie the dump
to the level).

The Vert.x and Jackson lines make a major upgrade of either a level change. That upgrade also sets
`OLDEST_API_LEVEL`, by review: the test cannot tell additive from breaking.

**D5. The test providers.** `JarEchoPluginProvider` gains nested providers stating `PluginApi.LEVEL + 1` and
`OLDEST_API_LEVEL - 1` for the two out-of-range scenarios. For "built before levels existed" the test writes a jar
with a provider class that lacks the method. `JarEchoPluginProvider.writeJar` copies class files compiled with the
tests, where every provider must have the method, so the test compiles a small source with
`javax.tools.JavaCompiler` against a stub interface without `apiLevel()`. If that proves heavy, the scenario is
covered by a unit test that calls the check with a provider throwing `AbstractMethodError`; record which in
`review.md`.

Differences from today's behaviour, all deliberate: a provider without `apiLevel()` is refused; nothing else changes
for a provider built with this Core.

## Per-request effect

None. The check runs once per provider at start. No Core pass, body copy, parse, Redis or blob call is added or
removed; nothing to measure.

## Risks / Trade-offs

- An author writes `return 1;` → the jar claims a level it may not have; the Javadoc and docs forbid it, and the
  sample shows the right form.
- A breaking change is merged with `LEVEL` raised but `OLDEST_API_LEVEL` left → old plugins load and fail on a
  request, as today; the PR template line in the docs task and review are the mitigation.
- While plugins are in preview, breaking changes are frequent, so `OLDEST_API_LEVEL` will often equal `LEVEL` and a
  plugin must be rebuilt for most Core versions → accepted; the refusal is now a clear message at start.

## Migration Plan

1. Rebuild every plugin against this Core's API with `apiLevel()` implemented; a jar built earlier stops the start.
2. Roll the plugin and Core out together, as the docs already require for plugin code.
3. Rollback: the previous Core ignores `apiLevel()`, so a rebuilt plugin still loads there if the rest of the API it
   uses is unchanged.

## Open Questions

- None. Decided: Core shows the levels it serves in one start log line only. An endpoint with the levels is not
  built until someone asks for it.
