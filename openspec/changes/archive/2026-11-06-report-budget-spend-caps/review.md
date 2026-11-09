# Review: report-budget-spend-caps

## What shipped

- `EffectiveBudgets.resolveInCostObject`: the caller's cost budgets in one cost object, whatever the model, not
  metered. `resolveAll` is now built on it.
- `BudgetEnforcer`:
  - `checkWithSpendCaps`, the `enforce` check of the spend-cap routes. It picks and stores the payer as `check`
    does. It attaches the payer's caps when the caller has exactly one budget in the cost object, and
    `SpendCaps.EMPTY` otherwise. When every candidate is used up, it rejects with the cap of the budget that pays
    again first. A failed load is wrapped in `SpendReadException` only when a candidate has a window below 2^53.
  - `readSpendCaps` and `isReadFromBudgets`, the read-only path of the usage endpoint and of translated deployments.
  - `toSpendCaps` and `hasLimitedWindow`.
  - `pick` now also keeps the payer's usage and the cap of the dry candidate that resets first (strictly earlier
    replaces it, so a tie keeps the earlier budget). `check`'s answers are unchanged.
- `RateLimiter`:
  - `checkLimits` sends `withSpendCaps` calls in `enforce` to `checkBudgetsWithSpendCaps`. The budget is checked
    first, then the token and request limits through `submitCheck` (the former inline lambda). A token or request
    rejection wins over a failed budget load, and a failure of that check itself decides.
  - `shadow` now runs the budget check on the spend-cap routes too (`isBudgetCheck` lost `!withSpendCaps`).
  - `hasCostLimit` returns true when budgets are enforced.
  - `readSpendCaps` reads from budgets in `enforce`.
- OpenAPI header and 429 descriptions in `MessagesController` and `ResponsesController`, and the three header
  descriptions in `docs/open_api_core.yaml`.
- Docs:
  - a "Budgets (`enforce` mode)" section in `docs/anthropic-spend-limits.md` and `docs/codex-spend-limits.md`;
  - a paragraph in `docs/error_codes.md`;
  - the `spendingLimits` row in `README.md`.

## Drift from design.md

- **D2.** The branch is the private `RateLimiter.checkBudgetsWithSpendCaps`. The token and request check moved into
  `submitCheck`, which both paths now use, with no change to what it does.
- **D5.** The condition is the static `BudgetEnforcer.isReadFromBudgets`. A budget with no window below 2^53 is
  skipped with `hasLimitedWindow`, which D6 uses too.
- **D3.** `pick` computes the earliest cap for `check` as well. That is a small in-memory `toSpendCaps` per used-up
  candidate, and `check`'s results are unchanged (`BudgetEnforcerTest`).
- **Task 2.1.** It planned a per-request key helper that carries a project. It was not needed: the chain test goes
  through a real application, whose per-request key copies the caller's claims, project included. Recorded in
  tasks.md.
- **Task 6.3.** `SpecMerger` keeps the committed spec's `description` over the annotation's, so `replaceSpec` does
  not carry a changed description. The three header descriptions were edited in `docs/open_api_core.yaml` to match
  the annotations, and `checkOpenApiDiff` passes. The 429 descriptions in the YAML keep their generic text, as
  `codex-spend-limit-headers` already recorded.

## Measurement (task 5.3)

`RateLimiterTest.testRoundTripsOfTheSpendCapRoutes` spies on `ResourceService` and the blob storage, with a caller
of its own per mode. The numbers are in design.md ("Effect per request"):
- `shadow` and `enforce` add one budget load (one `listResources`, one blob `list`) before the call, and one budget
  row write after it;
- `enforce` drops the role cost record read;
- the `enforce` usage read is one budget load with one budget, and nothing with two.

The test asserts the differences between modes, because the raw counts include the service's calls to its own
overloads. No body is copied or parsed.

## Checks run

