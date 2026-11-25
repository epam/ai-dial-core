# Spec Delta

## ADDED Requirements

### Requirement: Routers in the generic deployment listings
`GET /v1/deployments`, `GET /v1/deployment-names` and `GET /v1/deployments/{id}` SHALL include routers the caller's
`userRoles` admit, as `object: "router"` entries with the fields of a `GET /v1/routers` entry plus `interfaces`.
`GET /v1/deployments` lists them for `interface_type` `all`, `chat` (when they declare `openaiChatCompletions`) and
`embedding` (when they declare `openaiEmbeddings`); `GET /v1/deployment-names` accepts `type=router`.

#### Scenario: Router in the deployment listing
- **WHEN** `routers.r2` declares `openaiChatCompletions` and no `userRoles`, and a caller sends `GET /v1/deployments`
- **THEN** the answer holds an entry with `id: "r2"`, `object: "router"`, `interfaces` containing `chat`, and no
  `base_url` or `endpoint`

#### Scenario: Interface filter
- **WHEN** `routers.r2` declares `openaiChatCompletions` but not `openaiEmbeddings`, and a caller sends
  `GET /v1/deployments?interface_type=embedding`, then `GET /v1/deployments?interface_type=mcp`
- **THEN** neither answer holds `r2`

#### Scenario: Router names
- **WHEN** a caller sends `GET /v1/deployment-names?type=router`, then `GET /v1/deployment-names`
- **THEN** both answers hold `{"id": "r2", "object": "router"}`, and the first holds no model or application

#### Scenario: Single router through deployment info
- **WHEN** `routers.r1` has `userRoles: ["analysts"]` and a caller without that role sends `GET /v1/deployments/r1`,
  then `GET /v1/deployments/r2`
- **THEN** Core answers `403`, then `200` with `object: "router"`

### Requirement: Router identity through user info
Core SHALL NOT send `X-DIAL-USER-INFO` to a router, on a forwarded request, a feature endpoint call, a Responses item
call or a decide request, and SHALL drop a client-sent header of that name. A router SHALL get the caller's identity
from `GET /v1/user/info` called with the `API-KEY` Core sent it.

#### Scenario: No identity header, user info through the key
- **WHEN** a client with project key `p1` sends `POST /openai/deployments/r1/chat/completions` with
  `X-DIAL-USER-INFO: {"roles": ["admin"]}`, and the router calls `GET /v1/user/info` with the `API-KEY` it received
- **THEN** the router's request carries no `X-DIAL-USER-INFO`, and the user info answer has `project: "p1"`

#### Scenario: JWT caller's identity through the key
- **WHEN** a client authenticated by a JWT with user id `u1` and roles `["analysts", "b"]` calls router `r1`, and the
  router calls `GET /v1/user/info` with the `API-KEY` it received
- **THEN** the answer has `userId: "u1"` and roles `["analysts", "b"]`

### Requirement: Decide request key
For each decide request Core SHALL mint a per-request key carrying the caller's identity, roles and claims and the
per-request permissions granted to the router, with the router last in its execution path, and send it as `API-KEY`.
With it the router MAY call `GET /v1/user/info` and deployments open to per-request keys. Their usage SHALL count in
the served pass's `statistics.usage_per_model`. Core SHALL invalidate the key when the decide call ends.

#### Scenario: Router asks a model while deciding
- **WHEN** the router, on receiving the decide request, calls `POST /openai/deployments/m1/chat/completions` with
  the decide request's `API-KEY`, and then answers `{"candidates": ["gpt-4"]}`
- **THEN** the router's call is served, its analytics row has `parent_deployment: "r1"` and no `decision`, the
  decide request's key differs from the key the served `gpt-4` pass receives, and the client's non-streamed answer
  has `m1` in `statistics.usage_per_model`

#### Scenario: Deployment closed to per-request keys
- **WHEN** the router calls, with the decide request's `API-KEY`, a deployment whose features set
  `accessibleByPerRequestKey: false`
