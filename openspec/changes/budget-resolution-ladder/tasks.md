# Tasks

## 1. Flags and the claim path flag

- [ ] 1.1 Add `autoBudgetSelection` (default `true`) and `budgetMandatory` (default `false`) to `SpendingLimits`;
  `SpendingLimitsConfigTest` covers both defaults and a config that sets them.
- [ ] 1.2 Add the `costObjectPathSet` component to `ExtractedClaims`, excluded from JSON, set by
  `IdentityProvider.toExtractedClaims`; `ApiKeyDataTest` shows a key written with it reads back on a mapper that fails
  on unknown fields, and that a key without it still reads.
- [ ] 1.3 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "*SpendingLimitsConfigTest" --tests "*ApiKeyDataTest"`.

## 2. Root header check, effective cost object and key data

- [ ] 2.1 Write `BudgetResolutionApiTest` with config `dial-config/budget-resolution.json` (cost objects `IMF`, `ORG`,
  `EDU`; `IMF/default-members` with a `TRUE` subject, `IMF/TKT-1` with `sub` equal to alice, `ORG/personal`; models
  `m`, `m2` behind aliases `strong` and `hi-end`; application `app`) and two identity providers, with and without
  `costObjectPath`. Its first tests, red until 2.2-2.4: "Indication with one element", "Cost object with a slash",
  "Malformed header where a claim path is set", "Claim missing, indication on the project", "Unindicated call with
  the flag on", "API key caller with the flag on", "Malformed header from an API key" (budget-resolution), and
  "Malformed indication message", "Malformed cost object message", "Cost object mismatch message", "Indication
  required on Responses" (spending-refusals).
- [ ] 2.2 Share the element rule of `PolicyService.isValidElement`, and add the root check called from the
  deployment controllers before the limit check (D1): JWT caller, no per-request key, any deployment, every mode;
  ignored for API keys; returns the 400 `RateLimitResult` with its `SpendingRefusal` through the existing rejection
  path (D9), counted in `dial_budget_rejections_total`.
- [ ] 2.3 Resolve the effective cost object at the root: claim, `defaultCostObject`, or, without a claim path, the
  indication's cost object or `X-DIAL-COST-OBJECT` with `CostObject.Source.HEADER` (`default` when it equals
  `defaultCostObject`); `ProxyContext.getCostObject` / `getCostObjectSource` return it on deployment calls only, so
  `/v1/user/info` is unchanged. `CostObjectTest` covers each source.
- [ ] 2.4 Store `budgetIndication` and `headerCostObject` in `ApiKeyData` from the root only, `NON_NULL` (D2); a call
  with a per-request key ignores both headers and reads these. `ApiKeyDataTest` covers root, child and absent values.
- [ ] 2.5 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "*BudgetResolutionApiTest" --tests "*CostObjectTest" --tests "*ApiKeyDataTest"`.

## 3. The ladder

- [ ] 3.1 Unit tests first in `EffectiveBudgetsTest`: holding of one instance by id (unexpired cost budget, subject
  match) with and without the attestation rule (D6, D7), including a `[TRUE, sub = alice]` subject.
- [ ] 3.2 In `BudgetEnforcer.checkBudgets`: indication → held (403 `no_budget`, uniform message) → covers (403
  `budget_not_applicable`, head of the chain, D8) → `pickPayer` over that one (429); else the default from
  `DefaultBudgetService.findDefault` on the task executor (D4), in effect only on the effective cost object and while
  held under the attestation rule → covers (403 `default_budget_not_applicable`) → `pickPayer` over it; else the walk
  when `autoBudgetSelection`, with `TRUE` rules dropped under a header source, else 403 `no_budget` asking to
  indicate a budget. Each 403 sets `BudgetCheck.noBudget()` and goes through `reject`, so `shadow` logs and meters.
  `BudgetEnforcerTest` covers each branch in `enforce` and `shadow`.
