# Review: plugin-directories

## Implementation

Tasks done: 17. Deferred: 3.5 (build the image with `--build-arg SAMPLE_PLUGINS=true`, start it and check the log line `Loaded plugin directory samples/forward`); needs Docker and the GitHub Packages credentials.

Files changed: `PluginRegistry.java`, `AiDial.java`, `aidial.settings.json`, `PluginRegistryTest.java`, `ConfigPluginApiTest.java`, `ConfigPostProcessorTest.java`, `PluginManagerTest.java`, `Dockerfile`, `docs/dynamic-settings/plugins.md`, `README.md`, `CLAUDE.md`, and this change's `design.md` and `tasks.md`.

Tests added (spec scenario in brackets):

- `PluginRegistryTest.testJarInThePluginDirectoryIsLoaded` (renamed, moved to `custom/echo`) [Jar in the directory]
- `PluginRegistryTest.testDuplicateTypeFailsTheLoadNamingBothDirectories` [Duplicate provider type]
- `PluginRegistryTest.testJarOutsidePluginDirectoryFailsTheLoad` (root and group dir) [Jar outside a plugin directory]
- `PluginRegistryTest.testPluginDirectoryWithoutProviderFailsTheLoad` [Plugin directory without a provider]
- `PluginRegistryTest.testEachPluginDirectoryLoadsItsOwnClasses` (compiles classes at test time with javax.tools) [Two plugins with their own copy of a class]
- `PluginRegistryTest.testDefaultLoadsEveryPluginDirectory` [Default loads everything]
- `PluginRegistryTest.testExcludedGroupIsNotLoaded` (string form) [Excluded group]
- `PluginRegistryTest.testCommaSeparatedPatternsAreEachApplied` (several patterns in one string)
- `PluginRegistryTest.testIncludeListLimitsTheLoad` [Include list]
- `PluginRegistryTest.testExcludedBuiltInIsReplacedByMountedBuild` [Built-in replaced by a mounted build]
- `PluginRegistryTest.testExcludedBrokenPluginDirectoryIsNotOpened` [Excluded broken plugin directory]
- `PluginRegistryTest.testMalformedPatternFailsTheLoad` [Malformed pattern]
- `ConfigPluginApiTest$ExcludedPlugin.testExcludedPluginTypeIsUnknown` (integration) [Excluded plugin directory]

Design drift (recorded in design.md, "Implementation notes"). No code drift since ccc370e47; only 33d8e210a (CI and docs) landed, and the MODIFIED requirement still validates against the current text.

1. Signature is `PluginRegistry.load(JsonObject settings)`, not `load(Path, List, List)` plus a helper in AiDial. AiDial passes `settings("plugins")`. Code defaults and array-or-comma-string parsing live in PluginRegistry, so the string form is tested through the real path. ConfigPostProcessorTest and PluginManagerTest pass `{"dir":"no-plugins-dir"}`.
2. D4: each plugin directory keeps the providers whose class names its own jars register, not those its class loader defined (test jars carry only the registration). Same production behaviour except one edge: a jar registering a classpath provider now fails with a duplicate type instead of being dropped silently.
3. The duplicate-type message names each provider with its origin (plugin directory or "the classpath").
4. A misplaced jar fails the start whatever `exclude` says.
5. Two test names shortened for the checkstyle abbreviation rule; tasks.md uses them.

## Verification

Implementation checks: checkstyleMain/checkstyleTest PASS (JDK 21.0.8); PluginRegistryTest 17/17, PluginManagerTest 15/15, ConfigPostProcessorTest 90/90, `ConfigPluginApiTest*` 10/10 + SkipMode 3/3 + ExcludedPlugin 1/1; `openspec validate plugin-directories --strict` valid; git status shows only the 13 modified tracked files.

First verify: passed, no failures.

- checkstyleMain checkstyleTest (jdk-21.0.8.9-hotspot): BUILD SUCCESSFUL, no warnings
- PluginRegistryTest: 17 tests, 0 failures
- ConfigPluginApiTest: 10 passed; the plain class filter skips nested classes, so a second run used `--tests 'com.epam.aidial.core.server.ConfigPluginApiTest*'`: ExcludedPlugin 1 passed, SkipMode 3 passed
- ConfigPostProcessorTest: 90, 0 failures
- PluginManagerTest: 15, 0 failures
- openspec validate plugin-directories --strict: valid
- No untracked files; port 16370 free before the integration run

Cost-claim check: design.md says no per-request effect. Confirmed. Main-code changes are the one-time startup call in `AiDial.start` (same place, before the config stores), `PluginRegistry.load` and helpers (startup-only file I/O, one URLClassLoader per plugin directory, ServiceLoader filtering, pattern matching, log lines), and the `include ["*"]` / `exclude []` defaults. No Future chain, event-loop call, body parse/copy, Redis or blob call added. `PluginRegistry.get(type)` is unchanged (immutable map lookup).

Final verify: passed, same test set and results as the first verify; cost claim confirmed again.

## Review findings

