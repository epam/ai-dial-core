## Why

Spending limits charge per-user budget counters, but `GET /v1/user/limits` and `GET /v1/user/usage` report only the
role-based cost limit. A user cannot see which budgets apply to them, how much is spent or when it resets.

## What Changes

- Both endpoints gain an additive `budgets[]`: one entry per budget that matches the caller, in every cost
  object, the default cost object included. An entry holds the `costObject`, the budget's `policy` and `priority`,
  and the `day` and `month` windows, each with `{total, used, resetsAt}`. A cost object can hold several entries.
- `used` is the sum of the caller's current-period model rows of the budget. `resetsAt` is the end of the current
  period, computed from the rate-limit schedule, so it is known before the first charge.
- Entries are ordered by cost object, then by policy priority (lowest first), then by id.
- The legacy top-level cost stats and the per-deployment stats are unchanged.

## Capabilities

### New Capabilities
- `user-limits`: the user limits and usage endpoints. No capability in `openspec/specs` covers them today.

### Modified Capabilities
None.

## Impact

- Issue [#2148](https://github.com/epam/ai-dial-core/issues/2148), part of #2143.
- Out of scope: per-model budget rows, API key callers.
- `budgets` is empty when `spendingLimits.mode` is `off` and for API key callers, which no budget reaches.
