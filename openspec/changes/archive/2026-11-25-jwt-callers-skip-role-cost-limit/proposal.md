# Proposal

## Why

In `enforce` mode a JWT user's call to an application is still checked against the role `costLimit`. The role cost
record holds all of the user's spend, including what budgets paid, so a user with budget left gets a 429 on every agent
call while direct model calls pass.

## What Changes

- In `enforce` mode, Core does not check the role `costLimit` on any call that carries a user's claims (a JWT, or a
  per-request key of a chain a JWT user started), whatever the deployment: application, route, MCP. Token and request
  limits still apply. The application's own calls to models are checked against the user's budgets, as today.
- A Messages or Responses call to an application by such a caller reports no spend caps (no
  `anthropic-ratelimit-unified-*` and no `x-codex-*` header), instead of the role caps.
- API key callers, and chains an API key started, keep the role `costLimit`. `off` and `shadow` are unchanged. The role
  cost record keeps being charged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `budget-counters`: a new requirement, the role cost limit is off for user callers in `enforce` mode.
- `spend-limit-reporting`: "Budgets decide the caps in enforce mode", the application call reports no caps.

## Impact

- **Code:** `RateLimiter.checkLimits`, `BudgetEnforcer`. **Docs:** `docs/anthropic-spend-limits.md`,
  `docs/codex-spend-limits.md` ("Applications"). The README `spendingLimits` row already says this.
- **Motivation:** https://github.com/epam/ai-dial-core/issues/2375, covered in full.
- **Out of scope:** a budget check when the application is called (its first model call is checked instead); budgets
  for API key callers (#2369).
