# Tasks

## 1. Coded spending refusals

- [x] 1.1 Compare `design.md` with the code on `development`; on a drift, correct the decision or the Code map entry
      in place
- [x] 1.2 Tests first. `BudgetSpendCapsApiTest`: "No budget on chat completions" and "No budget in enforce mode"
      (`testNoBudgetOnChatCompletions`), "Budget used up on chat completions" (`testBudgetUsedUpOnChatCompletions`),
      "No budget in shadow mode" (`testShadowNoBudgetDoesNotReject`), "Used up in shadow mode"
      (`testShadowExhaustedBudgetsDoNotReject`); `assertNoBudgetAnswer` expects 403 and `no_budget` for "Budget for
      another model only", its Responses scenario, and "No budget, through an alias and a router" (one decide request).
      `BudgetEnforcerTest`: the no-budget results are 403 with code `no_budget`, the exhausted ones 429 with
      `budget_exhausted`
- [x] 1.3 Implement D1: the refusal-code enum with every M2 code and its status; `RateLimitResult` carries the code and
      `throwIfError` writes it into `error.code`; `BudgetEnforcer` builds and meters its refusals from the enum
- [x] 1.4 Docs: `docs/error_codes.md` (403 and 429), the "No budget for the model" bullets of
      `docs/anthropic-spend-limits.md` and `docs/codex-spend-limits.md`, `docs/dynamic-settings/policies.md`, the README `spendingLimits` row, and the
      `@ApiResponse` text on `MessagesController.handle` and `ResponsesController.handle`
- [x] 1.5 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "*BudgetSpendCapsApiTest" --tests "*BudgetEnforcerTest" --tests "*RateLimiterTest" --tests "*SpendCapCheckTest" --tests "*MessagesControllerLimitTest" --tests "*ResponsesControllerTest"`,
      then `npx -y @fission-ai/openspec@1.14.3 validate coded-spending-refusals --strict`
