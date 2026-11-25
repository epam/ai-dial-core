# Review

## What shipped
In #2349: the "no budget for the model" rejection on `POST /anthropic/v1/messages`, `POST /openai/v1/responses` and
`POST /openai/responses` carries `x-should-retry: false`, directly and through an alias. Status, body, no
`retry-after` and no spend header as before; chat completions and the other routes unchanged. Docs: the two
spend-limit pages, `docs/error_codes.md`, the 429 `@ApiResponse` text of both controllers.

## Verification
Checkstyle and `BudgetSpendCapsApiTest`, `BudgetEnforcerTest`, `RateLimiterTest`, `SpendCapCheckTest`,
`MessagesControllerLimitTest`, `ResponsesControllerTest`, `MessagesSpendLimitApiTest`, `ResponsesSpendLimitApiTest`
pass. `validate no-budget-should-retry-false --strict` passes.

## Drift from design.md
None.

## Not done
1.6, Claude Code against a dev environment (deferred: needs this build with budgets enforced). The 429 status is
superseded by `coded-spending-refusals`, archived right after this change.
