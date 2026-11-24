# Design

## Context

A call of an alias runs in two passes. The alias pass checks that the caller may use the alias, mints a per-request
key, and hands the call to the target pass, which serves the target. The key records, in `lentDeployment`, the name of
the target the alias lends; for a resource target it also puts `READ` on the target in `attachedDeployments`, so that
the resource access checks let the target pass read it. In the target pass, `DeploymentService.isLent` compares the
deployment's name with `lentDeployment`: when they match, the caller's roles on the target, the target's
`accessibleByPerRequestKey` and the rate limiter's role match are skipped, and the limits come from all the caller's
roles. The key the target pass mints for an interceptor in front of the target, or for a call back for the target,
carries `lentDeployment` on; a router's decision stops it. Next to all this the key has `perRequestSharedResources`, the
resource urls its holder may use with the rights on each, which the per-request permission API and the resource
dependencies of an application fill.

## Decisions

### D1. The right to call a deployment is `READ` on its url in `perRequestSharedResources`
The alias's grant becomes an entry of the record every other grant uses, so the key has one record of granted access,
and the listing and the access checks see it as a resource right. A resource deployment's url is its name. A config
deployment's url is `<type>/platform/<name>`, the url the config store keeps a platform entity under
(`PlatformEntityLocationStrategy`); the type is the config map the name is in. `READ` stands for "may call": reading a
deployment and calling it are the same right for a resource application today.

**Rejected: a `lent` flag on the entry** - a second meaning in one record that every copy and merge must keep, and the
listing and the access checks would not know it.
**Rejected: a lend set of its own on the key** - two records of granted access again, which issue #2285 asks to remove.

### D2. The grant on a deployment matches its url exactly, not a folder above it
`DeploymentService.isGranted` reads the entry at the deployment's url only. A folder entry such as `models/platform/`
would let a key call every model, and no grant of a folder of deployments has a use.

**Rejected: the folder matching of `AccessService`** - one grant would open every deployment of a type.

### D3. The alias's target becomes a one-entry resource-dependency list, baked as a resource dependency's grant
`AliasService.toPass` turns the target into `[{kind: "dial.resourceLink", target: {url: <target url>}, access:
["READ"], required: true}]`, carried by the pass (`AliasPass.getTargetDependencies`). The alias pass bakes each entry
into its key with `ApiKeyData.grant`, the copy-on-write merge `ResolveResourceDependenciesFn` uses for an application's
dependencies. The admin consent and the check of the caller's own reach that an application's dependencies go through
are not applied: the admin who wrote the alias is the consent, and the alias's `userRoles`, checked when the alias is
found, are the authority; with the reach check, an alias could never grant more than the caller has.

**Rejected: running the alias's list through `ResolveResourceDependenciesFn`** - that function runs in four
controllers only, not on the toolset MCP path, and the reach check would refuse every grant an alias exists for.
**Rejected: putting the list in the pass's `Application.resourceDependencies`** - `ResolveResourceDependenciesFn` would
then apply the admin consent to an application pass and fail its required entry.

### D4. The grant is baked after the per-request permissions are shared on
The key's sharing of `perRequestReceivers` copies whole entries with `putAll`, which would replace the grant's entry
with a plain one of the same url. Baking the grant after it, with the merge, keeps both rights.

**Rejected: baking first, as `lentDeployment` was set first** - a share of the target's url to the alias would drop
the grant.

### D5. What goes on is `READ` on the url of the deployment the pass acts for, with the alias name
An interceptor's hop acts for the deployment its chain is in front of, the initial deployment; any other pass acts for
its own deployment, the target pass and a call back for the target among them. When the incoming key holds `READ` on
that deployment's url, the minted key gets `READ` on it and the incoming `aliasName`; a router's decision still stops
both. The url is worked out once (`DeploymentGrants.urlOf`): the platform url of a config name, or else the resource
url, decoded and encoded again, as the initial deployment is a deployment's encoded name on chat completions and
Responses, and the decoded id from the path on deployments POST.

**Rejected: trying the receiver's url, the initial deployment's and its encoded form in turn** - the pass's kind
already says which deployment it acts for, and a pass of another deployment would get the grant of the initial one.
**Rejected: carrying the whole entry** - its other rights are a resource grant of their own, which the sharing of
`perRequestReceivers` already decides.

### D6. The per-request permission API refuses a platform url
A global reader holds `READ` on every resource, so its application could pass `READ` on `models/platform/<name>`, the
right to call the model, on to another application. Core refuses every url in the platform bucket, whatever the
caller holds; only Core grants rights on config deployments.

**Rejected: refusing only the deployment types** - the platform bucket holds admin config only, and no grant of it
has a use.

