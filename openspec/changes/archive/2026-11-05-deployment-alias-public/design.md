## Context

See proposal.md and `deployment_alias.md`. Phase 1 (`deployment-alias-config`, archived in b028e3bf7) resolves a config
alias by its short name (`Config.java:selectDeployment`), allows it by `userRoles`
(`DeploymentService.java:findDeployment`) and calls the target through a second pass (`AliasService.java:toPass`).
A public application is resolved on demand: `DeploymentService.java:findDeployment` turns a url that names no config
deployment into a resource (`toResourceDescriptor`, with a read-access check) and reads it
(`ApplicationService.getApplication`, through the resource cache). Stored aliases follow the public-application model.

## Goals / Non-Goals

Goals: aliases stored under `aliases/public/<name>`, managed by admins, called and listed by their url. Non-goals: see
"Out of scope" in proposal.md.

## Decisions

### Resolved on demand, by url
`DeploymentService.java:findDeployment` gets an `ALIAS` case: `toResourceDescriptor` answers `404` for an `aliases`
url that is not directly in `aliases/public/` (another bucket, a folder), before the read-access check;
`findStoredAlias` reads the blob with `AliasService.findStoredAlias` (`ResourceService.getResource`, cached), names the
alias by its url, and checks `userRoles` (`403`). `findCallableDeployment` then builds the pass as for a config alias,
so the alias pass and its `execution_path` entry carry the url. A blob whose fields are invalid is logged and treated
as missing. Every caller of `findDeployment` already runs on the task executor, since a public application is read the
same way: `DeploymentPostController.handleDeployment`, `ChatCompletionsController.setupDeployment`,
`ResponsesController.setupDeployment`, `MessagesBaseController.setupDeployment`, `McpProxyController`,
`DeploymentController.getDeploymentInfo`, `ApplicationController.getApplication`, `ToolSetController.getToolSet`, and
the request functions run in `handleRequestBody` on the executor.

Rejected: merging stored aliases into `Config.aliases` through `MergedConfigStore` (the first version of this change).
It needed a managed type in the public bucket, replica events, a shared name space with a rule kept identical on four
write paths, and invalid-entity bookkeeping, all for aliases that are called by url anyway. Without the merge,
`MergedConfigStore`, `ConfigPostProcessor`, `PlatformEntityLocationStrategy` and `ResourceTypes.EVENT_TYPES` stay as in
phase 1.

### Targets of a stored alias
`StoredAliases.validateFields` adds one rule to `AliasTargets.validateFields`: a resource target must be in the `public`
bucket. It runs on `PUT` (`ConfigResourceController.checkAliasTarget`) and when a blob is read for a call or a listing
(`StoredAliases.parse`), so a blob written another way is ignored too. Decided with the user: a stored alias grants its
target to every caller in its `userRoles`, and the owner of a user bucket could change an application there at any
time without review. Config-file aliases keep any resource target (phase 1); the config file is the admin's own.

### Management API
Routes `ALIAS_RESOURCE` / `ALIAS_RESOURCE_METADATA` (`^/v1/(aliases)/<bucket>/<path>`), registered before `RESOURCE` in
`ControllerSelector`, go to `ConfigResourceController` and `ConfigResourceMetadataController`; `RESOURCE` and
`RESOURCE_METADATA` do not accept `aliases`. `EntityBucketBinding` binds `aliases` to `public` only (`404` before
authorization). `AdminRoleAuthorizationService.java:isAuthorized` requires the admin role for every operation on
`aliases`, `GET` included. `ENTITY_NAME_PATTERN` answers `400` for a nested path or a bad name, and a stored alias name
must also be letters, digits, `.`, `_` and `-` (`StoredAliases.isUrlSafeName`): the alias is named by its encoded url
and called by the id a client sends, which Core encodes once more (`toResourceDescriptor`), so a name the encoding
changes (`%`, `[`, `]`, space) would be listed under an id that cannot be called back.
Public applications carry the same mismatch today; aliases avoid it by refusing those names.

`ConfigResourceController.java:handleAliasPut` writes only the blob: `StoredAliases.fromRequest` keeps `presentAs`,
`target`, `userRoles` and the display fields (either spelling), ignores `status` and a `name` equal to the path name,
and answers `400` for any other field or `name`; then, under the admin bucket locks, the conditional headers,
`checkAliasTarget` (`AliasTargets.validate` as at load; a resource target read with `AliasService.findTarget`: missing
is accepted, present must fit `presentAs`), and the blob write of `StoredAliases.toJson`. `handleAliasDelete` deletes
the blob (`If-Match`). `handleAliasGet` reads the blob as calls do (`StoredAliases.parse`, shared with
`AliasService.findStoredAlias`): `status: valid` with the stored fields, or, for a blob calls ignore, the body as stored
with `status: invalid` and its `validationWarnings`. Field and type problems answer `400` with the `validationWarnings`
body. A missing config snapshot only skips the config target check (`checkAliasTarget`).

