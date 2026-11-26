# Proposal

## Why

Spec 3 inference packages open the tier aliases (`weak`, `medium`, `strong`, `hi-end`), which the router resolves to
a routing-target alias and then a model. Today a budget's `object` is matched against the model's name only, so a
budget cannot pay for "anything reached through `strong`" without listing every model behind it, and listing them
also opens direct model access, which Spec 3 keeps for the API package.

## What Changes

- New managed config type **package** (`packages/<name>`, global, flat): a non-empty list of deployment names and a
  description, written by admins only through `PUT/GET/DELETE /v1/packages/platform/<name>` and the `Package` kind of
  `POST /v1/admin/validate` and `/v1/admin/apply`. A member that is not a deployment is accepted and logged. Delete is
  refused with `409` while a policy names the package.
- A policy's `object` may name a package with `@`: `"@engineering"`, or a list mixing names and packages. A policy
  naming a missing package is refused on write (`422`) and goes through `config.onInvalidEntity` on a rebuild. Policy
  reads and the user stats return `object` as written. The reserved `packages` policy field stays rejected.
- **Coverage along the call chain.** A budget covers a model call when its object set contains any name on the call's
  coverage chain: the model and the run of aliases and routers that led to it, read back from the execution path
  until the first application, interceptor or toolset. An application's own call to a model starts a new chain.
- **Chain attribution.** When budgets cover the call at different chain positions, only those covering the earliest
  position any of them covers are candidates; the priority walk runs over them as today.
- Editing a package re-scopes every policy that names it on the next config rebuild; counters are not touched.

Out of scope: alias tags on counter rows (Core design D13; the M1 counter layout is unchanged here, the tag comes with
the object-shaped `per` of #2367); the indication and the user's default as payers (#2364, #2365), which leave one
candidate and make attribution order only the walk; packages in a discovery endpoint (#2361 D19); project-scoped or
nested packages; any permission effect of packages (they gate nothing, Core design D15).

## Capabilities

### New Capabilities
- `budget-packages`: the package record, its Configuration API and manifest kind, and how a policy names a package.
- `budget-coverage`: which budgets cover a model call: the object set, the coverage chain and chain attribution.

### Modified Capabilities
- `user-limits`: "Budgets in the user stats" returns a package-naming `object` as written.
- `config-store`: "Manifest names" and "Batch dependency order" take the `Package` kind.

## Impact

- Issue epam/ai-dial-core#2366 (part of #2361, Spending Limits M2), with the Core design
  `documentation/design/spending_limits_m2_core.md` §3 as the reference. Covers every point of #2366. Of the design's
  §3 it leaves out the alias-tagged counters of §3.5 (above).
- Depends on #2362 (cost object rename), merged. Independent of #2364; #2365 builds on the candidate list this change
  produces.
- Code: `config` (`ResourceTypes`, `Config`, a `Package` POJO, `PolicyObjectSet`, `CostObjectIndex`,
  `PolicyAssignmentValidator`) and `server` (`ConfigResourceController`, manifest handling, `MergedConfigStore`,
  `ConfigPostProcessor`, `PolicyService`, `EffectiveBudgets`, `BudgetEnforcer`). Docs: `docs/dynamic-settings/`.
- No new setting, no change to the Redis counter layout, no migration.