- `./gradlew checkstyleMain checkstyleTest`: clean.
- One run of the touched classes: 563 tests, 0 failures. The 2 skipped were already skipped before this change. It
  covered `BudgetSpendCapsApiTest`, `MessagesSpendLimitApiTest`, `ResponsesSpendLimitApiTest`, `UserBudgetsApiTest`,
  and the `limiter` and `controller` packages. Counts per class:

  | Class | Tests |
  |---|---|
  | `BudgetSpendCapsApiTest` | 51 (new) |
  | `MessagesSpendLimitApiTest` | 17 |
  | `ResponsesSpendLimitApiTest` | 18 |
  | `UserBudgetsApiTest` | 6 |
  | `RateLimiterTest` | 50 |
  | `BudgetEnforcerTest` | 29 |
  | `EffectiveBudgetsTest` | 21 |
  | `SpendCapCheckTest` | 7 |
- Before any production code, `BudgetSpendCapsApiTest` failed in exactly the cases tasks 3.1, 4.1 and 5.1 predicted.
- `./gradlew :openapi-generator:checkOpenApiDiff`: passes, with 89 lint warnings on schemas this change does not
  touch.
- `openspec validate report-budget-spend-caps --strict`: valid. `validate --all --strict` reports the two failures it
  reported before this change (`user-limits` placeholder Purpose, `resource-subscription`).
- On this Windows machine Gradle runs only from PowerShell. From Git Bash it fails with "Unable to establish loopback
  connection", with or without the sandbox.

## Reviews

- **Spec.** A separate reviewer ran six passes until CLEAN. The fixes included:
  - limiting the reject rules to deployments that are not translated;
  - testable scenario values;
  - the 2^53 rule for reporting and for failing closed;
  - a token or request limit deciding over an unreadable counter;
  - the `shadow` requirement;
  - the tie rule;
  - the translated "one budget for another model" case.
- **Implementation.** A fresh reviewer ran three passes until CLEAN. There were no blockers or major findings. The
  fixes:
  - a lower bound on `retry-after`;
  - the exact "no budget" body;
  - `corrupt()` checking that the row really cannot be read;
  - `RateLimiterTest` cases for a failed token check after a failed budget load, and for a token 429 that carries the
    budget's caps (asserting the budget's day window of 1000);
  - `/openai/responses` in the README;
  - a `limitWithSpendCaps` Javadoc clause;
  - the unused `gpt-burst` fixture removed;
  - the `buildExhaustedResult` verb-phrase rename;
  - a caller of its own per mode in the measurement test.
- **A test bug found while implementing.** `testUsageEndpointAgreesWithHeaders` read the usage endpoint after the
  Messages call, which had already charged $0.50. It now reads first.

## Not done

- No manual check with a real Claude Code or Codex CLI against this branch. The headers and bodies are the ones the
  existing spend-cap code builds, and the earlier changes checked those against the clients.
- Deployment: set `identityProviders.<idp>.projectPath` to `project` for the launcher's identity provider before
  switching prod to `enforce` (Migration Plan).

## Spec-to-test map

All tests are in `BudgetSpendCapsApiTest` unless a class is named.

