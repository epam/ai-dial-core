# Review

## What shipped

On `POST /anthropic/v1/messages`, the no-budget refusal answers the client's own request, directly, streaming and through an alias or a router, with 402 `billing_error` and `x-should-retry: false`; a call made with a per-request key keeps the 429. Inside Core it stays a 429, marked for the alias pass, and `X-DIAL-REFUSAL` is removed from upstream answers on Messages and `count_tokens`. The no-budget code both routes share moved to `BaseDeploymentPostController`. Docs: `anthropic-spend-limits.md`, `error_codes.md` (402 section), `dynamic-settings/policies.md`, `dynamic-settings/aliases.md`, and the OpenAPI 402 and 429 text of `MessagesController.handle`.

## Verification

- Checkstyle and `BudgetSpendCapsApiTest`, `MessagesControllerLimitTest`, `SpendCapCheckTest`, `ResponsesControllerTest`, `ApiKeyDataTest`, `MessagesApiTest`, `AliasApiTest`, `AliasInProcessApiTest`: green.
- `openspec validate no-budget-reason-on-messages --strict`: valid.
- This branch's Core run locally in `enforce` mode: Claude Code 2.1.332, in gateway mode and not, printed `API Error: 402 <message>`, and Codex 0.172.0 printed the message, directly and through an alias, each after one request.

## Drift from design.md

- `count_tokens` also removes `X-DIAL-REFUSAL` from upstream answers; found in review, added to the spec and the Code map.
- D4 (the 402 written by its number, `HttpStatus` unchanged) was added while applying: `HttpStatus` has no 402.

## Not done

- A check against a dev environment with a real identity provider, and of the interactive screens and Claude Desktop.
- Pre-existing, on both routes: when a router in decision mode chose an alias and its next decide request fails, the client gets a 503 instead of the no-budget answer.
