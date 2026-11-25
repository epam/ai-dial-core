# Review

## What shipped

The no-budget 429 on Messages and the Responses routes carries `x-should-retry: false` and no `retry-after`, directly and through an alias. Docs: `anthropic-spend-limits.md`, `codex-spend-limits.md`, `error_codes.md`, and the OpenAPI 429 text of both controllers.

## Verification

- Checkstyle and `BudgetSpendCapsApiTest`, `BudgetEnforcerTest`, `RateLimiterTest`, `SpendCapCheckTest`, `MessagesControllerLimitTest`, `ResponsesControllerTest`, `MessagesSpendLimitApiTest`, `ResponsesSpendLimitApiTest`: green.
- Claude Code 2.1.332 against Core run locally in enforce mode: the no-budget refusal shown after one request.

## Drift from design.md

None.

## Not done

- The spec delta is not merged: `no-budget-400-on-responses` and `no-budget-reason-on-messages` modified the same requirement and carry this change's `x-should-retry: false` in their own text, so this change is archived with `--skip-specs`.
- Since those changes, the client's own request gets 400 on Responses and 402 on Messages; the 429 with the header stays for a call made with a per-request key.
