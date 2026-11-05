## Context

See proposal.md, Why. Line numbers are on `development` at 0cd885f3.

One parameter, `onSkip`, decides two things in `ConfigPostProcessor`, and `MergedConfigStore` builds it from
`onInvalidEntity`:

- `null` (`abort`): hard failures throw, and no cross-reference is checked (`processModels` and `validateSingleModel`
  call `validateCrossReferences` only under `if (onSkip != null)`; `cascadeInterceptorDelete` returns at once).
- non-null (`skip`): hard failures drop the entity and record it, and cross-references are checked.

`softValidation` is read in a different place: at the write gates (`ConfigResourceController.checkModel`,
`ConfigApplyService.applyModel`, `ConfigValidationService.validateOnly`), which admit a dangling reference when it is
`true`. Nothing ties the gate to the acceptance step, so under `abort` the gate can admit what the rebuild never
checks. The file load is a third place: `FileConfigStore` calls `ConfigPostProcessor.process`, with `onSkip == null`,
on the file alone, where an interceptor defined in a blob is not yet visible, so a reference check there would be
wrong.

The result is four `softValidation` x `onInvalidEntity` combinations with three different outcomes for the same
dangling reference. After this change the outcome is the same in all four; only the answer of the write gate differs
(`422` or `200`).

## Goals / Non-Goals

**Goals**
- A dangling model interceptor reference is recorded, never left live, whatever the two settings say.
- Partial update and full rebuild keep giving the same result for a model, including after an interceptor delete.
- Acceptance does not read `softValidation`, so each setting answers one question: `softValidation` whether a write may
  create a dangling reference, `onInvalidEntity` what happens to a hard-broken entity.

