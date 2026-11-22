# Proposal: report-budget-spend-caps

## Why

Claude Code and Codex CLI show a user's DIAL spend from what Core sends on `POST /anthropic/v1/messages`,
`POST /openai/v1/responses` and `GET /anthropic/api/oauth/usage`. Those three routes read only the role `costLimit`
(one cap per user, across all projects), and they skip budgets entirely: `RateLimiter.checkLimits` sets
`isBudgetCheck = !withSpendCaps && ...`. So in `enforce` mode a coding client is limited by the role cap that every
other route ignores. In both `shadow` and `enforce`, no budget counter is charged for these calls. Users who work
in a project with its own budget ($100 in project A against $500 personal, say) see the wrong limit and the wrong
spend.

The `dial` launcher already sends the project: in claim mode (the fleet's machine configuration) the JWT carries a
`project` claim, which Core reads through `identityProviders.*.projectPath` and uses as the budget's cost object.
Only Core's reporting has to change.

## What Changes

- In `spendingLimits.mode: enforce`, for a JWT caller of a model, the three routes report the budget instead of
  the role `costLimit`, and only when the caller has **exactly one** cost budget in their cost object (whatever
  models it covers). With several budgets, or none, Core sends no spend headers and the usage endpoint returns
  `{"limits":[]}`, because no single budget would describe what the user can still spend.
- The reported budget's `day` and `month` windows become the spend caps. Budgets have no week window, and a window
  of 2^53 or more is not reported, as for role caps.
- When every budget that could pay the call is used up, Core rejects the request before the upstream with the
  route's existing spend-cap 429 (`billing_error` for Claude Code, `usage_limit_reached` for Codex). The answer
  names the window and reset of the budget that can pay again first. This also applies with several budgets.
- When no budget covers the model, Core answers the DIAL 429 "no budget" that other routes already return.
- In `enforce` these routes no longer read or enforce the role cost limit, as every other route already does. An
  unreadable budget counter fails closed with the route's "spend limit could not be read" 429, unless a token or
  request limit rejects first, as for an unreadable role spend record today. With only budgets of 2^53 or more for
  the model, it fails with 500.
- Translated deployments only read the budget: one budget is reported and rejects when used up; in every other case
  the request is forwarded, and the translator's call back to Core does the check and the charge.
- In `shadow` and `enforce` these routes now pick and charge the paying budget. In `shadow` the role cost limit still
  decides and is still what is reported. An unreadable budget counter then fails the call with 500, as on the other
  model routes in `shadow`.
- `off` mode, API key callers and calls to applications: no change. They keep the role cost limit, so in `enforce`
  a call to an application reports role caps while the usage endpoint reports the budget.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `spend-limit-reporting`: budget-backed spend caps on the Responses, Messages and Anthropic usage routes in
  `enforce` mode, and what `shadow` keeps. Two existing requirements change: "No Codex headers without a cost cap"
  gains the budget cases (several budgets, or a budget with no window below 2^53), and "Unreadable spend record" is
  narrowed to role caps. The unreadable budget counter gets its own requirements, one for Messages and Responses and
  one for the usage endpoint.
- `budget-counters`: calls to `/anthropic/v1/messages` and `/openai/v1/responses` charge the paying budget's counter.

## Out of scope

- A project header (`X-Project`, the launcher's header mode). The project comes only from the JWT claim, or from
  `defaultCostObject` when the token has none.
- Reporting several budgets to Claude Code or Codex, as one cap or as a sum.
- Budgets on routes other than these three, which already enforce them, and the `budgets[]` of
  `GET /v1/user/limits` and `GET /v1/user/usage`, which stay as they are.
- Changing the answer of the "no budget" 429 to the Anthropic error shape.
- Prod settings: `projectPath: "project"` for the identity provider the launcher uses is a deployment step, not code.

## Impact

- Motivation: epic [epam/ai-dial-core#2143](https://github.com/epam/ai-dial-core/issues/2143) (per-project budgets).
  This change covers its use case 5 (a user sees per-budget total, used and reset) for coding clients, and its
  requirement that every call by a JWT caller lands on a budget, on the three routes that still skipped budgets.
  It leaves out the project-owner view and token and request budgets, which the epic also defers.
- Depends on the merged budget enforcement ([epam/ai-dial-core#2147](https://github.com/epam/ai-dial-core/issues/2147))
  and the Claude Code and Codex spend reporting already on `development`. No dependency on the launcher.
- Code: `RateLimiter` (`checkLimits`, `readSpendCaps`, `hasCostLimit`), `BudgetEnforcer`, `EffectiveBudgets`.
  The controllers and `SpendCapCheck` keep their logic; only the OpenAPI header and response descriptions in
  `MessagesController` and `ResponsesController` change.
- Docs: `docs/anthropic-spend-limits.md`, `docs/codex-spend-limits.md`, `docs/error_codes.md`, the `spendingLimits`
  row in `README.md`, and `docs/open_api_core.yaml` regenerated.
- Operators: before switching to `enforce`, set `projectPath` to the claim that holds the project. The sample
  `aud`/`azp` value would make the OAuth client id the cost object.
