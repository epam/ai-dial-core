## MODIFIED Requirements

### Requirement: No budget for the model
Under the requirement "Budgets decide the caps in enforce mode", for a deployment that is not translated, when no budget of the caller's cost object covers the model, Core SHALL answer with the `no_budget` 403 of the spending-refusals capability, with `x-should-retry: false` and no spend header. Through an alias, also one that a router in decision mode chose, the client SHALL get the same answer, and Core SHALL NOT ask the router again.

#### Scenario: Budget for another model only
- **WHEN** a caller's only budget in their cost object covers only `claude-opus` and they send a Messages request
  for another model
- **THEN** Core answers 403 without calling the upstream, with `error.code` `no_budget`, an `error.message` that
  names that model and the cost object, `x-should-retry: false`, and no `retry-after` and no
  `anthropic-ratelimit-unified-*` header

#### Scenario: Budget for another model only, Responses
- **WHEN** a caller's only budget in their cost object covers only `gpt-opus` and they send a Responses request for
  another model to `POST /openai/v1/responses` or to `POST /openai/responses`
- **THEN** Core answers 403 without calling the upstream, with `error.code` `no_budget`, an `error.message` that
  names that model and the cost object, `x-should-retry: false`, and no `retry-after` and no `x-codex-*` header

#### Scenario: No budget, through an alias and a router
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller whose only budget covers only `claude-opus` sends
  `POST /anthropic/v1/messages` or `POST /openai/v1/responses` with `"model": "strong"`, an alias of the router `r1`
  in decision mode, and `r1` answers `{"candidates": ["alias-m1"]}` to every decide request
- **THEN** `r1` is asked once, and Core answers 403 with `error.code` `no_budget`, an `error.message` that names `m1`
  and the cost object, `x-should-retry: false`, no `retry-after` and no `anthropic-ratelimit-unified-*` or `x-codex-*`
  header, and the upstream of `m1` is not called
