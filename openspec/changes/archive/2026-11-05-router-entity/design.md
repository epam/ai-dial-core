# Design

## Context

See proposal.md, Why. Line numbers are from `development` on 2026-11-04.

What exists today, and what this change builds on:

- Config entities of a deployment kind are `Model`, `Application`, `ToolSet` and `Interceptor`, all
  `extends Deployment` (`config/.../Deployment.java:16`, which carries `baseUrl`, `interfaces`, `userRoles` via
  `RoleBasedEntity`, display and feature fields, `defaults`, `defaultHeaders`, `interceptors`, `overridePaths` and the
  `resolveFeatures` / `resolveDefaultHeaders` / `resolveDefaults` helpers at 147-180). `Interceptor.java:3` is a bare
  subclass and the closest template. The four maps share one deployment-id namespace (`Config.java:35-47`) and
  `Config.selectDeployment` (`Config.java:91-108`) looks a name up across them; `DeploymentService.findDeployment`
  (`DeploymentService.java:63-78`) is the only way the request path resolves a config deployment, adding the
  `hasAccess` check.
- The config file is bound by Jackson with its default strictness (`FileConfigStore.buildJsonMapper`,
  `FileConfigStore.java:135-149`); only `Config` itself ignores unknown fields (`Config.java:28`). A field a
  deployment class does not declare rejects the config.
- Validation on load runs in `ConfigPostProcessor.processSemantic` (`ConfigPostProcessor.java:115-132`), one
  `process<Kind>` per map threading a shared `deploymentIds` set through `skipOnDuplicate` (912-928), which throws
  under `abort` and records the entity under `skip`. `processInterceptors` (864-887) is the smallest instance:
  `setName`, `validateOverridePaths`, raise or record. Interface rules run for models only, in
  `validateDeploymentInterfaces(Model, ...)` (625-648), reached from `validateModelInvariants` (513-519). The
  cross-kind dedupe map is `isDeploymentIdTakenByAnotherDeploymentType` (935-948).
- `MergedConfigStore.rebuildMergedConfig` (1209-1395) copies every file map into the merged config (1217-1249) and
  `shallowClone` (1016-1037) copies every map on a partial update; a map left out of either is dropped silently.
- Every listing iterates a named map, never "all deployments": `ModelController.getModels` (74-89),
  `DeploymentController.getModels`/`listDeployments` (440, 153), `ApplicationController.getApplications` (141),
  `ToolSetController.getToolSets` (94), `AnthropicModelController.getModels` (94-100), `LimitController.getUserLimits`
  (126). `DeploymentController.getDeploymentInfo` (268) resolves any deployment and answers `404` for a kind its
  switch (277-284) does not know.
- The POST paths treat an unknown `Deployment` subclass as an application everywhere but one place:
  `UpstreamRouteProvider.getKey(Deployment)` (168-178) switches on `Model`, `Application`, `ToolSet` and throws for
  anything else; it is reached from `BaseChatCompletionController:113`, `ResponsesController:340` and
  `MessagesBaseController:173`. Limits: deployments-POST checks only a `Model`
  (`DeploymentPostController.handleDeployment`, 177); the three other APIs call `checkLimits` or
  `SpendCapCheck.verify` for any kind (`ChatCompletionsController.verifyLimit:162`,
  `ResponsesController.verifyLimit:262`, `MessagesController.verifyLimit:143`), all gated by
  `BaseDeploymentPostController.isSubjectToLimits` (244-246), which today reads only the interface mode.
- Headers: `BaseDeploymentPostController.sendProxyRequest` (405-457) copies the client's headers through
  `ProxyUtil.copyHeaders` (86-95) minus an exclude list, then adds Core's own; the application-id and properties
  headers are both excluded from the copy (414-415) and added afterwards (434-443), which is the no-spoof pattern.
  `UserInfoController.handle` (36-55) is the source of the identity values: `context.getUserRoles()`,
  `context.getKey().getProject()` for a project key, `context.getExtractedClaims().userId()` for a JWT.
