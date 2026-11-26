# Proposal

## Why

With budgets enforced, Core refuses a Messages call of a model that no budget of the caller's cost object covers with
a 429. When Claude Code runs against a gateway, it prints `API Error: Server is temporarily limiting requests (not your
usage limit)` in front of the message of such a 429. The user reads that the server is busy for a while, but only an
admin who assigns a spend limit makes the call pass. Measured with Claude Code 2.1.332 (design.md D1).

## What Changes

- On `POST /anthropic/v1/messages`, Core answers the client's own request (not a per-request key's) with 402 instead
  of the no-budget 429: the Anthropic error body of type `billing_error` with the same message, `x-should-retry:
  false`, and no `retry-after` and no `anthropic-ratelimit-unified-*` header. Claude Code prints
  `API Error: 402 <message>`, in gateway mode and not, after one request.
- The same holds through an alias, and through a router in decision mode with an alias as its target or candidate.
  Inside Core the refusal stays a 429, so alias passes and routers keep their fallback; an alias's target pass marks
  its 429 with `X-DIAL-REFUSAL: no_budget` for the alias pass, as `no-budget-400-on-responses` does on Responses.
- `X-DIAL-REFUSAL` never leaves Core on Messages and `count_tokens` either: Core removes it from what a model's or an
  application's upstream answers.
- The code that `no-budget-400-on-responses` wrote for Responses moves to the base controller, and both routes use it.
- Tests, `docs/anthropic-spend-limits.md`, `docs/error_codes.md`, `docs/dynamic-settings/policies.md`,
  `docs/dynamic-settings/aliases.md`, and the 402 and 429 descriptions of `MessagesController.handle`.

Out of scope:
- The Responses routes, which `no-budget-400-on-responses` covers, and every other route.
- A call made with a per-request key, an application's or a translator's: it keeps the 429 with the DIAL error body.
- The Messages spend-cap 429, which keeps `billing_error` with 429.
- Translated deployments, which forward to the translator, and shadow mode, which never rejects.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `spend-limit-reporting`:
  - "No budget for the model": its Messages answer moves to a requirement of its own.
  - "No budget through an alias" and "The no-budget mark stays inside Core": they cover Messages too.
  - New requirement "No budget on the Messages route": 402 `billing_error` to the client's own request, 429 to a
    per-request key.
- `deployment-aliases`: "The alias relays the target's answer" names Messages in its one exception.

## Impact

Depends on `no-budget-400-on-responses` ([epam/ai-dial-core#2360](https://github.com/epam/ai-dial-core/pull/2360)):
it modifies requirements that change wrote and reuses its code, and lands after it.

Motivation: a user report that Claude Code in gateway mode shows `Server is temporarily limiting requests (not your
usage limit)` for this refusal, and `policy-refusals-report.md` (in the `ai-dial-harness-launcher-cli` repository),
row "No spend limit assigned on the project". Covered: that misleading text, in Claude Code's gateway mode and not.
Not covered: how Claude Desktop shows the refusal, which was not measured.

Server module only: the Messages controllers, the shared no-budget code in `BaseDeploymentPostController`, and the
OpenAPI text. No new endpoint, setting or dependency, and no stored record changes.
