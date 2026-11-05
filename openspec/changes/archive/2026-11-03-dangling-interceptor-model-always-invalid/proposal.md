# Proposal

## Why

With `config.write.softValidation: true`, a model write whose only failure is an unknown interceptor is accepted
(`200`). What the model is afterwards depends on `config.onInvalidEntity`. Under `skip` it is dropped from the merged
config and recorded as invalid. Under `abort` (the default) the rebuild never checks cross-references, so the model
stays a deployment, its GET answers `status: "valid"`, `GET /v1/admin/health/config` answers `ok`, and every request
to it answers `404 Interceptor is not found`. The admin gets no signal that the model is broken. The same happens
under `abort` whatever `softValidation` says, when the reference dangles because of the file config, a blob written
outside the API, or an interceptor `DELETE`. `config-store` documents the `abort` behaviour as current (requirement
"Dangling interceptor reference", #2171); this change removes it.

## What Changes

- A model whose interceptor reference does not resolve is an invalid entity in every combination of
  `config.write.softValidation` and `config.onInvalidEntity`. It is dropped from the merged config alone and recorded:
  its GET answers `status: "invalid"` with warning `interceptors[<i>]`, the health endpoint lists it as `degraded`, and
  it is no longer a deployment. Under `abort` this does not fail the rebuild.
- The check runs wherever Core builds or updates the merged config: the full rebuild (blob and file-defined models),
  a single entity write, the batch apply, a replica event, and the cascade after an interceptor `DELETE`.
- A recorded model becomes valid again on the first rebuild, or interceptor write, that resolves the reference. Both
  already do this under `skip`.
- What a write answers is unchanged: `422` with `softValidation: false`, `200` with `true`. `softValidation` stops
  affecting anything after the write is accepted.
- Failures that are not dangling references (rebuild invariants, duplicate deployment ids, pricing and the like) keep
  today's behaviour: `abort` still fails the rebuild and keeps the previous config, `skip` still records them.
- `/v1/admin/validate` and `/v1/admin/apply` change no response. A soft failure stays `VALID` in validate; apply's
  `APPLIED_INVALID` becomes true under `abort`, where today the model ends up live.
- **BREAKING** (operators only, default settings included): a model with a dangling interceptor reference, whether
  file-defined, a blob written outside the API, or left behind by an interceptor `DELETE`, stops being a deployment
  and is listed as degraded in the health endpoint. Today under `abort` it is listed as a model and answers `404` on
  use. An interceptor `DELETE` now takes the models that use it out of service in every mode, as it already does under
  `skip`.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `config-store`: the "Dangling interceptor reference" requirement, and the wording of "Invalid entity policy", which
  names `skip` as the only mode that handles a dangling reference.

## Impact

- Issue [#2189](https://github.com/epam/ai-dial-core/issues/2189), all of its points: the check on rebuild and partial
  update, the drop-and-record outcome, recovery on the next resolving rebuild, file-defined models, and the same
  outcome for `validate` and `apply`. It follows #2171, which documented the old behaviour. One deliberate difference:
  the issue limits the new outcome to `softValidation: true`; this change applies it whatever `softValidation` is, so
  the outcome no longer depends on a write-side setting. The PR description and a comment on the issue say so.
- Out of scope: rejecting an interceptor `DELETE` that a model still references when `softValidation` is `false`
  (issue [#2226](https://github.com/epam/ai-dial-core/issues/2226), a separate change that builds on this one: with
  the guard, that `DELETE` is refused instead of cascading); the answer of the write gates; catalog-schema
  references; the difference between startup and reload under `abort`; validating the `onInvalidEntity` value at
  boot; applications and toolsets, whose interceptor references stay unchecked on write.
- Docs: no file under `docs/` or `README.md` describes `softValidation` or `onInvalidEntity`, so there is nothing to
  update there; `config-store` is where the behaviour is written. `docs/open_api_core.yaml` is not affected, no
  response shape changes.
- Code: `ConfigPostProcessor` (`processSemantic`, `processModels`, `validateSingleModel`,
  `cascadeInterceptorDelete`), `MergedConfigStore` (`rebuild`, `skipRouter`, `applyEntityWriteLocked`,
  `applyEntityDeleteLocked`, `applyBatch`, `applyChangeInPlace`).
