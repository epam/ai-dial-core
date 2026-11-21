# Tasks

## 1. `pluginId` and the deprecated `type` alias

- [ ] 1.1 Write the tests first. `config/.../PluginTest.java`: `pluginId` binds; `type` binds as `pluginId`; both,
  neither and blank are refused; the serialized object has `pluginId` and no `type`. `FileConfigStoreTest`: Overlay
  changes the plugin (two files in the list, the first with `type: test-echo` and settings, the second with
  `pluginId: test-origin`). `PluginRegistryTest`: a provider that overrides only `type()` loads under that id. Write it as a new
  top-level `LegacyTypePluginProvider implements PluginProvider` returning `jar-legacy` from `type()` only, not as a
  subclass of `JarEchoPluginProvider` (which 1.4 moves to `id()`, so a subclass would never reach the fallback).
  `NoType` still fails the load, with a message saying "id". Verify that they fail against today's code, except the
  `LegacyTypePluginProvider` test, which passes today and must keep passing.
- [ ] 1.2 `aidial-core-plugin-api` `PluginProvider`: `default String id()` returning `type()`, and `type()` as a
  `@Deprecated(forRemoval = true)` default returning null, with javadoc on both (D3). Verify with `./gradlew
  :aidial-core-plugin-api:build -x test`.
- [ ] 1.3 `config` `Plugin`: field `pluginId`, and a creator taking `pluginId` and `type`, refusing both, neither or
  blank (D2). Verify with `./gradlew :config:test --tests "com.epam.aidial.core.config.PluginTest"`.
- [ ] 1.4 Server: `PluginRegistry` keys providers by `id()`, with messages saying "id" (duplicate, blank);
  `ConfigPostProcessor.validatePlugin` looks up `getPluginId()` and reports an unknown one under field `pluginId`;
  `ConfigApplyService.applyPlugin` and `PluginManager.create` look up `getPluginId()`;
  `ConfigResourceController.projectInvalidItem` renames `type` to `pluginId` in a plugin payload that has `type`
  only; `FileConfigStore.loadConfig` reads each file into its own tree, renames `type` to `pluginId` in its `plugins`
  entries that have no `pluginId`, then merges (D2). Move the test providers to `id()`: `TestEchoPluginProvider`, `TestOriginPluginProvider`,
  `JarEchoPluginProvider` and its subclasses, `PluginRegistryTest.OtherPluginProvider`, the `IsolatedProvider` source
  string in `PluginRegistryTest` and the anonymous provider in `ConfigPostProcessorTest.registryOf`; keep
  `LegacyTypePluginProvider` on `type()`. Move the `forward` sample to `id()`. Verify with `PluginRegistryTest`,
  `PluginManagerTest`, `ConfigPostProcessorTest`, `FileConfigStoreTest` and `ForwardOriginPluginProviderTest`.
- [ ] 1.5 Move the test fixtures and assertions from `type` to `pluginId`: `server/src/test/resources/aidial.config.json`,
  `dial-config/router-decision-embedded.json`, and the tests listed in design.md's Code map. `file-plugin` moves to
  `pluginId`; add a file entry `legacy-plugin` with `type` to `aidial.config.json` for "File entry with the alias". Verify with each of those test classes.
- [ ] 1.6 Restated scenarios, each in its test, using `pluginId`. Plugin entity shape (Minimal declaration, Missing
  type, Unknown field), Jar in the directory, Unknown plugin id (Strict write, Soft write under skip, Rebuild under
  abort keeps the entity, Rebuild under skip drops the entity), Rejected settings on write, File plugin inspected and
  Excluded plugin directory: `ConfigPluginApiTest` and its nested `SkipMode` and `ExcludedPlugin`. Unknown type in a
  soft batch: `AdminApplyApiTest`. Rejected settings in a batch: `AdminValidateApiTest`. Provider failure and
  Rejected settings on a rebuild under skip: `ConfigPluginApiTest.SkipMode`. File plugin migrated:
  `ConfigFileMigrateApiTest`. Settings change takes effect, Entry removed and Closing a plugin at once:
  `EmbeddedOriginApiTest` and `PluginManagerTest`. Loading, load errors and included/excluded: `PluginRegistryTest`.
  Verify that all of these pass.
- [ ] 1.7 Deprecated plugin type alias scenarios. Written with the alias (also read the blob and see `pluginId`, no
  `type`), Both names, Unknown id written with the alias, Stored blob with the alias (write the blob directly, then
  reload) and File entry with the alias: `ConfigPluginApiTest`. Overlay changes the plugin: `FileConfigStoreTest`.
  Invalid blob with the alias: `ConfigPluginApiTest.SkipMode`. Manifest with the alias: `AdminApplyApiTest`. Verify
  that they pass.
