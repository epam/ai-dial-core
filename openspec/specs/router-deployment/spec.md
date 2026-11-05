# router-deployment Specification

## Purpose
A router is a deployment that picks the deployment serving a request. This capability covers what a router is in the
dynamic config, how its name and interfaces are validated, how it is listed, and how a request to it is served and
accounted, whether the router proxies the request itself or, in decision mode, answers a verdict and Core serves
the chosen deployment.

## Requirements

### Requirement: Router config entry
Core SHALL read `routers` in the dynamic config as a map of deployments keyed by name, each accepting the common
deployment fields (`baseUrl`, `interfaces`, `userRoles`, display and feature fields, `defaults`, `defaultHeaders`,
`overridePaths`, `forwardAuthToken`, `overrideName`) and SHALL reject a config whose router declares a field of
another deployment kind (such as `upstreams`, `limits`, `pricing`), or declares `interceptors`, `endpoint` or
`responsesEndpoint`.

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
`display_version`, `icon_url`, `description`, `features`, `defaults`, `description_keywords` and no url.

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

#### Scenario: Embeddings proxied to the router
- **WHEN** `routers.r1` declares `baseUrl: http://router` and `interfaces.openaiEmbeddings: {}`, and a client sends
  `POST /openai/deployments/r1/embeddings`
- **THEN** the router receives `POST http://router/openai/deployments/r1/embeddings` and the client receives its answer

#### Scenario: Interface the router does not declare
- **WHEN** `routers.r1` declares only `interfaces.openaiChatCompletions: {}` and a client sends `POST /openai/v1/responses`
  with `{"model": "r1"}`
- **THEN** Core answers `503` and sends nothing to the router

### Requirement: Router never limit-checked
Core SHALL neither check nor charge limits for a router on any API that reaches it; the role's `limits` for a router
name are ignored, and the models the router calls back through Core are checked and charged against the initiator
as app-to-model-accounting states.

#### Scenario: Exhausted request limit on the router does not refuse
- **WHEN** the caller's role declares `limits.r1.requestHour: 1` and the caller sends two
  `POST /openai/v1/chat/completions` requests with `{"model": "r1", ...}` within one hour
- **THEN** Core forwards both to the router

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

### Requirement: Decision interface mode
Core SHALL read `interfaces.<type>.mode: decision` on a router, with the entry's own `base_url` as the URL of the
decide request, an optional `context.body` (default `false`) and an optional `decisionTimeoutMs` (default 5000,
must be positive). Core SHALL reject `decision` on any deployment that is not a router (`Mode 'decision' is allowed on a
router only`), a decision entry without its own `base_url` (`Mode 'decision' requires base_url`) and one declaring a
`translator`.

#### Scenario: Decision mode on a model or application rejected
- **WHEN** the config declares `models.m` with `interfaces.openaiChatCompletions: {"mode": "decision", "base_url": "http://r/decide"}`,
  or `applications.a` with the same entry
- **THEN** the config is rejected with a message containing `Mode 'decision' is allowed on a router only`, and an
  admin `PUT /v1/applications/platform/a` with that entry is answered `422` with the same message

#### Scenario: Decision entry needs its own url
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router` and `interfaces.openaiChatCompletions: {"mode": "decision"}`
- **THEN** the config is rejected with a message containing `Mode 'decision' requires base_url`

#### Scenario: Decision for one interface, proxy for another
- **WHEN** `routers.r1` declares `interfaces.anthropicMessages: {"mode": "decision", "base_url": "http://router/decide"}`
  and `interfaces.openaiResponses: {"base_url": "http://router"}`
- **THEN** the config loads; a Messages request for `r1` triggers a decide request and a Responses request for `r1`
  is proxied to `http://router/openai/v1/responses`

### Requirement: Decide request
For a request to a router whose interface is in decision mode, Core SHALL send `POST <base_url>` with the client's
headers as it forwards them to any origin, the router's `defaultHeaders` for the interface where the client sent
none, `X-DIAL-USER-INFO`, `X-DIAL-ROUTER` (router name) and `X-DIAL-API` (interface type), no `API-KEY`, and an
empty body, or the client's request bytes unchanged when `context.body` is `true`.

