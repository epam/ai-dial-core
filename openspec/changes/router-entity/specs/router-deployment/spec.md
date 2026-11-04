# Spec Delta

## Purpose
A router is a deployment that picks the deployment serving a request. This capability covers what a router is in the
dynamic config, how its name and interfaces are validated, how it is listed, and how a request to it is served and
accounted when the router proxies the request itself. The decision protocol, where Core asks the router for a
verdict and serves the chosen deployment, joins this capability in a later change.

## ADDED Requirements

### Requirement: Router config entry
Core SHALL read `routers` in the dynamic config as a map of deployments keyed by name, each accepting the common
deployment fields (`baseUrl`, `interfaces`, `userRoles`, display and feature fields, `defaults`, `defaultHeaders`,
`overridePaths`, `forwardAuthToken`, `overrideName`) and SHALL reject a config whose router declares a field of
another deployment kind (such as `upstreams`, `limits`, `pricing`) or declares `interceptors`.

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

### Requirement: Router name in the deployment namespace
A router name SHALL share the one deployment-id namespace with models, applications, toolsets and interceptors: a
second deployment of any of those kinds with the same name is a duplicate deployment id under config-store's
invalid entity policy, and a name containing `/` is rejected as for the other kinds.

#### Scenario: Router name taken by a model
- **WHEN** the config files declare `models.x` and `routers.x` and Core starts with `config.onInvalidEntity` unset
- **THEN** startup fails on the rejected config

### Requirement: Router interfaces validated as a model's
Core SHALL apply to a router's `interfaces` the rules deployment-interfaces-config states for a model: a pass-through
entry needs a url, base url or translator decided by mode, translator matches the interface, translator output served
pass-through, and override path validation; the rejection message names the router (`Router 'r1': ...`).

#### Scenario: Pass-through entry with no url anywhere
- **WHEN** the config declares `routers.r1` with `interfaces: {"openaiChatCompletions": {}}` and no `baseUrl`
- **THEN** the config is rejected with a message containing `Router 'r1'` and
  `Interface 'openaiChatCompletions' declares no base_url`

#### Scenario: Translator without mode
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router` and
  `interfaces.anthropicMessages: {"translator": {"out": "openaiChatCompletions", "baseUrl": "http://t"}}`
- **THEN** the config is rejected with a message containing `A translator requires mode 'translator'`

### Requirement: Router listing
`GET /v1/routers` SHALL answer `200` with `{"data": [...]}` holding one entry per router whose `userRoles` admit the
caller, and `GET /v1/routers/{id}` SHALL answer `200` with that entry, `404` for an unknown name and `403` for a
router the caller's roles do not admit. An entry carries `id`, `object: "router"`, `display_name`,
`display_version`, `icon_url`, `description`, `features`, `defaults`, `description_keywords`, `created_at`,
`updated_at` and no url.

#### Scenario: Listing filtered by role
- **WHEN** the config declares `routers.r1` with `userRoles: ["analysts"]` and `routers.r2` without `userRoles`, and a
  caller whose roles do not include `analysts` sends `GET /v1/routers`
- **THEN** the answer's `data` holds `r2` and not `r1`, and the `r2` entry has `object: "router"` and no `base_url`
  or `endpoint` field

#### Scenario: Single router by role
- **WHEN** the same caller sends `GET /v1/routers/r1`, then `GET /v1/routers/r2`, then `GET /v1/routers/missing`
- **THEN** Core answers `403`, then `200` with `id: r2`, then `404`

### Requirement: Routers absent from other listings
A router SHALL NOT appear in `GET /openai/models`, `GET /openai/deployments`, `GET /openai/applications`,
`GET /v1/deployments`, `GET /v1/deployment-names` or `GET /anthropic/v1/models`, and `GET /v1/deployments/{id}` for a
router name SHALL answer `404`.

#### Scenario: Router hidden from the model and deployment listings
- **WHEN** the config declares `routers.r1` with `interfaces.anthropicMessages: {}` and a caller sends `GET /openai/models`,
  `GET /v1/deployments`, `GET /anthropic/v1/models` and `GET /v1/deployments/r1`
- **THEN** no listing holds an entry with id `r1`, and the last answer is `404`

### Requirement: Router served as an application
For an interface a router serves in `passthrough` or `translator` mode, Core SHALL forward the request to the
router's origin as it does for an application: the origin URI, the per-request key, the override name and
features headers and the upstream retry of deployment-origin apply; a router has no upstream list, so the origin is
the url the interface resolves to.

#### Scenario: Chat completion proxied to the router
- **WHEN** `routers.r1` declares `baseUrl: http://router` and `interfaces.openaiChatCompletions: {}`, and a client
  sends `POST /openai/deployments/r1/chat/completions`
- **THEN** the router receives `POST http://router/openai/deployments/r1/chat/completions` with an `API-KEY` that
  is a per-request key and a JSON `X-DIAL-DEPLOYMENT-FEATURES` header, and the client receives the router's answer

#### Scenario: Messages request proxied to the router
- **WHEN** `routers.r1` declares `baseUrl: http://router` and `interfaces.anthropicMessages: {}`, and a client sends
  `POST /anthropic/v1/messages` with `{"model": "r1", ...}`
- **THEN** the router receives `POST http://router/anthropic/v1/messages` and the client receives its answer

#### Scenario: Interface the router does not declare
- **WHEN** `routers.r1` declares only `interfaces.openaiChatCompletions: {}` and a client sends `POST /openai/v1/responses`
  with `{"model": "r1"}`
- **THEN** Core answers `503` and sends nothing to the router

### Requirement: Router never limit-checked
Core SHALL neither check nor charge limits for a router on any API that reaches it; the role's `limits` for a router
name are ignored, and the models the router calls back through Core are checked and charged against the initiator
as app-to-model-accounting states.

#### Scenario: Exhausted request limit on the router does not refuse
- **WHEN** the caller's role declares `limits.r1.requestMinute: 1`, the caller has already sent one request to `r1`
  this minute, and sends `POST /openai/v1/chat/completions` with `{"model": "r1", ...}`
- **THEN** Core forwards the request to the router

#### Scenario: Model called back is still charged
- **WHEN** the router answers a client's request by calling `POST /openai/deployments/gpt-4/chat/completions` back
  with its per-request key and the model answers `200` with `usage.total_tokens: 15`
- **THEN** the `gpt-4/tokens` limit record under the initiator's bucket grows by 15

### Requirement: Identity header for a router
Core SHALL add `X-DIAL-USER-INFO` to every request it forwards to a router, a JSON object with `roles` (the caller's
roles), `project` (for a project-key caller) and `userId` (for a JWT caller), the same values `GET /v1/user/info`
answers that caller, replacing any header of that name the client sent.

#### Scenario: JWT caller
- **WHEN** a client authenticated by a JWT with user id `u1` and roles `["a", "b"]` sends a request to router `r1`
  with a header `X-DIAL-USER-INFO: {"roles": ["admin"]}`
- **THEN** the router receives exactly one `X-DIAL-USER-INFO` header whose JSON has `userId: "u1"`, `roles: ["a", "b"]`
  and no `project`

#### Scenario: Project-key caller
- **WHEN** a client authenticated by a project key of project `p1` with role `default` sends a request to router `r1`
- **THEN** the router receives `X-DIAL-USER-INFO` with `project: "p1"`, `roles: ["default"]` and no `userId`

#### Scenario: Not added for an application
- **WHEN** a client sends a request to an application
- **THEN** the origin request carries no `X-DIAL-USER-INFO` header