**Non-Goals**
- Rejecting an interceptor `DELETE` that a model references (#2226).
- Making the `abort` rebuild throw on a dangling reference.
- Changing the answer of the write gates, or any other rule tier (catalog schemas, translators, applications).
- Moving `softValidation` or `onInvalidEntity` into one place.

## Decisions

**1. The mode and one recorder, passed together.** An enum `OnInvalidEntity { ABORT, SKIP }` replaces the
`MODE_ABORT` / `MODE_SKIP` strings of `MergedConfigStore`. `ConfigPostProcessor` takes the mode and a
`recorderOfInvalid` (a `BiConsumer<ResourceTypes, InvalidEntityException>`, never `null`) in place of `onSkip`. A hard
violation throws when `mode == ABORT` and is otherwise dropped and passed to `recorderOfInvalid`; a dangling
interceptor reference is always dropped and passed to it. `MergedConfigStore` builds the recorder once, from the lambda
`rebuild` already has, and the partial-update path builds the same one for API-sourced writes.
The file load, `ConfigPostProcessor.process` (used by `FileConfigStore`), runs `ABORT` with a no-op recorder and an
explicit `checkReferences = false`: it sees only the file, where an interceptor defined in a blob is not visible yet.
A no-op recorder alone would not do, because the model is dropped before it is recorded, so a file model naming a blob
interceptor would vanish at file load.
- Rejected: `onSkip` plus a second nullable `crossRefSink`, the first form of this change. Two lambdas whose `null`
  means different things ("throw", "do not check") are what a reader has to decode in every method.
- Rejected: gate the check on `softValidation`, which is what the issue's wording implies. It keeps a rebuild rule that
  depends on a write-side setting, and leaves `abort` with `softValidation: false` as the one combination where a
  broken model stays live; the file config and an interceptor `DELETE` still create it there. The check is one
  `containsKey` per reference, so there is no cost reason either.
- Rejected: a post-pass in `MergedConfigStore.rebuild` that checks references after `processSemantic`. It would repeat
  the drop-and-record logic and could not serve the partial-update path, which must replay what the rebuild does
  (`applyChangeInPlace`).

**2. Order inside `processModels` and `validateSingleModel`.** Cross-references are checked after the rebuild
invariants (`validateModelInvariants`), as today.
- `SKIP`: unchanged. Both kinds of warning are collected and recorded in one entry.
- `ABORT`, any invariant warning: throw with the invariant warnings, as today. A model that has both an invariant
  failure and a dangling reference still fails the rebuild.
- `ABORT`, no invariant warning: a dangling reference drops the model alone through `recorderOfInvalid`.

**3. The cascade after an interceptor delete always runs.** `cascadeInterceptorDelete` takes `recorderOfInvalid` and
walks the models on every interceptor delete. This keeps partial update equal to a rebuild: a model left live by a
delete would be dropped by the next rebuild, and its status would change after a restart. The cost is that a `DELETE`
of a referenced interceptor now takes its models out of service in every mode, as it already does under `skip`;
refusing that `DELETE` is #2226.
- Rejected: leave the cascade out and document the flip. It leaves two paths that disagree.

**4. File-defined models are checked too.** `processModels` iterates the merged config, so a file model is already
there with its blob siblings, and the recorder gives it `source: file` from the same `apiSourcedKeys` bookkeeping the
`skip` path uses (`MergedConfigStore.java:1359-1363`). Per-entity GET is blob-only, so a file model is visible through
the health endpoint (canonical id `models/platform/<name>`) and not through GET. This keeps the property the old
`abort` skip protected, that a dangling reference already in the file cannot fail the whole config: recording never
fails the rebuild.
- Rejected: check blob models only. It needs the provenance map in `processModels`, and leaves a file model
  live-and-broken, which is the problem this change removes.

**5. One recorder for every path.** The lambda that `rebuild` builds for `skip` (`MergedConfigStore.java:1346-1365`)
becomes a local `recorderOfInvalid` passed to `processSemantic` and `processPolicies` together with the mode. On the
partial-update side `skipRouter` and `crossRefRouter` become one method, `recorderOfInvalid(nextInvalid)`, returning
the `recordSkippedByMapKey` consumer for API-sourced writes, and `applyChangeInPlace` takes it and reads the mode from
the store's field. The one place that forced `ABORT` through a `null` today, a policy assignment whose policy is
missing (`MergedConfigStore.java:811`, a replica event that overtook its policy), passes `OnInvalidEntity.ABORT`
explicitly. `applyEntityWriteLocked` and `applyEntityDeleteLocked` (the single writes and deletes, and the replica
events, which call `applyEntityWrite` and `applyEntityDelete` at `MergedConfigStore.java:432` and `:486`) and
`applyBatch` (only `ConfigApplyService`, `/v1/admin/apply`) use it. `MergedConfigStore.softValidation` stays for
`isSoftValidation()`, which the write controllers read; acceptance does not. The constructors take the enum;
`AiDial` converts the setting with the same rule as today (`skip`, case-insensitive, else `abort`).

**6. Validate and apply services are not changed.** They already admit a soft failure under `softValidation` and
answer `VALID` and `APPLIED_INVALID`. `applyModel` hands the model to `applyBatch`, which now records it under
`abort` too, so `APPLIED_INVALID` is true there. The check in `validateOnly` reads the batch scratch config, not the
store, so its answer does not change.

**7. Recovery is by full rebuild.** A full rebuild clears a record by not producing it, in any mode, and it puts the
model back under its short name. `resurrectInvalidModels` (`MergedConfigStore.java:979`) also runs on every interceptor
write, but it is not relied on here: it skips a record without a stored payload, which is every record made by a write,
a delete cascade, a replica event or a file-defined model (`recordSkippedByMapKey` is called with `id -> null`), and
for the others it puts the revived model into `Config.models` under its canonical id instead of its short name, so
the model answers `valid` on GET and is not served until the next rebuild. `skip` has the same behaviour today. Found
while applying; filed as #2228 and not changed here. The issue words it the same way: the model becomes valid again
"on the first rebuild that resolves the reference".

## Deliberate differences

- From today under `abort`: the rebuild checks references. From the issue's text: it does so whatever
  `softValidation` is (proposal.md, Impact).
- From today: an interceptor `DELETE` cascades under `abort` too.
- The old rationale for not checking under `abort`, that a file inconsistency must not fail the whole config, is
  dropped, because recording never fails the config.

## Effect per request

None. Every touched method runs on config build or on an admin write; the request path (`Proxy`, the deployment
controllers) is not changed. At config time, `validateCrossReferences` costs one `containsKey` per interceptor
reference per model, which `skip` already pays on each rebuild; `abort` now pays it too. The delete cascade walks every
model once per interceptor `DELETE`, as `skip` does today. No Redis or blob round trip is added, and no body is copied
or parsed. No cost effect is claimed, so there is no measurement task.

## Risks / Trade-offs

- [A model with a dangling reference leaves the model list on every deployment, default settings included] → this is
  the case the proposal marks **BREAKING**. The model was already answering `404` on every request, and is now
  visible in the health endpoint. The release note says so.
- [An interceptor `DELETE` now takes models out of service under `abort`, with `softValidation: false` too, where
  before it succeeded silently and left them broken] → the models are recorded and recover when the interceptor is
  back; #2226 adds the guard that refuses the `DELETE` instead.
- [A model is checked against interceptors that `processInterceptors` may drop afterwards (duplicate deployment id, bad
  override paths)] → `skip` has the same ordering today and is not changed here; not addressed.
- [`softValidation` and `onInvalidEntity` are per-pod settings, so pods set differently disagree about a hard failure
  until each pod's next rebuild] → the same convergence rule as for every config write (config-store, "Write visible
  on return"); not addressed. For a dangling reference the pods now agree, since neither setting matters.

## Migration Plan

None for data: `invalidEntities` is derived in memory and never persisted, so nothing needs cleaning up on upgrade or
rollback. Operators should expect any model with a dangling interceptor reference, file-defined or not, to show as
`degraded` in `GET /v1/admin/health/config` after the upgrade; fixing the reference or creating the interceptor brings
it back. Rollback is a revert of the change.
