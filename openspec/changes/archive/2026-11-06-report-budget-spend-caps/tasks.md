# Tasks

Test names are given per spec scenario. `BudgetSpendCapsApiTest` is a new integration test (full stack, embedded
Redis, `TestWebServer` upstreams). Seed budgets through the policy and assignment admin API, as `UserBudgetsApiTest`
does. Seed spend through `BudgetCounterStore.charge`. Make a counter unreadable by writing a corrupt row, as
`ResponsesSpendLimitApiTest.corruptSpend` does for the role record.

## 1. Budgets of one cost object and their caps

- [x] 1.1 `EffectiveBudgets.resolveInCostObject(index, costObject, claims, now)` (design D1), and `resolveAll`
  built on it. Verify with new `EffectiveBudgetsTest` cases `testInCostObjectIgnoresTheModel`,
  `testInCostObjectSkipsExpiredAndOtherSubjects` and `testInCostObjectSkipsOtherCostObjects`, and that the existing
  `resolveAll` tests still pass.
- [x] 1.2 `BudgetEnforcer.toSpendCaps(instance, usage)` (D3). Verify with `BudgetEnforcerTest` cases
  `testSpendCapsOfDayAndMonth`, `testSpendCapsLeaveOutAnUnsetWindow`, `testSpendCapsLeaveOutHugeWindows` and
  `testSpendCapsReportTheExceededWindow`.
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.limiter.budget.*"`.

## 2. Test fixtures

- [x] 2.1 `ResourceBaseTest`: `authorization: user@<project>` returns the `user` claims with that project. Verify that
  `UserBudgetsApiTest`, `MessagesSpendLimitApiTest` and `ResponsesSpendLimitApiTest` still pass. (No per-request key
  helper is needed: the chain test goes through a real application, whose per-request key copies the caller's
  claims, project included.)
- [x] 2.2 `dial-config/budget-spend-caps.json` (`spendingLimits.mode: enforce`) and
  `dial-config/budget-spend-caps-shadow.json` (`shadow`). Each holds an Anthropic model, a Responses model, a model
  that the "other model" budgets name, a translated model of each interface, an application served on Messages,
  and a `user` role with a role cost limit and a per-minute token limit on one model. Verify with a
  `BudgetSpendCapsApiTest.testFixturesLoad` case that starts Core on each and gets 200 from one model.
- [x] 2.3 `./gradlew checkstyleMain checkstyleTest` and the test classes of 2.1 and 2.2 with `--tests`.

## 3. The check of the spend-cap routes in enforce mode

- [x] 3.1 Write the `BudgetSpendCapsApiTest` cases first, one per scenario.
  - "Budgets decide the caps in enforce mode": `testProjectBudgetReportedOnMessages`,
    `testProjectBudgetReportedOnResponses`, `testRoleCostLimitReachedBudgetHasRoom`, `testApiKeyCallerKeepsRoleCaps`,
    `testApplicationCallKeepsRoleCaps` (its usage half lands in 4.1).
  - "Only a single budget is reported": `testTwoBudgetsAreNotReported` (headers half),
    `testSecondBudgetForAnotherModelIsCounted`.
  - "Budget windows as spend caps": `testDayAndMonthAmounts`, `testSpendOfEveryModelOfTheBudget`,
    `testAmountTooLargeToReport` (headers half).
  - "Exhausted budgets rejected in the client's format": `testOneBudgetDayExhaustedOnMessages`,
    `testOneBudgetDayExhaustedOnResponses`, `testOneBudgetMonthExhausted`, `testSeveralBudgetsAllExhausted`,
    `testOneOfSeveralBudgetsHasRoom`, `testExhaustedBudgetWinsOverTokenLimit`. `testSeveralBudgetsAllExhausted`
    asserts `resets_at` and computes the expected window by the tie rule, because the API test cannot set the
    clock.
  - "No budget for the model": `testBudgetForAnotherModelOnly`.
  - "Unreadable budget counter": `testCounterUnreadableOnMessages`, `testCounterUnreadableOnResponses`,
    `testTokenLimitDecidesOverUnreadableCounter`, `testBudgetTooLargeToCapCounterUnreadable`,
    `testTokenLimitDecidesForHugeBudget`.
  - "Budget of the chain's initiator": `testApplicationCallToTheModelUsesInitiatorBudget`.
  - The new scenarios of "No Codex headers without a cost cap": `testSeveralBudgetsSendNoCodexHeaders` and
    `testBudgetTooLargeSendsNoCodexHeaders`.

  Verify that every case fails before 3.2 and 3.3, except these, which guard today's behaviour and pass from the
  start: `testApiKeyCallerKeepsRoleCaps` and the header half of `testApplicationCallKeepsRoleCaps`; and
  `testTokenLimitDecidesOverUnreadableCounter` and `testTokenLimitDecidesForHugeBudget`. Today these routes
  read only the role record in `enforce`, so the corrupt budget row is not read, and the token limit answers 429.
