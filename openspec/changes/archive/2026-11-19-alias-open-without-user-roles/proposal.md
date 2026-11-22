# Proposal

## Why

An alias without `userRoles` takes its target's access, while any other deployment without `userRoles` is open to
anyone. So an alias cannot be open to everyone while the deployment below it stays hidden, which tier aliases need.

## What Changes

- **BREAKING** An alias without `userRoles` whose target is a config deployment is open to every caller, as any
  deployment without `userRoles` is. This holds for an alias of the config file, a platform alias and a public stored
  alias, and for calls, listings, single-item endpoints, toolset sign-in, consent, and a router choosing the alias.
- The alias lends its target to every caller it admits. So an existing alias without `userRoles` over a target with
  `userRoles` opens that target, through the alias, to every caller: on a direct call of the alias, and when a router
  chooses the alias as a candidate (today such a candidate is skipped as `forbidden`). The target pass checks limits
  under the target's name by the caller's own roles, then by the `default` role; with neither, there is no limit.
- Before upgrading, an operator copies the target's `userRoles` (`[]` included) onto every alias without `userRoles`
  whose config target has them. Copied roles no longer follow later changes of the target. The release note says so;
  `docs/dynamic-settings/aliases.md` states the current rule.
- Unchanged: `userRoles: []` admits no caller; explicit roles decide as today; a resource target keeps today's rule
  (design.md D1); a router target is lent alone, and the deployment it chooses is checked against the caller's rights.

Out of scope:

- Migration code and start-up warnings (design.md D2).
- Inheriting the target's roles on request, for example an `inheritUserRoles: true` flag: the option if someone later
  asks for inheritance.

## Capabilities

### Modified Capabilities

- `deployment-aliases`: "Who may use an alias" and "Who may call a stored alias".

## Impact

- No issue yet; one is to be opened before the PR leaves draft.
- Supersedes: `2026-11-05-deployment-alias-config` and `2026-11-05-deployment-alias-public`, their rule that an alias
  without `userRoles` takes the target's (neither numbers its decisions).
- Code: `AliasService` in the server module; tests in `AliasApiTest`, `AliasPublicApiTest`, `AliasPlatformApiTest`
  and `AliasServiceTest`. Docs: `docs/dynamic-settings/aliases.md`. No API shape, config field or setting changes.
