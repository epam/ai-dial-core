# Tasks

## 1. `x-should-retry: false` on the no-budget 429

- [x] 1.1 Compare `design.md` with the code on `development`; on a drift, correct the decision or the Code map entry
      in place
- [x] 1.2 Tests in `BudgetSpendCapsApiTest`, written first; each fails on today's code only on the missing
      `x-should-retry` (run them and confirm):
      "Budget for another model only" (`testBudgetForAnotherModelOnly`, gains the `x-should-retry: false` assertion);
      "Budget for another model only, Responses" (`testBudgetForAnotherModelOnlyOnResponses`: a budget for
      `gpt-other` only, a call of `gpt-spend` through `postResponses` (`/openai/v1/responses`) and through `send` on
      `/openai/responses`; asserts 429, no upstream call, the DIAL body with `code` `429` and today's `message` and
      `display_message`, `x-should-retry: false`, no `retry-after`, no `x-codex-*` header);
      "No budget, through an alias and a router" (`testNoBudgetThroughAliasAndRouterOnMessages` and
      `testNoBudgetThroughAliasAndRouterOnResponses`, each with its own test server: a budget for `claude-other` only,
      a call of `strong`; asserts three decide requests, no upstream call, `No budget covers m1 for cost object projA`,
      `x-should-retry: false`, no `retry-after`, no `anthropic-ratelimit-unified-*` or `x-codex-*` header)
- [x] 1.3 Guard test `testNoBudgetOnChatCompletionsHasNoShouldRetry` in `BudgetSpendCapsApiTest`: a chat completions
      call of `gpt-translated` with no budget for it gets the no-budget 429 without `x-should-retry`; it passes before
      and after the change
- [ ] 1.4 Implement D2: `RateLimitResult` gains `isClientRetryDisabled`, and `throwIfError` adds `x-should-retry: false`
      when it is set; `BudgetEnforcer.rejectNoBudget` sets it only on the `checkWithSpendCaps` path. The tests of 1.2
      and 1.3 pass
- [x] 1.5 Docs: the "No budget for the model" bullets of `docs/anthropic-spend-limits.md` and
      `docs/codex-spend-limits.md`, the 429 paragraph of `docs/error_codes.md`, and the 429 `@ApiResponse`
      descriptions on `MessagesController.handle` and `ResponsesController.handle` say the answer carries
      `x-should-retry: false` and no `retry-after`; each reads the same as the requirement
- [ ] 1.6 Claude Code calling a model that no budget of the project covers shows the error on the first request,
      with no retries (deferred: needs a dev environment with this build and budgets in enforce mode)
- [ ] 1.7 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "*BudgetSpendCapsApiTest" --tests "*BudgetEnforcerTest" --tests "*RateLimiterTest" --tests "*SpendCapCheckTest" --tests "*MessagesControllerLimitTest" --tests "*ResponsesControllerTest" --tests "*MessagesSpendLimitApiTest" --tests "*ResponsesSpendLimitApiTest"`,
      then `npx -y @fission-ai/openspec@1.14.3 validate no-budget-should-retry-false --strict`

## Workflow follow-up

- Write `review.md`, archive with `npx -y @fission-ai/openspec@1.14.3 archive no-budget-should-retry-false -y`, then
  `npx -y @fission-ai/openspec@1.14.3 validate --specs --strict`.
- One local commit, staging explicit paths. No push and no PR until the owner has reviewed it.
