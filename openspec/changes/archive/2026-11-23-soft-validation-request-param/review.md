# Review

## What shipped

- The query parameter `softValidation` (`true` or `false`, default `false`, any other value `400`) on a config `PUT`,
  `POST /v1/admin/validate` and `POST /v1/admin/apply`. `config.write.softValidation` is removed, and Core logs a
  warning at start when it is set. File migration stays strict. A soft failure is `VALID` in validate and
  `APPLIED_INVALID` in apply.
- A model with a dangling interceptor and a plugin of an unknown type are recorded as invalid on every rebuild, single
  write, apply, replica event, file load and interceptor `DELETE`, in both `onInvalidEntity` modes. `abort` throws only
  for a hard violation. An interceptor `DELETE` always succeeds and records the models that use it.
- Plugin validation is split into a settings check (hard) and a type check (soft), and the three write gates treat
  plugins as they treat models.
- Creating an interceptor requests a rebuild when an invalid model exists, so the model returns within seconds under
  its short name; `resurrectInvalidModels` is removed.
- Docs: the parameter on four operations of `docs/open_api_core.yaml`, `docs/dynamic-settings/models.md` and
  `plugins.md`, and the `validate-config-on-every-write` skill.

## Verification

- Checkstyle (main and test) is clean, `validate --all --strict` passes, and a fresh OpenAPI merge equals
  `docs/open_api_core.yaml`.
- Passing: the `config` unit package, `ConfigManifestSupportTest`, `InvalidEntityApiTest`, `ModelCrossRefValidation*`,
  `OverridePathsWriteApiTest`, `AdminApplyApiTest`, `AdminValidateApiTest`, `ConfigPluginApiTest`,
  `ConfigFileMigrateApiTest`, the other config, alias, embedded-origin and resource-dependency classes that touch
  plugins or interceptors, and `:openapi-generator:test`.
- The resource-storage classes (resources, publications, skills, projects, toolsets, org units, application
  deployment) fail on this Windows machine with file locks and `\` in listed names; `ResourceApiTest`,
  `OrgUnitEditApiTest` and `CustomApplicationApiTest` fail the same way on the unchanged base. None reads the config
  store.

## Drift from design.md

None. `validateOnly` has a three-argument overload that file migration uses, and `descriptorFromCanonicalId` and a
reflective test helper went with `resurrectInvalidModels`.

## Not done

- The full suite runs in CI.
- The answer of a `PUT`, and of `/v1/admin/validate`, does not say that an entity was or would be saved as invalid
  (design.md, Open Questions).
- #2226 is closed by hand as not needed when this merges.
