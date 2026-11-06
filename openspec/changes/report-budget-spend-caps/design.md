# Design: report-budget-spend-caps

## Context

See proposal.md for why. The state on `development` that this change builds on:

- **Which routes skip budgets.** `RateLimiter.java:checkLimits` serves both the plain check (`limit`) and the check
  of the routes that report spend caps (`limitWithSpendCaps`). Budgets take part only in the plain one:
  `isBudgetCheck = !withSpendCaps && BudgetEnforcer.isApplicable(context, roleBasedEntity)`. On the plain path the
  legacy check (token, request, cost) runs first, and `BudgetEnforcer.check` runs after it, which sets
  `ProxyContext.budgetPayer`.
- **The legacy spend-cap check.** `RateLimiter.java:checkLimitWithSpendCaps` reads the role cost record first, so a
  reached cap wins over every other limit. A failed read is kept and decides only when no token or request limit
  rejects. `RateLimiter.java:buildSpendCaps` turns the record into `SpendCaps` (day, week, month).
  `SpendCaps.java:of` drops windows that are 0 or at least 2^53, and `SpendCaps.java:select` picks the cap to report
  (the fullest, or the used-up one that resets last, ties going to the longer period).
- **The read-only paths.** `RateLimiter.java:readSpendCaps` reads the role record only. Two callers:
  `AnthropicUsageController.java:handle` and, for translated deployments, `SpendCapCheck.java:verify`, which on
  that path recovers any failure to `SpendCaps.EMPTY`.
