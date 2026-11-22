# Spec Delta

## MODIFIED Requirements

### Requirement: Router config entry
Core SHALL read `routers` in the dynamic config (files or the `platform` bucket) as a map of deployments keyed by
name, each accepting the common deployment fields (`baseUrl`, `interfaces`, `userRoles`, display and feature
fields, `defaults`, `defaultHeaders`, `overridePaths`, `forwardAuthToken`, `overrideName`) and SHALL reject, on load
and on a Configuration API write, one with a field of another kind (`upstreams`, `limits`, `pricing`) or
with `interceptors`, `endpoint` or `responsesEndpoint`.

#### Scenario: Router loads with interfaces
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `userRoles: ["analysts"]` and
  `interfaces: {"openaiChatCompletions": {}, "anthropicMessages": {}}`
- **THEN** the config loads and `r1` resolves as a deployment for `POST /openai/deployments/r1/chat/completions`

#### Scenario: Router with upstreams rejected
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router` and `upstreams: [{"endpoint": "http://x"}]`
- **THEN** the config is rejected

#### Scenario: Router with interceptors rejected
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `interfaces.openaiChatCompletions: {}` and
  `interceptors: ["i1"]`
- **THEN** the config is rejected with a message containing `A router declares no interceptors`

#### Scenario: Router with a legacy endpoint rejected
- **WHEN** the config declares `routers.r1` with `endpoint: http://router/chat` and `interfaces.openaiChatCompletions: {}`
- **THEN** the config is rejected with a message containing `A router declares interfaces, not endpoint`

#### Scenario: Router with upstreams rejected on write
- **WHEN** an admin sends `PUT /v1/routers/platform/r-bad` with `{"baseUrl": "http://router", "upstreams":
  [{"endpoint": "http://x"}]}`
- **THEN** Core answers `400` and writes nothing

#### Scenario: Blob router read back
- **WHEN** an admin sends `PUT /v1/routers/platform/r-blob` with `{"baseUrl": "http://router", "userRoles":
  ["analysts"], "interfaces": {"openaiChatCompletions": {}}}`
- **THEN** Core answers `200` `{"name": "r-blob"}` with an `ETag`, and `GET /v1/routers/platform/r-blob` answers
  `200` with those fields plus `name: "r-blob"` and `status: "valid"`
