# Review: plugin-id-and-metadata

## What shipped

- A `plugins` entry names its provider with `pluginId`. `type` is still read as a deprecated alias on every input
  (Configuration API, validate and apply manifests, config files, stored blobs); a body with both is refused with
  `400`. Each config file is read into its own tree and its `type` renamed before the files merge, so an overlay's
  `pluginId` replaces a base file's `type`. Core stores and returns `pluginId` only, invalid entries with one name
  included, and warnings name `pluginId`.
- `PluginProvider` gets `id()` (falling back to a deprecated `type()`), `description()`, `targets()`,
  `settingsSchema()` and a default `validate()`; `PluginTarget` is new. A jar that overrides only `type()` loads
  unchanged.
- A declared settings schema is checked at start (draft-07 `$schema` only, no `$ref` outside the document or the
  draft-07 meta-schema, valid against the meta-schema, compiled with a loader that allows only the bundled
  meta-schema) and runs before `validate()` on every write and rebuild, which it skips on a violation. A check that
  throws, a stack overflow included, is one warning on `settings`. Empty or null targets and throwing metadata stop
  the start, naming the provider class and its plugin directory.
- `GET /v1/admin/plugin-providers` (admin, `x-preview`, `listPluginProviders`) lists the providers loaded at start,
  ordered by id, from copies kept at load: `id`, `targets`, and when known `directory`, the jar's
  `Implementation-Version`, `description` and `settingsSchema`.
- Docs: `docs/dynamic-settings/plugins.md` (field list, alias, settings schema, plugin code metadata, the listing),
  `docs/open_api_core.yaml` (the new operation, `Plugin` with `pluginId` and no `type`), the `aidial-core-plugin-api`
  row of `CLAUDE.md`, and the Purpose of `openspec/specs/plugin-registry/spec.md`.

## Verification

- `npx -y @fission-ai/openspec@1.14.3 validate plugin-id-and-metadata --strict` and `validate --specs --strict`: pass.
- `./gradlew replaceSpec -Plint`: pass; its warnings are the pre-existing ones on the manifest `oneOf` schemas.
- `./gradlew checkstyleMain checkstyleTest`: pass.
- In one run, all pass: `:config:test` `PluginTest`; `:plugin-samples:test`; `:server:test` `PluginRegistryTest`,
  `PluginManagerTest`, `ConfigPostProcessorTest`, `ConfigPluginApiTest` (with `SkipMode` and `ExcludedPlugin`),
  `AdminApplyApiTest`, `AdminValidateApiTest`, `ConfigFileMigrateApiTest`, `EmbeddedOriginApiTest`,
  `EmbeddedCallApiTest`, `MergedConfigStoreApiTest`, `MergedConfigStorePartialUpdateTest`,
  `MergedConfigStoreReplicaUpdateTest` and `FileConfigStoreTest`.

## Drift from design.md

- The OpenAPI generator checks every route against its list of controllers, so the new controller is registered in
  `AnnotationEndpointCollector`; the spec merge keeps a property found only in the committed `open_api_core.yaml`, so
  `type` was removed from the `Plugin` schema by hand once. Both added to the Code map.
- Targets are copied into an `EnumSet` by a loop rather than `EnumSet.copyOf`, so an empty set and a null in it get
  their own messages. D7 corrected.
- `PluginRegistry.list()` returns the answer item record (`data/PluginProviderItem`) directly, and the registry's
  test constructor takes a list of providers and runs the checks of a load.
- "Provider failure on a rebuild under skip" is covered by `ConfigPostProcessorTest`, not `ConfigPluginApiTest.SkipMode`
  as tasks.md 1.6 says.

## Not done

- ai-dial-admin-frontend: read `pluginId`, offer the listed plugins in the create form, hand `settingsSchema` to the
  JSON editor, filter base url plugin selectors by target (a separate PR).
- `ai-dial-model-provider-plugin`: move to `id()` and declare a schema, a description and the target `MODEL`.
- Removing the `type` alias, once blobs and config files are rewritten.
