# Tasks

## 1. Class loader and list

- [ ] 1.1 Compare `design.md` with the code on `development`; on a drift, correct the decision or the Code map entry
      in place
- [ ] 1.2 Test fixtures: `JarEchoPluginProvider.writeJar` copies the provider's class files into the jar; adapt
      `PluginRegistryTest` and `ConfigPluginApiTest` (an instance of a plugin's class is no instance of the test
      class path's); verify: both classes pass with the old loader still in place
- [ ] 1.3 Tests first, in `PluginRegistryTest`: "Two plugins with their own copy of a class"
      (`testEachPluginDirectoryLoadsItsOwnClasses`), "Plugin's copy of a library Core also carries"
      (`testPluginsOwnCopyOfACoreLibraryWins`), "Shared package comes from Core"
      (`testSharedPackageClassIsCores`), "Subpackage of the plugin API package is the plugin's"
      (`testSubpackageOfThePluginApiPackageIsThePlugins`), "Core class outside the shared packages is not visible"
      (`testCoreClassOutsideSharedPackagesFailsTheLoad`); a test that a shared-package class in a jar logs a warning
      naming the jar
- [ ] 1.4 D3: `SharedPackages` in `aidial-core-plugin-api` (list and `isShared`, with a unit test in that module),
      and its `api` dependencies on SLF4J and the OpenTelemetry API in `build.gradle`
- [ ] 1.5 D1, D4: `PluginClassLoader` in the server's `plugin` package; `PluginRegistry.load` uses it and drops the
      filter on providers of the parent
- [ ] 1.6 D5: the entry scan and warning in `PluginRegistry.listJars`
- [ ] 1.7 `plugin-samples/build.gradle` comment; verify the sample still loads (`ConfigPluginApiTest` or a test
      that loads the built sample jar)
- [ ] 1.8 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "*PluginRegistryTest" --tests "*ConfigPluginApiTest" :aidial-core-plugin-api:test`

## 2. Docs

- [ ] 2.1 `docs/dynamic-settings/plugins.md`: replace the paragraph "Each plugin directory gets its own class loader"
      with the rule and the table of shared packages; add the migration steps, the thread context class loader note
      and the bundle-everything-else guidance to "Building a plugin"; a test that the packages in the doc equal
      `SharedPackages`
- [ ] 2.2 `CLAUDE.md`: the `aidial-core-plugin-api` row says the module also holds the shared-package list
- [ ] 2.3 If #2383 has merged: remove its `kotlin-reflect` component rule in `build.gradle`, `KotlinRuntimeTest`
      and its Kotlin paragraph in `plugins.md`; verify the build resolves no `kotlin-reflect` for `server`
- [ ] 2.4 `npx -y @fission-ai/openspec@1.14.3 validate plugin-shared-packages --strict`

## 3. Checks that need more than this machine

- [ ] 3.1 (deferred: needs Docker) build the image with `--build-arg SAMPLE_PLUGINS=true` and call the `forward`
      sample
- [ ] 3.2 (deferred: needs ai-dial-model-provider-plugin and its upstream credentials) run its end-to-end tests
      against an image built from this branch, with `kotlin-reflect` off Core's class path
