## Context

See proposal.md and `deployment_alias.md`. Today a deployment is resolved by `Config.selectDeployment` (`config`
`Config.java:selectDeployment`) and `DeploymentService.findDeployment` (`server/.../service/DeploymentService.java`),
which checks `Deployment.hasAccess(userRoles)` for config entries. An application calls a model by sending a request
back to Core with the per-request key Core gave it; that second pass gets its own span, analytics row and limit
check, and its `ApiKeyData.executionPath` grows by one (`ApiKeyData.initFromContext`). Only a `Model` pass is
limit-checked (`DeploymentPostController.handleDeployment`) and charged.

## Goals / Non-Goals

Goals: config aliases to config deployments and to application and toolset resources, called by their short name
through every chat, Responses, Messages and MCP entry point, and listed with the deployments of their type.
Non-goals: see "Out of scope" in proposal.md.

## Decisions

### `Alias` is a `Deployment`
`config/.../Alias.java` extends `Deployment` with an optional `presentAs` (`AliasPresentation`: `model`,
`application`, `toolset`) and `target` (`AliasTarget`: `path`, and the reserved `id` and `version`, kept as fields
only so that a config that sets them can be refused rather than silently read). `Alias.getEffectivePresentation`
answers `presentAs`, or the type the first path segment names (`models`, `applications`, `toolsets`); every caller
uses it instead of `presentAs`. `Config.aliases` is a new map; `Config.selectDeployment` looks in it last. Being a
`Deployment` gives the alias `userRoles`, display fields and the shared name space for free. Operational fields it
inherits (`endpoint`, `interfaces`, `features`, ...) are ignored. Rejected: a separate class outside the
`Deployment` tree; every listing and lookup would need a second path.

`storage` gets `ResourceTypes.ALIAS("aliases")`, used here as the entity type of load errors and of the health
report, and by `deployment-alias-public` as the resource type.

### Validation at load
`ConfigPostProcessor.processAliases` runs after toolsets, on the same `deploymentIds` set
(`ConfigPostProcessor.skipOnDuplicate`), so a name clash fails the file load as it does for other deployments.
`AliasTargets.validate` refuses: no `target.path`; `target.id` or `target.version`; an `aliases/` target; a resource
path other than `applications/...` or `toolsets/...`; a config path that is not `<type>/<name>`; and a type that does
not fit a config target the config has. Each is an `InvalidEntityException` (throw when loading the file,
skip-and-record in the merged rebuild, as `processInterceptors` does). A missing config target is a `log.warn` only.
A resource target is not read at load: the load runs without a request and must not do blob I/O per alias; it is
read on each call. Nor is the type of a schema-based application target checked at load: its `mcp` comes from its
schema (`ApplicationSchemaService.getMcp`), which may need a blob read; the call checks it. A role limit keyed by an
alias name logs a warning (`warnOnLimitsUnderAliasName`).
`MergedConfigStore.shallowClone` and `rebuildMergedConfig` carry `aliases` over from the file config;
`isDeploymentIdTakenByAnotherDeploymentType` includes them, so an admin write of a model cannot take an alias name.

### The alias pass is an application that calls Core
`DeploymentService.findCallableDeployment(context, id)` calls `findDeployment` (which checks the alias's
`userRoles`); for an `Alias` it resolves the target (`AliasService.toPass`), checks the type pair, and returns a
per-call pass deployment built by `AliasPasses.build`:

- type `model` or `application`: `AliasApplicationPass extends Application`, named like the alias, with
  `overrideName` = target name (the decoded url for a resource) and one `interfaces` entry per interface the target
  declares (`DeploymentEndpointUtil.isInterfaceDeclared`), each with `baseUrl` = Core's own address. Chat
  completions and embeddings carry an `overridePaths` entry `/openai/deployments/<target>/chat/completions`
  (`.../embeddings`); Responses and Messages need none because `EnhanceDeploymentRequestFn` rewrites the body `model`
  to `overrideName`. An `mcp` target gets `mcp.endpoint` = `<self>/v1/deployments/<target>/mcp`.