- **Formatting the answer.** `SpendCapCheck.java:verify` turns a `RateLimitResult` that carries `SpendCaps` into the
  route's answers. A reported cap that is exceeded becomes `Answers.spendCapReached`, and any other rejection
  becomes `Answers.rejectOtherLimit`. A `SpendReadException` becomes `Answers.spendUnavailable` only when
  `RateLimiter.java:hasCostLimit` is true, which today reads only the role cost limit; any other failure becomes
  `Answers.limitCheckFailed`. `MessagesController.java:verifyLimit` and `ResponsesController.java:verifyLimit` supply
  the Anthropic and Codex answers. `CodexLimitResponses.java:spendCapReached` builds `display_message` from the
  cap's period (`You've exceeded your daily cost limit`) when it is given none.
- **Charging.** `BaseDeploymentPostController.java:increaseLimits` passes `context.getBudgetPayer()` to
  `RateLimiter.java:increase`, which charges it through `BudgetEnforcer.java:charge`. The Messages and Responses
  controllers extend `BaseDeploymentPostController`, so charging needs only the payer on the context. Translated
  outer requests skip `increaseLimits`.
- **Budget building blocks.** `BudgetEnforcer.java:resolveCostObject` returns the project claim, else the key's
  project, else `defaultCostObject`. For a budget caller the key is always null, because only JWT callers reach
  budgets. `EffectiveBudgets.java:resolve` lists the candidates for one model, ordered by priority.
  `EffectiveBudgets.java:resolveAll` lists every cost budget of the caller in every cost object.
  `BudgetEnforcer.java:pick` walks the candidates and loads each with `BudgetCounterStore.java:load` until one has
  room. Its reset rule (the latest reset of the budget's used-up windows) is the same instant `SpendCaps.select`
  reports for that budget. `BudgetEnforcer.java:reject` meters a rejection and, in `shadow`, logs it and turns it
  into success. `PolicyAssignmentValidator.java:validateAmounts` makes every set amount greater than 0 and sets no
  upper bound.
- **The cost of a budget load.** `BudgetCounterStore.java:load` calls `ResourceService.listResources` on the user's
  folder of the budget. That is one `getFolderMetadata` (a blob store `list`) followed by a read of each model row.
- **Where the project comes from.** `IdentityProvider.java:toExtractedClaims` reads the project from
  `projectPath`, and `ProxyContext.java:initExtractedClaims` copies it to `userProject`. A per-request key carries
  the initiator's claims there too (`ApiKeyData.initFromContext`).

## Goals / Non-Goals

**Goals:**
- In `enforce`, the three routes check, report and charge budgets with the same candidates, order and payer as the
  plain routes. They only add the reporting rule (exactly one budget) and the client-shaped answers.
- Controllers, `SpendCapCheck` and the answer builders keep their logic. The change lives in `RateLimiter`,
  `BudgetEnforcer` and `EffectiveBudgets`.

**Non-Goals:**
- No change to the plain routes, the counters' layout, or `GET /v1/user/limits` and `GET /v1/user/usage`.
- No new setting.

## Decisions

### D1. Count the caller's budgets in the cost object, whatever the model

A new pure method `EffectiveBudgets.resolveInCostObject(index, costObject, claims, now)` returns the caller's cost
budgets in one cost object. It applies the same filter as `resolveAll` (kind `budget`, quantity `cost`, not
expired, subject matched), but only on `index.getInstances(costObject)`, and it ignores the model. `resolveAll`
calls it for each cost object, so the filter is written once. A budget is reported only when this list has exactly
one element.

- Rejected: counting per model (`resolve`). The usage endpoint has no model. Also, Claude Code mixes models in one
  session (opus in the main loop, haiku in the background), so per-call headers would come and go from one call to
  the next.
- Rejected: reporting the first budget, by priority, that has room. The owner ruled it misleading when the user holds
  several budgets: the client would show one budget as the whole limit.

### D2. In `enforce`, the budget check runs first on these routes

`checkLimits` gets a third branch for `withSpendCaps && BudgetEnforcer.isEnforced(context, entity)`:

1. `BudgetEnforcer.checkWithSpendCaps` (new, see D3 and D4) runs with the asynchronous loads, as `check` does today.
   A rejection (no budget, or every candidate used up) is returned at once, so the token and request records are
   not touched.
2. Otherwise the existing `checkLimit(context, limit, entity, false, true)` runs on the task executor. It checks
   token and request limits and skips the role cost check, as the plain path does in `enforce`. Its result carries
   the budget's caps (`withSpendCaps`).
3. If step 1 fails, whether with `SpendReadException` or with the unwrapped failure of D6, step 2 still runs. A
   token or request rejection is returned, and otherwise the original failure is rethrown as it was. If step 2
   itself fails (for example when Redis is down), that failure is returned and decides the answer, which is then
   `limitCheckFailed` (500), as on the role path today. This is the order `checkLimitWithSpendCaps` uses for an
   unreadable role record, where a token 429 also wins for a caller with no cost limit.

This keeps the route's documented order: a reached spend cap wins over a token or request limit, and the call is
not counted toward the request limits.

- Rejected: running the budget after the legacy check, as the plain path does. A token limit would then win over an
  exhausted budget, so the route's documented order would hold for role caps but not for budgets.
- Rejected: letting an unreadable counter win over a token or request rejection. That would reverse the documented
  rule for unreadable spend on these routes.

The `shadow` and `off` branches stay as they are. The only change there is to drop `!withSpendCaps` from
`isBudgetCheck`, so `BudgetEnforcer.check` also runs (and sets the payer) after `checkLimitWithSpendCaps`. In
`shadow`, `check` never rejects, so the legacy result, with its caps, still decides. A failed budget load fails
`checkLimits`, as on the plain path, and `SpendCapCheck` answers `limitCheckFailed` (500).

### D3. The rejection names the budget that can pay again first

`checkWithSpendCaps` reuses `pick`. Its `Outcome` gains the payer's `BudgetUsage` and, when no budget pays, the caps
of the dry candidate that resets first. A new `BudgetEnforcer.toSpendCaps(instance, usage)` builds them as
`SpendCaps.of(day, month)`: `total` is the policy amount, `used` the loaded spend, `resetsAtMillis` the loaded reset,
and a window the policy does not set is left out.

For a dry candidate, `select` reports the used-up window that resets last, which is the instant `pick` already
computes for it. Candidates are walked in priority order, and a later one replaces the kept caps only if it resets
strictly earlier, so a tie keeps the earlier budget.

The result is a new 429 `RateLimitResult` with `new SpendCaps(List.of(cap), cap)` and no `displayErrorMessage`.
Then `SpendCapCheck.rejectIfSpendCapReached` sees an exceeded reported cap and answers `billing_error` or
`usage_limit_reached`, with no change to `SpendCapCheck`. Codex builds `display_message` from the cap's period
(`You've exceeded your daily cost limit`). That is the text of the translated path, and of a role cap when one window
is exceeded; when several role windows are exceeded, `CostRateLimit.check` names them all. `retry-after` comes from `SpendCap.secondsUntilReset` when
the answer is built, as for role caps. It names the same reset instant as `check`'s `Retry-After`, but it is not the
same number: `check` rounds up and never goes below 1, and `secondsUntilReset` never goes below 0.