| Lens | Severity | File:line | Finding |
| --- | --- | --- | --- |
| conformance | low | server/src/test/java/com/epam/aidial/core/server/plugin/PluginRegistryTest.java:119 | "Core's copy of a class winning" (parent-first loader, PluginRegistry.java:83) has no test that fails without it; no test jar carries a copy of a class Core also has. A child-first loader would pass all tests. Fix: a jar carrying a class already on the test classpath and an assertion that the classpath class wins. |
| correctness | low | server/src/main/java/com/epam/aidial/core/server/plugin/PluginRegistry.java:44 | The pattern accepts any character but `/` and `*` in a name, so `[builtin/echo]`, quoted items or items with stray spaces pass the malformed check, match nothing, and the exclusion silently does nothing. Fix: refuse whitespace, brackets, quotes and commas in names, or WARN on patterns that match no directory. |
| correctness | low | server/src/main/java/com/epam/aidial/core/server/plugin/PluginRegistry.java:159 | Every group subdirectory is a plugin directory, including `lost+found` and dot-directories; a volume mounted over `custom` (or as `plugins.dir`) can stop the start with AccessDenied or "registers no plugin provider", and at the root level no setting can skip it. Fix: skip dot-names and `lost+found`, document it. |
| simplicity | low | server/src/test/java/com/epam/aidial/core/server/ConfigPluginApiTest.java:216 | `ExcludedPlugin` starts a third full Core stack to check two facts already covered (exclude drops the type: PluginRegistryTest; unknown type gives 422 on `type`: testStrictWrite). Fix: delete it and point the scenario at those tests, or keep it if every scenario must be proved over HTTP. |

## Fix round 1

No fix round ran: all findings are low severity. Fixed: none. Rejected: none. Still open: the four low findings above.

## Low findings triaged after the run

- **Lenient pattern names: fixed.** A name in a pattern now refuses whitespace, brackets, quotes and commas, so `aidial.plugins.exclude=[builtin/echo]` (not JSON, so it arrives as a string) and `" builtin/echo"` stop the start instead of matching nothing. `testMalformedPatternFailsTheLoad` covers the array and the string forms.
- **`lost+found` and dot-directories: fixed.** `listPluginDirs` skips a group or plugin directory whose name starts with `.` or is `lost+found`, so a formatted volume mounted at `custom` (or as `plugins.dir`) no longer stops the start. Spec delta: one sentence and the scenario "Filesystem directories ignored"; docs Layout section; `testDotAndLostAndFoundDirectoriesAreIgnored`.
- **Parent-first loading untested: left.** `URLClassLoader` delegates to its parent first by JDK contract; a test would guard only against someone replacing it with a child-first loader, and the code comment at the loader states why it is parent-first.
- **`ExcludedPlugin` integration test: kept.** Task 2.2 asks for the scenario over HTTP, and it is the one test that sees `include`/`exclude` pass from `AiDial` into the registry. The cost is one more Core start in CI.

Checks after these fixes (JDK 21.0.8): `checkstyleMain checkstyleTest` pass; `PluginRegistryTest` 18/18; `ConfigPluginApiTest` 10/10, `$SkipMode` 3/3, `$ExcludedPlugin` 1/1; `openspec validate plugin-directories --strict` valid.

## Archive

`validate --specs --strict` after the archive warned that "Plugin providers loaded at start" ran over 500 characters (the change-level validate does not check this). The requirement is now three: "Plugin providers loaded at start" (location, layout, ignored directories), "Plugin directories loaded apart" (class loader isolation) and "Plugin load errors stop the start" (the failure list), each with its scenarios; the archived delta was changed to match (one MODIFIED, three ADDED). `plugin-registry` validates. `resource-subscription` still fails `--specs --strict` with its "TBD" Purpose, from #2202 on development, outside this change.

## PR review round 1

Six low findings, inline on the PR.

- **Empty `include` loads nothing: fixed.** `aidial.plugins.include=` or `[]` now stops the start naming `plugins.include`; `exclude=*` loads none on purpose. Spec scenario "Empty include", docs, `testEmptyIncludeFailsTheLoad`.
- **Array items not stripped, unlike the string form: fixed.** Both forms go through one strip-and-drop-blank step; a `null` or number item stops the start as `'null'` or `'5'`. `testIncludeListLimitsTheLoad` (array with spaces and a blank item), `testMalformedPatternFailsTheLoad` (null item).
- **Filter providers by class loader instead of registered class name: kept.** Design D4 note: a loader filter would drop a jar that registers a classpath class without a word and then report "registers no plugin provider"; the name filter names the clash. The test jars also rely on it.
- **`isJar` without `Files.isRegularFile`: kept.** `refuseJar` is only called on entries that are not directories. In a plugin directory a `*.jar` directory is an exploded jar; skipping it would start Core without that code, while the current error names it.
- **`matchesAny` five-element list: kept.** Two lines at start time; splitting each pattern into parts is longer.
- **Class loader created for a directory with no jars: kept.** The error is already "registers no plugin provider" (`testPluginDirectoryWithoutProviderFailsTheLoad`); moving the check changes nothing a user sees.

Checks (JDK 21.0.8): `checkstyleMain checkstyleTest` pass; `PluginRegistryTest` 19/19; `ConfigPluginApiTest` 10/10, `$SkipMode` 3/3, `$ExcludedPlugin` 1/1; `validate plugin-registry --type spec --strict` valid.

## PR review round 2

- **Class loader for a directory with no jars (follow-up: "needed if the setup doesn't have any jars"): changed.** A setup without plugin jars never created a loader: the default image has no `plugins` directory, and with no plugin directory the loop does not run. A loader was created only for an existing plugin directory without jars, just before the start failed. The "registers no plugin provider" check now runs on the jars' registrations before the loader is built (same message, same behaviour); the check on the loaded types after it went, since each registered class is created or fails the load. `testPluginDirectoryWithoutProviderFailsTheLoad` now also covers a directory holding only a library jar.

Checks (JDK 21.0.8): `checkstyleMain checkstyleTest` pass; `PluginRegistryTest` 19/19.

## Outcome

High/medium findings open: none.

Low findings left: two, by choice (parent-first loading untested; `ExcludedPlugin` kept), reasons above.

CI must still confirm: the full server test suite (only targeted classes ran locally), and the Docker image build with sample plugins plus the `Loaded plugin directory samples/forward` log line (task 3.5, deferred).
