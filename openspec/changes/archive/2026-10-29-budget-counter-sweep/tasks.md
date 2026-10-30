## 1. Sweep

- [x] 1.1 `BudgetCounterSweepService` and `budgetCounterSweep` settings with defaults; wire in `AiDial`
- [x] 1.2 Tests: `BudgetCounterSweepServiceTest`

## 2. Guard and shadow record

- [x] 2.1 `MergedConfigStore` warning and `dial_budget_personal_missing`; `PersonalBudgetGuardTest`
- [x] 2.2 `user` in the `shadow` log line of `BudgetEnforcer`

## 3. Docs and checks

- [x] 3.1 README: spending limits, the guard and the sweep setting
- [x] 3.2 `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`
- [x] 3.3 `npx -y @fission-ai/openspec@1.14.3 validate budget-counter-sweep --strict`