- [ ] 3.3 Pass `DefaultBudgetService` into `RateLimiter` / `BudgetEnforcer` (wired in `AiDial`).
- [ ] 3.4 Add to `BudgetResolutionApiTest`: "Claim present", "Claim missing, header ignored", "Header scopes the
  walk", "Header and indication", "Indication on the claim's cost object", "Ticket indicated", "Another person's
  ticket", "Indicated budget does not cover the model", "Indicated budget used up", "Header walk skips a TRUE-only
  budget", "Indicated TRUE-only budget without a claim path", "Header walk reaches a named budget", "Default pays
  over a higher-priority budget", "Default does not cover the call", "Default used up", "Default on another cost
  object", "Default withdrawn", "Zero setup", "Walk turned off", "Application calls a model under the root's
  indication", "Application sends its own indication", "Header cost object carried to the model", "Budget withdrawn
  mid-session" (budget-resolution); "Indicated budget not held", "Walk turned off", "Indicated budget used up",
  "Default not applicable in shadow mode", "Malformed indication in shadow mode", "Indicated budget through a tier
  alias", "Default budget not covering" (spending-refusals).
- [ ] 3.5 Keep the existing scenarios passing in `BudgetSpendCapsApiTest` and `BudgetChainCoverageApiTest`
  ("No budget on chat completions", "Budget used up on chat completions", the metered refusals, coverage and chain
  attribution).
- [ ] 3.6 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*EffectiveBudgetsTest"
  --tests "*BudgetEnforcerTest" --tests "*BudgetResolutionApiTest" --tests "*BudgetSpendCapsApiTest"
  --tests "*BudgetChainCoverageApiTest" --tests "*DefaultBudgetApiTest"`.

## 4. Reporting and analytics

- [ ] 4.1 `checkWithSpendCaps` reports the payer's windows whatever step picked it, dropping the one-budget count;
  an exhausted declared payer reports its own cap; `readSpendCaps` keeps the one-budget rule.
- [ ] 4.2 Tests in `ResponsesSpendLimitApiTest` and `MessagesSpendLimitApiTest`: "Several budgets", "Two budgets in
  the cost object", "Indicated budget reported", "Default budget reported", "Second budget covers another model",
  "One of several budgets has room", "Indicated budget exhausted, another has room" (spend-limit-reporting), and
  "Indicated budget not covering on Messages" (spending-refusals); the other scenarios of the modified requirements
  keep passing.
- [ ] 4.3 Tests in `BudgetAnalyticsLogApiTest` and `CostObjectAnalyticsLogApiTest`: "Indicated budget not covering in
  shadow mode", "Cost object from the header" (analytics-log).
- [ ] 4.4 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*ResponsesSpendLimitApiTest"
  --tests "*MessagesSpendLimitApiTest" --tests "*BudgetAnalyticsLogApiTest" --tests "*CostObjectAnalyticsLogApiTest"
  --tests "*SpendCapCheckTest"`.

## 5. Docs

- [ ] 5.1 `README.md` `spendingLimits` row: the two flags, the headers, the ladder and the new refusals; the
  reporting rule (the payer, not one budget).
- [ ] 5.2 `docs/dynamic-settings/policies.md`: the indication, the effective cost object and the attestation rule,
  the default at call time, the analytics `header` source, the new meter reasons; `docs/error_codes.md`: the new
  codes; `docs/anthropic-spend-limits.md` and `docs/codex-spend-limits.md`: the payer is reported.
- [ ] 5.3 Run `./gradlew checkstyleMain checkstyleTest` and `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`.

## 6. Verification and measurement

- [ ] 6.1 Run every test class named above together; all pass.
- [ ] 6.2 Measure the default read (design Effect per request): in `BudgetResolutionApiTest`, count the
  `resource.get` spans and budget counter loads of one unindicated and one indicated model call, compare with the
  same call on `development`, and record the numbers in `review.md`.

## Workflow follow-up

- Write `review.md`, archive with `npx -y @fission-ai/openspec@1.14.3 archive budget-resolution-ladder -y`, then
  `validate --specs --strict`.
- Release notes: the new headers, the 400s and 403s, the reporting change, and the rolling-upgrade note of design D2.
