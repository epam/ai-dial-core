## Context

Counters are `limit` resources at `platform/limits/<costObject>/budgets/<instance>/<user>/<model>/costs`. A row
whose windows are closed counts as zero, so deleting it never changes a limit decision.

## Decisions

- `BudgetCounterSweepService` follows `ComplexResourceSweepService`: a `TimerService` timer, and a Redis cursor
  that a tick claims and advances under a brief cluster lock, then releases before it handles the page, so another pod
  can claim the next page meanwhile. The cursor moves before the rows are handled; a page lost to a crash is reached
  again by the next pass, and every step is idempotent.
- A budget is retired when neither an assignment nor a self-applying policy is stored at `<costObject>/<instance>`.
  The check reads storage, not the in-memory config, which can lag behind a write made on another pod. An assignment
  whose `expiresAt` has passed is not live.
- A row is deleted under `tryLockResource` after it is read again and found closed, so a charge made after the
  listing is not lost. A row that is locked is left for the next pass.
- Rows that do not match the layout of a counter are ignored.
- The guard is a static check on the rebuilt `Config`, run on each full rebuild and read lazily by the gauge, so it
  also reports after a partial update. It cannot throw into the rebuild.
- The `shadow` record of a would-be rejection is the existing log line plus `user`. A metric label per user would
  have unbounded cardinality.

## Risks

- A pass lists every counter row once per `period * rows / batch`; the defaults are 500 rows every 5 minutes.
