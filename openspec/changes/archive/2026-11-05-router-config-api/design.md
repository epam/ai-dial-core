# Design

## Context

See proposal.md for motivation. Today a router is file-only: `MergedConfigStore.rebuildMergedConfig` copies
`base.getRouters()` into the merged config and no blob scan adds to it (the comment "file-only for now" marks the
spot), `ResourceTypes.ROUTER` is in `EVENT_TYPES` but not in `ResourceTypes.of` or `MergedConfigStore.MANAGED_TYPES`,
and every per-type switch of the Configuration API lacks a `ROUTER` arm. The rules a router must satisfy live in
one loop, `ConfigPostProcessor.processRouters`: duplicate id (`skipOnDuplicate`), `"interceptors": null` turned
into an empty list, `interceptors` otherwise empty, no legacy `endpoint` or `responsesEndpoint`,
`validateDeploymentInterfaces` against the merged translators.

The Configuration API is one generic path per concern with a per-type switch at each step, and the most recent
type added end to end is the translator (commit ff98572c, "support translators in the admin config API"). This
change follows it arm by arm; where a translator and a router differ (a router is a deployment and shares the
deployment-id namespace, a translator is not), the interceptor arm is the template instead.

Constraints: no change to the request path; every rule the rebuild enforces on a router must run on `PUT`,
validate and apply (the repo's `validate-config-on-every-write` rule); `platform` bucket only.

## Goals / Non-Goals

**Goals:**

- A router is a managed config type on every surface a translator is on, plus the deployment-only checks an
  interceptor has (409 on a deployment-id clash).
- One validator for a router, called from the rebuild loop, the partial-update path, `PUT`, validate and apply.

**Non-Goals:**

- A `public` bucket, user buckets, sharing or publishing for routers (phase 2 of the design).
- `customProperties` or an app-type schema on a router.
- A generic "managed deployment type" abstraction over the per-type switches; the switches stay, with one more arm.

## Decisions

### D1. `routers` resolves as a resource type; the generic paths pick it up

`ResourceTypes.of` gains `case "routers" -> ROUTER`. The config entity controller
(`ConfigResourceController.resourceType`), the metadata controller (`ConfigResourceMetadataController.handle`,
which calls `ResourceTypes.of(entityType)`) and the replica listener resolve the URL segment through it, so most of
the generic behaviour (authorization, conditional headers, `405`, metadata listing, resource events) needs no
router-specific code once the regexes admit the segment: `RouteTemplate.CONFIG_RESOURCE`, `CONFIG_RESOURCE_METADATA`
and `ADMIN_FILE_CONFIG` get `routers` in their alternations; `EntityBucketBinding` binds `routers` to `platform`;
`PlatformEntityLocationStrategy.resolveBucket` accepts `ROUTER` (the rebuild's `recordSkippedByMapKey` also needs it).

Rejected: keeping `ROUTER` out of `of()` and special-casing the controller. `of()` is how every other segment is
resolved; `router-entity` left it out only because nothing served the segment yet.

Route shapes: `GET /v1/routers/{id}` (one segment, `RouteTemplate.ROUTER`; `ROUTERS` is the listing) and
`/v1/routers/platform/{path}` (two or more segments) do not overlap; `router-entity` left the second shape free for
this change (comment on `RouteTemplate` line 77). Deliberate difference: as for every config type, once `routers`
is in `CONFIG_RESOURCE`, `/v1/routers/{bucket}/{path}` for any bucket goes to the config entity API (`404` when the
bucket is not `platform`) and no longer falls through to the operator's config routes; the translator change had the
same effect.

### D2. The merged store treats a router like an interceptor

`MergedConfigStore`: `ROUTER` joins `MANAGED_TYPES` (the blob scan of `rebuildMergedConfig` then reads
`routers/platform/*`, and `MANAGED_URL_SEGMENTS` admits the replica events), `deserializeReplicaEntity`
(`Router.class`), `addBlobEntity` (`config.getRouters()` with `warnIfReplaced`) and `removeAddedEntity`
(`restore`), `isShortNameKeyed` (blob wins at the file map key), `cloneTypeMap`, `peekEntity`, `putEntityInPlace`,
`removeEntityInPlace`, and the per-type switch in `applyChangeInPlace` (`validateSingleRouter`), which
`applyEntityWrite`, `applyEntityDelete` and `applyBatch` share. The "file-only for now" comment on the routers map
goes; `shallowClone` already copies routers.

Deliberate difference: today the rebuild's `onSkip` records invalid entities for `MANAGED_TYPES` only and merely
logs a skipped router. With `ROUTER` in that list, a router the merged rebuild skips under
`config.onInvalidEntity: skip` is recorded: `GET /v1/admin/health/config` reports it and
`GET /v1/routers/platform/{name}` answers `200` with `status: "invalid"`, as for any other managed type. In practice
that is a blob router (written by a pod without this change's write checks, or by hand) or a file router whose name
a blob of another kind took. A file router that breaks its own rules never gets that far: `FileConfigStore.load`
runs `ConfigPostProcessor.process` with no `onSkip`, so the file load is strict whatever the policy (drift found
during apply: the review assumed a file router would be recorded; the scenario uses a blob router).

Rejected: a separate code path for routers in the store. The interceptor arm already does what is needed; the
only difference is the validator called after the put.

### D3. One router validator, factored out of the rebuild loop

`ConfigPostProcessor` gets `public static void validateRouterInvariants(String name, Router router, Map<String,
Translator> translators, List<ValidationWarning> warnings)`: it calls `router.setName(name)` first (the messages of
`validateDeploymentInterfaces` are prefixed from `deployment.getName()`, and a `PUT` body or manifest spec carries no
name), turns `"interceptors": null` into `List.of()` (the request path iterates the list, so this normalization
must reach every write path, not only the file load), then runs the three checks of `processRouters`: interceptors
empty, no legacy `endpoint` or `responsesEndpoint`, `validateDeploymentInterfaces`. Duplicate ids stay with
`skipOnDuplicate` and `isDeploymentIdTakenByAnotherDeploymentType`, which already lists `ROUTER`.

Callers: `processRouters`; `validateSingleRouter(Config, mapKey, onSkip)` (the partial-update helper, shaped like
`validateSingleInterceptor`); `ConfigResourceController.checkRouter`, with the merged translators as `checkModel`
takes them, throwing `422` with the warnings; the `AdminRouterManifest` arm of `ConfigValidationService.validateOnly`
and a `ROUTER` block in `ConfigApplyService.applyManagedEntity` next to the `INTERCEPTOR` block, both followed by
`ConfigManifestSupport.validateDeploymentIdUniqueness`. The scratch config's translators (merged plus the batch's
earlier `Translator` entries) are the map for the manifest paths.

A router referencing an unknown translator: `validateDeploymentInterfaces` decides today, on load; the write path
inherits whatever it does. No new rule.

Rejected: duplicating the checks in the controller and the services. That is the drift the
`validate-config-on-every-write` rule exists to prevent. Rejected: a dedicated `applyRouter` like `applyTranslator`;
the translator needs one because it is not a managed entity with a map key, a router is.

### D4. `PUT` body shape and the `400` for foreign fields

`ConfigResourceController.prepareWrite` gets `case ROUTER -> new WriteSpec(descriptor, Router.class, false, false)`
and `entityClassFor` returns `Router.class`. A router carries no encrypted field: `DeploymentInterface` has no
`@EncryptedField` (only `UpstreamInterface`, `Upstream` and `Key` do), and a router has no upstreams. Its
`defaultHeaders` are stored and returned as written, as an interceptor's are; the docs say they must not hold a
secret. `ConfigEntityCodec.treeToEntity` fails on an unknown property, which is what turns `upstreams` on a router
into the `400` the write path already answers for a body that does not bind; the file load rejects it the same way
(router-entity D1). The uniqueness check `rejectDuplicateDeploymentId` runs for `ROUTER` as it does for `MODEL` and
`INTERCEPTOR`.

Rejected: a hand-written field check for `upstreams`, `limits`, `pricing`. The class shape already rejects them.

### D5. Manifest kind `Router`, ordered after `Interceptor`, before `Role`

`AdminRouterManifest(String kind, String name, Router spec)` joins the sealed `AdminTypedManifest` (the compiler
then forces the arms in `mutateScratch`, `validateOnly` and `applySingle`) and the `@ApiSubType` list of
`AdminManifest`; `ConfigManifestSupport`: `DEPENDENCY_ORDER` `Router` at 4 (after `Interceptor` 3; `Role` to
`PolicyAssignment` shift by one, which is safe because `DEPENDENCY_ORDER_COMPARATOR` compares values only),
`KIND_URL_SEGMENT` `routers`, `newScratch` copies the live map into a new one (`scratch.setRouters(new
HashMap<>(live.getRouters()))`, never the live instance: `mutateScratch` writes into the scratch, and sharing the
map is the bug ff98572c removed for translators), `mutateScratch` puts the spec, `parseManifest` builds it.

Today `newScratch` carries no routers at all, so a `Model`, `Application` or `ToolSet` manifest named after a file
router passes validate and apply; copying the map closes that, and a scenario pins it.

The order matters for translators (a router's translated interface may name a translator of the same batch) and
for deployment-id uniqueness against models and applications later in the batch, which
`isDeploymentIdTakenByAnotherDeploymentType` sees through the scratch.

Rejected: placing `Router` after `Model`. Nothing references a router, so either works for references, but a
router before the models keeps the uniqueness check symmetric with how interceptors are handled.

### D6. File inspection and migration

`FileConfigController.entitySource` gets `case ROUTER -> config.getRouters()` and the two OpenAPI blocks
(`listFileConfigRouters`, `getFileConfigRouter`); `ConfigFileMigrateController` adds `routers` to `ALL_TYPES` and a
`ManagedTypeSpec("routers", "Router", ResourceTypes.ROUTER, Config::getRouters)`, which migrates through the apply
path and so through D3's validator.

### D7. OpenAPI and docs

`ConfigResourceController` gains the `getRouter` / `saveRouter` / `deleteRouter` annotation blocks (tag `Routers`,
schema `allOf {Router, EntityMetadata}`), `ConfigResourceMetadataController` the `getRouterMetadata` block;
`./gradlew :openapi-generator:replaceSpec` regenerates `docs/open_api_core.yaml`. `docs/dynamic-settings/routers.md`
gains a "Configuration API" section: the three URLs, the metadata URL, the manifest kind and name shape, the file
inspection and migration type, blob routers shadowing file routers by name, `defaultHeaders` stored in plain text,
and the rollout notes of the Migration Plan.

## Effect per request

None on the request path: no new pass, body copy, parse or Redis/blob round trip for a client request. A router
`PUT` costs one blob put and one resource event, as any config entity write does; a rebuild lists one more blob
prefix (`routers/platform/`). Measured by inspection of the diff: no class on the request path changes.

## Risks / Trade-offs

- [`DEPENDENCY_ORDER` renumbering touches every later kind] → the comparator reads relative values only; a test
  applies `Model`, `Router`, `Translator` in that input order and asserts the results come back translator, router,
  model. The stale comment "Model (7)" in `AdminApplyApiTest` is updated.
- [A router manifest whose `spec` has `upstreams`] → `ConfigEntityCodec.treeToEntity` fails and the entry is
  `FAILED`, as for a model manifest with an unknown field today; covered by a validate test.
- [The live routers map shared with the scratch] → D5 copies it; a validate of a failing router followed by a GET of
  the merged config shows no change.

## Migration Plan

`router-entity` (PR #2232) and this change may ship in one release or in two. In a mixed fleet where some pods
have `router-entity` only: those pods ignore `routers/...` resource events (`MANAGED_URL_SEGMENTS` does not admit
the segment) and never scan the prefix, so they answer `404` for a blob router until upgraded; they cannot write
one, so no event is lost, and file routers behave the same on every pod. Rollback: a Core without this change
ignores blob routers, which stay in storage for the next upgrade. No data migration.

## Open Questions

None.
