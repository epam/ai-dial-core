# Review: user-budget-cost-object

A record of what was done and checked. No issue: the change came out of a review of Claude Code spend reporting with
several budgets per project, and a maintainer approved the proposal.

## Shipped

- `GET /v1/user/usage` and `GET /v1/user/limits` return a top-level `costObject` (the token's project claim, or
  `spendingLimits.defaultCostObject`; the root caller's for a per-request key), absent in `off` mode and for an API key
  root caller. `BudgetEnforcer.collectStats` returns it with the budgets in a `BudgetReport` record.
- Each `budgets[]` entry carries `object`, the policy's `PolicyObjectSet` (`"*"` or the sorted deployment names),
  unfiltered by the caller's access (design.md decision 7).
- Docs: `anthropic-spend-limits.md`, `codex-spend-limits.md`, `open_api_core.yaml` (regenerated; adds only the two
  fields).
- Tests: `UserBudgetsApiTest` (9 added, 1 extended), `BudgetSpendCapsApiTest` (1 added), fixture
  `dial-config/user-budgets-default.json`.

## Checked

| Check | Result |
|---|---|
| New tests before the code | 7 failed as expected; the 4 absence checks passed, as they should before the field exists |
| `checkstyleMain`, `checkstyleTest` | pass |
| `UserBudgetsApiTest`, `BudgetSpendCapsApiTest`, `com.epam.aidial.core.server.limiter.*` | pass (one embedded-Redis start failure in `testApplicationCallKeepsRoleCaps`, passed on re-run) |
| `:openapi-generator:replaceSpec` diff | only `BudgetStats.object` and `UserLimitStats.costObject` |
| Diff for new `store.*` / `resourceService` calls | none |
| `openspec validate user-budget-cost-object --strict` | valid |
| Planning artifacts, four independent review rounds | last round: 0 major, 0 should-fix |
| Implementation, independent review | 0 major, 0 should-fix; 3 of 4 minors fixed, checks re-run and passing |

## Not covered

- The generated OpenAPI schema of `PolicyObjectSet` is `type: object`, not "string or array of strings"; left for a
  follow-up change.
- A JWT with a project but no user id gets a `costObject`; how its budgets are loaded is unchanged and not tested here.
- The full test suite runs in CI.
