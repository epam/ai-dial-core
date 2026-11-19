# Design

## Context

Only the alias pass decides who may use an alias. It checks twice: before the target is read (the deployment lookup
and the listing filter), and once the target is resolved (the call, the listing view, toolset sign-in and consent). A
router that chooses an alias as a candidate goes through the same lookup. Without `userRoles`, both checks ask the
target today: a config target's `userRoles`, the caller's read access to a resource target, or the `userRoles` of a
resource in the `platform` bucket, which only admins can read. The alias pass then lends the target to the target
pass, and attaches a resource target with read access to the per-request key, so the target pass's own checks always
pass. Stored aliases, public and platform, keep `userRoles` in their body and go through the same checks.

## Decisions

### D1. A resource target keeps today's rule, the platform bucket included

Only the alias pass checks a resource target: the per-request key it makes gives the target pass read access to the
resource. So dropping the check would open a resource to callers who cannot read it. For the `platform` bucket the
rule today is the resource's `userRoles`; it stays, so that every resource-path target keeps the rule it has. The same
platform application named by its config name (`applications/<name>`) is a config target and is open.

**Rejected: treat a `platform` resource like a config target** - one rule for both names of the same application,
but it opens existing aliases over `applications/platform/...` too; an easy follow-up if wanted.

**Rejected: drop the resource check because the target pass checks it** - the target pass does not.

### D2. No migration code and no start-up warning

The operator gives `userRoles` to the aliases that relied on the target's before upgrading; the release note and
`aliases.md` say so, as earlier breaking config changes did.

**Rejected: a warning at config load for each alias without `userRoles` over a target with them** - it would also
fire for every alias that is open on purpose, which is the point of the change.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| Alias without `userRoles`, config target | the target's `userRoles` | open to every caller | Who may use an alias |
| Stored alias without `userRoles`, config target | the target's `userRoles` | open to every caller | Who may call a stored alias |

## Effect per request

None: the access check no longer looks a config target up in the config; no pass, copy, parse or round trip is added.

## Risks / Trade-offs

- An alias without `userRoles` over a hidden target opens that target to every caller after the upgrade. Mitigation:
  the operator step in the Migration Plan, the BREAKING line and the release note.

## Migration Plan

Before upgrading, copy the target's `userRoles` (`[]` included) onto every alias without `userRoles` whose config
target has them: in the config file, in `aliases/platform/` and in `aliases/public/`. Rollback: the earlier version
reads the same config.

## Code map

- `AliasService.java:hasConfigAccess`, `hasAccess`, `toView`: the checks; `toView` stops copying a config target's
  `userRoles` to the view that `DeploymentService.java:listAliasViews` and `listAliasNames` filter by.
- Callers, unchanged: `DeploymentService.java:findDeployment`, `findStoredAlias`, `findAliasView`, `listUsableAliases`;
  `RouterController.java:getRouterAlias`; `AliasService.java:toSecuredTarget` (from
  `ResourceCredentialsController.java:signInThroughAlias`); `ConsentService.java:buildConsent`; a router candidate,
  served by `DeploymentPostController.java:handleDeployment` through `DeploymentService.java:findCallableDeployment`.
- The lend, unchanged: `ApiKeyData.java:initFromContext` (`lentDeployment`, the resource attached `READ_ONLY`),
  `DeploymentService.java:isLent`, `toResourceDescriptor`; `RateLimiter.java:getLimitByUser` (all the caller's roles).
- Tests: `AliasApiTest`, `AliasPublicApiTest`, `AliasPlatformApiTest`, `AliasServiceTest`, `dial-config/aliases.json`.
