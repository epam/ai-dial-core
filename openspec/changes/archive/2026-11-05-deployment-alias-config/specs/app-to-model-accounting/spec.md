## MODIFIED Requirements

### Requirement: Deployment closed to per-request keys
Core SHALL answer `403` `Deployment <id> is not accessible by <sourceDeployment>` to a deployments-POST,
`/openai/v1/chat/completions`, `/openai/v1/responses` or `/anthropic/v1/messages` request with a per-request key
for a deployment whose features resolved for the interface have `accessibleByPerRequestKey: false`. The last
interceptor's call and a translator's call back carry such a key, so they are refused; an alias's target pass is not.

#### Scenario: Application call refused
- **WHEN** `gpt-4` declares `features.accessibleByPerRequestKey: false` and `app` calls it with its key
- **THEN** Core answers `403` with body `Deployment gpt-4 is not accessible by app`

#### Scenario: Alias call accepted
- **WHEN** `gpt-4` declares `features.accessibleByPerRequestKey: false` and a caller calls an alias that targets it
- **THEN** Core answers `200` with the model's answer