#### Scenario: Headers and empty body
- **WHEN** `routers.r1` declares `defaultHeaders: {"X-DIAL-ROUTER-TIER": "medium"}` and
  `interfaces.openaiChatCompletions: {"mode": "decision", "base_url": "http://router/decide"}`, and a client with
  project key `p1` sends `POST /openai/deployments/r1/chat/completions` with `X-CONVERSATION-ID: c1`
- **THEN** the router receives `POST http://router/decide` with `X-CONVERSATION-ID: c1`, `X-DIAL-ROUTER-TIER: medium`,
  `X-DIAL-ROUTER: r1`, `X-DIAL-API: openaiChatCompletions`, `X-DIAL-USER-INFO` with `project: "p1"`, no `API-KEY`,
  and an empty body, and the model that serves the request receives no `X-DIAL-ROUTER-TIER` header

#### Scenario: Body on request
- **WHEN** the same interface declares `context: {"body": true}` and the client sends body `{"model":"r1","messages":[]}`
- **THEN** the decide request's body is exactly `{"model":"r1","messages":[]}` with `Content-Type: application/json`

### Requirement: Verdict
Core SHALL accept a `200` decide answer whose JSON body has `candidates`, a non-empty array of deployment names in
order of preference, or `deployment`, one name, read as a one-element list; it SHALL ignore unknown fields. Any
other `200` body SHALL be answered `502`.

#### Scenario: Shorthand accepted
- **WHEN** the router answers `{"deployment": "gpt-4", "ttl": 60}`
- **THEN** Core serves the request with `gpt-4`

#### Scenario: Schema mismatch
- **WHEN** the router answers `{"candidates": []}`, then `{"model": "gpt-4"}`, then `not json`
- **THEN** Core answers `502` each time and sends nothing to any deployment

### Requirement: Chosen deployment served as if called directly
Core SHALL serve a candidate as if the client had called it: the caller's roles, consent and per-request-key access
to it, its limits checked against the caller, one per-request key, one token-stats span, the enhancement chain and
the forward of deployment-origin. On `/openai/v1/chat/completions`, `/openai/v1/responses` and
`/anthropic/v1/messages` Core SHALL set the body `model` to the candidate's name before the chain. A candidate that
is a router SHALL be rejected.

#### Scenario: Deployments-POST served by the chosen model
- **WHEN** the router answers `{"candidates": ["gpt-4"]}` to a decide request for `POST /openai/deployments/r1/chat/completions`
  and `gpt-4` has `overrideName: gpt-4-real`
- **THEN** the model's origin receives `POST .../openai/deployments/gpt-4-real/chat/completions` with a per-request
  `API-KEY` and `X-DIAL-OVERRIDE-NAME: gpt-4-real`, and the client receives its answer

#### Scenario: Messages body model rewritten
- **WHEN** a client sends `POST /anthropic/v1/messages` with `{"model": "r1", ...}` and the router answers `{"candidates": ["claude"]}`
- **THEN** the model's origin receives a body whose `model` is `claude` (or `claude`'s `overrideName` when it has one)
  and `X-DIAL-DEPLOYMENT-ID: claude`

#### Scenario: Chosen model's limits checked against the caller
- **WHEN** the caller's role declares `limits.gpt-4.requestHour: 1`, the caller has already used it this hour,
  and the router answers `{"candidates": ["gpt-4"]}`
- **THEN** Core answers `429` and sends nothing to `gpt-4`

#### Scenario: Candidate is a router
- **WHEN** the router answers `{"candidates": ["r2"]}` and `r2` is a router
- **THEN** Core answers `502` and sends nothing to `r2`

#### Scenario: Embeddings decided and served
- **WHEN** `r1` serves `openaiEmbeddings` in decision mode, a client sends `POST /openai/deployments/r1/embeddings`
  and the router answers `{"candidates": ["ada"]}`
- **THEN** `ada`'s origin receives `POST .../openai/deployments/ada/embeddings` and the client receives its answer

### Requirement: Decision after the router's interceptors
When the config declares `globalInterceptors`, Core SHALL send a request to a router through them first, as for any
deployment, and SHALL run the decision in the pass that reaches the router after the chain; the chosen
deployment's key path SHALL then be the interceptor chain's path followed by the router and the chosen deployment.

#### Scenario: Global interceptor before the decision
- **WHEN** the config declares `globalInterceptors: ["i1"]`, a client calls `r1` and the router answers
  `{"candidates": ["gpt-4"]}`
- **THEN** `i1` receives the request first, the decide request is sent once, when `i1` calls Core back, and the key
  `gpt-4` receives has `executionPath: ["i1", "r1", "gpt-4"]`

