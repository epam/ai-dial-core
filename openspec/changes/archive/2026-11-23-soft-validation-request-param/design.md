# Design

## Context

See proposal.md, Why. Line numbers are on `development` at 4a2aecb0.

Two settings decide what Core does with a model whose interceptor does not resolve, and they are read in different
places:

- `config.onInvalidEntity` reaches `ConfigPostProcessor` as one parameter, `onSkip`, which `MergedConfigStore` builds
  in `rebuild` (`MergedConfigStore.java:1409-1437`) and in `skipRouter` (`:994`). `onSkip == null` is `abort`: a hard
  violation throws, and no cross-reference is checked (`processModels` `ConfigPostProcessor.java:545` and
  `validateSingleModel` `:256` call `validateCrossReferences` only when `onSkip != null`; `cascadeInterceptorDelete`
  `:448` returns at once). A non-null `onSkip` is `skip`: a violation drops the entity and records it, and references are
  checked.
- `config.write.softValidation` is read once in `AiDial.java:315-320` and handed out three ways. `MergedConfigStore`
  keeps it (`:166`, constructors `:192-238`) and gives it to the write controllers through `isSoftValidation()`
  (`:610`). `ConfigResourceController` copies it into a field per request (`:133`), and `checkModel` (`:2319`) and
  `checkPlugin` (`:2359`) read it. `ConfigApplyService` (`:77`, `:97`) and `ConfigValidationService` (`:54`, `:62`)
  get it as a constructor argument from `AiDial.java:374-378`; `applyModel` (`:339`), `applyPlugin` (`:291`) and the
  `Model` and `Plugin` cases of `validateOnly` (`:95`, `:143`) read it.

Nothing ties the write gate to the rebuild, so under `abort` the gate can admit what the rebuild never checks. The
file load is a third place: `FileConfigStore` calls `ConfigPostProcessor.process` (`:101-104`) on the file alone, with
`onSkip == null`, where an interceptor defined in a blob is not yet visible. The result is four combinations of the two
settings with three outcomes for the same dangling reference (see Why).

The two kinds of entity are also checked differently:

| | Model | Plugin |
|---|---|---|
| Hard rules | `validateModelInvariants` (`ConfigPostProcessor.java:606`) | `validatePlugin` settings part (`:199`) |
| Soft rule | `validateCrossReferences` (`:583`), returns `true` when every reference resolves | the same `validatePlugin`, switched on by a `checkType` argument |
| Write gates | `hard \|\| (soft && !softValidation)`, soft case logged | `!softValidation` is passed as `checkType`, so the soft warning is never produced; apply recomputes it as `registry.get(type) == null` (`ConfigApplyService.java:295`) |
| Rebuild | `validateSingleModel` (`:256`) | `validateSinglePlugin` (`:173`), `checkType = onSkip != null` |

