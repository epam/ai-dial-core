# Design

## Context

The identity provider reads the token claim at `projectPath` into `ExtractedClaims.project`. `ProxyContext` keeps it
as `userProject`; `getProject()` answers it, or the API key's project. `BudgetEnforcer.resolveCostObject` picks the
cost object of a budget check: the claim, then the key's project, then `spendingLimits.defaultCostObject`. The
analytics row writes `project.id` from `getProject()` and, when budgets were checked, `spending_limits.cost_object`
from the check. `GET /v1/user/info` writes `project` only. No surface says where a cost object came from.

## Decisions

### D1. One resolver gives the cost object and its source
A record `CostObject(id, source)` in `limiter/budget`, with its `Source` enum nested as `BudgetCheck.Outcome` is, resolves claim, then key project, then the default, and says
which won. `ProxyContext.getCostObject()` / `getCostObjectSource()` and `BudgetEnforcer.resolveCostObject` all call it,
so the budget check and the logs cannot disagree. `resolveCostObject` keeps reading `context.getUserProject()` and
`context.getKey()`, as today, so the M1 tests that mock those still pass unchanged.
**Rejected: store the cost object on the context when it is built** - the default comes from the hot-reloaded config;
resolving on read keeps today's behaviour of the budget check, which reads the config of the moment.

### D2. Old names stay as delegates, with no `@Deprecated` annotation
`getUserProject()` answers the claim cost object and `getProject()` answers claim or key project, never the default,
as today: the bucket of an API key, the channel controller and the plugin's `Caller` keep their meaning. Javadoc marks
them as transition names; an annotation would turn every caller into a compiler warning for a rename that ships over
several PRs.
**Rejected: make `getProject()` return `getCostObject()`** - it would add the default to every row's `project.id` and
change the plugin's caller, a behaviour change this issue rules out.

### D3. The analytics `cost_object` follows `spending_limits`
`GfLogStoreTest` pins `spending_limits` right after `project`, and the M1 tests must pass unchanged, so the new object
goes after `spending_limits`. Its keys are snake_case like the rest of the row.
**Rejected: inside `project`** - `project.id` keeps its meaning during the transition; mixing the two meanings in one
object is what this change removes.

### D4. `projectPath` is read when `costObjectPath` is missing
The identity provider reads `costObjectPath`, falls back to `projectPath`, and logs one WARN at start for each
provider that still has `projectPath`: a deprecation when it is used, "ignored" when `costObjectPath` is also set.
**Rejected: fail start when both are set** - the issue asks for a transition, and a settings file with both is valid.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| IdP setting | `projectPath` | `costObjectPath`, `projectPath` as fallback with a warning | user-info: Project of a JWT caller in user info |
| `/v1/user/info` | `project` | adds `costObject`, `costObjectSource` | user-info: Cost object in user info |
| Analytics row | `project`, `spending_limits.cost_object` | adds `cost_object.{id,source}` | analytics-log: Cost object in the analytics row |
| OTel log attributes | `user.project` | adds `user.cost_object`, `user.cost_object.source` | none (log layout, not a spec boundary) |

## Effect per request

None: the resolver reads fields already on the context and the config snapshot; no Redis or blob call, no body parse.
A background job record stores two more short strings.

## Risks / Trade-offs

- A background job record written by an older Core has no cost object; its row then has no `cost_object`. Records live
  only while the job runs, so this lasts one rollout.

## Migration Plan

None required. Operators can rename `projectPath` to `costObjectPath` at any time; until they do, Core warns at start.

## Code map

- `server/.../security/IdentityProvider.java:IdentityProvider` (constructor, `getClaimPath`) and `toExtractedClaims`.
- `server/.../ProxyContext.java:initExtractedClaims`, `getProject`, `getConfig`.
- `server/.../limiter/budget/BudgetEnforcer.java:resolveCostObject`; new `limiter/budget/CostObject.java:resolve`
  (with `CostObject.Source`).
- `server/.../controller/UserInfoController.java:handle`.
- `server/.../log/AnalyticsLogContext.java:from` (both), `log/GfLogStore.java:append`.
- `server/.../data/BackgroundJobRecord.java:from`.
- `server/.../log/layout/AutoEnrichedOtelJsonLayout.java:enrichAttributesFromContext`.
- `config/.../SpendingLimits.java` (`defaultCostObject`).