- Rejected: the DIAL 429 "budget exhausted" through `rejectOtherLimit`. Claude Code retries a 429 that has no
  `x-should-retry: false`, and neither client would show when the budget resets.
- Rejected: the spend-cap 429 only when the caller has one budget. With several, the 429 is still true (no budget
  can pay until the named reset). The owner's rule is about usage shown before the limit, not about the rejection.
- Rejected: `display_message` `You've exceeded your budget` (the plain route's text). The translated path has no
  limiter message and would show a different text for the same state.

### D4. The reported caps come from the load the check already made

When a payer is found, `checkWithSpendCaps` sets it on the context and attaches
`toSpendCaps(payer.instance(), payerUsage)` if `resolveInCostObject` (D1) has exactly one element, and
`SpendCaps.EMPTY` otherwise. With one budget in the cost object, the payer is that budget, because a budget that
does not cover the model leaves no candidate and ends in "no budget". A budget whose windows are all at 2^53 or more
gives `SpendCaps.EMPTY` through `SpendCaps.of`.

- Rejected: a separate load of the reported budget after the check. It would add a blob listing per call for data
  the check has already read.

### D5. The read-only paths

`RateLimiter.readSpendCaps` takes the budget path when `spendingLimits.mode` is `enforce`, the caller has claims,
and the context's deployment is absent (the usage endpoint) or a `Model`. A new
`BudgetEnforcer.readSpendCaps(context, timestamp, schedule)` resolves the list from D1. With one element that sets
a window below 2^53, it loads that budget and returns `toSpendCaps`. Otherwise it returns `SpendCaps.EMPTY` without
any load, because a budget too large to report needs no read and must not fail the usage read. (The check of a
Messages or Responses call still loads such a budget, to see whether it has room, and D6 decides what a failed load
gives there.) A failed load fails the
future: the usage endpoint answers its existing 500, and `SpendCapCheck` recovers it to no headers on a translated
deployment. Every other case keeps the role path.

- Rejected: making the read model-aware for translated deployments. With one budget that does not cover the model,
  the outer request reports that budget (the spec scenario "one budget for another model"). The translator's call
  back to Core then answers "no budget", so a real translator returns an error, and an error carries no spend
  headers. This is an edge case, and the inner call is still correct.

### D6. Fail closed on an unreadable counter, only when a budget caps the caller

`checkWithSpendCaps` already holds the candidates for the model. When a load fails, it wraps the failure in the
existing `SpendReadException` only if a candidate sets a window below 2^53, and passes it on unwrapped otherwise.
`RateLimiter.hasCostLimit` returns true when budgets are enforced for the context
(`BudgetEnforcer.isEnforced(context, context.getDeployment())`). In `enforce` the role record is not read, so a
`SpendReadException` there can only come from this wrap. `SpendCapCheck.verify` then answers `spendUnavailable` on
Messages and Responses, and `limitCheckFailed` (500) for the unwrapped failure. This matches the rule for role
caps, where a window at or above 2^53 is unlimited for both reporting and the fail-closed check.

The wrap lives in `checkWithSpendCaps` only, not in the shared `pick`. In `shadow`, `check` must keep failing with
the plain exception, so that `SpendCapCheck` answers 500 there and not the 429 `fetch_error`.

- Rejected: a new exception type. `SpendCapCheck` already maps this one, and the meaning is the same ("the spend that
  caps this caller is unknown").
- Rejected: failing closed for any enforced budget caller. That would treat a 2^53 budget as a limit, which the role
  path does not do.
- Rejected: `hasCostLimit` working out the candidates again with `EffectiveBudgets.resolve`. `resolve` meters
  ambiguous priorities, so the call would be counted twice in `dial_budget_ambiguous_priority_total`. The candidates
  are already in hand where the failure happens.

### D7. "No budget" keeps the plain routes' answer

`BudgetEnforcer`'s `NO_BUDGET` result carries no caps, so `SpendCapCheck` passes it to `rejectOtherLimit`.
`RateLimitResult.throwIfError` then writes the DIAL 429 body with no `retry-after`, as on every other route.

- Rejected: the Anthropic error shape for this answer. Other rejections that are not about spend on that route (for
  example the 403 for a missing role limit) already use the DIAL shape, and changing one of them is out of scope.
- Rejected: a spend-cap 429. There is no window and no reset to name, and waiting does not help.

### D8. Calls to applications keep the role caps

`BudgetEnforcer.isApplicable` requires a `Model`, and the plain routes still check an application call against the
role cost limit. These routes do the same, and report the role caps for it.

- Rejected: reporting the budget on an application call. Budgets do not cover the application's own call, which the
  role limit still decides, so the headers would describe a limit that does not apply to that call. The usage
  endpoint does not know about the deployment, so it reports the budget, and the docs say so.

### Deliberate differences from today

- `enforce`, on the three routes: the role cost limit is no longer reported, read or enforced for calls to models,
  the per-minute window included, as on the plain routes. A JWT caller with no budget for the model now gets 429
  where today the call went through.
- `enforce`: an unreadable budget counter gets the 429 "spend limit could not be read" when a budget below 2^53
  covers the model, and 500 otherwise. Token and request limits decide first, the same rule as for an unreadable
  role record.
- `shadow`, on the three routes: the payer is picked and charged, and would-be rejections are logged and metered.
  Reporting and enforcement stay on the role cost limit. If a budget counter cannot be read, the call fails with
  500, as on the plain routes.
- `enforce`: a call to an application reports role caps, while the usage endpoint reports the budget (D8).

### Effect per request

No body is copied or parsed, and there is no extra Core pass. The budget lookups (`resolve` and
`resolveInCostObject`) work on the in-memory index, and `hasCostLimit` reads only the mode and the deployment type. A budget load is one blob store `list` of the user's folder of the
budget plus a read of each model row. Round trips for a JWT caller of a model:

| Mode, route | Before the call | After the call |
|---|---|---|
| `off`, any | unchanged | unchanged |
| `shadow`, Messages/Responses | + one budget load per candidate tried (usually one) | + one `computeResource` (budget row) |
| `enforce`, Messages/Responses | − one `getResource` (role cost record); + one budget load per candidate tried; token and request reads skipped when the budget rejects | + one `computeResource` (budget row); the role cost write is unchanged |
| `enforce`, usage endpoint | − one `getResource`; + one budget load with one reportable budget, none otherwise | — |
| `enforce`, translated outer request | − one `getResource`; + one budget load with one reportable budget, none otherwise | unchanged (not charged) |

The blob `list` before the upstream is new latency for Claude Code and Codex in `shadow` and `enforce`. The plain
routes already pay it today. Its duration depends on the storage provider and is not measured here. It is counted
in `RateLimiterTest` with Mockito spies on `ResourceService` and on the blob storage, which count `getResource`,
`listResources`, `computeResource` and blob `list` calls per call (task 5.3).

## Risks / Trade-offs

- [Users with several budgets get no warning before the 429] → the rejection still names the reset, and
  `GET /v1/user/usage` (the `dial-usage` mod) lists every budget. The proposal records the owner's choice.
- [A blob listing before the upstream on every Claude Code and Codex call in `shadow` and `enforce`] → the same cost
  the plain routes pay today. The count is measured; the latency is the storage provider's.
- [A prod `projectPath` set to `aud`/`azp` makes the OAuth client id the cost object] → a deployment step in the
  Migration Plan, and a line in the operator docs.
- [The "no budget" 429 is in the DIAL shape on the Anthropic route, so Claude Code may retry it] → D7. Shaping it is
  a possible follow-up.
- [In `shadow`, an unreadable budget counter fails these calls with 500] → the plain routes already behave this way.
  Changing it means changing `checkLimits` for every route, which is out of scope.

## Migration Plan

1. Deploy Core. In `off` and `shadow` nothing visible changes, except that budget counters now also grow from Claude
   Code and Codex calls in `shadow`, and an unreadable counter fails those calls in `shadow`.
2. Before `enforce`, set `identityProviders.<idp>.projectPath` to the claim the launcher's IdP mints (`project`), and
   check that the launcher's project ids are the cost object names of the assignments.
3. Switch `spendingLimits.mode` to `enforce`. Rollback: switch back to `shadow` or `off`. Counters keep the spend.

## Open Questions

- How many prod users hold more than one budget in a project. This decides how many users lose the early warning,
  but changes neither the specs nor the code.