- `ResourceTypes` (`storage/.../ResourceTypes.java:6-42`) declares `NAME(group, requireCompression, ttl)`;
  `EVENT_TYPES` (51-54) says a type must be listed one release before a consumer relies on its events; `of(String)`
  (69-97) is an explicit switch. `AiDial.java:470` copies each type's TTL into the settings.
- A new controller is picked up by the OpenAPI generator only when listed in
  `AnnotationEndpointCollector.CONTROLLER_CLASSES` (`openapi-generator/.../AnnotationEndpointCollector.java:71`);
  `./gradlew :openapi-generator:replaceSpec` regenerates `docs/open_api_core.yaml`, and the build fails when the
  committed spec is stale.

Reference: documentation MR 211, `design/router_resource.md`, sections "Resource model", "Invocation",
"Listing and metadata", "Limits, pricing and analytics".

## Goals / Non-Goals

**Goals:**

- A router is a `Deployment` the existing controllers already know how to serve; the change adds a kind, not a path.
- Nothing changes for a config without `routers`: same listings, same headers, same limits.
- The next change can attach `mode: decision` to `interfaces.<type>` of a router without touching the entity.

**Non-Goals:**

- The Configuration API, manifests and file inspection for routers (follow-up, touch list below).
- A `customProperties` field or app-type schema on a router.
- Any new abstraction: no `DeploymentKind` enum, no registry, no shared "listing controller".

## Decisions

### D1. `Router` is a bare `Deployment` subclass; forbidden fields are rejected by the class shape

`config/.../Router.java` is `public class Router extends Deployment {}` plus the Lombok annotations `Application`
uses (`@Data @EqualsAndHashCode(callSuper = true)`). `upstreams`, `limits`, `pricing` and the application fields are
rejected because the class does not declare them and the config mapper fails on unknown properties. The `Deployment`
fields a router must not use are rejected in `processRouters`: `interceptors` (`A router declares no
interceptors`) and the legacy `endpoint` / `responsesEndpoint` (`A router declares interfaces, not endpoint`),
which would otherwise serve the deployments-POST family through the legacy rule MR 211 excludes for routers.

Rejected: a `Router` with explicit `@JsonIgnoreProperties(ignoreUnknown = false)` or hand-written field checks. The
mapper default already does it; the integration test "Router with upstreams rejected" pins the behaviour so a later
mapper change cannot loosen it unnoticed.

### D2. Interface validation reuses the model rules by widening one parameter type

`validateDeploymentInterfaces(Model, ...)` and the `validateTranslatedInterface` it calls use only `Deployment`
getters; the `Model` type appears in the signature and in the `"Model"` prefix of `messageWithEntityPrefix`. Change
the parameter to `Deployment` and pass the kind name (`"Model"` / `"Router"`), used both as the message prefix and
in the tail of `declares no base_url and the model declares no baseUrl`, and call it from `processRouters`.
`validateModelInvariants` keeps its signature and semantics for the write paths. This keeps the
`validate-config-on-every-write` rule trivially true for routers in this change, because routers have no write path
yet; the follow-up admin change calls the same method from its PUT, validate and apply paths.

Rejected: a router-specific validator. It would be the same loop with a different word.

### D3. Dedupe and merge: the router map joins every per-map list

`processStructural` (`rejectSlashKeyedNames`), `processSemantic` (`processRouters` after `processToolSets`, sharing
`deploymentIds`), `isDeploymentIdTakenByAnotherDeploymentType` (add `ROUTER -> getRouters()`),
`Config.selectDeployment` (before interceptors), `MergedConfigStore.rebuildMergedConfig` (copy and set the map next
to `toolsets`) and `MergedConfigStore.shallowClone`. `MANAGED_TYPES` and the blob-scan switches stay untouched:
routers are file-only here, so no `routers/` blob is listed, parsed or decrypted. A test on `shallowClone` behaviour
(partial update keeps routers) guards the silent-drop case.

### D4. `ResourceTypes.ROUTER` is registered now, used later

