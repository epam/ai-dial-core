# Tasks

## 1. Skip the role cost limit for user callers in enforce mode

- [x] 1.1 In `BudgetSpendCapsApiTest`, add the scenario "Application called with the role cost limit reached": role
  day spend $10, one budget with room, a JWT user calls `app-chain`, which calls `gpt-spend` with its per-request key;
  assert both answer 200. Add "API key caller of an application": `proxyKey1` with its role day spend at $10 calls
  `app-chain`; assert 429. The first fails before 1.3.
- [x] 1.2 In `BudgetSpendCapsApiTest`, change `testApplicationCallKeepsRoleCaps` to the scenario "Call to an
  application": the Messages answer carries no `anthropic-ratelimit-unified-*` header, the usage endpoint reports the
  budget. Add "Messages call to an application with the role cost limit reached": answers 200.
- [x] 1.3 Add `BudgetEnforcer.isRoleCostLimitSkipped` and use it in `RateLimiter.checkLimits` (design Code map);
  update the `isApplicable` Javadoc.
- [x] 1.4 In `RateLimiterTest`, check that the role cost record is not read for a user's call to an application in
  `enforce`, and is read in `shadow` and for an API key.
- [x] 1.5 Update "Applications" in `docs/anthropic-spend-limits.md` and `docs/codex-spend-limits.md`.
- [x] 1.6 Run `./gradlew checkstyleMain checkstyleTest :server:test --tests "*BudgetSpendCapsApiTest" --tests
  "*RateLimiterTest" --tests "*BudgetEnforcerTest"` and `npx -y @fission-ai/openspec@1.14.3 validate
  jwt-callers-skip-role-cost-limit --strict`.
