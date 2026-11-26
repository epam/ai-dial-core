# Review

## What shipped
In `enforce` mode a call no budget covers gets 403 with `error.code` `no_budget` (was 429) on every route but
Messages and Responses, which keep #2360's 402, 400 and internal 429; their DIAL error body gets `no_budget` too. A used-up budget
stays 429 with `retry-after`, and its DIAL error body has `error.code` `budget_exhausted`. The metric reason and the
shadow log use the codes; constants for every M2 code. Docs: `error_codes.md`, both spend-limit pages, `policies.md`,
the README `spendingLimits` row, the `@ApiResponse` text (the committed `open_api_core.yaml` keeps its generic text).

## Verification
Checkstyle and `BudgetSpendCapsApiTest`, `BudgetEnforcerTest`, `RateLimiterTest`, `SpendCapCheckTest`,
`MessagesControllerLimitTest`, `ResponsesControllerTest` pass. `:openapi-generator:replaceSpec` leaves
`docs/open_api_core.yaml` unchanged. `validate coded-spending-refusals --strict` passes.

## Drift from design.md
#2360 merged first; D2 and the spec deltas were changed in place to keep its Messages and Responses answers.

## Not done
How Claude Code shows a 403 in the DIAL body format is not measured. Release note text is in `proposal.md`.