| Requirement | Scenario | Test |
|---|---|---|
| Budgets decide the caps in enforce mode | Project budget reported on Messages | `testProjectBudgetReportedOnMessages` |
| | Project budget reported on Responses | `testProjectBudgetReportedOnResponses` |
| | Role cost limit reached, budget has room | `testRoleCostLimitReachedBudgetHasRoom` |
| | Token without a project | `testTokenWithoutProjectUsesDefaultCostObject` |
| | API key caller | `testApiKeyCallerKeepsRoleCaps` |
| | Call to an application | `testApplicationCallKeepsRoleCaps` |
| Only a single budget is reported | Two budgets in the cost object | `testTwoBudgetsAreNotReported` |
| | Second budget covers another model | `testSecondBudgetForAnotherModelIsCounted` |
| | Budget in another cost object | `testBudgetOfAnotherCostObject` |
| | Expired budget not counted | `testExpiredBudgetIsNotCounted` |
| | No budget | `testNoBudgetGivesEmptyUsage` |
| Budget windows as spend caps | Day and month amounts | `testDayAndMonthAmounts` |
| | Month amount only | `testMonthAmountOnly` |
| | Spend of every model of the budget | `testSpendOfEveryModelOfTheBudget` |
| | Amount too large to report | `testAmountTooLargeToReport` |
| Cap reported from the budget | Usage endpoint agrees with the headers | `testUsageEndpointAgreesWithHeaders` |
| Exhausted budgets rejected in the client's format | One budget, day exhausted, Messages | `testOneBudgetDayExhaustedOnMessages` |
| | One budget, day exhausted, Responses | `testOneBudgetDayExhaustedOnResponses` |
| | One budget, month exhausted | `testOneBudgetMonthExhausted` |
| | Several budgets, all exhausted | `testSeveralBudgetsAllExhausted` |
| | Tie between budgets | `BudgetEnforcerTest.testTieKeepsTheEarlierBudget` (fixed clock; the API test cannot set the date) |
| | One of several budgets has room | `testOneOfSeveralBudgetsHasRoom` |
| | Exhausted budget wins over a token limit | `testExhaustedBudgetWinsOverTokenLimit` |
| No budget for the model | Budget for another model only | `testBudgetForAnotherModelOnly` |
| Unreadable budget counter | Counter unreadable on Messages | `testCounterUnreadableOnMessages` |
| | Counter unreadable on Responses | `testCounterUnreadableOnResponses` |
| | Token limit decides over an unreadable counter | `testTokenLimitDecidesOverUnreadableCounter` |
| | Budget too large to cap, counter unreadable | `testBudgetTooLargeToCapCounterUnreadable` |
| | Token limit decides for a budget too large to cap | `testTokenLimitDecidesForHugeBudget` |
| Unreadable budget counter on the usage endpoint | Counter unreadable | `testUsageEndpointCounterUnreadable` |
| | Budget too large to report, counter unreadable | `testUsageEndpointBudgetTooLargeCounterUnreadable` |
| Budget of the chain's initiator | Application's call to the model | `testApplicationCallToTheModelUsesInitiatorBudget` |
| Translated deployments read the budget | Translated deployment, one budget | `testTranslatedOneBudget` |
| | Translated deployment, one budget exhausted | `testTranslatedOneBudgetExhausted` |
| | Translated deployment, two budgets | `testTranslatedTwoBudgets` |
| | Translated deployment, several budgets all exhausted | `testTranslatedAllExhaustedIsForwarded` |
| | Translated deployment, no budget in the cost object | `testTranslatedNoBudgetInCostObjectIsForwarded` |
| | Translated deployment, one budget for another model | `testTranslatedOneBudgetForAnotherModel` |
| | Translated deployment, counter unreadable | `testTranslatedCounterUnreadableIsForwarded` |
| Shadow mode keeps the role caps | Responses in shadow | `testShadowReportsRoleCapsOnResponses` |
| | Usage endpoint in shadow | `testShadowUsageEndpointReportsRoleCaps` |
| | Translated deployment in shadow | `testShadowTranslatedReportsRoleCaps` |
| | Budgets exhausted in shadow | `testShadowExhaustedBudgetsDoNotReject` |
| | Counter unreadable in shadow | `testShadowCounterUnreadableFails` |
| No Codex headers without a cost cap (modified) | Uncapped caller, Upstream error | `ResponsesSpendLimitApiTest` (unchanged) |
| | Several budgets | `testSeveralBudgetsSendNoCodexHeaders` |
| | Budget too large to report | `testBudgetTooLargeSendsNoCodexHeaders` |
| Unreadable spend record (modified) | both scenarios | `ResponsesSpendLimitApiTest` (unchanged) |
| budget-counters: Coding client routes charge the budget | Enforce mode | `testEnforceChargesTheBudget` |
| | Shadow mode | `testShadowChargesBudgetAndRole` |
| | Fall-through to the next budget | `testFallThroughChargesTheSecondBudget` |
| | Translated deployment | `testTranslatedOuterRequestChargesNoBudget` |
