# Review

## What shipped
In `enforce` mode a call no budget covers gets 403 with `error.code` `no_budget` (was 429); Messages and Responses
keep `x-should-retry: false`, and through an alias a router in decision mode is not asked again. A used-up budget
stays 429 with `retry-after`, and its DIAL error body has `error.code` `budget_exhausted`. The metric reason and the
shadow log use the codes; constants for every M2 code. Docs: `error_codes.md`, both spend-limit pages, `policies.md`,
the README `spendingLimits` row, the `@ApiResponse` text (the committed `open_api_core.yaml` keeps its generic text).

## Verification
Checkstyle and `BudgetSpendCapsApiTest`, `BudgetEnforcerTest`, `RateLimiterTest`, `SpendCapCheckTest`,
`MessagesControllerLimitTest`, `ResponsesControllerTest` pass. `:openapi-generator:replaceSpec` leaves
`docs/open_api_core.yaml` unchanged. `validate coded-spending-refusals --strict` passes.

## Drift from design.md
None.

## Not done
How Claude Code shows a 403 in the DIAL body format is not measured. Release note text is in `proposal.md`.
