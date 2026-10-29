## Why

Budget counters are never deleted. A budget that was withdrawn leaves its rows in storage for good, and nothing
tells an operator that `shadow` or `enforce` runs with no budget for the personal cost object. The cutover from the
role `costLimit` also needs to know which (user, project) pairs `shadow` would have rejected.

## What Changes

- A background sweep deletes the counter rows of a budget instance that has no assignment and no self-applying
  policy, once both windows of the row have closed. Rows of a live budget, and rows with an open window, stay.
- When `spendingLimits.mode` is `shadow` or `enforce` and `defaultCostObject` has no live cost budget, Core logs a
  warning on each config rebuild and reports the gauge `dial_budget_personal_missing`. Startup is never blocked.
- The `shadow` log line for a call that would be rejected also names the user.
- New static setting `budgetCounterSweep` (`period`, `batch`).

## Capabilities

### New Capabilities
- `budget-counters`: the stored spend counters of budgets and their lifecycle.

### Modified Capabilities
None.

## Impact

- Issue [#2149](https://github.com/epam/ai-dial-core/issues/2149), part of #2143.
- Out of scope: the cutover scripts and runbook, which live outside Core, and a new admin endpoint: the policy and
  assignment API already exists. The per-pair `shadow` record is a log line, not stored data.
- No guard for an absent Redis: Core does not start without it.
