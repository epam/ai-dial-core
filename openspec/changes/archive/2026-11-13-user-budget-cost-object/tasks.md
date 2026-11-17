# Tasks

## 1. Cost object and models in the user stats

- [x] 1.1 Tests first, one per new or changed scenario of the `user-limits` delta. A token with a project uses the
      `user@projA` authorization. In `UserBudgetsApiTest` (`dial-config/user-budgets.json`, mode `shadow`, unless
      noted):
      - Token with a project claim: `testCostObjectIsTheTokenProject`.
      - Token without a project claim: `testCostObjectIsTheConfiguredDefault`, with a new
        `dial-config/user-budgets-default.json`, a copy of `user-budgets.json` with `defaultCostObject` `PERSONAL`, so
        the test cannot pass with a hard-coded `ORG` (a fixture of its own rather than reusing
        `config-file-migrate.json`, which belongs to another test).
      - No matching budget: `testCostObjectWithoutMatchingBudget`.
      - API key caller: extend `testApiKeyCallerHasNoBudgets` to assert no `costObject` (`proxyKey1` sets project
        `budget-project`).
      - Spending limits off: `testNoCostObjectWhenSpendingLimitsOff`, without `@DialConfigLocation`: the default test
        config sets no `spendingLimits`, so the mode is `off`.
      - Application called by a user: `testPerRequestKeyHasRootCallerCostObject`, a per-request key built as
        `ResourceBaseTest.createAppKey` does but with `createClaims("user", "projA")`, assigned with
        `apiKeyStore.assignPerRequestApiKey` and sent as `api-key`.
      - Application called by an API key: `testPerRequestKeyOfApiKeyHasNoCostObject`, a per-request key with
        `setOriginalKey` from `proxyKey1` and no claims, as `FileApiTest.testPerRequestBucket` builds one;
        the absent `costObject` carries the test.
      - Budget for every model: `testBudgetForEveryModelHasObjectStar`.
      - Budget for listed models: `testBudgetForListedModelsHasSortedObject`, with a `putPolicy` overload that takes
        the `object` JSON.
      In `BudgetSpendCapsApiTest` (`dial-config/budget-spend-caps.json`, mode `enforce`):
      - Enforce mode: `testUserUsageNamesTheCostObject`, through the existing `userUsage(USER_A)` helper.
      The `UserBudgetsApiTest` tests call `/v1/user/limits` (the enforce test calls `/v1/user/usage`), so both
      endpoints are covered. The other scenarios of "Budgets in the user stats" keep their existing tests. Verify the new tests fail before 1.2.
- [x] 1.2 Add `costObject` (`@JsonInclude(NON_NULL)`) to `UserLimitStats` and `object` (`PolicyObjectSet`) to
      `BudgetStats`. In `BudgetEnforcer.collectStats` set `object` from `instance.policy().getObject()` and return the
      list together with `resolveCostObject` (a record), with no cost object under the existing `off` and no-claims
      check; set both in `RateLimiter.collectUserStats`. Verify the tests of 1.1 pass.
- [x] 1.3 Update the `UserLimitStats.budgets` javadoc to mention `costObject`, and check the diff of 1.2 adds no
      `store.*` or `resourceService` call (the per-request cost claim of design.md).
- [x] 1.4 Run `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "com.epam.aidial.core.server.UserBudgetsApiTest" --tests "com.epam.aidial.core.server.BudgetSpendCapsApiTest" --tests "com.epam.aidial.core.server.limiter.*"`;
      both pass.

## 2. Docs

- [x] 2.1 In the "Reported only with one budget" bullet of `docs/anthropic-spend-limits.md` and
      `docs/codex-spend-limits.md`, where they point to `budgets[]`, say that `GET /v1/user/usage` names the caller's
      cost object in `costObject` and the models of each budget in `object`, and that which budget of that cost object
      pays depends on the model (`object`) and the priority. Verify by reading both bullets.
- [x] 2.2 Regenerate `docs/open_api_core.yaml` with `./gradlew :openapi-generator:replaceSpec` and check the diff adds
      only `costObject` to `UserLimitStats` and `object` to `BudgetStats`.
- [x] 2.3 Run `./gradlew checkstyleMain checkstyleTest` and
      `npx -y @fission-ai/openspec@1.14.3 validate user-budget-cost-object --strict`; both pass.

## Workflow follow-up

- Write `review.md`, archive with `npx -y @fission-ai/openspec@1.14.3 archive user-budget-cost-object -y`, then
  `validate --specs --strict`.
