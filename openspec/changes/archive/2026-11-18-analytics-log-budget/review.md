# Review

## What shipped

- `BudgetCheck` (`limiter/budget`): mode, cost object, outcome (`paid`, `no_budget`, `exhausted`) and payer, kept on
  `ProxyContext.budgetCheck`. `BudgetEnforcer.checkBudgets` sets it for each outcome, with no outcome until the
  counters are read. `RateLimiter.checkLimits` sets `BudgetCheck.OFF` for a JWT caller's model call in `off` mode.
- `AnalyticsLogContext` carries it from `from(ProxyContext, ...)`, `routerRow` clears it, and
  `from(BackgroundJobRecord, ...)` never sets it.
- `GfLogStore.append` writes `"spending_limits":{"mode",...}` right after `project`.
- `docs/dynamic-settings/policies.md`: "Spending limits in the analytics log".

## Verification

- Tests, one per scenario: `BudgetAnalyticsLogApiTest` (11 integration tests) and `GfLogStoreTest` (8 new tests).
- The first version of the tests (payer only) failed without the change: with the payer copy and the router clear
  removed, 6 of the 9 API tests and the router-row test failed. The tests added for the mode and outcomes assert
  values that only the new `setBudgetCheck` calls produce.
- `checkstyleMain checkstyleTest` pass. `BudgetAnalyticsLogApiTest`, `GfLogStoreTest`, `RouterDecisionApiTest`,
  `BudgetSpendCapsApiTest`, `UserBudgetsApiTest` and `limiter.*` pass.
- The embedded Redis binary of the tests needs Homebrew `openssl@3`, which this machine lacks. The API tests ran
  with `DYLD_LIBRARY_PATH` pointing at another OpenSSL 3 build, through Gradle's Java launcher, because macOS strips
  `DYLD_*` from shell scripts. CI is unaffected.
- Per request: the diff adds a record allocation, a field copy and string appends only, with no `store.*`,
  `resourceService` or body access.

## Drift from design.md

None.

## Findings left out of scope

- A background Responses job charges no budget: `BackgroundJobService` calls `rateLimiter.increase` without a payer,
  and `BackgroundJobRecord` does not keep one. Its spend reaches the role cost record only. This is a
  `budget-counters` gap for a separate change, which should also write `spending_limits` on the job's row.
- A call refused in `enforce` mode writes no analytics row, so ADAS cannot count refusals from `dial_usage_log`.
  The metric `dial_budget_rejections_total` counts them.
- `openspec/specs/resource-subscription/spec.md` fails `validate --specs --strict` on `development` already (a
  placeholder Purpose).

## Follow-up review

- A router's next candidate reuses the request's `ProxyContext`, and neither `RouterDecisionFlow.nextCandidateOrError`
  nor `retryWithNextCandidate` cleared `budgetCheck`. When a model candidate failed, or was refused, and an
  application (or a model whose limits the pass does not check) served next, that row carried the first model's
  `spending_limits`. Both paths now clear it with the rest of the candidate's state. The scenario "Candidate after
  a failed model" and `RouterDecisionApiTest.failedModelCandidateLeavesNoSpendingLimitsOnTheApplicationThatServes`
  cover it. The charge was never affected: only a model whose limits the pass checks is charged, and its own check
  replaces the payer.