- type `toolset`: `AliasToolSetPass extends ToolSet`, `endpoint` = `<self>/v1/toolset/<target>/mcp` or
  `<self>/v1/deployments/<target>/mcp`, `forwardPerRequestKey: true`, the target's `allowedTools`.

Both implement `AliasPass` (`alias`, `targetName`, `isResourceTarget`). Five call sites move from `findDeployment` to
`findCallableDeployment`: `DeploymentPostController`, `ChatCompletionsController`, `ResponsesController`,
`MessagesBaseController` and `McpProxyController`. Every other `findDeployment` caller (listing by id, consent,
limits, credentials) keeps getting the `Alias`. `findDeployment` has no `aliases/...` resource case: an alias is
found only by its short name in `Config.aliases`, so a path never invokes one.

A resolved application target is completed by its schema as `DeploymentController.resolveLocalApplication` does:
endpoints (`modifyEndpointsForCustomApplication`), and `mcp` and `viewerUrl` from the schema, on a copy
(`AliasService.resolveSchema`); found by review, without it a toolset alias of a schema-based MCP application
answered 503.

Because the pass is an `Application`, the existing code already does what the design document asks: no limit check
and no charge in the alias pass (`McpProxyController.limit` skips the check for an `AliasPass`; found by review, the
MCP endpoints limit-checked any deployment, so a limit under the alias name applied there), a per-request key sent as `Api-Key` (`BaseDeploymentPostController.sendProxyRequest`,
`McpUpstreamAuthInjector`), a second pass that Core handles like any child call. `DeploymentService.getInterceptors`
returns none for an `AliasPass`, so global interceptors run once, in the target pass.

Core's own address is `backgroundJob.localBaseUrl`, default `http://localhost:<port>`, already computed in
`AiDial.start` for `BackgroundJobService`; `AliasService` gets the same value.

Rejected: an in-process second pass with no network hop, as the design document proposes. Core builds a pass from a
Vert.x `HttpServerRequest`; there is no way to make one without a connection, and faking one would duplicate
`Proxy.handle`. A loopback HTTP call is how every second pass works today (applications, interceptors, translators).
Rejected: resolving the alias to its target in one pass; the toolset credentials (`ToolSetMcpProxyController`
builds its `CredentialsLocator` from the id in the path), spans and analytics rows would all be keyed by the alias,
and `execution_path` would lack the target.

### Granting the target
An alias is an admin-managed access policy, so it grants the target to every caller who may use it; there is no
check of anybody's rights on the target. `ApiKeyData` gets `lentDeployment`. `ApiKeyData.initFromContext` sets it to
the target name when the context's deployment is an `AliasPass`, and for a resource target also puts the target url
in `attachedDeployments` with `READ`, which `AccessService.lookupPermissions` (`AccessService.java:336`) reads for a
per-request key, as `AutoShareDeploymentFn` does for interceptors. `DeploymentService.isLent(context, deployment)` is
true when the caller authenticated with a per-request key whose `lentDeployment` is the deployment's name.
`findDeployment` skips the `userRoles` check for a lent config deployment, and the four `accessibleByPerRequestKey`
checks skip a lent deployment. A key a pass creates carries the grant on only when that pass is lent for the same
target and is the target itself (its handed-on key, used by a translator calling back) or an interceptor in front of
it (`ApiKeyData.isLentTo`, comparing with `context.getInitialDeployment()`). The grant names one deployment, so none
of these keys reaches another one. The key is stored in Redis by Core and only sent to Core's own address, so a
caller cannot forge or reuse it. The grant lets the target pass call the target, not read it: the caller's own key
is unchanged, and reading the resource with it answers `403`.

### Limits of the target pass (drift found while implementing)
`RateLimiter.getLimitByUser` reads limits only from the caller's roles that are in the deployment's `userRoles`, and
falls back to the `default` role otherwise. A caller who reaches the target only through an alias has none of its
roles there, so a limit defined for its role under the target's name, as the design document asks, was ignored. For
a lent call `getLimitByUser` now reads all the caller's roles, as it does for a deployment with no `userRoles`.
Nothing changes for any other call.

