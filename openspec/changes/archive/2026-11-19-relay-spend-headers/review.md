# Review

## What shipped

- On Messages (`/anthropic/v1/messages`) and Responses (`/openai/v1/responses`, `/openai/responses`), the pass of an
  alias makes no limit check. It reads no spend caps, and it checks and counts no token, request or cost limit:
  `SpendCapCheck.verify` returns no caps for an `AliasPass`. The target pass checks and refuses as on a direct call.
- The same pass leaves the `anthropic-ratelimit-*` and `x-codex-*` headers of the target pass's answer as they are,
  on every status, and adds none (`MessagesController.putLimitHeaders`, `ResponsesController.putLimitHeaders`). A
  client sees the caps of the deployment that serves the call: through an alias of a model in `enforce` mode, the
  budget. A router in decision mode that chooses an alias candidate follows the same rule.
- Unchanged: a direct call of a model, an application, a translated deployment or a router in proxy mode; chat
  completions; MCP.
- Docs: `docs/anthropic-spend-limits.md` (a paragraph under "Which cap is reported"), `docs/codex-spend-limits.md`
  (a new section "Aliases"), `docs/dynamic-settings/aliases.md` (the alias pass on Messages and Responses).

## Verification

- `./gradlew checkstyleMain checkstyleTest` (JDK 25): passed.
- `./gradlew :server:test --tests` with `BudgetSpendCapsApiTest`, `MessagesSpendLimitApiTest`,
  `ResponsesSpendLimitApiTest`, `AliasApiTest`, `AliasInProcessApiTest`, `RouterDecisionApiTest` and
  `SpendCapCheckTest`: all passed. The new tests in `BudgetSpendCapsApiTest` failed before the code change.
- `npx -y @fission-ai/openspec@1.14.3 validate relay-spend-headers --strict`: valid.
- Cost: the diff removes the alias pass's limit check on these two routes (the cost record read, the token record read
  and the request counter write), as design.md "Effect per request" says. It adds no Redis call, no body parse and no
  blocking call on the event loop.

## Drift from design.md

- Code map: the `AliasPass` entry said that every alias presentation but `toolset` is served as an `AliasPass`.
  `AliasToolSetPass` implements `AliasPass` too, and it serves MCP only. The entry is corrected in place. No decision
  changed.

## Not done

- Task 2.3 is deferred. It needs a dev environment with this build and a budget in `enforce` mode, to see Claude Code
  and Codex CLI show the budget through an alias over a decision-mode router.
- Left open for a separate change: a target pass answers the spend-cap 429 to an alias candidate, and then asking the
  router again fails. The client then gets the error of the alias pass's used-up upstream route, without spend
  headers (design.md "Risks"). The change that ends a decision at a budget refusal covers this case.
- Follow-up: chat completions still check the alias pass (the role cost limit, and a request limit under the alias's
  name). `docs/dynamic-settings/aliases.md` says that a limit under an alias's name has no effect. The proposal leaves
  chat completions out of scope.
- The saved limit check is not measured. The count follows from the code paths.
- CI must still run the full suite.
