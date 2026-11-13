# Proposal

## Why

`GET /v1/user/usage` and `GET /v1/user/limits` list every budget that matches the caller in every cost object, but
a client such as a Claude Code status line cannot tell which of them its calls are charged to. It does not know its
own cost object when the token has no project claim (Core then uses `spendingLimits.defaultCostObject`, a server
setting the client cannot see), and it does not know which models a budget covers, so with two budgets in one
project it cannot tell which one a call to a given model hits.

## What Changes

- A top-level `costObject` in the response of both endpoints: the cost object Core charges this caller's model calls
  to, which is the token's project claim, or `defaultCostObject` when the token has none (the root caller's for a
  per-request key). It is present in `shadow` and `enforce`, and absent when `budgets` is always empty (spending
  limits `off`, or an API key caller).
- An `object` on each `budgets[]` entry: the models the budget covers, `"*"` or the list of deployment names, copied
  from the budget's policy in the same form the policy API returns it.
- No query parameter. The client filters `budgets[]` by `costObject` and, if it wants, by model. Nothing existing
  changes: the entries, their order and the other fields stay as they are.
- `docs/anthropic-spend-limits.md` and `docs/codex-spend-limits.md` explain the two fields.

Out of scope:
- Telling which budget pays the next call to a given model. That depends on the model and on how much each budget
  has left; if a client needs it, it belongs on `GET /v1/deployments/{id}/limits` in a separate change.
- Renaming the policy's `object` field.
- `GET /anthropic/api/oauth/usage` and the spend-cap headers (`spend-limit-reporting`): unchanged.
- A JWT with a project but no user id: it gets a `costObject` like any JWT; how its budgets are loaded is unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `user-limits`: "Budgets in the user stats" is modified to add `object` to each entry and to narrow "the other
  fields SHALL NOT change" to `deployments` and the `*CostStats` fields; a new requirement adds the caller's
  `costObject` at the top level. The other requirements stay as they are.

## Impact

- Motivation: the review of Claude Code spend reporting with several budgets per project (no issue yet). It covers
  the two gaps above; the per-model "which budget pays next" question is left out.
- API: `GET /v1/user/usage` and `GET /v1/user/limits` gain two fields. Additive; existing clients are unaffected.
- Code: `server` module only (`UserLimitStats`, `BudgetStats`, `BudgetEnforcer.collectStats`,
  `RateLimiter.collectUserStats`); `docs/open_api_core.yaml` regenerated; the two spend-limit docs.
- Per request: none on proxied calls. The two endpoints do no extra Redis or blob reads.