### Requirement: Candidate walk
Core SHALL try the candidates in order: one the caller may not use, whose limits are exhausted, that does not serve
the interface or does not exist is skipped before any call; one whose call fails with a connect failure or a
retriable status after its own upstream retries is skipped while no byte has reached the client. When none is left
Core SHALL answer the last failure. No fallback after a non-retriable answer or an interceptor hand-off.

#### Scenario: Forbidden first candidate skipped
- **WHEN** the router answers `{"candidates": ["private-model", "gpt-4"]}` and the caller's roles do not admit
  `private-model`
- **THEN** `gpt-4` serves the request and nothing is sent to `private-model`

#### Scenario: Failing first candidate skipped
- **WHEN** the router answers `{"candidates": ["m1", "m2"]}` and `m1`'s only upstream answers `503`
- **THEN** `m2` serves the request, the client receives `200`, and one analytics row is written, for `m2`

#### Scenario: Every candidate forbidden
- **WHEN** the router answers `{"candidates": ["private-a", "private-b"]}` and the caller may use neither
- **THEN** Core answers `403`

#### Scenario: Non-retriable answer relayed
- **WHEN** the router answers `{"candidates": ["m1", "m2"]}` and `m1` answers `400` with a JSON error
- **THEN** the client receives that `400` and nothing is sent to `m2`

### Requirement: Decision failures
Core SHALL answer `504` when the decide request exceeds `decisionTimeoutMs` (connecting included), `502` when the
router is unreachable, answers `5xx`, or names a candidate that does not exist or is a router, or when the router is
re-entered through a deployment it chose, and SHALL relay a router `4xx` status and body to the client. In every
case Core SHALL send nothing to any deployment and SHALL write no analytics row.

#### Scenario: Slow router
- **WHEN** `decisionTimeoutMs: 200` and the router takes 2 s to answer
- **THEN** Core answers `504` within the second

#### Scenario: Router rejects the request
- **WHEN** the router answers `400` with `{"error": {"message": "state either tier or target"}}`
- **THEN** the client receives `400` with that body

#### Scenario: Router down
- **WHEN** nothing listens on the decide URL
- **THEN** Core answers `502`

#### Scenario: Unknown last candidate
- **WHEN** the router answers `{"candidates": ["private-a", "no-such-model"]}` and the caller may not use `private-a`
- **THEN** Core answers `502`

#### Scenario: Router re-entered through an application
- **WHEN** the router answers `{"candidates": ["app"]}` and `app` calls `POST /openai/deployments/r1/chat/completions`
  back with its per-request key
- **THEN** that call is answered `502` and no second decide request is sent

### Requirement: Decision record
For a request served through a decision, a background Responses job included, Core SHALL write one analytics row,
for the deployment that served, with the router before it in `execution_path`, `parent_deployment` the router, and a
`decision` object: `router`, `api`, `candidates`, `deployment` (the one that served), `decide_ms` and `attempts`, one
`{deployment, status, reason}` per candidate tried before it, `status` being Core's answer for it.

#### Scenario: Row of a decided request
- **WHEN** the router `r1` answers `{"candidates": ["m1", "m2"]}`, `m1` is skipped for a `503` and `m2` serves
- **THEN** the row has `deployment: "m2"`, `execution_path: ["r1", "m2"]`, `parent_deployment: "r1"` and
  `decision.router: "r1"`, `decision.api: "openaiChatCompletions"`, `decision.candidates: ["m1", "m2"]`,
  `decision.deployment: "m2"`, a numeric `decision.decide_ms` and
  `decision.attempts: [{"deployment": "m1", "status": 502, "reason": "upstream"}]`

#### Scenario: Row of a decided background job
- **WHEN** a client sends `POST /openai/v1/responses` with `{"model": "r3", "background": true, "input": "hi"}`, the
  router `r3` answers `{"candidates": ["gpt-4"]}`, the origin accepts the job and later answers the poll with
  `status: completed`
- **THEN** the create is served by `gpt-4` with the dial id replaced as for any background create, and the one row
  written when the job ends has `deployment: "gpt-4"`, `execution_path: ["r3", "gpt-4"]`, `parent_deployment: "r3"`
  and `decision.router: "r3"`, `decision.api: "openaiResponses"`, `decision.deployment: "gpt-4"`
