# Design

## Context

A budget policy's `object` is `"*"` or a list of deployment names, held in memory as a set. On each JWT caller's model
call, `BudgetEnforcer` asks `EffectiveBudgets` for the budgets of the caller's cost object that match the caller and
whose set contains the model's name, sorts them by priority and walks them for one with money left. Alias passes and
routers are never limit-checked: the check and the charge run on the model pass, under the model's name. That pass's
per-request key records how the call got there as `executionPath`, for example `[strong, r1, claude-opus-vertex, m]`;
an application's own call adds the application's name in front, and an interceptor's call back adds the interceptor.
Policies, assignments and the derived `CostObjectIndex` live in the merged `Config` and are rebuilt on every config
change; policies are written through `PolicyService`, which also rejects two budgets that tie on priority and can pay
the same calls.

## Decisions

### D1. A policy keeps its object as written; the index holds the expanded set
`PolicyObjectSet` keeps the terms the admin wrote (names and `@package` references) and serialises them back
unchanged, so policy reads, manifests and `GET /v1/user/limits` show `"@engineering"` and never the models behind it
(Core design §3.5, E2E §3.7). `CostObjectIndex.build` takes the package map and stores on each `BudgetInstance` the
flat set of names it covers, so the per-call test stays a set lookup and a package edit takes effect when the index
is rebuilt.
**Rejected: expand `@name` while parsing the policy** - the stored and read-back policy would lose the reference, and
a package edit would need every policy re-parsed.
**Rejected: look packages up per call** - one map lookup per package per candidate on the hot path, for no gain over
a rebuild-time expansion.

### D2. Packages are a config entity served by `ConfigResourceController`, like roles
`PACKAGE("packages", true, 30 days)` joins `ResourceTypes` and the config binding (platform bucket only), with a
`PolicyPackage` POJO in `config` (not `Package`, which `java.lang` already names), a `Config.packages` map, the
manifest kind `Package` ordered before `Policy`, and the existing admin check of the Configuration API. Like policies,
packages are blob-only: `Config.packages` is `@JsonIgnore`, so the dynamic config file cannot carry them. This reuses
blob storage, ETags, the merged config, validate/apply and the health listing with no new path.
**Rejected: a dedicated controller as `PolicyController`** - policies need one because of their cost-object folders
and assignment rules; a package is a flat global record with nothing of the kind.

### D3. Referential integrity is checked on write against storage plus the batch
A policy write naming a missing package is `422`: `PolicyService.checkPolicy` reads the package blob, or the package
staged earlier in a validate batch (`PolicyAssignmentPrecheck.stagePackage`). A package `DELETE` is `409` while a
stored policy names it: `PolicyService.requirePackageUnreferenced` lists the policy blobs, called by the controller
before the blob is deleted. Both run under the admin bucket locks that every policy and package write holds, so no
write can slip between the check and the change, which is how `PolicyService` already checks its other cross-record
rules. On a rebuild, a policy naming a missing package fails `ConfigPostProcessor.validateSinglePolicy`, so
`config.onInvalidEntity` decides: `abort` keeps the previous config, `skip` drops the policy and, as today, its
assignments as dangling. A package change on the partial-update path (a write, or a replica event) requests a full
rebuild when policies were skipped earlier or a policy now names a missing package, since only a rebuild reads such
policies back.
**Rejected: check against the merged config** - another pod's write reaches it only with the replica event, so a
check there can pass while storage already disagrees; the lock and storage are what make the rule hold.

### D4. The coverage chain is computed from the incoming path at check time
`ApiKeyData.initFromContext` builds the path the next hop will see: the incoming key's path (none for a root call),
the router's name after a decision, then the called deployment. A helper extracted from it, `ApiKeyData.pathOf(context)`,
gives the same list to `BudgetEnforcer`, which keeps from the tail the model and the names before it until the first
application, interceptor or toolset (a config application, interceptor or toolset, or a name starting with
`applications/` or `toolsets/`; a resource-url test alone would also stop at a stored alias, `aliases/public/...`).
Everything else (aliases of the config or `aliases/public/...`, routers, a name no longer
in the config) stays on the chain.
**Rejected: keep only names that are aliases or routers** - a translator's call back and any future pass kind would
silently cut the chain; stopping only at the kinds that start their own call (Core design §3.5) is the rule the design
states.
**Rejected: read the path of the key minted for the origin** - the budget check runs before that key exists on some
routes; one helper keeps both in step.

### D5. Attribution narrows the candidates before the priority sort
`EffectiveBudgets.resolve` takes the chain instead of the model name. For each budget that matches the caller, it
finds the first chain position its set contains; it keeps the budgets at the smallest such position, then sorts them
by priority and id as today. `"*"` covers at the model, the last position, so it ties by priority with budgets naming
the model and yields to a budget naming an earlier name, instead of shadowing every narrower budget. The walk, the exhausted outcome and the ambiguous-priority
metric are unchanged, so a used-up alias budget refuses with `budget_exhausted` instead of falling through to a model
budget further down the chain (Core design D13, "the first effective budget in the chain takes the call").
**Rejected: walk all covering budgets in chain order, then priority** - grants arriving at a later position would
redirect spend after the earlier budget runs dry, against "grants never redirect a call" (Core design §1.3).

