# Config Validation Logic — Scattered Today, Unify Later

## Context

Config-entity validation (models, keys, interceptors, translators, applications, …) is currently
implemented independently in at least five places, with no single owner:

- `ConfigPostProcessor` — full-rebuild-time checks (override paths, pricing, upstream interfaces,
  cross-references, translator shape, deployment-id uniqueness, key-secret collision primitives).
- `ConfigManifestSupport` — precheck/apply-shared helpers for the `/v1/admin/apply` and
  `/v1/admin/validate` batch surfaces (`validateSchema`, `validateDeploymentIdUniqueness`, and now
  `validateKey`).
- `ConfigApplyService` — real-apply `applyX` methods, each re-running a subset of the above before
  writing.
- `ConfigValidationService` — precheck `validateOnly`, historically re-implementing the same subset
  inline per manifest kind rather than calling shared helpers.
- `ConfigResourceController` — single-entity `PUT`/`DELETE` (`/v1/{type}/{bucket}/{path}`), with its
  own bespoke field checks (`validateKeyForApiWrite`, `validateProjectKey`) and its own duplicate-id/
  duplicate-secret rejection methods.
- `ApiKeyStore` — its own private `validateProjectKey`, run only during full rebuild
  (`addProjectKeysLocked`), plus its own collision detector (`putAndWarnOnDuplicateSecret`) that is
  algorithmically different again (whole-map build-time collision, not candidate-vs-snapshot).

This causes real drift, not just duplication risk:

- **Confirmed bug**: `ConfigApplyService.applyApplication` never calls
  `ConfigPostProcessor.validateOverridePaths` before writing, but `ConfigValidationService`'s
  `AdminApplicationManifest` case does. `/v1/admin/validate` can reject an application for bad
  override paths that `/v1/admin/apply` then accepts. Found while extracting key validation
  (see below); not yet fixed — real-apply behavior change, deliberately left out of the keys-only PR.
- Model, Interceptor and Translator validation are each duplicated verbatim between
  `ConfigApplyService` and `ConfigValidationService` (same checks, same order, same messages, only the
  result wrapper — `EntityResult`/`AdminApplyStatus` vs `ValidationResult`/`ValidationStatus` — differs).
  `validateSchema` (already shared) and the new `validateKey` (see below) are the only two that
  currently escape this.

## Key validation as a worked example (done: batch apply/validate only)

Extracted `ConfigManifestSupport.validateKey(Key, Config scratch, ParsedName parsed)` — returns
`String` error or `null`, mirroring `validateSchema`'s shape — and pointed both
`ConfigApplyService.applyKey` and `ConfigValidationService`'s `AdminKeyManifest` case at it. This
covers the two **batch** surfaces (`/v1/admin/apply` real-apply, `/v1/admin/apply?precheck=true` /
`/v1/admin/validate`).

**Deliberately not folded in yet — two more call sites, each with a real structural wrinkle:**

1. **`ConfigResourceController`** (single-entity `PUT`) — `validateKeyForApiWrite` +
   `validateProjectKey` duplicate the same three blank-field checks (with slightly different
   messages — `"...on PUT"` suffix), and `rejectDuplicateKeySecret` duplicates the collision check.
   **This is not a drop-in reuse of `validateKey`**: the single-entity path merges the request over
   the *raw JSON* of the existing blob (`mergePreservingOmittedSecrets`, preserve-on-omit) **before**
   decrypting, so when a request omits `"key"` entirely, the parsed `Key.getKey()` at
   validation time is still the stored **ciphertext**, while `oldSecret` (captured separately via
   `secretFieldProcessor.decryptFields` on a fresh copy) is **plaintext**. Comparing them directly
   would spuriously flag every unrelated-field update (e.g. renaming `project`) as a secret change.
   That's why `rejectDuplicateKeySecret` guards on `requestNode.hasNonNull("key")` — it's compensating
   for object state, not expressing real business logic. A shared validator needs either (a) an
   explicit "was the secret provided/changed" boolean the two callers compute differently (manifest:
   always true; single-entity: `requestNode.hasNonNull("key")`), or (b) to run strictly after
   decrypt-in-place, which would require restructuring the write path's ordering. Also note
   `ConfigApplyService.applyKey`'s batch write is always a **full authoritative replace** (no merge
   concept at all), whereas the single-entity `PUT` is a **partial patch with preserve-on-omit** — so
   even the notion of "the candidate secret" isn't computed the same way on both paths.
   `ApiKeyStore#validateProjectKey` (private) is a **third**, near-identical copy of the same two
   blank checks, run only from `addProjectKeysLocked` during full rebuild — a candidate for the same
   unification once the above is settled.

2. **Replica path** (`MergedConfigStore.applyReplicaEvent` / `onResourceEvent`) — already calls
   `ConfigPostProcessor.isKeySecretChangedAndTakenByAnotherKey` (warn-only; a replicated write is
   already committed elsewhere and can't be rejected here). Structurally different from the three
   blocking paths above: it never runs the blank-field checks (a replicated blob is assumed
   already-validated), and it can only log, never throw. Left as its own thing; not part of the
   shared blocking validator.

3. **`ApiKeyStore.addProjectKeysLocked`** (full rebuild) also runs its own collision detector,
   `putAndWarnOnDuplicateSecret` — algorithmically different from
   `isKeySecretChangedAndTakenByAnotherKey` (it's a *build-the-whole-map-and-warn-on-any-collision*
   pass over all file+API keys at once, not a *candidate-vs-existing-snapshot* check) and, like the
   replica path, warn-only rather than blocking.

## Suggested shape for the eventual refactor

Not attempting now — flagging the shape so the next pass doesn't start from scratch:

- One `KeyValidation` (or similar) home with:
  - A pure blank-field validator: `String validateRequiredFields(Key)`.
  - A collision validator taking the comparison inputs explicitly rather than deriving them from
    ambient object state: `String validateSecretNotTaken(Config snapshot, String selfMapKey, Key
    candidateWithResolvedSecret, String oldSecret)` — callers are responsible for resolving
    `candidateWithResolvedSecret`'s secret to plaintext (or to "unchanged") *before* calling, closing
    the ciphertext-timing gap above rather than working around it with a request-shape guard.
  - The three blocking callers (`ConfigResourceController`, `ConfigApplyService`,
    `ConfigValidationService`) call both. `ApiKeyStore`'s rebuild-time blank check folds in too.
  - The two warn-only callers (`MergedConfigStore` replica, `ApiKeyStore` rebuild collision) stay
    separate — different contract (log vs throw, snapshot-diff vs whole-map-build) — but should say so
    explicitly in a comment pointing back here, rather than silently duplicating logic that *looks*
    unifiable but isn't.
- Apply the same "extract a shared `validateX` returning `String`, called from both apply and
  validate" pattern (already proven by `validateSchema` and `validateKey`) to Model, Interceptor,
  Translator and Application in `ConfigApplyService`/`ConfigValidationService` — and fix the
  `applyApplication` override-paths gap as part of extracting `validateApplication`, since a shared
  method makes that drift impossible by construction rather than needing another manual audit.

## Why this matters

`ConfigApplyService`/`ConfigValidationService`/`ConfigManifestSupport`/`ConfigResourceController`/
`ApiKeyStore` collectively implement admin config write validation with no single source of truth per
entity type. Every new entity kind risks repeating this pattern. This note exists so the key-only
extraction (batch surfaces only, see above) can be pointed to as the worked example — including its
*deliberately unresolved* parts — when this gets picked up properly.
