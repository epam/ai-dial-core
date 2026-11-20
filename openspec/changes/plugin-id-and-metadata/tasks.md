# Tasks

## 1. `pluginId` and the deprecated `type` alias

- [ ] 1.1 Write the tests first. `config/.../PluginTest.java`: `pluginId` binds; `type` binds as `pluginId`; both, neither
  and blank are refused; the serialized object has `pluginId` and no `type`. `PluginRegistryTest`: a provider that
  overrides only `type()` (new `JarEchoPluginProvider.LegacyType`) loads under that id; `NoType` still fails the load,
  with a message saying "id". Verify that they fail against today's code.
- [ ] 1.2 `aidial-core-plugin-api` `PluginProvider`: `default String id()` returning `type()`, and `type()` as a
  `@Deprecated(forRemoval = true)` default returning null, with javadoc on both (D3). Verify with `./gradlew
  :aidial-core-plugin-api:build -x test`.
- [ ] 1.3 `config` `Plugin`: field `pluginId`, and a creator taking `pluginId` and `type`, refusing both, neither or
  blank (D2). Verify with `./gradlew :config:test --tests "com.epam.aidial.core.config.PluginTest"`.
- [ ] 1.4 Server: `PluginRegistry` keys providers by `id()`, with messages saying "id" (duplicate, blank);
  `ConfigPostProcessor.validatePlugin` looks up `getPluginId()` and reports an unknown one under field `pluginId`;
  `ConfigApplyService.applyPlugin` and `PluginManager.create` look up `getPluginId()`. Move the test providers
  (`TestEchoPluginProvider`, `TestOriginPluginProvider`, `JarEchoPluginProvider` and its subclasses except
  `LegacyType`) and the `forward` sample to `id()`. Verify with `PluginRegistryTest`, `PluginManagerTest` and
  `ForwardOriginPluginProviderTest`.
- [ ] 1.5 Move the test fixtures and assertions from `type` to `pluginId`: `server/src/test/resources/aidial.config.json`,
  `dial-config/router-decision-embedded.json`, and the tests listed in design.md's Code map. Keep one file entry
  with `type` in `aidial.config.json` for "File entry with the alias". Verify with each of those test classes.
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
  reload) and File entry with the alias: `ConfigPluginApiTest`. Manifest with the alias: `AdminApplyApiTest`.
  Verify that they pass.
- [ ] 1.8 `docs/dynamic-settings/plugins.md`: `pluginId` in the field list, the example and "How an entry is
  checked"; `type` as its deprecated alias, a body with both refused; "plugin code" sections say id. Verify by
  reading it against the spec delta.
- [ ] 1.9 Run `./gradlew checkstyleMain checkstyleTest` and the touched classes: `:config:test --tests
  "com.epam.aidial.core.config.PluginTest"`; `:plugin-samples:test`; `:server:test --tests` for `PluginRegistryTest`,
  `PluginManagerTest`, `ConfigPostProcessorTest`, `ConfigPluginApiTest`, `AdminApplyApiTest`,
  `AdminValidateApiTest`, `ConfigFileMigrateApiTest`, `EmbeddedOriginApiTest`, `EmbeddedCallApiTest`,
  `MergedConfigStoreApiTest`, `MergedConfigStorePartialUpdateTest` and `MergedConfigStoreReplicaUpdateTest`. Verify
  that every command exits 0 (check the exit code, not only the tail of the output).

## 2. Settings schema as a validation rule

- [ ] 2.1 Write the tests first. A new `server/src/test/.../config/TestSchemaPluginProvider.java` (id `test-schema`,
  a schema requiring a string `greeting`, counting the calls of its `validate`), registered in the test
  `META-INF/services` file. `ConfigPluginApiTest`: Wrong value type (with `validate` not called), Missing required
  property and Accepted by the schema (with `validate` called). `ConfigPluginApiTest.SkipMode`: Rejected by the
  schema on a rebuild under skip. `AdminValidateApiTest`: Rejected by the schema in a batch. `PluginRegistryTest`:
  Invalid settings schema (`JarEchoPluginProvider.BadSchema` declaring `{"type": "no-such-type"}`). Verify that they
  fail against the code of group 1.
- [ ] 2.2 `PluginProvider`: `default ObjectNode settingsSchema()` returning null, and `validate()` becomes a default
  returning an empty list, with javadoc (draft-07, self-contained, checked before `validate()`). Verify with
  `./gradlew :aidial-core-plugin-api:build -x test`.
- [ ] 2.3 `PluginRegistry.load`: for a provider with a schema, check it against the draft-07 meta-schema of the
  validator jar, compile it with the draft-07 factory, and keep both the declared node and the compiled schema; a
  failure stops the start, naming the id, the directory and the settings schema (D5). Verify with the
  `PluginRegistryTest` case from 2.1.