### D6. The priority-tie check compares expanded sets
`PolicyService.Instances.requireNoTie` expands package references with the stored packages before `intersects`, so two
budgets naming `@engineering` and `["strong"]` at one priority are still a `409`. A tie can still appear later when a
package edit adds a member; the walk's ambiguous-priority metric and WARN, unchanged, report it.
**Rejected: re-check every referencing policy on a package write** - it reads every policy and assignment of every
cost object on one admin write, and the outcome of a tie is deterministic anyway (priority, then id).

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| Names a budget is matched on | the model's name | every name on the coverage chain | budget-coverage: Budget covers a name on the chain |
| Budget for an alias, model called through it | not covered | covered | same |
| Candidates when budgets cover different names of one call | all, by priority | only those at the earliest chain position | budget-coverage: Earliest chain position takes the call |
| Policy `object` | `"*"` or names | also `@package`, alone or in a list | budget-packages: Policy names a package |
| Policy `object` read back | `"*"` or a sorted list | a single package reference stays a string | budget-packages: Policy names a package |
| Config types and manifest kinds | no package | `packages`, kind `Package` before `Policy` | budget-packages, config-store |

## Effect per request

| | Today | After | How measured |
|---|---|---|---|
| Redis / blob round trips per model call | budget counter reads | unchanged | the existing `BudgetEnforcer` tests count store calls |
| In-memory work per model call | one set lookup per budget of the cost object | one walk of the execution path (at most `MAX_CALL_DEPTH` names) with up to three config map lookups per name, then up to chain-length set lookups per budget | `EffectiveBudgetsTest.testResolveCostWithLongChainAndManyBudgets`: 100 budgets, a 10-name chain against the model alone (today's lookup); recorded in review.md |
| Config rebuild | index from policies and assignments | plus one expansion of each policy's terms | none: proportional to the policies, done off the request path |

## Risks / Trade-offs

- [The money plane follows routing topology: retargeting a router or moving an interceptor can change which budget
  pays] → accepted with variant A (Core design §3.5); docs say so next to the package examples.
- [An interceptor in front of a tier's model makes `@tier` budgets cover nothing for that model] → the no-budget
  refusal names the model; docs tell admins to add the model to the package.
- [A translator's call back carries the outer path plus the model again] → D4 keeps the repeated model name on the
  chain; `BudgetChainCoverageApiTest.testTranslatedCallThroughTierAliasIsCovered` pins it.
- [A package edit can create a priority tie no write check saw] → D6.

## Migration Plan

None: no record changes shape. Existing policies have no `@` terms and keep their coverage, except that a policy naming
an alias or a router, which today covers no call (the check runs under the model's name), now covers the calls made
through it. Rollback: delete packages and the policies naming them before deploying an older Core, which would
reject `@` terms as invalid policies under `config.onInvalidEntity`.

## Code map

- `config/.../policy/PolicyObjectSet.java`: `fromJson`, `toJson`, `packageNames`, `expand`, `indexOfFirstCovered` -
  terms kept as written, the package-reference form, the flat set and the first covered chain position.
- `config/.../policy/PolicyPackage.java` - the record.
- `config/.../policy/CostObjectIndex.java`: `build` - takes the package map.
- `config/.../policy/BudgetInstance.java` - gains `covered`; a five-argument constructor covers the object as written.
- `config/.../policy/PolicyAssignmentValidator.java`: `validatePackage`; `validatePolicy` keeps rejecting `packages`.
- `config/.../Config.java` - add `packages`; `storage/.../resource/ResourceTypes.java` - add `PACKAGE` and its
  segment mapping, as `ROLE`.
- `server/.../controller/ConfigResourceController.java`: the `case ROLE` branches of single GET, descriptor and
  `WriteSpec`, `checkPackage` on PUT, and the `DELETE` path with the referenced-package `409`.
- `server/.../data/RouteTemplate.java`, `security/EntityBucketBinding.java`, `config/PlatformEntityLocationStrategy.java`
  - the `packages` segment, its platform bucket and location.
- `server/.../service/config/ConfigManifestSupport.java`: `DEPENDENCY_ORDER`, `KIND_URL_SEGMENT`, `parseManifest`,
  `mutateScratch`; `ConfigValidationService.validateOnly`, `ConfigApplyService.applySingle`;
  `server/.../data/config/manifest/AdminManifest.java`, `AdminTypedManifest.java`, `AdminPackageManifest.java`.
- `server/.../config/MergedConfigStore.java`: the per-type switches, `refreshCostObjectIndex` (a `PACKAGE` change
  touches the index) and `requestRebuildIfPoliciesDependOnPackages` (D3).
- `server/.../config/ConfigPostProcessor.java`: `processPolicies` (packages first), `validateSinglePackage` (WARN on
  a member that is not a deployment), `validateSinglePolicy` (missing package), `hasPolicyNamingMissingPackage`.
- `server/.../service/policy/PolicyService.java`: `checkPolicy`, `requirePackageUnreferenced`, `Instances.requireNoTie`
  - D3 and D6; `StagedPolicyRecords`, `PolicyAssignmentPrecheck.stagePackage` for a validate batch.
- `server/.../data/ApiKeyData.java`: `initFromContext` - extract `pathOf(context)` (D4).
- `server/.../limiter/budget/BudgetEnforcer.java`: `resolveCoverageChain`, `resolveCandidates` - build the chain and
  pass it.
- `server/.../limiter/budget/EffectiveBudgets.java`: `resolve` - D5; `resolveAll`, `resolveInCostObject` unchanged.
- Tests: `PolicyObjectSetTest`, `CostObjectIndexTest`, `PolicyAssignmentValidatorTest` (config); `EffectiveBudgetsTest`,
  `BudgetEnforcerTest`, `MergedConfigStorePolicyTest`, `PolicyApiTest`, `UserBudgetsApiTest`; new `PackageApiTest` and
  `BudgetChainCoverageApiTest` (fixture `dial-config/budget-chain-coverage.json`) on the full stack.