- [ ] 1.8 `docs/dynamic-settings/plugins.md`: `pluginId` in the field list, the example and "How an entry is
  checked"; `type` as its deprecated alias, a body with both refused, and an overlay's `pluginId` replacing a base
  file's `type`; "plugin code" sections say id. `CLAUDE.md`: the `aidial-core-plugin-api` row
  of the module table says `pluginId` and `id()`. Verify by reading both against the spec delta.
- [ ] 1.9 Run `./gradlew checkstyleMain checkstyleTest` and the touched classes: `:config:test --tests
  "com.epam.aidial.core.config.PluginTest"`; `:plugin-samples:test`; `:server:test --tests` for `PluginRegistryTest`,
  `PluginManagerTest`, `ConfigPostProcessorTest`, `ConfigPluginApiTest`, `AdminApplyApiTest`,
  `AdminValidateApiTest`, `ConfigFileMigrateApiTest`, `EmbeddedOriginApiTest`, `EmbeddedCallApiTest`,
  `MergedConfigStoreApiTest`, `MergedConfigStorePartialUpdateTest` and `MergedConfigStoreReplicaUpdateTest`. Verify
  that every command exits 0 (check the exit code, not only the tail of the output).

## 2. Settings schema as a validation rule

- [ ] 2.1 Write the tests first. A new `server/src/test/.../config/TestSchemaPluginProvider.java` (id `test-schema`,
  a schema requiring a string `greeting` with `additionalProperties: false`, counting the calls of its `validate`),
  registered in the test `META-INF/services` file. `ConfigPluginApiTest`: Wrong value type (with `validate` not
  called), Missing required property, Property not allowed and Accepted by the schema (with `validate` called).
  `ConfigPluginApiTest.SkipMode`: Rejected by the schema on a rebuild under skip. `AdminValidateApiTest`: Rejected
  by the schema in a batch. `PluginRegistryTest`: Invalid settings schema (`JarEchoPluginProvider.BadSchema`
  declaring `{"type": "no-such-type"}`), Schema of another draft (`JarEchoPluginProvider.OtherDraft`), Remote
  reference (`JarEchoPluginProvider.RemoteRef`, with a `$ref` to a `MockWebServer` url, asserting that it received
  no request), Remote reference no keyword uses (`JarEchoPluginProvider.UnusedRef`) and Metadata that throws
  (`JarEchoPluginProvider.ThrowingSchema`), and Draft-07 named by its https address
  (`JarEchoPluginProvider.HttpsDraft`: the registry loads it and `list()`, from group 3, has it with that schema). `ConfigPostProcessorTest`: Schema check fails
  (a provider with `{"allOf": [{"$ref": "#"}]}` in `registryOf`; one warning on `settings`, `validate` not called).
  Verify that they fail against the code of group 1.
- [ ] 2.2 `PluginProvider`: `default ObjectNode settingsSchema()` returning null, and `validate()` becomes a default
  returning an empty list, with javadoc (draft-07, self-contained, checked before `validate()`). Verify with
  `./gradlew :aidial-core-plugin-api:build -x test`.
- [ ] 2.3 `PluginRegistry.load`: for a provider with a schema, the five steps of D5: `$schema` absent or draft-07
  (http or https); a walk refusing any `$ref` that is not `#...` or the draft-07 meta-schema IRI; valid against the
  draft-07 meta-schema of the validator jar; compiled with a V7 factory whose first schema loader is an
  `AllowSchemaLoader` allowing only `classpath:draft-07/schema`; `initializeValidators()`. Keep a `deepCopy()` of
  the declared node and the compiled schema. A failure stops the start, naming the id, the directory and the settings
  schema. Wrap every call into the provider at load (`id()`, `settingsSchema()`, and in group 3 `targets()` and
  `description()`) so that a throw stops the start with a message naming the provider class and the directory.
  Verify with the `PluginRegistryTest` cases from 2.1.
- [ ] 2.4 `ConfigPostProcessor.validatePlugin`: run the compiled schema before `validate()`, inside the guarded block
  with `StackOverflowError` added to its catch; a failure is one warning on `settings`. Each violation is a warning
  on `settings` plus its instance location, plus the property for `required` and `additionalProperties`; a
  `dependencies` violation stays on the object. `validate()` is skipped when there is any (D4). Verify with the
  tests from 2.1.
- [ ] 2.5 `docs/dynamic-settings/plugins.md`: a provider's settings schema, the check order, the warning fields, and
  that a schema must be self-contained: another draft or a `$ref` outside the document stops the start, and Core
  fetches nothing. Verify by reading it against the spec delta.
