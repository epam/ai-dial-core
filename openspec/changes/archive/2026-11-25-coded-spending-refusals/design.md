# Design

## Context

With `spendingLimits.mode` `enforce`, every model route checks a JWT caller's call against the budgets of their cost
object before the token and request limits run. The budget check refuses in two cases, no budget and every budget
used up, and both refusals are 429. A route that answers with the DIAL error body writes the HTTP status into
`error.code`. Messages and Responses answer a used-up budget in their own spend-cap format. A router in decision mode
reaching a model through an alias asks the router again after a 429 from the alias's target, because Core retries a
429, but it does not retry a 403. `shadow` mode runs the same check, counts the refusal in
`dial_budget_rejections_total{reason, mode}` and lets the call through.

## Decisions

### D1. A refusal carries its code, and the DIAL error body writes it into `error.code`

An enum of refusal codes holds each code with its status. The budget check builds its refusal from that enum, and
the rate-limit result carries the code. `throwIfError` writes the code into `error.code` and keeps the status number
for every other limit, so token, request and role refusals do not change.

**Rejected: a new error field next to `code`** - the issue keeps the Azure-style body, and `code` is already the
field clients read.

### D2. Through an alias, a router does not ask again after `no_budget`

This follows from the status: Core retries 429, 502, 503 and 504, not 403. A router that chooses the model directly
still skips to the next candidate on any refusal before the call, as it does today for `403 Access denied`.

**Rejected: make Core retry a `no_budget` 403 through an alias** - it adds a special case to the retry rules, and
under M2 the payer is declared, so moving to another model would not find a different budget.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| No budget, any model route | 429, `error.code` `"429"` | 403, `error.code` `no_budget` | No budget refused with 403 |
| No budget on Messages / Responses | 429 with `x-should-retry: false` | 403 with `x-should-retry: false` | No budget for the model |
| No budget, through an alias and a router in decision mode | router asked `maxDecisions` times | router asked once | No budget for the model |
| Used up, DIAL error body | `error.code` `"429"` | `error.code` `budget_exhausted` | Used-up budget refused with 429 |

## Effect per request

None: a different status and code on a refused call; no extra pass, parse, Redis or blob call.

## Risks / Trade-offs

- Clients that retry or explain only 429 now see a 403. That is the breaking change, and it goes in the release note.
- Claude Code with a 403 in the DIAL body format: not measured. `x-should-retry: false` stays, so the Anthropic SDK
  does not retry it.

## Migration Plan

None in Core. Clients follow the release note.

## Code map

- `BudgetEnforcer.java:rejectNoBudget`, `BudgetEnforcer.java:reject`, `BudgetEnforcer.java:buildExhaustedResult`: where
  the refusals are built and metered.
- `RateLimitResult.java`: the record, its constructors, `withSpendCaps` and `throwIfError` (the DIAL error body).
- `SpendCapCheck.java:verify`, `MessagesController.java:verifyLimit` (`rejectOtherLimit`), `ResponsesController.java:CODEX_ANSWERS`:
  a 403 goes to `throwIfError`.
- `BaseDeploymentPostController.java:isRetriableError`, `RouterDecisionFlow.java:nextCandidateOrError`: the retry rules of D2.
- Tests: `BudgetEnforcerTest`, `BudgetSpendCapsApiTest` (`assertNoBudgetAnswer`, the no-budget tests, `testShadowExhaustedBudgetsDoNotReject`).
