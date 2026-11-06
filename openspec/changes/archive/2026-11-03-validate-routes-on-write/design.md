# Design

## Context

Line numbers are at `development` `55c1add4`. See proposal.md for why.

The rule already exists. `ConfigPostProcessor.validateRouteUpstreams` (`:428`) reports an upstream whose `authType`
is not `API_KEY` and whose `key` is null: field `upstreams[i].authType`, message `Route '<name>': authType '<type>'
requires a key`. It runs in two places, both after the blob is written:

- the full rebuild, `processRoutes` (`:404`), which throws under `abort` and records under `skip`;
- the partial update after a write, `validateSingleRoute` (`:312`), called from `MergedConfigStore.applyChangeInPlace`
  (`:803`). It throws under `abort` and the change is rolled back in memory (`:814`); under `skip` it drops the route
  and records it. Neither undoes the blob.

The three write surfaces have no route check:

| Surface | Today |
|---|---|
| `PUT` | `ConfigResourceController.handlePut` checks Model, Interceptor and Translator only (`:1623`-`:1629`); the blob is written at `:1646` |
| `/v1/admin/validate` | `ConfigValidationService.validateOnly`: `case AdminRouteManifest -> { }` (`:122`) |
| `/v1/admin/apply` | `ConfigApplyService.applyManagedEntity` validates only `INTERCEPTOR` (`:227`); `applySingle` routes `Route` there (`:163`) |

The rebuild runs no other route check (`processRoutes`), so the rule above is the whole set a gate has to match.
`GET` of a route reads the in-memory config (`ConfigResourceController.handleSingleGet`), which is why the rolled-back
write shows as `404` in #2123 while the blob exists.

At request time a non-`API_KEY` upstream with no key falls back to sending the caller's per-request DIAL key as
`API-KEY` (`BaseRouteController.copyHeaders`), which is why the rebuild refuses it.

## Goals / Non-Goals

**Goals:**
- Refuse the route before any blob is written, on all three surfaces, with the answer each already uses for a refused
  entity (`422` with `validationWarnings`; `FAILED` with `error`).
- Keep what a write admits equal to what the rebuild accepts, for routes.

**Non-Goals:**
- Any change to what the rebuild and the partial update do with a failing route: they still throw or record exactly
  as today. They only call the new method instead of `validateRouteUpstreams` (decision 1).
- Healing blobs already written by earlier releases.

## Decisions

**1. Add `validateRouteInvariants` and call it from the rebuild and from all three gates.**
`ConfigPostProcessor.validateModelInvariants` is the model counterpart: one method that the rebuild, the partial
update and every pre-write surface call, so a gate cannot admit what the rebuild rejects. Routes get the same shape:
the new `validateRouteInvariants(Route, List<ValidationWarning>)` calls `validateRouteUpstreams`, which keeps its
body and drops its `entityLabel` and `entityName` parameters (the label is always "Route", the name is
`route.getName()`), as `validateModelInvariants` calls `validatePricing`
and its siblings. `processRoutes` and
`validateSingleRoute` switch to the new method, and the gates call it. A later route rule is added inside it and
reaches every surface. The existing unit tests in `ConfigPostProcessorTest` (`testSemanticAbortThrowsOnRoute...`) keep
covering the rule through the rebuild. Rejected: calling `validateRouteUpstreams` from the gates directly. It works,
but leaves no single named entry point for what the rebuild requires of a route.

**2. Refuse before the write, rather than undo the write on failure.** The established pattern is a check ahead of
`putResource`: `checkTranslator` (`:1951`) and `checkOverridePaths` (`:1965`) reject through
`rejectWithValidationWarnings` (`:1974`), which gives `422`. Rejected: catching the in-memory failure and deleting the
blob. Another pod can read the blob between the two steps, and a failed delete would leave the same orphan.

**3. The check is hard, whatever `softValidation` and `onInvalidEntity`.** It reads nothing but the route, so it is
not a reference a later write could still resolve; the same reasoning as `checkTranslator`.

**4. `PUT` checks the merged entity; apply checks the manifest as sent.** `handlePut` merges omitted secrets from the
stored blob first (`mergePreservingOmittedSecrets`, `:1614`), then builds the entity (`:1622`). The new
`checkRoute(Route)` goes in the `instanceof` chain there, so it sees exactly what would be stored and an update that
omits an existing key passes. Apply does not merge (`applyManagedEntity` encrypts and writes the spec as given), so
the spec itself is what is checked. A create has nothing to merge from, which is the #2123 case.

**5. `/validate` and `/apply` share the rule through the same call.** `validateOnly` replaces its empty
`AdminRouteManifest` arm with the check and returns `FAILED` with `ConfigManifestSupport.joinWarnings`. `applyManagedEntity`
gets a `ROUTE` branch ahead of the encrypt and put, next to the `INTERCEPTOR` one, with the same `FAILED` result.
`precheck: true` runs `validateOnly` for the batch, so it is covered by the validate arm; `precheck: false` needs the
apply branch. File migration reuses the apply path; file routes already passed the same check at file load, so it
adds no new failure there.

**6. The gate message carries no route name.** `validateRouteUpstreams` takes the name from `route.getName()`, and its
prefix helper leaves the `Route '<name>': ` prefix out when it is null, as
`validateModelInvariants` does for models. A route read from a request has no name yet, so the gate says
`authType 'BEARER' requires a key` with field `upstreams[0].authType`. The rebuild's
route is named by then (`sortRoutes` runs first, `ConfigPostProcessor:382`; `sortRoutesInPlace` precedes
`validateSingleRoute` at `MergedConfigStore:804`), so its message keeps the prefix, as #2123 shows. The caller already
knows which route it sent.

## Risks / Trade-offs

- [A client relied on `200` or `APPLIED` under `skip` for such a route] → The route was never served (it was dropped
  and recorded), so nothing working is lost. The proposal and spec state the change.
- [The admin UI Duplicate of a route with a key still fails] → It now fails with a readable `422` and no stored copy.
  Keeping the key needs a frontend or a secret-reveal path, both out of scope.
- [A pod on an earlier release can still write the bad blob during a rolling upgrade] → Same exposure as today; it
  ends once every pod runs this version.
- [Leftover blobs already stored] → Not healed. The operator removes one with `DELETE /v1/routes/platform/<name>`.
  `handleDelete` deletes the blob by descriptor under the admin lock without consulting the merged config
  (`ConfigResourceController:1708`); this is read from the code, not covered by a test here.

Effect per request: none. The check runs on admin writes only, once per upstream of the written route, with no extra
Redis or blob round trip, so no measurement task is needed.

## Migration Plan

None. No setting, blob format or API field changes. Rollback is a revert; routes accepted since the change stay valid,
because the rule is the one the rebuild already enforces.
