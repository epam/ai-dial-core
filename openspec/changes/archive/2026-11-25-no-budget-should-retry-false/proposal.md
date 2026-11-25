# Proposal

## Why

With budgets enforced, Core rejects a call of a model that no budget in the caller's cost object covers with a 429
that has neither `retry-after` nor `x-should-retry`. The Anthropic and OpenAI SDKs read that as "throttled, try
again", so Claude Code retries it ten times over about three minutes, though only an admin adding a budget can make
the call pass.

## What Changes

- On `POST /anthropic/v1/messages`, `POST /openai/v1/responses` and `POST /openai/responses`, the "no budget for the
  model" 429 gains `x-should-retry: false`, as the Messages spend-cap 429 already has. On Responses it is the first
  answer with the header; the Responses "could not read your spend limit" 429 stays without one. The status, the
  DIAL error body and its message stay as they are, and the answer still has no `retry-after` and no spend header.
- Through an alias, also one that a router in decision mode chooses, the client gets the header too. The alias pass
  already passes on the target pass's `x-should-retry`; a scenario and a test pin it.
- Tests per route and through an alias; `docs/anthropic-spend-limits.md`, `docs/codex-spend-limits.md`,
  `docs/error_codes.md` and the OpenAPI 429 descriptions of the two routes.

Out of scope: the status code, the body and the message; a router in decision mode asking again after a target's
429 (another model may have a budget); translated deployments, which forward to the translator; shadow mode, which
never rejects; every other route (chat completions, embeddings and the rest), whose no-budget 429 stays without the
header; the role-limit `403 Access denied`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `spend-limit-reporting`: "No budget for the model" gains `x-should-retry: false`, a Responses scenario and a
  scenario through an alias and a router.

## Impact

No GitHub issue yet. Motivation: the brief `dial-core-no-budget-should-retry-prompt.md` (the owner's document, not
in the repo) and its measurement of Claude Code 2.1.330 against this exact answer (design.md D1). This change covers
every point of that brief. Server module only: the budget check, the rate-limit result it returns, and
the OpenAPI text of the two controllers. No new endpoint, setting or dependency.
