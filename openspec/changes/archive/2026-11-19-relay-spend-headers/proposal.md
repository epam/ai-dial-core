# Proposal

## Why

Claude Code and Codex CLI show the user how much of a DIAL spend cap is used, from headers Core puts on a Messages
or Responses answer (`anthropic-ratelimit-unified-*`, `x-codex-*`), and stop with a clear message on the spend-cap
429. Core writes these headers on every Messages and Responses pass: each pass first removes the ones its upstream
sent and then adds the caps it read for its own deployment.

That is right for one pass, and wrong for a chain. A call of an alias is two Core passes: the alias pass, then the
target pass. Only a pass of a model is charged, and only it reads the caller's budget, because a budget is checked
against a model. The alias pass is not a model: with `spendingLimits.mode` `enforce` it reads the role cost limit,
not the budget. It removes the headers the target pass wrote and adds the caps it read itself. A router in decision
mode adds no pass of its own: the pass that asks it serves the chosen candidate, so an alias candidate is one more
alias pass. So a client that calls `strong` (an alias over a decision-mode router that chooses an alias of a model)
sees the role cost limit, or no spend headers, and never the budget that is charged. The alias pass also checks the
role cost limit on its own, so it can refuse a call the budget allows.

## What Changes

- On the pass of an alias, the Messages and Responses routes leave the `anthropic-ratelimit-*` and `x-codex-*`
  headers of the target pass's answer as they are, on every status, and add none of their own. The headers the client
  receives are the ones the pass of the serving deployment wrote.
- The same pass makes no limit check: it reads no spend caps and checks no token, request or cost limit, as the MCP
  route already does for an alias pass. The target pass checks and refuses as on a direct call, and its 429 reaches
  the client with its headers.
- A router in decision mode needs no rule of its own: the pass that asks it takes the candidate's deployment before
  the limit check, so an alias candidate gets the alias rule and a model candidate is the serving pass.
- A direct call of a model, an application, a router in proxy mode or a translated deployment is unchanged.
- Tests, and updates of `docs/anthropic-spend-limits.md`, `docs/codex-spend-limits.md` and
  `docs/dynamic-settings/aliases.md`.

Out of scope:

- Chat completions and other routes, which send no spend headers. `/openai/v1/chat/completions` still checks the
  limits of an alias pass, with the role cost limit; that is a separate change.
- A router in proxy mode: its pass keeps today's check and headers, and what the router outside Core does with the
  headers of the answers it gets back is up to it.
- A budget refusal ending the decision of a router at once (it is a separate change, waiting for the choice of status
  codes). Until then a spend-cap 429 of an alias candidate's target makes Core ask the router again, as any 429 does.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `spend-limit-reporting`: the pass of an alias relays the spend headers and makes no limit check; "Upstream Codex
  headers removed" gains that exception.

## Impact

No GitHub issue yet; one is to be opened before this leaves draft. Motivation: coding clients that call a model
through an alias over a router in decision mode (`alias-router-target`, `alias-in-process-hop`, both archived) must
see the caps of the budget that is charged. This change covers the spend headers and the limit check of an alias pass
on Messages and Responses; it leaves out chat completions, routers in proxy mode and the router's handling of a budget
refusal.

Touches `SpendCapCheck`, `MessagesController` and `ResponsesController` (the limit check and the limit headers), and
their tests. No new endpoint, setting or dependency.