- [ ] 2.4 `ConfigPostProcessor.validatePlugin`: run the compiled schema before `validate()`. Each violation is a
  warning on `settings` plus its instance location, or on the property itself for a missing required property, and
  `validate()` is skipped when there is any (D4). Verify with the tests from 2.1.
- [ ] 2.5 `docs/dynamic-settings/plugins.md`: a provider's settings schema, the check order, the warning fields, and
  that a schema must be self-contained (no remote `$ref`). Verify by reading it against the spec delta.
- [ ] 2.6 Run `./gradlew checkstyleMain checkstyleTest` and `:server:test --tests` for `PluginRegistryTest`,
  `ConfigPostProcessorTest`, `ConfigPluginApiTest` and `AdminValidateApiTest`. Verify that every command exits 0.

## 3. Targets, description, version and the listing

- [ ] 3.1 Write the tests first, in `ConfigPluginApiTest`. Plugin with its metadata: `JarEchoPluginProvider`
  declares the description `Echoes the greeting`, the targets `MODEL` and `APPLICATION` and the schema
  `{"type": "object"}`, and `writeJar` writes a manifest with `Implementation-Version: 1.2.0`. Also Provider without
  metadata (`test-echo`), Listed whatever the config holds, and Not an admin (`authorization: user`, as
  `AdminHealthConfigTest` does); Excluded plugin directory not listed in `ConfigPluginApiTest.ExcludedPlugin`. Add
  `PluginRegistryTest` No targets (`JarEchoPluginProvider.NoTargets`) and, in `EmbeddedOriginApiTest`, Plugin url
  outside the declared targets (`TestOriginPluginProvider` declares only `ROUTER`). Verify that they fail against the
  code of group 2.
- [ ] 3.2 `aidial-core-plugin-api`: a new `PluginTarget { MODEL, APPLICATION, ROUTER, TRANSLATOR }`;
  `PluginProvider.targets()` defaulting to `EnumSet.allOf(PluginTarget.class)` and `description()` defaulting to null,
  with javadoc (targets are a hint). Verify with `./gradlew :aidial-core-plugin-api:build -x test`.
- [ ] 3.3 `PluginRegistry`: read each jar's `Implementation-Version` in `listJars`, keyed by the provider classes it
  registers (D8); per id keep the provider, directory, version, description, targets and schema (D7); an empty or
  null target set stops the start; add `list()` ordered by id. Verify with `PluginRegistryTest`.
- [ ] 3.4 The listing: a record for the item in `server/.../data/` (nulls left out, targets in lower case in the
  enum's order), `AdminPluginsController` (GET, `isAdmin` or `403`, `x-preview`, OpenAPI annotations as
  `AdminHealthConfigController`), `RouteTemplate.ADMIN_PLUGINS` and its `ControllerSelector` route (D9). Verify with
  the `ConfigPluginApiTest` listing tests from 3.1.
- [ ] 3.5 Plugin targets are a hint: verify that the `EmbeddedOriginApiTest` case from 3.1 passes without any change
  to the deployment checks.
- [ ] 3.6 `docs/dynamic-settings/plugins.md`: description, targets (a hint, all by default), the jar's
  `Implementation-Version`, and the `GET /v1/admin/plugins` answer with an example. Run `./gradlew replaceSpec` and
  check that `docs/open_api_core.yaml` has the new operation and the plugin schema with `pluginId` and no `type`.
- [ ] 3.7 Run `./gradlew checkstyleMain checkstyleTest` and `:server:test --tests` for `PluginRegistryTest`,
  `ConfigPluginApiTest` and `EmbeddedOriginApiTest`. Verify that every command exits 0.

## 4. Integration check

- [ ] 4.1 Run `npx -y @fission-ai/openspec@1.14.3 validate plugin-id-and-metadata --strict` and `./gradlew replaceSpec
  -Plint`, and the test classes of 1.9 together once more. Verify that each exits 0.

## Workflow follow-up

- Open a draft PR with the proposal and the spec delta for review before the code is pushed.
- After apply: `review.md`, `npx -y @fission-ai/openspec@1.14.3 archive plugin-id-and-metadata -y`, then
  `validate --specs --strict`, then one commit staging explicit paths.
- ai-dial-admin-frontend: a PR that reads `pluginId`, offers the listed plugins in the create form, hands
  `settingsSchema` to the JSON editor and filters base url plugin selectors by target.
- `ai-dial-model-provider-plugin`: move to `id()`, and declare a schema, a description and the target `MODEL`.