- [x] 3.2 `BudgetEnforcer.checkWithSpendCaps` (D3, D4): reuse `pick`; keep the payer's usage, and the caps of the
  dry candidate that resets first (a tie keeps the earlier one); set the payer; attach the reported caps when
  `resolveInCostObject` has one element; build the rejection with that cap and no display message. A failed load is
  wrapped in `SpendReadException` only when a candidate sets a window below 2^53 (D6). The wrap goes in
  `checkWithSpendCaps` only, never in the shared `pick`, so that `check` (used in `shadow`) keeps failing unwrapped.
  Verify with `BudgetEnforcerTest` cases `testWithSpendCapsReportsTheOneBudget`,
  `testWithSpendCapsReportsNothingForTwoBudgets`, `testWithSpendCapsRejectsWithTheEarliestCap` (the rejection cap's
  `resetsAtMillis` equals the earliest reset `check` computes), `testTieKeepsTheEarlierBudget` (a fixed clock on the
  last day of a month, for the "Tie between budgets" scenario), `testWithSpendCapsNoBudgetHasNoCaps`,
  `testWithSpendCapsWrapsFailedLoad`, `testWithSpendCapsDoesNotWrapForHugeBudgets` and
  `testCheckDoesNotWrapFailedLoad`.
- [x] 3.3 `RateLimiter.checkLimits` (D2): the `enforce` branch for `withSpendCaps`. It runs the budget check first,
  then `checkLimit(..., false, true)` with the caps attached, and lets a token or request rejection win over any
  failed budget load, wrapped or not, rethrowing the original failure otherwise. `RateLimiter.hasCostLimit` returns true when budgets are enforced for the context, and keeps
  today's role rule otherwise (D6). Verify that the cases of 3.1 pass, as do `MessagesSpendLimitApiTest`,
  `ResponsesSpendLimitApiTest`, `SpendCapCheckTest`, `RateLimiterTest` (new cases
  `testHasCostLimitWhenBudgetsAreEnforced` and `testHasCostLimitKeepsTheRoleRuleForAnApplication`) and
  `DeploymentPostControllerTest`.
