## 1. Plugin directories and class loaders

- [x] 1.1 `PluginRegistryTest`: move the existing cases to the layout `<dir>/<group>/<plugin>/`
      (`testJarInThePluginDirectoryIsLoaded` = "Jar in the directory", `testMissingDirectoryLoadsOnlyTheClasspathProviders`
      = "Missing directory", `testSameProviderInTwoJarsFailsTheLoad` = "Same provider class in two jars")
- [x] 1.2 `PluginRegistryTest.testDuplicateTypeFailsTheLoadNamingBothDirectories` ("Duplicate provider type")
- [x] 1.3 `PluginRegistryTest.testJarOutsidePluginDirectoryFailsTheLoad`, for the root and for a group directory
      ("Jar outside a plugin directory")
- [x] 1.4 `PluginRegistryTest.testPluginDirectoryWithoutProviderFailsTheLoad` ("Plugin directory without a provider")
- [x] 1.5 `PluginRegistryTest.testEachPluginDirectoryLoadsItsOwnClasses`: two directories, each with a provider and a
      class of the same name ("Two plugins with their own copy of a class"); extend `JarEchoPluginProvider.writeJar`
      as needed
- [x] 1.6 `PluginRegistry.load`: list `<group>/<plugin>` directories, refuse a misplaced jar, one `URLClassLoader` per
      plugin directory keeping only the providers that loader defined, classpath providers once, refuse a directory
      without a provider, name the directories in the duplicate-type message (design D1 to D4)
- [x] 1.7 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "com.epam.aidial.core.server.plugin.PluginRegistryTest"`

## 2. Include and exclude

- [x] 2.1 `PluginRegistryTest`: `testDefaultLoadsEveryPluginDirectory` ("Default loads everything"),
      `testExcludedGroupIsNotLoaded` ("Excluded group"), `testIncludeListLimitsTheLoad` ("Include list"),
      `testExcludedBuiltInIsReplacedByMountedBuild` ("Built-in replaced by a mounted build"),
      `testExcludedBrokenPluginDirectoryIsNotOpened` ("Excluded broken plugin directory"),
      `testMalformedPatternFailsTheLoad` ("Malformed pattern")
- [x] 2.2 `ConfigPluginApiTest`: put the jar-echo jar in a plugin directory; add
      `testExcludedPluginTypeIsUnknown` with `plugins.exclude` set ("Excluded plugin directory")
- [x] 2.3 `PluginRegistry.load(dir, include, exclude)` with the pattern match and the start log lines (design D5, D7)
- [x] 2.4 `AiDial.start`: read `plugins.include` and `plugins.exclude` as an array or a comma-separated string, with
      code defaults `["*"]` and `[]`; add both to the built-in `aidial.settings.json` (design D6). Test the string
      form in `PluginRegistryTest` or a unit test of the helper
- [x] 2.5 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "com.epam.aidial.core.server.plugin.PluginRegistryTest" --tests "com.epam.aidial.core.server.ConfigPluginApiTest"`

## 3. Image and docs

- [x] 3.1 `Dockerfile`: `SAMPLE_PLUGINS=true` copies the sample jar to `/build/plugins/samples/forward/`
- [x] 3.2 `docs/dynamic-settings/plugins.md`: "Plugin code" (the layout, `builtin` and `custom`, one mount per
      plugin under `custom`, isolation and "Core's classes win", the start failures, include and exclude with the
      environment form, replacing a built-in plugin with a mounted build) and "The forward sample" (the new path)
- [x] 3.3 `README.md`: the `plugins.dir` row, new rows `plugins.include` and `plugins.exclude`
- [x] 3.4 `CLAUDE.md`: the `plugin-samples` row (the new path)
- [ ] 3.5 Build the image with `--build-arg SAMPLE_PLUGINS=true`, start it and check the log line
      `Loaded plugin directory samples/forward` (deferred: needs Docker and the GitHub Packages credentials)
- [x] 3.6 `./gradlew checkstyleMain checkstyleTest` and
      `npx -y @fission-ai/openspec@1.14.3 validate plugin-directories --strict`
