# Review

## What shipped

- The resolution ladder for a JWT caller's model call: `X-DIAL-BUDGET` pays alone (403 `no_budget` when not held,
  403 `budget_not_applicable` when it does not cover the call, 429 when used up); else the user's default budget on
  the effective cost object (403 `default_budget_not_applicable` when it does not cover); else the priority walk,
  behind `spendingLimits.autoBudgetSelection` (default `true`), or 403 `no_budget` asking to indicate a budget.
- The root check (`BudgetHeaders.verifyRootCall`) on a JWT caller's root call to any deployment (deployments-POST,
  chat completions, Responses, Messages, MCP toolsets), in every mode: 400 `invalid_budget_indication`,
  `invalid_cost_object`, `cost_object_mismatch` (where the provider sets `costObjectPath`) and
  `budget_indication_required` (`spendingLimits.budgetMandatory`, default `false`). API key calls and per-request-key
  calls ignore both headers.
- The effective cost object: claim, else `defaultCostObject`; without a claim path, the indicated budget's cost
  object or `X-DIAL-COST-OBJECT`, with source `header` in the analytics row. Under source `header`, `TRUE` subject
  rules do not count.
- The per-request key stores the root's indication and header cost object only when present (`NON_NULL`); each hop
  resolves the indication against the current config. `ExtractedClaims.costObjectPathSet` is not stored.
- Messages and Responses report the payer's windows; the usage endpoint keeps the one-budget rule. Every 403 refusal
  of the budget check gets the route's no-budget answer there.
- Docs: README `spendingLimits` row; `docs/dynamic-settings/policies.md` ("Which budget pays a call", default budget,
  analytics `outcome`, `cost_object.source`, meter reasons); `docs/error_codes.md`; `docs/anthropic-spend-limits.md`
  and `docs/codex-spend-limits.md` (the payer is reported).

## Verification

- `./gradlew checkstyleMain checkstyleTest`: passed.
- `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`: passed.
- `SpendingLimitsConfigTest`, `ApiKeyDataTest`, `CostObjectTest`, `EffectiveBudgetsTest`, `BudgetEnforcerTest`,
  `RateLimiterTest`, `CostRateLimitTest`, `DefaultBudgetServiceTest`, `BudgetResolutionApiTest` (new),
  `BudgetSpendCapsApiTest`, `BudgetChainCoverageApiTest`, `DefaultBudgetApiTest`, `MessagesSpendLimitApiTest`,
  `ResponsesSpendLimitApiTest`, `BudgetAnalyticsLogApiTest`, `CostObjectAnalyticsLogApiTest`, `SpendCapCheckTest`,
  `UserBudgetsApiTest`, `BudgetDisplayNameApiTest`, the `UserInfo*` and `IdentityProvider*` classes and
  `GfLogStoreTest`: passed.
- Measurement (`RateLimiterTest.testRoundTripsOfTheSpendCapRoutes`): an unindicated check in shadow and enforce makes
  one more default-budget read than before, two `ResourceService.getResource` invocations (the service calling its
  own overload), with listings, blob lists and writes unchanged; on `development` shadow equalled off in
  `getResource`. An indicated call reads no default and loads one counter (`BudgetEnforcerTest`).

## Drift from design.md

- `RateLimiter` builds the `DefaultBudgetService` for `BudgetEnforcer` from its own `ResourceService`, instead of
  `AiDial` passing one in (D4, Code map).
- The root check also runs on MCP toolset calls (`McpProxyController.handle`), as "any deployment" requires.
- `RateLimitResult.isNoBudget` now covers every 403 refusal, so the Messages and Responses routes answer
  `budget_not_applicable` and `default_budget_not_applicable` as `no_budget` (added to the Code map).
- The measurement is in `RateLimiterTest` rather than `BudgetResolutionApiTest`; some scenario tests live in
  `BudgetSpendCapsApiTest` and `BudgetResolutionApiTest` instead of the classes the tasks first named.

## Not done

- The key's bound budget and 400 `budget_mismatch` (#2369), caps (#2368), pools (#2367) and discovery (#2370) are
  their own changes.
- The full suite runs in CI after the push.
- Release notes: the new headers, the 400s and 403s, the reporting change, and the rolling-upgrade note of design D2.