- [x] 3.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.BudgetSpendCapsApiTest" --tests "com.epam.aidial.core.server.MessagesSpendLimitApiTest"
  --tests "com.epam.aidial.core.server.ResponsesSpendLimitApiTest" --tests "com.epam.aidial.core.server.limiter.*"
  --tests "com.epam.aidial.core.server.controller.*"`.

## 4. The read-only paths

- [x] 4.1 Write the `BudgetSpendCapsApiTest` cases first.
  - Usage endpoint: `testTokenWithoutProjectUsesDefaultCostObject`, the usage half of
    `testApplicationCallKeepsRoleCaps`, `testTwoBudgetsAreNotReported` (usage half), `testBudgetOfAnotherCostObject`,
    `testExpiredBudgetIsNotCounted`, `testNoBudgetGivesEmptyUsage`, `testMonthAmountOnly`,
    `testAmountTooLargeToReport` (usage half), `testUsageEndpointAgreesWithHeaders`,
    `testUsageEndpointCounterUnreadable` and `testUsageEndpointBudgetTooLargeCounterUnreadable`.
  - "Translated deployments read the budget": `testTranslatedOneBudget`, `testTranslatedOneBudgetExhausted`,
    `testTranslatedTwoBudgets`, `testTranslatedAllExhaustedIsForwarded`,
    `testTranslatedNoBudgetInCostObjectIsForwarded`, `testTranslatedOneBudgetForAnotherModel` and
    `testTranslatedCounterUnreadableIsForwarded`.

  Verify that they fail before 4.2. They do, because the fixture's role cost limit makes today's role path send
  headers where these cases expect the budget's headers or none.
- [x] 4.2 `BudgetEnforcer.readSpendCaps` and the budget branch of `RateLimiter.readSpendCaps` (D5): one budget with a
  window below 2^53 is loaded, and anything else returns `SpendCaps.EMPTY` without a load. Verify that the cases of
  4.1 pass.
- [x] 4.3 `./gradlew checkstyleMain checkstyleTest` and the test classes of 3.4.

## 5. Shadow mode and charging

- [x] 5.1 Write the `BudgetSpendCapsApiTest` cases first.
  - "Shadow mode keeps the role caps", on the shadow config: `testShadowReportsRoleCapsOnResponses`,
    `testShadowUsageEndpointReportsRoleCaps`, `testShadowTranslatedReportsRoleCaps`,
    `testShadowExhaustedBudgetsDoNotReject` and `testShadowCounterUnreadableFails`.
    `testShadowExhaustedBudgetsDoNotReject` adds a `SimpleMeterRegistry` to `Metrics.globalRegistry` for the test
    and removes it after, as `EffectiveBudgetsTest` does, because the test settings turn metrics off and the
    counter would otherwise always read 0.
  - budget-counters "Coding client routes charge the budget": `testEnforceChargesTheBudget`,
    `testShadowChargesBudgetAndRole`, `testFallThroughChargesTheSecondBudget` and
    `testTranslatedOuterRequestChargesNoBudget`, reading the counters through `GET /v1/user/usage`.

  Verify that `testShadowExhaustedBudgetsDoNotReject`, `testShadowCounterUnreadableFails` and
  `testShadowChargesBudgetAndRole` fail before 5.2. The other cases pass already: the three shadow role-cap cases
  guard today's behaviour, `testTranslatedOuterRequestChargesNoBudget` holds today, and the `enforce` charge cases
  pass after group 3 because `BaseDeploymentPostController.increaseLimits` charges the payer.
- [x] 5.2 Drop `!withSpendCaps` from `isBudgetCheck` in `RateLimiter.checkLimits` (D2). Verify that the cases of 5.1
  pass.
- [x] 5.3 Measurement (design "Effect per request"): `RateLimiterTest` cases with Mockito spies on `ResourceService`
  and on the blob storage. They count `getResource`, `listResources`, `computeResource` and blob `list` for one
  Messages-style check and charge in `off`, `shadow` and `enforce`, and for one usage read in `enforce` with one and
  with two budgets. Record the counts in design.md, and update the table if they differ.
- [x] 5.4 `./gradlew checkstyleMain checkstyleTest` and the test classes of 3.4.

## 6. Docs and the API description

- [x] 6.1 `docs/anthropic-spend-limits.md` and `docs/codex-spend-limits.md`: a "Budgets (`enforce` mode)" section
  covering:
  - where the caps come from, and the one-budget rule;
  - the windows (day and month, 2^53 not reported);
  - the exhausted, no-budget and unreadable-counter answers, and the order of the checks;
  - translated deployments (one budget is reported even when it covers other models), calls to applications,
    `shadow`;
  - the `projectPath` prerequisite.

  Verify every example against `BudgetSpendCapsApiTest`.
- [x] 6.2 `docs/error_codes.md` (the 429 section on the Anthropic and Responses routes) and the `README.md`
  `spendingLimits` row: in `enforce`, these routes report and enforce budgets, the "no budget" 429, and that
  `projectPath` must name the project claim. Verify that both match the specs.
- [x] 6.3 The OpenAPI header and response descriptions in `MessagesController` and `ResponsesController` ("Sent only
  when the caller has a day/week/month cost limit"): add the budget case. Then run
  `./gradlew :openapi-generator:replaceSpec` to regenerate `docs/open_api_core.yaml`. Verify with
  `./gradlew :openapi-generator:checkOpenApiDiff`. (`SpecMerger` keeps the committed spec's `description` over the
  annotation's, so `replaceSpec` does not carry a changed description. The three header descriptions were updated in
  `docs/open_api_core.yaml` to match the annotations. The committed 429 descriptions stay the generic text they
  already had, which the detailed 429 annotations never replaced.)
- [x] 6.4 `./gradlew checkstyleMain checkstyleTest`,
  `npx -y @fission-ai/openspec@1.14.3 validate report-budget-spend-caps --strict` and
  `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`. (`--all` reports the two failures it reported before this
  change, in `user-limits` (placeholder Purpose) and `resource-subscription`, which this change does not touch.)

## Workflow follow-up

- Write `review.md`, then archive with `npx -y @fission-ai/openspec@1.14.3 archive report-budget-spend-caps -y` and
  run `validate --specs --strict`.
- Deployment (not tracked here): set `projectPath: "project"` for the launcher's identity provider before switching
  prod to `enforce`.