- [ ] 2.6 Run `./gradlew checkstyleMain checkstyleTest` and `:server:test --tests` for `PluginRegistryTest`,
  `ConfigPostProcessorTest`, `ConfigPluginApiTest` and `AdminValidateApiTest`. Verify that every command exits 0.

## 3. Targets, description, version and the listing

- [ ] 3.1 Write the tests first, in `ConfigPluginApiTest`. Plugin with its metadata: `JarEchoPluginProvider`
  declares the description `Echoes the greeting`, the targets `MODEL` and `APPLICATION` and the schema
  `{"type": "object"}`, and `writeJar` writes a manifest with `Implementation-Version: 1.2.0`. Also Provider without
  metadata (`test-echo`), Listed whatever the config holds, and Not an admin (`authorization: user`, as
  `AdminHealthConfigTest` does); Excluded plugin directory not listed in `ConfigPluginApiTest.ExcludedPlugin`, whose
  fixture excludes `builtin/echo` as the scenario does. Add
  `PluginRegistryTest` No targets (`JarEchoPluginProvider.NoTargets`) and, in `EmbeddedOriginApiTest`, Plugin url
  outside the declared targets (`TestOriginPluginProvider` declares only `ROUTER`). Verify that they fail against the
  code of group 2.
- [ ] 3.2 `aidial-core-plugin-api`: a new `PluginTarget { MODEL, APPLICATION, ROUTER, TRANSLATOR }`;
  `PluginProvider.targets()` defaulting to `EnumSet.allOf(PluginTarget.class)` and `description()` defaulting to null,
  with javadoc (targets are a hint). Verify with `./gradlew :aidial-core-plugin-api:build -x test`.
- [ ] 3.3 `PluginRegistry`: read each jar's `Implementation-Version` in `listJars`, keyed by the provider classes it
  registers (D8); per id keep the provider, directory, version, description, targets (`EnumSet.copyOf`) and schema
  (D7); an empty or null target set, or a null in it, stops the start; add `list()` ordered by id. Verify with `PluginRegistryTest`.
- [ ] 3.4 The listing: a record for the item in `server/.../data/` (nulls left out, targets in lower case with
  `Locale.ROOT`, in the enum's order), `AdminPluginProvidersController` (GET, `isAdmin` or `403`, `x-preview`, OpenAPI annotations as
  `AdminHealthConfigController`), `RouteTemplate.ADMIN_PLUGIN_PROVIDERS` and its `ControllerSelector` route (D9). Verify with
  the `ConfigPluginApiTest` listing tests from 3.1.
- [ ] 3.5 Plugin targets are a hint: verify that the `EmbeddedOriginApiTest` case from 3.1 passes without any change
  to the deployment checks.
- [ ] 3.6 `docs/dynamic-settings/plugins.md`: description, targets (a hint, all by default), the jar's
  `Implementation-Version`, and the `GET /v1/admin/plugin-providers` answer with an example. Run `./gradlew replaceSpec` and
  check that `docs/open_api_core.yaml` has the new operation and the plugin schema with `pluginId` and no `type`.
- [ ] 3.7 Run `./gradlew checkstyleMain checkstyleTest` and `:server:test --tests` for `PluginRegistryTest`,
  `ConfigPluginApiTest` and `EmbeddedOriginApiTest`. Verify that every command exits 0.

## 4. Integration check

- [ ] 4.1 Run `npx -y @fission-ai/openspec@1.14.3 validate plugin-id-and-metadata --strict`, `./gradlew replaceSpec
  -Plint`, `./gradlew checkstyleMain checkstyleTest`, and the test classes of 1.9 together once more. Verify that
  each exits 0.
- [ ] 4.2 `openspec/specs/plugin-registry/spec.md`: change the Purpose, which says "its provider `type`", to
  `pluginId` (the delta cannot change it; archive keeps the edit). Verify with `npx -y @fission-ai/openspec@1.14.3
  validate --specs --strict`.

## Workflow follow-up

- Open a draft PR with the proposal and the spec delta for review before the code is pushed.
- After apply: `review.md`, `npx -y @fission-ai/openspec@1.14.3 archive plugin-id-and-metadata -y`, then
  `validate --specs --strict`, then one commit staging explicit paths.
- ai-dial-admin-frontend: a PR that reads `pluginId`, offers the listed plugins in the create form, hands
  `settingsSchema` to the JSON editor and filters base url plugin selectors by target.
- `ai-dial-model-provider-plugin`: move to `id()`, and declare a schema, a description and the target `MODEL`.
