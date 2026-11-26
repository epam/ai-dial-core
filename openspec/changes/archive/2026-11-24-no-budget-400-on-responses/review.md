# Review

## What shipped
With enforced budgets, on `POST /openai/v1/responses` and `POST /openai/responses`, the client's own request for a
model that no budget covers gets 400 with the message as plain text, `x-should-retry: false`, and no `retry-after` or
`x-codex-*` header, also when streaming. The same holds through an alias, also with a router in decision mode behind
the alias or choosing it. A call made with a per-request key keeps the 429. Inside Core the alias's target pass marks
its 429 with `X-DIAL-REFUSAL: no_budget` when a Responses alias pass made its key; that flag is kept in memory only.
Core removes the header from what an upstream, an application or an interceptor answers. The no-budget message is
reworded on every route.

Docs:
- `codex-spend-limits.md`, `anthropic-spend-limits.md`, `dynamic-settings/aliases.md`, `dynamic-settings/policies.md`
  and `error_codes.md`;
- the 400 and 429 descriptions of both Responses paths, in the annotations and in `docs/open_api_core.yaml`, whose
  descriptions `SpecMerger` keeps over the annotations.

## Verification
- Checkstyle passes, and these test classes pass: `BudgetSpendCapsApiTest`, `ApiKeyDataTest`, `BudgetEnforcerTest`,
  `RateLimiterTest`, `ResponsesControllerTest`, `ResponsesSpendLimitApiTest`, `MessagesSpendLimitApiTest`,
  `AliasApiTest`, `AliasInProcessApiTest`, `ResponsesInterceptorControllerTest`, `SpendCapCheckTest` and
  `MessagesControllerLimitTest`.
- The new and changed tests failed on the old code for the expected reason.
- `validate no-budget-400-on-responses --strict` passes.
- The committed `docs/open_api_core.yaml` is the output of `:openapi-generator:replaceSpec`.
- Codex CLI 0.168.0 and 0.172.0 against a fake server showed the reason only for a 400 with a plain-text body (D1).

## Drift from design.md
None.

## Not done
- Task 2.4: Codex CLI 0.172.0 against a dev environment with budgets enforced.
- `no-budget-should-retry-false` is still an open change, and its delta modifies "No budget for the model". This
  change's delta carries that requirement in full. Archive the older change with `--skip-specs`, or its text replaces
  this one.
- Follow-up: matching Core's own message on a relayed 429, instead of the header and the key flag, would be simpler.
  It would also cover models behind an interceptor and background requests through an alias.
