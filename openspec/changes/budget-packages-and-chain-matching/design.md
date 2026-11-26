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
`Package` POJO in `config`, a `Config.packages` map, the manifest kind `Package` ordered before `Policy`, and the
existing admin check of the Configuration API. This reuses blob storage, ETags, the merged config, validate/apply and
the health listing with no new path.
**Rejected: a dedicated controller as `PolicyController`** - policies need one because of their cost-object folders
and assignment rules; a package is a flat global record with nothing of the kind.

### D3. Referential integrity is checked on write against the merged config plus the batch
A policy write naming a missing package is `422` (`PolicyService.checkPolicy`, reading `Config.packages` of the live
merged config plus the packages staged earlier in the batch). A package `DELETE` is `409` while a policy of the merged
config names it, checked in the controller before the blob is deleted. On a rebuild, a policy naming a missing package
fails `ConfigPostProcessor.validateSinglePolicy`, so `config.onInvalidEntity` decides: `abort` keeps the previous
config, `skip` drops the policy and, as today, its assignments as dangling.
**Rejected: scan policy blobs on delete** - the merged config already holds every policy; a racing write is caught by
the rebuild check.

### D4. The coverage chain is computed from the incoming path at check time
`ApiKeyData.initFromContext` builds the path the next hop will see: the incoming key's path (none for a root call),
the router's name after a decision, then the called deployment. A helper extracted from it, `ApiKeyData.pathOf(context)`,
gives the same list to `BudgetEnforcer`, which keeps from the tail the model and the names before it until the first
application, interceptor or toolset (a config application, interceptor or toolset, or an `applications/` or
`toolsets/` resource url). Everything else (aliases of the config or `aliases/public/...`, routers, a name no longer
in the config) stays on the chain.
**Rejected: keep only names that are aliases or routers** - a translator's call back and any future pass kind would
silently cut the chain; stopping only at the kinds that start their own call (Core design §3.5) is the rule the design
states.
**Rejected: read the path of the key minted for the origin** - the budget check runs before that key exists on some
routes; one helper keeps both in step.

### D5. Attribution narrows the candidates before the priority sort
`EffectiveBudgets.resolve` takes the chain instead of the model name. For each budget that matches the caller, it
finds the first chain position its set contains; it keeps the budgets at the smallest such position, then sorts them
by priority and id as today. `"*"` covers position 0. The walk, the exhausted outcome and the ambiguous-priority
metric are unchanged, so a used-up alias budget refuses with `budget_exhausted` instead of falling through to a model
budget further down the chain (Core design D13, "the first effective budget in the chain takes the call").
**Rejected: walk all covering budgets in chain order, then priority** - grants arriving at a later position would
redirect spend after the earlier budget runs dry, against "grants never redirect a call" (Core design §1.3).

### D6. The priority-tie check compares expanded sets
`PolicyService.Instances.requireNoTie` expands package references with the live packages before `intersects`, so two
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
| In-memory work per model call | one set lookup per budget of the cost object | one walk of the execution path (at most `MAX_CALL_DEPTH` names) with one config map lookup per name, then up to chain-length set lookups per budget | a unit test of `EffectiveBudgets` over a 10-name chain and 100 budgets, timed against today's resolve; recorded in review.md |
| Config rebuild | index from policies and assignments | plus one expansion of each policy's terms | none: proportional to the policies, done off the request path |

## Risks / Trade-offs

- [The money plane follows routing topology: retargeting a router or moving an interceptor can change which budget
  pays] → accepted with variant A (Core design §3.5); docs say so next to the package examples.
- [An interceptor in front of a tier's model makes `@tier` budgets cover nothing for that model] → the no-budget
  refusal names the model; docs tell admins to put interceptors on the alias or name the model in the package.
- [A translator's call back may carry the path differently than assumed in D4] → the Messages-through-`strong` test
  in tasks pins it; if the outer names do not reach that pass, D4 is corrected in place before the code lands.
- [A package edit can create a priority tie no write check saw] → D6.

## Migration Plan

None: no record changes shape. Existing policies have no `@` terms and keep their coverage, except that a policy naming
an alias or a router, which today covers no call (the check runs under the model's name), now covers the calls made
through it. Rollback: delete packages and the policies naming them before deploying an older Core, which would
reject `@` terms as invalid policies under `config.onInvalidEntity`.

## Code map

- `config/.../policy/PolicyObjectSet.java`: `fromJson`, `toJson`, `contains`, `intersects` - terms kept as written;
  add the package-reference form and an `expand(Map<String, Package>)` returning the flat set.
- `config/.../policy/CostObjectIndex.java`: `build` - takes the package map; `BudgetInstance` gains the covered set.
- `config/.../policy/BudgetInstance.java` - the record the walk reads.
- `config/.../policy/PolicyAssignmentValidator.java`: `validatePolicy` - keeps rejecting the reserved `packages`.
- `config/.../Config.java` - add `packages`; `storage/.../resource/ResourceTypes.java` - add `PACKAGE` and its
  segment mapping, as `ROLE`.
- `server/.../controller/ConfigResourceController.java`: the `case ROLE` branches of single GET, descriptor and
  `WriteSpec`, and the `DELETE` path - mirror for `PACKAGE`, add the referenced-package `409`.
- `server/.../service/config/ConfigManifestSupport.java`: `DEPENDENCY_ORDER`, `KIND_URL_SEGMENT`, the manifest factory
  and kind-to-type switch; `server/.../data/config/manifest/AdminManifest.java` - the `Package` subtype.
- `server/.../config/MergedConfigStore.java`: the per-type map copy/get/put/remove/restore switches and
  `refreshCostObjectIndex` - treat `PACKAGE` as touching the index and re-run policy validation.
- `server/.../config/ConfigPostProcessor.java`: `processPolicies`, `validateSinglePolicy` - missing package; WARN on
  a package member that is not a deployment.
- `server/.../service/policy/PolicyService.java`: `checkPolicy`, `Instances.requireNoTie` - D3 and D6.
- `server/.../data/ApiKeyData.java`: `initFromContext` - extract `pathOf(context)` (D4).
- `server/.../limiter/budget/BudgetEnforcer.java`: `resolveCandidates` - build the chain and pass it.
- `server/.../limiter/budget/EffectiveBudgets.java`: `resolve` - D5; `resolveAll`, `resolveInCostObject` unchanged.
- `server/.../alias/AliasTargets.java`: `isResourcePath` - reuse for resource-url names on the chain.
- Tests to extend: `EffectiveBudgetsTest`, `BudgetEnforcerTest`, `PolicyObjectSet` cases in `PolicyRecordCodecTest`,
  `PolicyAssignmentValidatorTest`, `MergedConfigStorePolicyTest`, `PolicyApiTest`, `UserBudgetsApiTest`; new
  `PackageApiTest` and `BudgetChainCoverageApiTest` on the full stack, as `BudgetSpendCapsApiTest` does.