- **THEN** Core answers that call `403`

#### Scenario: Key gone after the decision
- **WHEN** the decide call has ended and the router calls `GET /v1/user/info` with the decide request's `API-KEY`
- **THEN** Core answers `401`

## MODIFIED Requirements

### Requirement: Routers absent from other listings
A router SHALL NOT appear in `GET /openai/models`, `GET /openai/deployments`, `GET /openai/applications` or
`GET /anthropic/v1/models`.

#### Scenario: Router hidden from the model and deployment listings
- **WHEN** the config declares `routers.r1` with `interfaces.anthropicMessages: {}` and a caller sends `GET /openai/models`,
  `GET /openai/deployments` and `GET /anthropic/v1/models`
- **THEN** no listing holds an entry with id `r1`

### Requirement: Decide request
For a request to a router whose interface is in decision mode, Core SHALL send `POST <base_url>` with the client's
headers as it forwards them to any origin, the router's `defaultHeaders` for the interface where the client sent
none, `X-DIAL-ROUTER` (router name), `X-DIAL-API` (interface type) and `API-KEY` (the decide request key), and an
empty body, or the client's request bytes unchanged when `context.body` is `true`.

#### Scenario: Headers and empty body
- **WHEN** `routers.r1` declares `defaultHeaders: {"X-DIAL-ROUTER-TIER": "medium"}` and
  `interfaces.openaiChatCompletions: {"mode": "decision", "base_url": "http://router/decide"}`, and a client with
  project key `p1` sends `POST /openai/deployments/r1/chat/completions` with `X-CONVERSATION-ID: c1`
- **THEN** the router receives `POST http://router/decide` with `X-CONVERSATION-ID: c1`, `X-DIAL-ROUTER-TIER: medium`,
  `X-DIAL-ROUTER: r1`, `X-DIAL-API: openaiChatCompletions`, an `API-KEY` that is not `p1`'s key, no
  `X-DIAL-USER-INFO` and an empty body, and the model that serves the request receives no `X-DIAL-ROUTER-TIER` header

#### Scenario: Body on request
- **WHEN** the same interface declares `context: {"body": true}` and the client sends body `{"model":"r1","messages":[]}`
- **THEN** the decide request's body is exactly `{"model":"r1","messages":[]}` with `Content-Type: application/json`

### Requirement: Verdict
Core SHALL accept a `200` decide answer whose JSON body has `candidates`, a non-empty array of deployment names in
order of preference; it SHALL ignore unknown fields. Any other `200` body SHALL be answered `502`.

#### Scenario: Shorthand accepted
- **WHEN** the router answers a one-element list with an unknown field, `{"candidates": ["gpt-4"], "ttl": 60}`
- **THEN** Core serves the request with `gpt-4`

#### Scenario: Schema mismatch
- **WHEN** the router answers `{"candidates": []}`, then `{"deployment": "gpt-4"}`, then `{"model": "gpt-4"}`, then
  `not json`
- **THEN** Core answers `502` each time and sends nothing to any deployment

### Requirement: Decision failures
Core SHALL answer `504` when the decide request exceeds `decisionTimeoutMs` (connecting included), `502` when the
router is unreachable, answers `5xx`, or names a candidate that does not exist or is a router, or when the router is
re-entered through a deployment it chose, and SHALL relay a router `4xx` status and body to the client. In every
case Core SHALL send nothing to any candidate and SHALL write no analytics row for the request.

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
For a request served through a decision, a background Responses job included, Core SHALL write one analytics row
for the request, for the deployment that served, with the router before it in `execution_path`, `parent_deployment` the router, and a
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

## REMOVED Requirements

### Requirement: Identity header for a router
**Reason**: Review of PR #2232. The header was a non-standard identity contract whose safety depended on the router
being reachable from Core only; the standard path is the router calling `GET /v1/user/info` with its key.
**Migration**: A router reads the caller's identity from `GET /v1/user/info` with the `API-KEY` Core sends on every
request, the decide request included (requirement "Router identity through user info").
