# Tasks

## 1. Spending limits in the analytics row

- [x] 1.1 Tests first, one per scenario of the `analytics-log` delta. In `BudgetAnalyticsLogApiTest`
      (extends `ResourceBaseTest`, reusing the fixtures of `BudgetSpendCapsApiTest`), reading the model's row from
      the collected analytics rows:
      - Enforce mode and assigned budget: `testEnforceModeRowNamesTheBudget` (`budget-spend-caps.json`).
      - Shadow mode: `testShadowModeRowNamesTheBudget` (`budget-spend-caps-shadow.json`).
      - Spending limits off: `testSpendingLimitsOffRowHasTheModeOnly`, with `dial-config/budget-analytics-off.json`,
        `budget-spend-caps.json` with mode `off`.
      - No budget in shadow: `testShadowNoBudgetRowSaysSo`.
      - Every budget used up in shadow: `testShadowExhaustedRowSaysSo`.
      - Every budget used up in enforce: `testEnforceRefusedRequestWritesNoRow`.
      - Fall-through: `testFallThroughRowNamesTheSecondBudget`.
      - Token without a project claim: `testTokenWithoutProjectNamesTheDefaultCostObject`.
      - Policy that applies by itself: `testSelfApplyingPolicyHasNoAssignment`.
      - API key caller: `testApiKeyRowHasNoBudget`.
      - Application calling a model: `testApplicationCallNamesTheInitiatorBudgetOnTheModelRowOnly`.
      In `GfLogStoreTest`:
      - Router row: `testRouterRowHasNoSpendingLimits`; background job row: `testBackgroundJobRowHasNoSpendingLimits`.
      - The JSON shape: `testSpendingLimitsAreWrittenRightAfterProject` (escaped values, all five keys),
        `testSpendingLimitsOfSelfApplyingPolicyHaveNoAssignment`, `testSpendingLimitsWithoutPayer`,
        `testSpendingLimitsOffHaveTheModeOnly`, `testSpendingLimitsWithoutOutcomeWhenTheCounterWasNotRead`,
        `testSpendingLimitsAreOmittedWithoutCheck`.
      Verify the new tests fail before 1.2.
- [x] 1.2 Add `BudgetCheck` (mode, cost object, outcome, payer) and `ProxyContext.budgetCheck`. Set it in
      `BudgetEnforcer.checkBudgets` for each outcome and in `RateLimiter.checkLimits` for `off`. Carry it on
      `AnalyticsLogContext` from `from(ProxyContext, ...)`, clear it in `routerRow`, and write `spending_limits` in
      `GfLogStore.append` right after `project`. Verify the tests of 1.1 pass.
- [x] 1.3 Run `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "com.epam.aidial.core.server.BudgetAnalyticsLogApiTest" --tests "com.epam.aidial.core.server.log.GfLogStoreTest" --tests "com.epam.aidial.core.server.RouterDecisionApiTest" --tests "com.epam.aidial.core.server.BudgetSpendCapsApiTest" --tests "com.epam.aidial.core.server.UserBudgetsApiTest" --tests "com.epam.aidial.core.server.limiter.*"`;
      both pass.
- [x] 1.4 Check the diff of 1.2 adds no `store.*`, `resourceService` or body access (the per-request claim of
      design.md).

## 2. Docs

- [x] 2.1 In `docs/dynamic-settings/policies.md`, add "Spending limits in the analytics log": the object, its keys
      and outcomes, when it is absent, that a call refused in `enforce` has no row, and that the spend charged to a
      budget is the sum of `deployment_price` over its rows. Verify by reading the section against the spec delta.
- [x] 2.2 Run `npx -y @fission-ai/openspec@1.14.3 validate --specs --strict` for `analytics-log`; it passes.
