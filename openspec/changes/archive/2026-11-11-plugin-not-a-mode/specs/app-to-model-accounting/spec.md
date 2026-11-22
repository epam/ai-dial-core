# Spec Delta

## MODIFIED Requirements

### Requirement: Translated interface not limit-checked
A request for an interface the deployment serves in `translator` mode SHALL be neither checked nor charged (bar a
background Responses job's finish, responses-api): the translator's call back to Core is. `/anthropic/v1/messages`
alone still refuses it `429` when the initiator's day, week or month cost limit is already reached.

#### Scenario: Translated interface not charged
- **WHEN** user `u1` has used the `m` per-minute token limit of its role, and `m` serves `anthropicMessages` in
  `translator` mode, and `u1` sends `POST /anthropic/v1/messages` for `m`
- **THEN** Core forwards the request to the translator rather than answering `429`, and answers the translator's
  call back to `POST /openai/deployments/m/chat/completions` `429`

#### Scenario: Translator plugin's call charged once
- **WHEN** model `m` serves `anthropicMessages` in `translator` mode through a translator with `out:
  openaiChatCompletions` and `plugin: p1`, `p1` calls `POST /openai/deployments/m/chat/completions` through its host,
  the origin of `m` answers `200` with `usage.total_tokens: 15`, and `p1` answers the client's request with that usage
- **THEN** the `m/tokens` limit record under `Users/u1/` grows by 15, not by 30

#### Scenario: Translator plugin's call refused over the limit
- **WHEN** user `u1` has used the `m` per-minute token limit of its role and sends `POST /anthropic/v1/messages` for
  that model
- **THEN** `p1` receives the request, and its call to `POST /openai/deployments/m/chat/completions` is answered `429`
