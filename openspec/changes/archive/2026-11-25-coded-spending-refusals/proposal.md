# Proposal

## Why

Every spending refusal is a 429 today, so a client cannot tell "wait" from "no budget can pay this call". M2 gives
each refusal a code and the status that fits it, starting with the one refusal M1 has besides "used up".

## What Changes

- **BREAKING**: in `enforce` mode, a call that no budget covers gets **403** (was 429) with `error.code` `no_budget`.
  The message stays the same: it names only the caller's own model and cost object, whatever the reason.
- A used-up budget still gets 429 with `retry-after`. Where the answer is the DIAL error body, `error.code` is now
  `budget_exhausted` instead of `"429"`. The `billing_error` and `usage_limit_reached` bodies stay as they are.
- `dial_budget_rejections_total{reason}` and the shadow log line use these codes. `shadow` still never refuses.
- Code constants, with their statuses, for the refusals later M2 issues add. No code path produces them yet.
- Docs: `docs/error_codes.md`, the two spend-limit pages, `docs/dynamic-settings/policies.md`, the README
  `spendingLimits` row, the OpenAPI text.

Out of scope: the 400 refusals and the other 403 and 429 codes (later M2 issues); the analytics `outcome` values;
the role-limit `403 Access denied`; translated deployments, which only read budgets.

Release note: *Spending limits: a call that no budget can pay is now refused with 403 and `error.code` `no_budget`,
not 429. A used-up budget is still 429 with `retry-after`, and its DIAL error body now has `error.code`
`budget_exhausted`. Clients that treat every spending refusal as 429 must show a 403 as "indicate another budget"
and a 429 as "wait, or indicate another budget".*

## Capabilities

- New `spending-refusals`: the refusal codes, their statuses, and shadow metering.
- Modified `spend-limit-reporting`: "No budget for the model" is a 403 with code `no_budget`.

## Impact

Issue epam/ai-dial-core#2363, part of #2361 (Spending Limits M2). Covers all of its acceptance criteria. Server
module only. Depends on `no-budget-should-retry-false`, which edits the same requirement: archive that change first.
