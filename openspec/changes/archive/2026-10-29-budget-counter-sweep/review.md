# Review: budget-counter-sweep

## What shipped

- `limiter/budget/BudgetCounterSweepService`: a timer on every pod; a tick claims one page of `platform/limits/` rows and
  advances a Redis cursor under a brief lock (as `ComplexResourceSweepService.claimBatch` does), then handles the page
  outside the lock. A row is deleted when both its windows have closed
  and no live assignment and no self-applying policy is stored for its instance. Liveness is read from storage. The row is
  re-read and re-checked under `tryLockResource` before the delete.
- `budgetCounterSweep.period` (5 min) and `budgetCounterSweep.batch` (500) in the static settings, with code defaults;
  metrics `dial_budget_sweep_scanned_total` and `dial_budget_sweep_deleted_total`.
- `MergedConfigStore`: warning on each rebuild and gauge `dial_budget_personal_missing` when the mode is `shadow` or
  `enforce` and `defaultCostObject` has no unexpired cost budget. `EffectiveBudgets.isExpired` is now public for it.
- `BudgetEnforcer`: the `shadow` would-reject log line also names the user.
- Tests: `BudgetCounterSweepServiceTest`, `PersonalBudgetGuardTest`. README rows for the settings.

## Drift from design.md

- The guard is evaluated on each full rebuild and by the gauge, not on every partial update, so the warning is not
  repeated per write. The gauge still follows partial updates.
- No guard for an absent Redis, as the proposal says: Core does not start without it.

## Not done

- The cutover scripts (dry-run, shadow report, backfill) and the runbook live outside Core and are not part of this change.
  Issue #2149 stays open for them.
- The per-(user, project) `shadow` record is a log line, so the report script has to parse logs.

## Known limits

- One page per tick: a large backlog clears at about `batch` rows per `period`.
- The filesystem blob provider lists directories too, so a page can hold few rows; cloud providers list only blobs.

## Review

PR #2206 was approved. The reviewer asked why a tick handles one page and why the cursor state did not follow
`ComplexResourceSweepService.claimBatch`. The first version held the lock for the whole page; it now claims the page
under a brief lock like `claimBatch`, so pods can handle pages in parallel.
