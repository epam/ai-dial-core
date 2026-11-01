## Context

`CostObjectIndex` holds the budget instances by cost object, `EffectiveBudgets` orders the ones that match a caller,
and `BudgetCounterStore.load` already sums the current-period rows and returns the reset instants.

## Decisions

- `EffectiveBudgets.resolveAll` is model-agnostic: it keeps kind `budget`, quantity `cost`, a live assignment and
  a matching subject, and returns every instance, by cost object and then in the order a call uses. It is not metered.
- One entry per instance, not per cost object, so a fallback budget that a call can fall through to is visible;
  `policy` and `priority` tell the entries of one cost object apart.
- `RateLimiter.collectUserStats` calls `BudgetEnforcer.collectStats` on the task executor thread it already runs on,
  through `BudgetCounterStore.loadBlocking`, so it does not queue behind itself.
- A window without a limit reports the unlimited sentinel (`Long.MAX_VALUE`) in `total`, as the legacy cost stats do.
- The personal cost object is `spendingLimits.defaultCostObject`; it is listed like any other cost object.

## Risks

- One storage listing per reported budget on each call. The set is bounded by the budgets that match the caller.
