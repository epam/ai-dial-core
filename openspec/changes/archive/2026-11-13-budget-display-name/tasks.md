# Tasks

## 1. Budget display names

- [x] 1.1 Write `BudgetDisplayNameApiTest` for the scenario mapping below; retain the existing user-budget regression tests.
- [x] 1.2 Add the policy and response fields, blank-name fallback and policy-only assignment error; cover them in the new API tests and `PolicyRecordCodecTest` (execution tracked in 1.5).
- [x] 1.3 Document configuration, response and rollout behavior in `docs/dynamic-settings/policies.md`, link it from README, and check the examples against the tests; leave generated OpenAPI to CI.
- [x] 1.4 Review correctness, compatibility, authorization and request cost; record findings in `review.md` and pass `openspec validate budget-display-name --strict`.
- [x] 1.5 Verify `./gradlew checkstyleMain checkstyleTest` and the classes selected by `./gradlew :server:test --tests '*BudgetDisplayNameApiTest' --tests '*UserBudgetsApiTest' --tests '*PolicyRecordCodecTest' --tests '*PolicyApiTest'`. Completed in CI across the runs linked in review.md: the final new API tests pass, and the unchanged regression/codec classes passed in the first run. Local server execution needs private package credentials; the full latest CI suite remains in progress.

## Scenario coverage

| Scenario | API test |
| --- | --- |
| Policy name survives a write and reload | `BudgetDisplayNameApiTest.testPolicyDisplayNameSurvivesReload` |
| Policy name applied in a manifest | `BudgetDisplayNameApiTest.testPolicyDisplayNameInManifest` |
| Named self-applying budget | `BudgetDisplayNameApiTest.testNamedSelfApplyingPolicy` |
| Unnamed policies remain valid; Missing or blank policy name | `BudgetDisplayNameApiTest.testMissingPolicyDisplayName`, `testBlankPolicyDisplayName` |
| Named assigned budgets | `BudgetDisplayNameApiTest.testNamedAssignmentsKeepPriorityOrder` |
| Rename after a charge | `BudgetDisplayNameApiTest.testRenamePreservesSpend` |
| Several budgets in one cost object | `UserBudgetsApiTest.testEveryMatchingBudgetOfOneCostObjectIsListedByPriority` |
| Entries of every cost object of the caller | `UserBudgetsApiTest.testBudgetOfEveryMatchingCostObjectIsListed` |
| Budget of another subject | `UserBudgetsApiTest.testBudgetOfAnotherSubjectIsNotListed` |
| No budgets for an API key | `UserBudgetsApiTest.testApiKeyCallerHasNoBudgets` |

## Workflow outcome

- Archived and merged into the two main specs; both touched specs pass strict validation.
- Committed explicit change paths and opened [PR #2283](https://github.com/epam/ai-dial-core/pull/2283), linked to #2282.