### The alias pass relays the target's answer (drift found while implementing)
`BaseDeploymentPostController.isRetriableError` treats 429, 502, 503 and 504 as "try the next upstream", and the
balancer of a deployment is cached and remembers a failed upstream. For the alias pass the one "upstream" is Core's
own address, so a 429 for one caller's limit came back as 503 and made the alias answer 503 for every caller until
the retry window passed. The target pass has already retried the target's own upstreams, so for an `AliasPass`:
`isRetriableError` is false (also the 429 branch of `McpProxyController`), and `UpstreamRouteProvider.get` builds a
fresh balancer per request instead of the cached one. Rejected: excluding only 429; a 5xx of the target would still
mark Core's own address failed for every caller.

### Listing
`AliasPasses.view(alias, presentation, target)` builds a `Model`, `Application` or `ToolSet` from the target: it
copies the target's operational fields (`interfaces`, `endpoint`, `responsesEndpoint`, `baseUrl` for interface
advertising, `features`, `inputAttachmentTypes`, `maxInputAttachments`, `defaults`, `responsesDefaults`,
`maxRetryAttempts`; for a model also `type`, `tokenizerModel`, `limits`, `pricing`, `embeddingDimensions`; for a
toolset `transport`, `allowedTools`) and sets the alias's name, `userRoles` and display fields. `displayName` and
`description` fall back to the target's when the alias has none; `displayVersion`, `iconUrl` and the other display
fields do not, as the design document asks. The view is built on every listing, so a fallback follows the target.
The view is passed to the existing `createModel`/`createApplication`/toolset mappers, none of which writes `endpoint`
or `baseUrl` into the response (checked: `ModelData`, `ApplicationData`, `ToolSetData`, `InterfaceConfigData`). A
view of an application shown as a model has `type: chat`. An alias with a missing or unfitting target has no view
and is skipped. The listings that answer on the event loop from memory (`/openai/models`, `/openai/deployments`,
`/anthropic/v1/models` and the model part of `/v1/deployments`) build views without storage
(`AliasService.toView(..., inMemory)`): no schema resolution, and an alias to a resource is left out of them.
Rejected: moving those listings to the task executor so that they can read resource targets; it changes the hot
path of every model listing for a case the design document only shows in an example. `/v1/deployment-names` checks a
config alias's target in memory.

### Operations
`AdminHealthConfigController.handle` adds `aliases/<name>` with reason `alias_target_missing` to `skipped` for each
alias of the live config whose config target is missing (`AliasTargets.isTargetMissing`, in memory), and
`alias_target_incompatible` when it no longer fits (`AliasTargets.isTargetIncompatible`): an API write of a target
runs no alias validation (`MergedConfigStore.applyChangeInPlace`), so this is where that shows.
`/v1/deployment-names` leaves out both. A resource
target is not checked there: it needs a blob read per alias. `FileConfigController` and the `ADMIN_FILE_CONFIG`
route serve `aliases` like the other file sections (`entitySource`).

### Errors
A missing target answers `503` (`HttpException(SERVICE_UNAVAILABLE)`), the status Core gives a deployment with
nothing serving the requested interface. A type that does not fit at call time (the target changed type after load,
or a resource target of the wrong kind) also answers `503`.

## Cost per alias call

One more Core pass than a direct call: one loopback HTTP request, one more per-request key written to and read from
Redis, one more token-stats span and analytics row, and the request body parsed twice (once per pass). No blob reads
for a config target, which is in memory. A resource target is read once more in the alias pass
(`ApplicationService.getApplication` or `ToolSetService.getToolSet`, through the resource cache), on top of the read
the target pass does as for a direct call. Measured by the integration tests counting passes through the
per-request key (`executionPath`); no latency measurement is claimed.

## Risks / Trade-offs

- `backgroundJob.localBaseUrl` must reach this pod; behind a proxy that rewrites hosts, operators already set it for
  background Responses jobs.
- The target pass's limits and cost are charged under the target's name, so several aliases to one model share a
  quota, as the design document says.
- A display name or description that falls back to the target changes when the target's does.

## Migration Plan

None. A config without `aliases` behaves as before.

## Open Questions

Carried over from the design document: rewriting `model` in answers; consent across the alias hop.