`ROUTER("routers", true, TimeUnit.DAYS.toMillis(30))`, added to `EVENT_TYPES` but not to `of()`. `processRouters`
needs the enum constant for `skipOnDuplicate` and `InvalidEntityException`, and the event-type rollout rule in the
enum's Javadoc means listing it one release before the admin change is what lets that change rely on replica
events at once. `of("routers")` stays unknown on purpose: it is what makes a `routers/...` URL parse in the share,
publication and invitation services, and nothing in this change serves such a URL; the admin change adds it with
the routes. `MergedConfigStore.isManagedEventUrl` (347) ignores a `routers/` event because `MANAGED_URL_SEGMENTS`
does not list it. `AiDial.java:470` adds a `resourceTypesExpiration.ROUTER` default, which is harmless.

Rejected: leaving the enum out and writing a router-specific duplicate check. More code, and the admin change
would then span two releases.

### D5. The POST paths change in two places only

- `UpstreamRouteProvider.getKey`: `case Router ignored -> "router"`. Without it every chat, Responses and Messages
  call to a router dies with `IllegalArgumentException` at route resolution.
- `BaseDeploymentPostController.isSubjectToLimits`: `return !(deployment instanceof Router) && resolveMode(...).isSubjectToLimits()`.
  That one predicate already gates `checkLimits`, `increaseLimits` and `SpendCapCheck.verify` on all three
  non-deployments-POST APIs; deployments-POST checks only a `Model` and needs nothing.

Everything else on the POST path treats a router as an application already: `findDeployment` with the role check,
consent, `accessibleByPerRequestKey`, `resolveServingEndpoint` (`503` when the interface is not declared), the
per-request key (`ApiKeyData.initFromContext`, type-agnostic), `getUpstreams` (synthesizes one upstream from the
resolved url for any non-`Model`), the translator-mode `X-DIAL-DEPLOYMENT-ID` header, usage roll-up for a
non-`Model` (`collectTokenUsage` to `trackDeploymentStats`), and the analytics row, which has no deployment-type
column (`AnalyticsLogContext.from`, `GfLogStore:247-262`).

Difference from today's behaviour, deliberate: a role's `limits.<router>` entry is ignored on the three APIs that
would check it for an application. MR 211: "A router has no limits of its own." `GET /v1/deployments/<router>/limits`
(`LimitController.getDeploymentLimits:66`) still resolves the router and answers the role's figures, which never
move; routers are not listed, so no client discovers that URL, and `routers.md` says what it shows. Not changed.

### D6. One identity header, the user-info JSON

`X-DIAL-USER-INFO` carries one JSON object built from the same three values `UserInfoController.handle` answers:
`roles` always, `project` for a project-key caller, `userId` for a JWT caller. Added in `sendProxyRequest` under
`context.getDeployment() instanceof Router`, after `copyHeaders`; the header name joins the exclude list next to
`HEADER_APPLICATION_ID` (414-415), so no origin request sent through `sendProxyRequest` carries a client-written
value. The interceptor path (`BaseInterceptorController:127`) copies client headers with no exclude list and is
left as it is: an interceptor is a trusted component, and the router forward after the chain rebuilds the header. The constant
lives in `Proxy.java` with the other `HEADER_*` names. Serialized with `ProxyUtil.MAPPER`; enable
`JsonWriteFeature.ESCAPE_NON_ASCII` on that one write so a non-ASCII user id stays a valid header value.

