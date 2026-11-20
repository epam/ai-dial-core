# Proposal

## Why

The analytics log says nothing about spending limits. `project.id` carries a JWT caller's project claim, which is
the cost object in most cases. But nothing says which of the cost object's budgets paid for a call, whether
spending limits were on, or whether the call went through in `shadow` mode with no budget that could pay.
Without that, ADAS (`dial_usage_log`) can break spend down per user and per project, not per budget (the
"package" a user was assigned). It cannot tell a call under spending limits `off` from one charged to a budget.
And when the token has no project claim, `project.id` is empty while Core charges
`spendingLimits.defaultCostObject`.

## What Changes

- The analytics row of a JWT caller's model call whose limits Core checks gets a `spending_limits` object,
  written right after `project`:
  - `mode`: `off`, `shadow` or `enforce`. Always present.
  - `cost_object`: the cost object the call is charged to. Absent in `off`.
  - `outcome`: `paid` (a budget pays), `no_budget` (no budget covers the caller and the model) or `exhausted`
    (every such budget is used up). Absent in `off`, and when a budget counter could not be read.
  - `policy`: the path of the budget's policy, as `GET /v1/user/limits` reports it in `budgets[].policy`. Present
    with `paid` only.
  - `assignment`: the path of the policy assignment that grants the budget, `<costObject>/<id>`. Present with
    `paid` only, and absent for a policy that applies by itself.
- The budget is the one the pre-call check picked to pay, which is the budget the call is charged to.
- A call refused in `enforce` mode writes no analytics row, as today, so `exhausted` and `no_budget` appear only in
  `shadow` mode.
- `spending_limits` is absent:
  - for an API key caller;
  - in a row whose limits Core does not check, such as the outer request of a translated interface;
  - in a row that is not a model's: an application, interceptor or route row, and the router row of a decided
    request. That row repeats the serving pass's fields, so it would count the model's spend twice;
  - in the row a background Responses job writes when it ends, because that job charges no budget today.
- `docs/dynamic-settings/policies.md` documents the object.

Out of scope:
- Charging background Responses jobs to the budget. Today `BackgroundJobService` charges only the role cost record
  when a job ends. That is a gap in `budget-counters`, for a separate change.
- A row for a call refused in `enforce` mode.
- `spendingLimitsMode` and `expiresAt` in `GET /v1/user/limits` (items 1 and 2 of issue #2302).
- Changing `project.id`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `analytics-log`: three new requirements, "Spending limits in the analytics row", "Budget in the analytics row"
  and "Rows without spending limits". The other requirements stay as they are.

## Impact

- Motivation: issue #2302, item 3 ("Charged budget in the usage log"). This change covers that item and also logs
  the mode and the outcome of the check. Items 1 and 2 (`spendingLimitsMode` and `expiresAt` in
  `/v1/user/limits`) are left out.
- Records: analytics log rows gain an optional `spending_limits` object. It is additive, so a consumer that does
  not know it is unaffected. ADAS adds its columns in a separate change in its own repository. That change depends
  on this one only for the data, so it can ship first.
- Code: `server` module only (`BudgetCheck`, `BudgetEnforcer`, `RateLimiter`, `ProxyContext`,
  `AnalyticsLogContext`, `GfLogStore`); `docs/dynamic-settings/policies.md`.
- Per request: no Redis or blob round trip, and no extra body copy or parse. The check stores a small record on
  the request context, and the row reads it.