`resurrectInvalidModels` (`MergedConfigStore.java:1024`) runs after each interceptor write (`:838`). It skips a record
that has no payload, which is every record made by a write, a delete cascade, a replica event or a file model, and it
puts a revived model under its canonical id and not its short name (#2228).

## Goals / Non-Goals

**Goals:**
- A dangling model interceptor reference and an unknown plugin type are recorded, never left live, whatever the two
  settings say; partial update and full rebuild give the same result, including after an interceptor `DELETE`.
- One place decides, per request, whether a dangling reference may be written, and the rule is the same for a model's
  interceptor and a plugin's type.
- Acceptance does not read `softValidation`, so each setting answers one question: the request, whether a write may
  create a dangling reference; `onInvalidEntity`, what happens to a hard-broken entity.
- An invalid model comes back after the interceptor is created, under its short name, on every pod.

**Non-Goals:**
- Making a soft write visible in the answer of the `PUT`, or telling the caller of `/v1/admin/validate` that an entry
  would be recorded as invalid (Decision 9).
- Making the `abort` rebuild throw on a dangling reference.
- Extracting a shared validator for all entities; plugins are only brought in line with models.
- Any change to the request path (`Proxy`, the deployment controllers).

## Decisions

**1. The mode and one recorder, passed together.** An enum `OnInvalidEntity { ABORT, SKIP }` replaces the
`MODE_ABORT` and `MODE_SKIP` strings of `MergedConfigStore` (`:104-105`, `:237`); `AiDial` converts the setting with
today's rule (`skip`, case-insensitive, else `abort`). `ConfigPostProcessor` takes the mode and a `recorderOfInvalid`
(a `BiConsumer<ResourceTypes, InvalidEntityException>`, never `null`) in place of `onSkip`. A hard violation throws
when the mode is `ABORT` and is otherwise dropped and passed to the recorder; a dangling model interceptor reference
is always dropped and passed to it. `MergedConfigStore` builds the recorder once, from the lambda `rebuild` has
(`:1409-1437`), and the partial-update path builds the same one for API-sourced writes (`skipRouter` becomes
`recorderOfInvalidFor`). The one place that forced `ABORT` through a `null`, a policy assignment whose policy is
missing (a replica event that overtook its policy), passes `OnInvalidEntity.ABORT` explicitly.
The file load, `ConfigPostProcessor.process` (used by `FileConfigStore`), runs `ABORT` with a no-op recorder and an
explicit `checkReferences = false`: it sees only the file, where an interceptor defined in a blob is not visible yet. A
no-op recorder alone would not do, because the model is dropped before it is recorded, so a file model naming a blob
interceptor would vanish at file load.
- Rejected: `onSkip` plus a second nullable sink for references. Two lambdas whose `null` means different things
  ("throw", "do not check") are what a reader has to decode in every method.
- Rejected: gate the reference check on `softValidation`. It keeps a rebuild rule that depends on a write-side setting,
  and leaves `abort` with strict writes as the one combination where a broken model stays live, since the file config
  and an interceptor `DELETE` still create it there. The check is one `containsKey` per reference.
- Rejected: a post-pass in `rebuild` after `processSemantic`. It would repeat the drop-and-record logic and could not
  serve the partial-update path, which must replay what the rebuild does (`applyChangeInPlace`).

**2. Order inside the model checks, and what is checked.** `processModels` and `validateSingleModel` share one helper.
Rebuild invariants come first (`validateModelInvariants`); references are checked after them, when `checkReferences`.
- `SKIP`: both kinds of warning are collected and recorded in one entry.
- `ABORT`, any invariant warning: throw with the invariant warnings. A model with both an invariant failure and a
  dangling reference still fails the rebuild.
- `ABORT`, no invariant warning: a dangling reference drops the model alone through the recorder.
File-defined models are checked too: `processModels` iterates the merged config, so a file model is there with its
blob siblings, and the recorder gives it `source: file` from the same `apiSourcedKeys` bookkeeping the `skip` path
uses. Per-entity GET is blob-only, so a file model shows in the health endpoint and not in GET. Recording never fails a
rebuild, so a dangling reference already in the file cannot fail the whole config, which is what the old `abort` skip
protected.
- Rejected: check blob models only. It needs the provenance map in `processModels`, and leaves a file model
  live-and-broken.

**3. The cascade after an interceptor delete always runs.** `cascadeInterceptorDelete` takes the recorder and walks the
models on every interceptor delete. This keeps partial update equal to a rebuild: a model left live by a delete would
be dropped by the next rebuild and change status after a restart. A `DELETE` of a referenced interceptor therefore
takes its models out of service in every mode, as it already does under `skip`.
- Rejected: leave the cascade out and document the flip. It leaves two paths that disagree.

**4. The value travels as an argument, read once per request.** Each entry point reads `softValidation` from the
query string and passes it down: `checkModel(entity, softValidation)` and `checkPlugin(entity, softValidation)` in
`ConfigResourceController`, `applyEntries(entries, scratch, softValidation)` in `ConfigApplyService`, and
`validateOnly(entry, scratch, [policyPrecheck,] softValidation)` in `ConfigValidationService`. The entry points are the
`PUT` handler (`:2008`, `:2016`), `AdminApplyController` (`:103-104`, `:133`, `:144`, `:164`) and
`AdminValidateController` (`:105-106`, `:153`). `ConfigFileMigrateController` (`:226`, `:475`) calls the same two
services and passes `false` (Decision 8). The apply precheck phase and the write phase use the same value, so the
precheck never rejects what the write would accept.
One static method parses it: `null` is `false`, `true` and `false` (any case) are themselves, anything else, an empty
value included, answers `400` before anything is written. It sits with the other shared manifest helpers
(`ConfigManifestSupport`); `AdminSystemImportController.java:103` (`ImportMode.parse`) is the precedent for a parsed
query parameter. A `PUT` reads it for every entity type and uses it for models and plugins only; `DELETE` and
`POST` do not read it.
- Rejected: a request-scoped holder or an attribute on `ProxyContext`. The gates would read a value that nothing at the
  call shows; an argument makes the dependency visible and the services stay singletons.
- Rejected: `Boolean.parseBoolean`. `?softValidation=yes` would read as `false` and the caller would see a `422` for a
  typo.

**5. The setting is removed, with a startup warning.** `config.write` leaves the built-in `aidial.settings.json` and
the constructor arguments go. Core reads settings by key, so an existing `config.write.softValidation` would be
ignored without a word; `AiDial` logs a warning when it is present, naming the parameter.
- Rejected: keep it as the default for requests without the parameter. Two ways to set one behaviour, and the
  question that started this change, what a `DELETE` should do under a global flag, comes back.
- Rejected: keep it as a ceiling that forbids the parameter. Nothing asks for a way to forbid it, and it needs a
  second decision per request.

**6. Plugin validation is split as model validation is.** `validatePlugin` becomes two methods in `ConfigPostProcessor`:
`validatePluginSettings(name, plugin, registry, warnings)`, hard, appends and returns nothing, and does nothing when
no provider is loaded; and `validatePluginType(name, plugin, registry, warnings)`, soft, returns `true` when a
provider of the type is loaded and appends warning `type` otherwise. The `checkType` argument goes.
- The three gates read like the model ones: hard warnings first, then the soft boolean, reject on
  `hard || (soft && !softValidation)`, `log.warn` on the soft case, and `APPLIED_INVALID` when the soft check failed.
  `applyPlugin` no longer asks the registry a second time.
- The two checks cannot both fail for one plugin: settings are checked only when a provider is loaded, and the type
  fails only when none is. So there is no combined case to order, unlike a model.
- Rebuild: `validateSinglePlugin` and `processPlugins` take the mode and the recorder of Decision 1. The type warning
  is always recorded; a settings warning throws under `ABORT` and is recorded under `SKIP`. The file-only load
  (`ConfigPostProcessor.java:103`) keeps the type unchecked, like model references (`checkReferences = false`): it has
  no recorder, and the merged rebuild (`MergedConfigStore.java:1435`) sees file plugins too.
- The condition `hard || (soft && !softValidation)` then appears at six sites (three gates, two entities). Not
  extracted here: each is one line, and a shared validator for all entities is a change of its own.
- Rejected: leave plugins alone. Their rebuild rule would still depend on `onInvalidEntity`, and the apply gate would
  keep its own copy of what counts as soft.

**7. Recovery requests a rebuild.** In `MergedConfigStore.applyChangeInPlace` (`:836-839`), the `INTERCEPTOR` case
stops calling `resurrectInvalidModels` and calls `requestRebuild()` (`:640`) when the invalid entities hold any
`MODEL` record. `resurrectInvalidModels` is deleted. The same code runs for a `PUT`, a batch apply and a replica event,
so each pod requests its own rebuild.
A full rebuild already puts a recovered model under its short name and clears its record, in every mode, so the two
defects of #2228 have no code left to live in.
- The interceptor write itself is visible when its request returns, as Write visible on return says. The model comes
  back after the debounce (`REBUILD_DEBOUNCE_MS`, `:97`, 500 ms) and the rebuild: "within seconds", not "at once". The
  specs say so, and their tests wait for it.
- Rejected: repair `resurrectInvalidModels`. It would be synchronous, but a record would have to carry the decrypted
  model (or the code re-read the blob), a file-defined record has neither and still needs a rebuild, so there would be
  two mechanisms.
- A plugin needs no such step. A provider appears only when a jar is loaded, which happens at start, and a start
  rebuilds.

**8. An interceptor `DELETE` and file migration.** A `DELETE` always succeeds and records the models that name the
interceptor (Decision 3). It takes no parameter: the request flag answers whether a write may create a dangling
reference, and a `DELETE` that removes an interceptor is not a write of the model.
- Rejected: refuse the `DELETE` with `409` while a model references it (#2226). It was only needed to keep a global
  "no dangling references" promise, and there is none now. The records and Interceptor created later give the way back.
`ConfigFileMigrateController` shares `applyEntries` and `validateOnly` with apply and validate, so those calls get a
value: it passes `false` and reads no parameter. A file model with a dangling reference is already recorded as
invalid, and its migration fails with the usual message; the reference is fixed, or the interceptor migrated, first.
- Rejected: read the parameter in migrate too. A migration would copy a broken model into a blob without being asked.

**9. Validate and apply keep their statuses and counters.** With `softValidation=true`, `validateOnly` answers `VALID`
for an entry that fails only a soft rule, and apply answers `APPLIED_INVALID` for the same entry, as the two do today
under the setting. Both mean that the entry is accepted; only apply says that it is recorded as invalid. `failed: 0`
still means that apply will reject nothing, and `AdminApplyController` precheck (`:145`) and
`AdminValidateController.validateBatch` need no change. The statuses of the two endpoints are already different sets
(`ValidationStatus`: `VALID`, `FAILED`, `SKIPPED`; `AdminApplyStatus`: `APPLIED`, `APPLIED_INVALID`, `FAILED`,
`SKIPPED`), and this change does not widen the gap.
- Rejected: a new `ValidationStatus` value `INVALID`, with an `invalid` counter in both responses, so that validate
  would predict apply exactly. It touches the precheck, the `422` collapse, the counters and both response records, and
  it adds a value to a pair of enums that already do not match.
- Not done, and left to the question below: a way for validate to say that an entry would be recorded as invalid.

**10. Specs.** The `config-store` requirements "Invalid entity policy", "Dangling interceptor reference", "Soft
validation", "Write rejected with 422" and "Apply partial failure" and the `router-deployment` requirement are
MODIFIED. The `plugin-registry` requirement is REMOVED and replaced by "Unknown plugin type is recorded as invalid",
because a MODIFIED block must keep every scenario of the current one and "Rebuild under abort keeps the entity" is now
false.

## Deliberate differences

- From today under `abort`: the rebuild checks a model's references and records the failure; an interceptor `DELETE`
  cascades; a file model with a dangling reference is recorded, not left live (Decisions 1 to 3). The old reason for not
  checking, that a file inconsistency must not fail the whole config, no longer applies, because recording never fails
  the config.
- From today: no `config.write.softValidation`; a parameter instead (Decisions 4 and 5).
- From today, under `abort`: an unknown plugin type is recorded as invalid and no longer stays `valid` in the merged
  config (Decision 6).
- From #2228's notes: recovery is by rebuild, not by repairing the shortcut (Decision 7).

## Effect per request

None on the request path. The parameter is parsed on a config `PUT`, `validate` or `apply`, which are admin calls. On
a rebuild, `abort` now pays what `skip` already paid: one `containsKey` per interceptor reference per model, and one
registry lookup per plugin. The delete cascade walks every model once per interceptor `DELETE`, as `skip` does today.
An interceptor write adds one full rebuild per pod when an invalid model record exists; each pod already rebuilds
every `config.reload` ms (60000 by default), so this is at most one more per interceptor write. No body is copied or
parsed and no Redis or blob call is added beyond that rebuild. No cost effect is claimed, so there is no measurement
task.

## Risks / Trade-offs

- [A model with a dangling reference leaves the model list on every deployment, default settings included: a file
  model, a blob written outside the API, or one left by an interceptor `DELETE`] → this is the **BREAKING** mark in the
  proposal. The model already answered `404` on every request under `abort`, and is now visible in the health endpoint
  as `degraded`. The release note says so.
- [A deployment that set `config.write.softValidation: true` becomes strict without an error] → the **BREAKING** mark,
  the startup warning, and the release note.
- [An interceptor `DELETE` or a soft write takes a model out of service] → it is recorded and visible in the health
  endpoint and in the entity GET, and it comes back when the reference resolves. The caller could already delete the
  model.
- [A plugin of an unknown type under `abort` is no longer served as `valid`, for example on a pod that has not yet
  got the jar during a rollout] → a deployment that names the plugin stays valid and its interface is unserved
  (deployment-interfaces-config, "Plugin url in a base url"), and the plugin returns on the first rebuild after the
  jar is loaded.
- [A model is checked against interceptors that `processInterceptors` may drop afterwards (duplicate deployment id,
  bad override paths)] → `skip` has the same ordering today; not addressed.
- [`softValidation` no longer exists, but `onInvalidEntity` is still a per-pod setting, so pods set differently
  disagree about a hard failure until each pod's next rebuild] → the same convergence rule as for every config write
  (config-store, "Write visible on return"). For a dangling reference the pods now agree, since neither setting matters.
- [A UI that creates an interceptor and reads the model at once can still see `invalid`] → recovery follows within
  seconds; the UI refreshes. Making the write synchronous is Decision 7's rejected alternative.

## Migration Plan

Data: none. `invalidEntities` is derived in memory and never persisted, and no blob changes.

Operators: remove `config.write.softValidation` from the settings; send `?softValidation=true` where a write must be
accepted with a dangling reference. During a rolling upgrade a pod that still runs the old version ignores the
parameter and uses its setting, so the choice is not per request on that pod until it is replaced. After the upgrade
any model with a dangling reference, and any plugin of an unknown type, shows in `GET /v1/admin/health/config` as
skipped. Rollback is a revert of the change.

## Open Questions

- Whether the answer of a `PUT`, and the answer of `/v1/admin/validate`, should say that the entity was or would be
  saved as invalid. Postponed by the author; it needs a decision on the statuses of validate and apply as a whole, not
  one more value.
