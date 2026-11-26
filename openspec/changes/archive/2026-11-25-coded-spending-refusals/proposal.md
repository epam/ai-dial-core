# Proposal

## Why

Every spending refusal is a 429 today, so a client cannot tell "wait" from "no budget can pay this call". M2 gives
each refusal a code and the status that fits it, starting with the one refusal M1 has besides "used up".

## What Changes

- **BREAKING**: in `enforce` mode, a call that no budget covers gets **403** (was 429) with `error.code` `no_budget`
  on every route but Messages and Responses. Those keep the answers of #2360 (402 and 400 to the client's own
  request, a 429 inside Core and to per-request-key callers); their DIAL error body gets `error.code` `no_budget`.
  The message is #2360's; it names only the caller's own model and cost object, whatever the reason.
- A used-up budget still gets 429 with `retry-after`. Where the answer is the DIAL error body, `error.code` is now
  `budget_exhausted` instead of `"429"`. The `billing_error` and `usage_limit_reached` bodies stay as they are.
- `dial_budget_rejections_total{reason}` and the shadow log line use these codes. `shadow` still never refuses.
- Code constants, with their statuses, for the refusals later M2 issues add. No code path produces them yet.
- Docs: `docs/error_codes.md`, the two spend-limit pages, `docs/dynamic-settings/policies.md`, the README
  `spendingLimits` row, the OpenAPI text.

Out of scope: the 400 refusals and the other 403 and 429 codes (later M2 issues); the analytics `outcome` values;
the role-limit `403 Access denied`; translated deployments, which only read budgets.

Release note: *Spending limits: a call that no budget can pay is now refused with 403 and `error.code` `no_budget`,
not 429, except on the Claude Code and Codex routes (402 and 400). A used-up budget is still 429 with `retry-after`, and its DIAL error body now has `error.code`
`budget_exhausted`. Clients that treat every spending refusal as 429 must show a 403 as "indicate another budget"
and a 429 as "wait, or indicate another budget".*

## Capabilities

- New `spending-refusals`: the refusal codes, their statuses, and shadow metering.
- `spend-limit-reporting` is not modified: its no-budget answers on Messages and Responses, from #2360, stay.

## Impact

Issue epam/ai-dial-core#2363, part of #2361 (Spending Limits M2). Covers all of its acceptance criteria. Server
module only. The 403 on Messages and Responses was dropped when #2360 merged first with measured answers for Codex CLI and
Claude Code on those routes.
