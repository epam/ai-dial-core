# Spec Delta

## MODIFIED Requirements

### Requirement: Interface mode
Core SHALL read `interfaces.<type>.mode` as `passthrough` (the default when absent), `translator` or `decision`. An
entry in `translator` mode SHALL be served by its translator's `baseUrl` only, never by a `base_url`, the
deployment-level `baseUrl`, `endpoint` or `responsesEndpoint`. An entry in `decision` mode (routers only, see
router-deployment) SHALL never have the client's request forwarded to its `base_url`.

#### Scenario: Translated interface goes to the translator
- **WHEN** a model declares `baseUrl: http://adapter` and `interfaces.anthropicMessages: {"mode": "translator",
  "translator": {"out": "openaiChatCompletions", "baseUrl": "http://translator/to-chat"}}` and
  `interfaces.openaiChatCompletions: {}`, and a client sends `POST /anthropic/v1/messages` for it
- **THEN** the request is sent to `POST http://translator/to-chat/anthropic/v1/messages`

#### Scenario: Translated interface never falls back
- **WHEN** a model declares `endpoint: http://legacy/chat` and
  `interfaces.openaiChatCompletions: {"mode": "translator", "translator": "missing"}` and no `translators.missing`
- **THEN** a chat completion for it is answered `503` and nothing is sent to `http://legacy/chat`

#### Scenario: Decision interface is not proxied
- **WHEN** a router declares `interfaces.openaiChatCompletions: {"mode": "decision", "base_url": "http://router/decide"}`
  and a client sends `POST /openai/deployments/<router>/chat/completions`
- **THEN** `http://router/decide` receives a decide request and never the client's chat completion
