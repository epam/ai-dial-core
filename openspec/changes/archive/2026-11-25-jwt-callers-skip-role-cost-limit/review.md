## What shipped

In `enforce` mode Core no longer checks the role `costLimit` on a call that carries a user's claims, whatever the
deployment; token and request limits stay, and the models an application calls are checked against the user's budgets.
A Messages or Responses call to an application by such a caller reports no spend caps. API key callers, `off` and
`shadow` are unchanged. Docs: "Applications" in `docs/anthropic-spend-limits.md` and `docs/codex-spend-limits.md`.

## Verification

- `BudgetSpendCapsApiTest`: the new application scenarios failed before the fix (`Hit cost rate limit`, the
  `billing_error` 429, role caps reported) and pass after it; the API key scenario passes before and after.
- `RateLimiterTest.testRoleCostLimitOfApplicationCall`: enforce, shadow and API key callers of an application.
- `./gradlew checkstyleMain checkstyleTest` and `BudgetSpendCapsApiTest`, `RateLimiterTest`, `BudgetEnforcerTest`,
  `BudgetAnalyticsLogApiTest`, `MessagesSpendLimitApiTest`: all pass.

## Drift from design.md

`POST /openai/deployments/{id}/chat/completions` never checks an application's limits, so the chat scenarios use
`POST /openai/v1/chat/completions`; Context and the scenario now say so.

## Not done

None.
