# Tasks

## 1. Store and API

- [x] 1.1 Compare `design.md` with the code on `development`; on a drift, correct the decision or the Code map entry
      in place
- [x] 1.2 Tests first, in `DefaultBudgetApiTest`: "Set and read a held budget" (`testSetAndReadHeldBudget`),
      "Replace the default" (`testReplaceDefault`), "Clear a set default" (`testClearDefault`), "Budget of another
      subject" (`testBudgetOfAnotherSubjectRejected`), "Budget that does not exist" (`testUnknownBudgetRejected`),
      "Malformed body" (`testMalformedBody`), "Nothing set" (`testNothingSet`), "Assignment withdrawn"
      (`testWithdrawnBudgetReadsAsUnset`), "Assignment expired" (`testExpiredBudgetReadsAsUnset`), "API key caller"
      (`testApiKeyCallerForbidden`), "Application on the user's behalf" (`testPerRequestKeyForbidden`), "Not reachable
      through the resource API" (`testRecordUnreachableThroughResourceApi`)
- [x] 1.3 D1: `ResourceTypes.USER_BUDGET_DEFAULT`, not in `of` or `EVENT_TYPES`
- [x] 1.4 D2, D3: `DefaultBudgetService` (`findDefault`, `setDefault`, `clearDefault`), built in `AiDial` and exposed
      by `Proxy`; a unit test `DefaultBudgetServiceTest` for its branches
- [x] 1.5 D4, D5: `RouteTemplate.USER_DEFAULT_BUDGET`, `DefaultBudgetController` (GET, PUT) with OpenAPI annotations,
      wired in `ControllerSelector`
- [x] 1.6 Docs: a "Default budget" section in `docs/dynamic-settings/policies.md`
- [x] 1.7 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "*DefaultBudgetApiTest" --tests "*DefaultBudgetServiceTest"`, then
      `npx -y @fission-ai/openspec@1.14.3 validate user-default-budget --strict`
