# Proposal

## Why

Harnesses such as Claude Code and Codex pick a model from `/openai/models` and `/anthropic/v1/models`. Routers are
not in these listings, and an alias, which is, cannot point to a router. So an admin cannot publish a routed
deployment under a model name. An alias also cannot tell its target anything about itself: its `defaultHeaders` are
accepted by the config and then ignored, so several aliases over one router all look the same to the router.

## What Changes

- An alias accepts the target path `routers/<name>`. A router is none of the alias types, so such an alias states
  `presentAs` (`model` or `application`).
- A call of such an alias is decided by the router and served as a direct call of the router is. The answer's
  `model` is the alias's name, not the router's, also when a stored response is read back.
- Such an alias cannot be a routing candidate, as a router cannot; a stored alias cannot target a router.
- An alias's `defaultHeaders` work as a deployment's do: the alias pass sets each one the client did not send. The
  target pass, and the decide request of a router target, see them as client headers.
  **Behaviour change:** an existing alias that declares `defaultHeaders` starts sending them.
- Tests for both, and updates of `docs/dynamic-settings/aliases.md` and `routers.md`.

Out of scope:

- Serving an alias without a second Core pass. The alias still calls Core's own address.
- Access of the caller to the deployments a router chooses. They are checked against the caller's roles, as on a
  direct call of the router; the alias grants the right to call the router only.
- Serving a stored alias (`aliases/public/...`) with a router target; such a `PUT` is refused. Toolset aliases with
  `defaultHeaders`.
- Any change to the decide request other than the headers the alias adds.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-aliases`: a router as a target; the alias's default headers applied on the alias pass.
- `router-deployment`: the name in the answer of a decision that was reached through an alias.

## Impact

No GitHub issue yet; one is to be opened before this leaves draft. Motivation: one router behind several model
names (for example quality tiers), each name telling the router which tier it stands for through a header, with the
names visible in the model listings harnesses read.

Touches `AliasTargets`, `AliasPasses`, `StoredAliases` (the public-bucket check) and its callers, the
default-settings step of the alias pass, `RouterDecision` and the answer-name rewrite (`RouterModelFn` call sites,
`ReplaceResponseIdFn`), the candidate check of `RouterDecisionFlow`, the lend in `ApiKeyData`, and their tests. No new
endpoint, setting or dependency.

This change is the first of a series; the next ones (granting a router's chosen deployments to the caller, and
serving an alias in one pass) are separate changes that build on it.
