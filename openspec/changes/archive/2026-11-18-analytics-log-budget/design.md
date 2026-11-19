# Design

## Context

- `RateLimiter.java:checkLimits` runs before a model call. Budgets take part when `BudgetEnforcer.java:isApplicable`
  holds: a mode other than `off`, a context with extracted claims (a JWT caller, or a per-request key rooted at one),
  and a `Model`. So an API key caller, an application, interceptor or router pass, and a route never take part.
  `BaseDeploymentPostController.java:isSubjectToLimits` also skips the whole check for a router and for the outer
  request of a translated interface. `DeploymentPostController` checks a model's limits only when no interceptor
  comes next.
- `BudgetEnforcer.java:checkBudgets` (behind `check` and `checkWithSpendCaps`) resolves the cost object with
  `resolveCostObject`: the token's project claim, then the key's project, then `SpendingLimits.defaultCostObject`.
  With no candidate it calls `rejectNoBudget`. Otherwise `pickPayer` loads candidates in priority order and stores
  the first one with room with `context.setBudgetPayer(...)`, in both `shadow` and `enforce`. When none has room it
  builds the exhausted result and calls `reject`, which refuses in `enforce` and logs and lets the call through in
  `shadow`. A failed counter load fails the future.
- `BaseDeploymentPostController.java:increaseLimits` passes `context.getBudgetPayer()` to `RateLimiter.java:increase`,
  which charges it with `BudgetEnforcer.java:charge` when the cost is above zero.
- A refused call is answered from `DeploymentPostController.java:handleRateLimitHit` (and the equivalent paths of the
  other controllers) with `respond(...)`, and no analytics row is written for it.
- `BudgetPayer` is `(costObject, BudgetInstance instance, userId)`. `BudgetInstance` has `id` (assignment path, or the
  policy path for a policy that applies by itself), `policyPath`, `policy` and `assignment` (null for a policy that
  applies by itself). `BudgetEnforcer.java:collectStats` reports `instance.policyPath()` as `budgets[].policy`.
- `AnalyticsLogContext.java:from(ProxyContext, String)` builds a pass's row from its context after the response.
  `from(BackgroundJobRecord, ...)` builds a background job's row from the stored record, and `routerRow` builds the
  router row with `serving.toBuilder()`, which copies every field it does not override.
- `BackgroundJobService.java` (the poller's `rateLimiter.increase(...)` call) charges a finished job without a payer.
- `GfLogStore.java:append(AnalyticsLogContext, LogEntry)` writes the row by hand: `chat`, then `session`, then
  `project`, then `user`.

## Goals / Non-Goals

**Goals:**
- The behaviour of the spec delta, with no new reads.

**Non-Goals:**
- A row for a call refused in `enforce` mode.
- Charging background jobs to the budget.
- Any change to `project.id`.

## Decisions

1. **One `spending_limits` object.** Mode, cost object, outcome and the paying budget describe one check, so they
   are one object. Rejected: a `budget` object for the payer plus a sibling with the mode and outcome. That repeats
   `cost_object` and leaves a consumer two objects to read.
2. **A `BudgetCheck` record on the context, next to the payer.** `BudgetEnforcer.checkBudgets` sets it when it
   resolves the cost object (no outcome yet), and again for each outcome: `NO_BUDGET` before `rejectNoBudget`,
   `PAID` with the payer, `EXHAUSTED` before `reject`. `RateLimiter.checkLimits` sets `BudgetCheck.OFF` for a JWT
   caller's model call in `off` mode, after the limit lookup succeeds. `AnalyticsLogContext` copies it and
   `GfLogStore` writes it. The payer stays on the context as it is, because the charge reads it. Rejected:
   recomputing the outcome at log time, which costs Redis reads and can differ from what the check saw.
3. **`mode` on every checked row, `off` included.** It is what tells "limits off" apart from "budgets don't apply
   to this caller". An API key caller and a non-model row get no object at all.
4. **No `outcome` when a counter load fails.** The check did not finish, so no outcome is true. Such a call fails
   with an error today, so the row is not normally written; the shape is kept for any path that does write it.
5. **Snake_case keys, right after `project`.** This matches the nested `project`, `user` and `session` objects and
   the snake_case of `core_span_id` and `route_name`.
6. **`assignment` absent for a policy that applies by itself,** rather than repeating the policy path, so a consumer
   knows there is no assignment.
7. **The router row clears it.** `routerRow` sets `budgetCheck(null)`. The router's `price` already rolls up its
   subtree, so a copied object would count the model's spend twice under one budget.
8. **The background job row has none.** The job charges no budget, so naming one would be false. When background
   jobs start charging budgets, that change carries the check in the record and writes it here.
9. **`userId` is not written.** The row already has the user's hash.

Per request: one small record allocated in the limits check, one field copy when the row is built, and a few string
appends when the row is written, on the log writer's thread. No Redis or blob round trip, no body copy or parse.
Measured by reading the diff: no `store.*`, `resourceService` or body access is added.

## Risks / Trade-offs

- [A call that failed after the check, such as an upstream error, has `outcome` `paid` although nothing was
  charged] → the row's `deployment_price` is then absent or 0. The spend charged to a budget is the sum of
  `deployment_price` over its rows. The policies doc says so.
- [A consumer sums `price` by budget] → for a model row, `price` equals `deployment_price`. Only application and
  router rows roll up a subtree, and they carry no `spending_limits`.
- [`exhausted` reads as a count of refused calls] → in `enforce` a refused call has no row. The docs and the spec say
  `exhausted` and `no_budget` appear in `shadow` only.
- [`policy` and `assignment` are paths that can be renamed] → a rename makes a new budget with new counters
  (`budget-counters`), so the log follows what the counters do.

## Migration Plan

None. The field is additive. A consumer that does not know it ignores it.