A listing does not fail when storage cannot list or read `aliases/public/`: `DeploymentService.listStoredAliasesOrNone`
logs a warning and the listing answers with the config deployments and config aliases.

Rejected: `ResourceController` with an admin check; its write path serves user resources and would need the alias
validation, flat-name and bucket rules added there, while `ConfigResourceController` already has the admin, bucket and
conditional-header handling.

### Access
A stored alias is allowed by its `userRoles`, as a config alias is (`Deployment.hasAccess`; none means every caller).
The design document's "Existing public-resource access rules" cannot apply: the publication API refuses rules for the
root folder (`PublicationService.java:595`) and `RuleService.java:evaluate` treats a resource whose parent is the bucket
root as allowed, so they would let every caller use every alias.

### Listings
`DeploymentService.listUsableAliases` takes the config aliases and `AliasService.listStoredAliases` (one listing of
`aliases/public/`, recursive as `ResourceService.listResources` is, keeping the items directly in it, with their
bodies), filters by presentation and `userRoles`, and `listAliasViews`/`listAliasNames` build the views
(`AliasService.toView`). The model listings (`ModelController`, `AnthropicModelController`,
`DeploymentController.getModels` through `AliasListings`) therefore always build their alias part on the task executor;
the other listings were there already. `findAliasView` answers for a config alias name or a stored alias url
(`DeploymentService.isStoredAliasId`), and `AliasListings.isAliasId` makes the single-item endpoints hop for both.

Single-item routes checked: `/openai/models/{id}`, `/openai/deployments/{id}`, `/anthropic/v1/models/{id}`,
`/v1/deployments/{id}`, `/openai/deployments/{id}/chat/completions` and `/v1/toolset/{id}/mcp` all match `(?<id>.+?)`
and accept slashes (`RouteTemplate`), so they answer for `aliases/public/<name>` as they do for public applications;
covered by `AliasPublicApiTest`.

`AliasService.toView` with `inMemory` is gone; `/v1/deployment-names` uses full views, so an alias to a missing
resource target is left out. This removes the phase-1 requirement "Listings answered from memory".

### Rejections
Sharing (`ShareService.java:getDefaultLimit`, `400`), publication add and delete
(`PublicationService.java:verifyResourceType`, `400`) already refuse. Move answered `500`
(`ResourceOperationService.java:move`), so `ResourceOperationController.java:rejectAlias` refuses copy and move with
`400`.

### Health
`AdminHealthConfigController` reports config aliases as in phase 1 (`aliases/<name>`), then lists the stored aliases on
the task executor and reports those whose config target is missing or incompatible by their url
(`aliases/public/<name>`). A resource target is not checked there, as in phase 1.

### Manifest names
`ConfigManifestSupport.java:newScratch` copies `aliases`, so `/v1/admin/apply` and `/v1/admin/validate` refuse a model
named like a config-file alias (`validateDeploymentIdUniqueness`). Before, the scratch config had no aliases and the
model and the alias could both enter the config, lookup order deciding. (Drift fixed while implementing.)

### Deliberate differences from the design document
- Stored aliases are called by their url `aliases/public/<name>`, not by their short name; requirement 13 and the
  section "Short-name resolution and uniqueness" apply to config aliases only, and stored aliases share no name space.
- Access is by `userRoles`, not "Existing public-resource access rules" ("Who may do what"); see "Access".
- `GET /v1/metadata/aliases/public/` is admin only, not "visible to the caller".
- Copy and move of an alias are refused outright, instead of applying the alias checks to the destination.
- No alias folders: `aliases/public/<name>` only.
- `PUT` answers `400`, not `422`, for validation warnings, as the design document's "Invalid bodies return 400".

## Cost per request

- A call of a stored alias: one blob read (resource cache) in `findDeployment` more than a config alias, plus the
  target read for a resource target, as for any alias.
- A listing that shows aliases (every model listing, `/openai/applications`, `/openai/toolsets`, `/v1/deployments`,
  `/v1/deployment-names`): one hop to the task executor and one folder listing of `aliases/public/` with one read per
  stored alias, once per request: `/v1/deployments` and `/v1/deployment-names` share it across the types they list
  (`DeploymentService.storedAliasesOnce`, measured by `DeploymentServiceTest`). Then, per listed alias, the view
  (`AliasService.toView`): a read of a resource target, and for an application target its schema
  (`ApplicationSchemaService`), which may read storage; `/v1/deployment-names` builds the same views.
- `/v1/admin/health/config`: one folder listing plus one read per stored alias.
- A write: one metadata read, a resource target read when the target is a resource, one blob write.
Measured only by the tests passing through these paths; no latency measurement is claimed.

## Risks / Trade-offs

- Every model listing now hops to the task executor and lists `aliases/public/`, even with no stored alias.
- A stored alias without `userRoles` serves every caller, as a config alias does.
- Many stored aliases make every listing read them all; there is no index.

## Migration Plan

None. No alias is stored until an admin writes one.

## Open Questions

None.