Why one JSON header over three plain headers (`X-DIAL-USER-ID`, `-ROLES`, `-PROJECT`): the receiving router already
parses the user-info response JSON (the Go router's identity resolver does), so one header with that shape is one
code path on both sides, and it sidesteps the "roles with commas" and non-ASCII encoding questions that three
headers raise. Why not every deployment: an application that reads this header would start trusting it, and the
claims it carries are already available to applications through `GET /v1/user/info`; widening is one condition
change later.

Not sent: `userClaims` and `userDisplayName` (user-info returns them; they can be large and no router reads them).

### D7. Listing: a `RouterController` cut from `ModelController`, `RouterData` cut from `ToolSetData`

`RouteTemplate.ROUTERS` (`^/+v1/routers$`) and `ROUTER` (`^/+v1/routers/(?<id>[^/]+)$`), registered in
`ControllerSelector` next to `MODELS`/`MODEL` (102-109) and before the resource routes. The one-segment id keeps the
future admin route `/v1/routers/platform/{path}` disjoint; a router named `platform` is served by the single GET
today and the admin change reserves the name when it adds the route. `RouterController.getRouters` loops `config.getRouters()`
filtered by `hasAccess` into a `ListData<RouterData>`; `getRouter` is `getRouters().get(id)`, `404`, `403`, `200`
in that order, as `ModelController.getModel` (49-63). `RouterData extends DeploymentData` with
`object = "router"`, `scaleSettings = null`, filled by a `toData(Router)` like `ToolSetData.toData` (31): id, display
name (falling back to the name), display version, icon, description, intro, reference, features
(`FeaturesData.createDeploymentFeatures`), defaults, description keywords, created and updated at. No url field.
`DeploymentData`'s `@ApiSubTypes` is not extended: routers never come back from `/v1/deployments`.
`RouterController` is added to `AnnotationEndpointCollector.CONTROLLER_CLASSES`, and `docs/open_api_core.yaml` is
regenerated.

Rejected: reusing `DeploymentController.listDeployments` with a kind filter. It would put routers one flag away from
a listing MR 211 says they must stay out of.

### D8. Docs

New `docs/dynamic-settings/routers.md` on the `interceptors.md` / `toolsets.md` pattern: the map, the common
fields with a pointer to `models.md` for `interfaces`, the "no limits, no upstreams, no interceptors" rule, the
listing endpoints and the identity header. One row in the README dynamic-settings table (437-449) and the word
`routers` in CLAUDE.md's dynamic-config line (71).

## Per-request cost

For a request to a router, compared with the same request to an application: the same single Core pass, the same
one body read and parse, the same per-request key write and token-stats span, the same analytics row. Added: one
small JSON serialization (roles list plus two strings) for the header. Removed on the router's side: one
`GET /v1/user/info` call per request, which was one Core request with its own Redis read. For a request to any
other deployment: one more name in the header exclude list, nothing else. Measured by the perf improvement
initiative's A/B on the router path once `router-decision-mode` lands; this change on its own claims no cost
effect and carries no measurement task.

## Risks / Trade-offs

- [A client-visible `IllegalArgumentException` if any other `switch (deployment)` without a default is missed] →
  the grep in the apply step covers `instanceof Model`, `instanceof Application`, `case Model`, `case Application`
  on `server/src/main`; the integration test sends a router request through all three POST controllers.
- [Roles lists of several hundred entries push `X-DIAL-USER-INFO` past a receiver's header limit] → Go's default
  server header limit is 1 MB and Vert.x's client does not bound outbound headers; document the ceiling in
  `routers.md`. If a deployment hits it, the fix is a role allow-list on the router entry, not a change of shape.
- [`ResourceTypes.ROUTER` makes a `routers/...` blob parseable before any code reads one] → nothing lists the group
  until the admin change; `isManagedEventUrl` ignores its events.
- [An operator declares `limits.<router>` on a role and expects it to bite] → `routers.md` states that limits on a
  router are ignored and where to put them (the models it calls).

## Migration Plan

none. A config without `routers` behaves as before. Rolling back to the previous release with `routers` still in
the config is safe: `Config` ignores unknown top-level keys (`Config.java:28`), so the file loads and the routers
are absent, which means requests to them answer `404` until the release is restored.

## Implementation notes

Compared with the code on 2026-11-04 before coding: the cited lines (`processInterceptors` 864,
`validateDeploymentInterfaces` 625, `isDeploymentIdTakenByAnotherDeploymentType` 935, `shallowClone` 1016,
`rebuildMergedConfig` 1209, `UpstreamRouteProvider.getKey` 169, `isSubjectToLimits` 244, `sendProxyRequest` 405)
still hold. Drift and adjustments:

- D2: no kind-name parameter. `validateDeploymentInterfaces`, `validateTranslatedInterface` and
  `validateTranslatorOutput` take a `Deployment` and use `getClass().getSimpleName()` as the message prefix, which is
  what `validateOverridePaths` in the same class already does; the lower-cased name fills "the model declares no
  baseUrl" and the two "The model serves / does not serve" messages. Every `Model` message is unchanged
  (`ConfigPostProcessorTest.testModelInterfaceMessageUnchanged`).
- `Limit` has no `requestMinute` (only `minute`, `day`, `week`, `month`, `requestHour`, `requestDay`), and the config
  mapper rejects an unknown field. The fixture uses `requestHour: 1` / `requestDay: 1`, and the router-deployment
  scenario "Exhausted request limit on the router does not refuse" now says `requestHour` and "within one hour".
- D6: the header is built by `ProxyUtil.buildUserInfoHeader(ProxyContext)`, used by `sendProxyRequest` and by
  `DeploymentFeatureController` (`/v1/{id}/rate`, `/tokenize`, `/truncate_prompt`, `/configuration`), which also
  forwards to a router and copies client headers on its own path. Both now drop a client `X-DIAL-USER-INFO` for every
  deployment and add Core's for a router (`RouterApiTest.featureEndpointCallCarriesTheIdentityHeader`).
  Response-item operations (`GET`, `DELETE`, `cancel`, `input_items` on `/openai/v1/responses/{id}`) go through
  `ResponsesApiClient.send`, which builds a fresh request and copies no client header; it takes the header value as a
  nullable argument, and `ResponseItemController` passes one for a router
  (`RouterApiTest.responseItemCallCarriesTheIdentityHeader`).
- Task 2.5 sweep of `server/src/main`: the only `switch` over a deployment kind without a default a router reaches is
  `UpstreamRouteProvider.getKey` (D5). `DeploymentController.toDeploymentData` has a `default` that answers `404`,
  which is the wanted result for a router; its comment now names routers.
- The JWT scenario test uses roles `["analysts", "b"]`, since the fixture's `r1` admits only `analysts`.
- OpenAPI: besides the two router paths, `RouterData` and `ListDataRouterData`, the regenerated
  `docs/open_api_core.yaml` gains `routers` / `MapStringRouter` / `Router` under the documented `Config` schema and
  `ROUTER` in the resource-type enum. Both follow from the change; nothing was hand-edited.

## Follow-up: the admin-API change (not in this change)

From the code map taken for this design, adding routers to the Configuration API touches: `MergedConfigStore`
(`MANAGED_TYPES`, `deserializeReplicaEntity`, `applyChangeInPlace`, `cloneTypeMap`, `peekEntity`,
`putEntityInPlace`, `removeEntityInPlace`, `isShortNameKeyed`, `addBlobEntity`, `removeAddedEntity`),
`PlatformEntityLocationStrategy.resolveBucket`, `EntityBucketBinding.ALLOWED_BUCKETS`, the `CONFIG_RESOURCE` and
`CONFIG_RESOURCE_METADATA` regexes in `RouteTemplate`, `ConfigResourceController` (`handleGet`, `descriptorFor`,
`prepareWrite`, `handlePut` checks, `entityClassFor`, OpenAPI blocks), `ConfigResourceMetadataController`
annotations, `AdminRouterManifest` plus `AdminTypedManifest` and `AdminManifest`, `ConfigManifestSupport`
(`DEPENDENCY_ORDER`, `KIND_URL_SEGMENT`, `newScratch`, `mutateScratch`, `parseManifest`), `ConfigValidationService`,
`ConfigApplyService.applyManagedEntity`, `ConfigPostProcessor.validateSingleRouter`, and optionally
`FileConfigController` and `ConfigFileMigrateController`. Interceptor is the template for all of it.
