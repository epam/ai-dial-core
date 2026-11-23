# Proposal

## Why

Whether a write may create a dangling reference is a choice the caller makes for that write, but Core takes it from a
server setting, `config.write.softValidation`. A UI cannot reject unknown references by default and offer "save anyway"
for one write, and the setting ties the write side to the rebuild side: under `abort` it admits a model the rebuild then
leaves live and broken (#2189), and it leaves an interceptor `DELETE` with no good answer (#2226). Once saving an
invalid entity is an ordinary request, the weak recovery of an invalid model (#2228) matters too.

## What Changes

- **BREAKING:** the setting `config.write.softValidation` is removed. The same choice becomes the query parameter
  `softValidation` (boolean, default `false`) on a Configuration API `PUT`, `POST /v1/admin/validate` and
  `POST /v1/admin/apply`. A value that is not `true` or `false` answers `400`. Core ignores the old setting, so a
  deployment that set it to `true` becomes strict until its clients send the parameter.
- A model whose interceptor does not resolve, and a plugin whose `type` no loaded provider declares, are recorded as
  invalid entities on every rebuild, whatever `config.onInvalidEntity`, and by a single write, an apply, a replica
  event, the file config and the cascade after an interceptor `DELETE`. They stay invalid until the reference
  resolves; the parameter changes only whether a write is accepted, and nothing about it is stored. `abort` throws only
  for a hard violation. Today a model is checked on a rebuild only under `skip` and a plugin type likewise, and under
  `abort` both stay live.
- **BREAKING:** under the default settings, a model with a dangling interceptor reference (declared in the file config,
  written to a blob outside the API, or left by an interceptor `DELETE`) stops being a deployment and shows as
  `degraded` in `GET /v1/admin/health/config`. Before, under `abort` it was listed and answered `404` on every
  request. An interceptor `DELETE` now takes the models that use it out of service in every mode, as it already did
  under `skip`.
- Creating an interceptor makes a model that was invalid only for it a deployment again within seconds, under its short
  name, without waiting for the next periodic rebuild (#2228).
- An interceptor `DELETE` always succeeds and records the models that reference it as invalid. The parameter does not
  apply to it and there is no `409` guard (#2226 is not done).
- Plugin validation is split into a settings check and a type check, as model validation already is in a rebuild
  invariant check and a reference check, so the three write gates treat both entities the same way. No behaviour of its
  own.

Out of scope:
- A guard that refuses an interceptor `DELETE` while a model references it (#2226).
- Returning the status of the entity in the answer of a `PUT`; deferred.
- A new status or counter in `/v1/admin/validate` and `/v1/admin/apply` for an entry accepted as invalid. Validate
  keeps answering `VALID` and apply `APPLIED_INVALID`, as they do today; the statuses of the two endpoints already
  differ, and a further value would add to that.
- Extracting a shared `ModelValidator`, or moving catalog-schema checks to the soft tier.
- `POST /v1/admin/config/file/migrate`: it does not take the parameter and stays strict.
- Applications' and routers' interceptor references; only models carry the soft rule.
- Any new permission: whoever may write the entity may send the parameter.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `config-store`: "Invalid entity policy", "Dangling interceptor reference", "Soft validation", "Write rejected with
  422" and "Apply partial failure" are rewritten around the rebuild rule, the request parameter and the recovery on
  interceptor create.
- `plugin-registry`: "Unknown plugin type is a dangling reference" follows the same rule as a model's interceptor.
- `router-deployment`: "Router properties validated against the schema" no longer names a soft-validation mode; its
  rule is unchanged.

## Impact

- Code (`server` module): `ConfigResourceController` (`checkModel`, `checkPlugin`), `ConfigApplyService`,
  `ConfigValidationService`, `AdminApplyController`, `AdminValidateController`, `ConfigFileMigrateController` (calls
  the shared services strictly), `ConfigPostProcessor` (the mode and recorder, model and plugin checks, rebuild),
  `MergedConfigStore` (the flag, the recorder and `resurrectInvalidModels`), a new enum `OnInvalidEntity`, `AiDial`
  (wiring), the built-in `aidial.settings.json`.
- API and docs: `docs/open_api_core.yaml` (the new parameter) and the `PUT` operation annotations,
  `docs/dynamic-settings/plugins.md`.
- Issues: this change is #2350. It fixes #2189 and #2228, and closes #2226 as not needed: the guard it asks for has no
  purpose once the choice is per request.
