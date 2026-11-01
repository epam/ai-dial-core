# Spec Delta

## MODIFIED Requirements

### Requirement: Translated interface not limit-checked
A request for an interface the deployment serves in `translator` mode through a translator service (`baseUrl`) SHALL
be neither checked nor charged (bar a background Responses job's finish, responses-api): the translator's call back
to Core is. `/anthropic/v1/messages` alone still refuses it `429` when the initiator's day, week or month cost limit
is already reached. A request a builtin translator serves is covered by anthropic-messages-translation.

#### Scenario: Translated interface not charged
- **WHEN** user `u1` has used the `m` per-minute token limit of its role, and `m` serves `anthropicMessages` in
  `translator` mode, and `u1` sends `POST /anthropic/v1/messages` for `m`
- **THEN** Core forwards the request to the translator rather than answering `429`, and answers the translator's
  call back to `POST /openai/deployments/m/chat/completions` `429`

### Requirement: Deployment closed to per-request keys
Core SHALL answer `403` `Deployment <id> is not accessible by <sourceDeployment>` to a deployments-POST,
`/openai/v1/chat/completions`, `/openai/v1/responses` or `/anthropic/v1/messages` request with a per-request key
for a deployment whose features resolved for the interface have `accessibleByPerRequestKey: false`. The last
interceptor's call to the initial deployment and a translator service's call back carry such a key, so a deployment
with interceptors, or an interface a translator service translates, is refused there. A builtin translator's check is
covered by anthropic-messages-translation.

#### Scenario: Application call refused
- **WHEN** `gpt-4` declares `features.accessibleByPerRequestKey: false` and `app` calls it with its key
- **THEN** Core answers `403` with body `Deployment gpt-4 is not accessible by app`
