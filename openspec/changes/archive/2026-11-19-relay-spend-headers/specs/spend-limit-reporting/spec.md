## MODIFIED Requirements

### Requirement: Upstream Codex headers removed
Core SHALL remove every response header whose name starts with `x-codex-` (case-insensitive) that the upstream sends on a Responses answer, whatever its status, before adding its own. The pass of an alias SHALL NOT remove them (requirement "Spend headers through an alias").

#### Scenario: Upstream sends its own limits
- **WHEN** the upstream answers 200 with `x-codex-primary-used-percent: 5` to an uncapped caller
- **THEN** the answer carries no `x-codex-*` header

#### Scenario: Upstream headers replaced
- **WHEN** the upstream answers 200 with `x-codex-primary-used-percent: 5` to a caller at 82% of a day cap
- **THEN** the answer carries `x-codex-primary-used-percent: 82` once

## ADDED Requirements

### Requirement: Spend headers through an alias
On the pass of an alias, Core SHALL leave the `anthropic-ratelimit-*` headers of a Messages answer and the `x-codex-*` headers of a Responses answer as the target pass sent them, whatever the status, and SHALL add none of its own. A router in decision mode SHALL be served as the candidate it chooses, so its pass follows this rule when the candidate is an alias.

#### Scenario: Messages through an alias
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller with one budget at 82% of its day amount sends
  `POST /anthropic/v1/messages` with `"model": "alias-m1"`, an alias of the model `m1`, and the upstream of `m1`
  answers 200
- **THEN** the answer carries `anthropic-ratelimit-unified-overage-utilization: 0.82` once

#### Scenario: Responses through an alias and a router
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller with one budget at 82% of its day amount sends
  `POST /openai/v1/responses` with `"model": "strong"`, an alias of the router `r1` in decision mode, `r1` answers
  `{"candidates": ["alias-m1"]}`, and the upstream of `m1` answers 200
- **THEN** the answer carries `x-codex-primary-used-percent: 82` once

#### Scenario: Upstream headers removed behind the alias
- **WHEN** an uncapped caller sends `POST /openai/v1/responses` with `"model": "alias-m1"` and the upstream of `m1`
  answers 200 with `x-codex-primary-used-percent: 5`
- **THEN** the answer carries no `x-codex-*` header

### Requirement: No limit check on an alias pass
On a Messages or Responses call, the pass of an alias SHALL NOT read the caller's spend caps, SHALL NOT check or count any token, request or cost limit, and SHALL NOT refuse the call on a limit. The target pass SHALL check and refuse as on a direct call of the target.

#### Scenario: Budget used up, through an alias and a router
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller whose one budget is used up sends
  `POST /anthropic/v1/messages` with `"model": "strong"`, an alias of the router `r1` in decision mode, and `r1`
  answers `{"candidates": ["alias-m1"]}` to every decide request
- **THEN** once `r1` has been asked three times (its default `maxDecisions`), Core answers 429 with `error.type`
  `billing_error`, `anthropic-ratelimit-unified-status: rejected` and `x-should-retry: false`, and the upstream of
  `m1` is not called

#### Scenario: Role cost limit reached, budget not
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT caller whose role day cost limit is reached and whose one budget
  is at 10% of its day amount sends `POST /anthropic/v1/messages` with `"model": "alias-m1"`, and the upstream of `m1`
  answers 200
- **THEN** the answer is 200 and carries `anthropic-ratelimit-unified-overage-utilization: 0.10`

#### Scenario: Request limit under the alias's name
- **WHEN** the caller's role has a `requestDay` limit of 1 under the name `alias-m1`, and the caller sends two
  `POST /anthropic/v1/messages` requests with `"model": "alias-m1"` that the upstream of `m1` answers 200
- **THEN** both answers are 200
