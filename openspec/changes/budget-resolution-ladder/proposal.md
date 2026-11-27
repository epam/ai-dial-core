# Proposal

## Why

M1 lets the server pick which budget pays a JWT caller's model call: a priority walk over the cost object's budgets.
That cannot express a person holding several budgets (a project default, a ticket, an education package) and
choosing which one pays. M2 makes the payer **declared**: the request names a budget, or the user's default budget
pays, and the walk runs only where the operator allows it.

## What Changes

- **The effective cost object.** It is the claim at `costObjectPath`, else `defaultCostObject` (a missing claim
  binds the call to the default, not to "no cost object"). Where the caller's identity provider has no
  `costObjectPath`, the header `X-DIAL-COST-OBJECT: <one element>` scopes the call instead; it is never an
  attestation. Where a claim path is configured, and on calls that carry an indication, the header is ignored.
- **The indication.** `X-DIAL-BUDGET: <costObject>/<id>` names the one budget instance that pays, exclusively: Core
  never switches to another payer. Where a claim path is configured its cost object must equal the effective one.
- **The attestation rule.** A budget whose subject only matches through a `TRUE` rule ("everyone on this cost
  object") is not reachable when the cost object came from a header (`X-DIAL-COST-OBJECT` or the indication's cost
  object without a claim path).
- **The user's default budget pays** an unindicated call when it is on the effective cost object; when it does not
  cover the call the call is refused, even when another budget would cover it.
- **Two flags** in `spendingLimits`: `autoBudgetSelection` (default `true`: the scoped M1 walk picks when nothing is
  indicated or defaulted) and `budgetMandatory` (default `false`: `true` refuses an unindicated call).
- **New refusals**, using the codes `SpendingRefusal` already declares: 400 `invalid_budget_indication`,
  `invalid_cost_object`, `cost_object_mismatch`, `budget_indication_required` on a JWT caller's root call to any
  deployment, in every mode, `off` included; 403 `budget_not_applicable`, `default_budget_not_applicable` and
  `no_budget` for an indicated budget the caller does not hold or with the walk off.
- **Nested hops** resolve the root's payer: the root's indication and header cost object go into the per-request
  key; both headers on a per-request-key call are ignored. The stored indication is resolved per hop against the
  current config, so a budget withdrawn mid-session refuses the next hop.
- **Reporting.** The Messages and Responses answers report the resolved payer's windows, whichever ladder step
  picked it, instead of reporting only a caller with exactly one budget in the cost object.
- **BREAKING**: a JWT call with a malformed `X-DIAL-BUDGET` or `X-DIAL-COST-OBJECT`, which Core ignores today,
  now gets 400; with `budgetMandatory` on, every unindicated JWT call to a deployment gets 400.

## Capabilities

### New Capabilities
- `budget-resolution`: which budget pays a JWT caller's model call. The effective cost object and its source, the
  indication, the attestation rule, the default budget at call time, the flags, the scoped walk and how nested
  hops inherit the root's payer.

### Modified Capabilities
- `spending-refusals`: the new refusal codes with their statuses, how shadow and `off` treat them, and their meter
  reasons; the no-budget 403 also covers an indicated budget the caller does not hold and the walk turned off.
- `spend-limit-reporting`: a call to a model reports the payer the ladder resolved, not only a caller's single
  budget in the cost object; a used-up declared payer names its own window.
- `analytics-log`: `cost_object.source` and `spending_limits.cost_object` can come from a header (`header`); the
  new 403 refusals are written as the outcome `no_budget`.

## Impact

- Issue: [epam/ai-dial-core#2365](https://github.com/epam/ai-dial-core/issues/2365), part of the M2 epic
  [epam/ai-dial-core#2361](https://github.com/epam/ai-dial-core/issues/2361); the design reference is
  `documentation/design/spending_limits_m2_core.md` §2.2 to §2.8 and §6.
- Covers all of #2365. Leaves out, for their own changes: the key's bound budget as an indication and 400
  `budget_mismatch` (#2369, keys as subjects); attached and standalone caps and `cap_exhausted` (#2368); pools and
  object `per` shapes (#2367); the discovery endpoint (#2370).
- Out of scope: API key callers. Budgets do not reach them yet, so Core ignores both headers on an API key call.
  The usage endpoint `GET /anthropic/api/oauth/usage` and the spend headers of a translated deployment keep the
  one-budget rule, as no call resolves a payer for them. `/v1/user/info` keeps its cost object without the header.
- Depends on #2362 (cost object rename), #2363 (coded refusals) and #2364 (default budget store), all merged.
- Code: `server` (`limiter/budget/BudgetEnforcer`, `EffectiveBudgets`, `CostObject`, `DefaultBudgetService`,
  `ProxyContext`, `data/ApiKeyData`, `security/IdentityProvider`, `ExtractedClaims`, the deployment controllers)
  and `config` (`SpendingLimits`). Docs: `docs/dynamic-settings` for the flags, the spending-limits doc for the
  headers and refusals.
