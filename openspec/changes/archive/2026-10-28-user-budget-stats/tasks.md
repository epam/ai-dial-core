## 1. Budgets in the stats

- [x] 1.1 `CostObjectIndex.getCostObjects` and `EffectiveBudgets.resolveAll`
- [x] 1.2 `BudgetCounterStore.loadBlocking` and `BudgetEnforcer.collectStats`
- [x] 1.3 `BudgetStats` and `UserLimitStats.budgets`; call from `RateLimiter.collectUserStats`
- [x] 1.4 Tests: `UserBudgetsApiTest` (one per scenario), `EffectiveBudgetsTest`
- [x] 1.5 `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`

## 2. Docs

- [x] 2.1 Regenerate `docs/open_api_core.yaml` (`:openapi-generator:replaceSpec`)
- [x] 2.2 `npx -y @fission-ai/openspec@1.14.3 validate user-budget-stats --strict`