### D7. The alias pass records the alias's name in the key, and the target pass finds the alias by it
The alias pass writes the alias's name as `aliasName` next to the grant, and D5 carries it with the grant.
`DeploymentService.findCallableDeployment`, which runs on the task executor, looks the alias up by that name when the key
holds the grant on the deployment: in the config (platform aliases are merged into it) or, for a name under
`aliases/public/`, in storage through the resource cache. The alias goes on the context, and `RateLimiter` takes the
limits from the caller's roles in its `userRoles`. `RouterDecisionFlow` answers under `aliasName` and needs no lookup.
The name grants nothing, so the key keeps one record of granted access.

**Rejected: the alias's `userRoles` in the key** - a copy of the alias's definition in every key, which may be stale,
and a second record next to the grant.
**Rejected: finding the alias in the execution path and checking its target** - a walk of the path next to the router's,
to learn what the alias pass already knew.
**Rejected: reading the alias in `RateLimiter`** - the limiter runs on the caller's thread, and a stored alias needs a
storage read.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| Record of an alias's grant | `lentDeployment`, and `attachedDeployments` `READ` for a resource target | `READ` in `perRequestSharedResources` on the target's url | deployment-aliases "Grant of the target in the per-request key" |
| Limits of the target pass | all the caller's roles | the caller's roles in the alias's `userRoles`; the target's rule for an alias without them | deployment-aliases "Limits of a call through an alias" |
| `READ` on a deployment's url from the per-request permission API | not possible for a config deployment; for a resource deployment it lets the receiver read it | a platform url is refused; `READ` on a resource deployment also skips its `userRoles` and `accessibleByPerRequestKey` | resource-access-control "Grant per-request permissions" |
| `list` with `{"with":"ME"}` in the target pass | does not show the alias's grant | shows `READ` on the target's url | resource-access-control "Revoke and list per-request permissions" (unchanged text) |

## Effect per request

| What | Today | After |
|---|---|---|
| Key | one field, `lentDeployment` | one `perRequestSharedResources` entry and one string, `aliasName` |
| Target pass of a config alias call | nothing looked up | one map lookup in the config |
| Target pass of a stored alias call | nothing looked up | one read of the alias through the resource cache, which the alias pass's own lookup filled |

No other request changes. The stored alias lookup is exercised by the stored alias test of the limits.

## Risks / Trade-offs

- `READ` on a resource deployment granted by the per-request permission API now also skips the deployment's
  `userRoles` and `accessibleByPerRequestKey`. A resource application has no `userRoles`, and its readers may call it
  today, so the change is in `accessibleByPerRequestKey` only.
- The alias's `userRoles` are read when the target pass runs: a change of the alias during the call applies to its
  target pass, and an alias deleted meanwhile gives the target's own rule.

## Migration Plan

none. A per-request key lives for one request; a key written by 0.50, with `lentDeployment`, still decodes, as
`ApiKeyData` ignores unknown fields.

## Code map

- Key and grant: `ApiKeyData.java:initFromContext`, `ApiKeyData.java:sharePerRequestPermissions`, new
  `ApiKeyData.java:grant`; the merge it takes from `ResolveResourceDependenciesFn.java:bakeGrant`.
- Url of a deployment: new `DeploymentGrants.java:urlOf`; config maps as `AliasTargets.java:resolve` reads them;
  `ResourceDescriptorFactory.java:fromDecoded` with `ResourceDescriptor.PLATFORM_BUCKET`; `UrlUtil.java:decodePath`,
  `UrlUtil.java:encodePath`. The initial deployment: `ChatCompletionsController.java:dispatch`,
  `DeploymentPostController.java:handleDeployment`, `BaseInterceptorController.java:handle`.
- Alias pass: `AliasService.java:toPass`, `AliasPasses.java:build`, `AliasPass.java`, `AliasApplicationPass.java`,
  `AliasToolSetPass.java`; `ResourceDependency.java`.
- Grant check: `DeploymentService.java:isLent` (becomes `isGranted`), `DeploymentService.java:findDeployment`,
  `DeploymentService.java:findStoredAlias`; the `accessibleByPerRequestKey` checks in
  `ChatCompletionsController.java:setupDeployment`, `ResponsesController.java`, `MessagesBaseController.java`,
  `DeploymentPostController.java`.
- Alias name and limits: `ApiKeyData.java:initFromContext`, `DeploymentService.java:findCallableDeployment`,
  `DeploymentService.java:findCallingAlias`, `AliasService.java:findStoredAlias`, `ProxyContext.java`,
  `RateLimiter.java:getLimitByUser`, `RouterDecisionFlow.java:aliasCalled`.
- Per-request permission API: `PerRequestPermissionService.java:validatePermissions`.
- Tests: `ApiKeyDataTest`, `AccessServiceTest`, `DeploymentServiceGrantTest`, `AliasServiceTest`, `AliasApiTest`, `RouterDecisionApiTest`,
  `PerRequestPermissionsApiTest`; fixture `dial-config/aliases.json`.
