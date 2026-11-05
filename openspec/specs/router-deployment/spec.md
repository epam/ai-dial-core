# router-deployment Specification

## Purpose
A router is a deployment that picks the deployment serving a request. This capability covers what a router is in the
dynamic config, how its name and interfaces are validated, how it is listed, and how a request to it is served and
accounted, whether the router proxies the request itself or, in decision mode, answers a verdict and Core serves
the chosen deployment.

## Requirements

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
A router SHALL NOT appear in `GET /openai/models`, `GET /openai/deployments`, `GET /openai/applications` or
`GET /anthropic/v1/models`.

#### Scenario: Router hidden from the model and deployment listings
- **WHEN** the config declares `routers.r1` with `interfaces.anthropicMessages: {}` and a caller sends `GET /openai/models`,
  `GET /openai/deployments` and `GET /anthropic/v1/models`
- **THEN** no listing holds an entry with id `r1`

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

### Requirement: Router type schema and properties
A router SHALL accept `applicationTypeSchemaId` (an app-type schema `$id`) and `applicationProperties` (an object),
with the names and aliases an application accepts, SHALL leave both out of the stored JSON when unset, and SHALL
reject, on load and on a Configuration API write, a router that sets one of the two without the other, with a
message containing `A router declares applicationTypeSchemaId and applicationProperties together`.

#### Scenario: Typed router loads
- **WHEN** the config declares the schema `https://dial.test/router-type` and `routers.r3` with that
  `applicationTypeSchemaId`, `applicationProperties: {"tier": "medium", "preferredModels": ["gpt-4"]}`, `baseUrl`
  and `interfaces.openaiChatCompletions: {}`
- **THEN** the config loads and `r3` resolves as a deployment

#### Scenario: Properties without a schema rejected
- **WHEN** an admin sends `PUT /v1/routers/platform/r-bad` with `applicationProperties: {"tier": "medium"}` and no
  `applicationTypeSchemaId`
- **THEN** Core answers `422` with a warning containing
  `A router declares applicationTypeSchemaId and applicationProperties together`, and writes nothing

#### Scenario: Untyped router stored without the fields
- **WHEN** an admin sends `PUT /v1/routers/platform/r-plain` with `baseUrl` and `interfaces.openaiChatCompletions: {}`
- **THEN** the stored blob has neither an `applicationTypeSchemaId` nor an `applicationProperties` key

### Requirement: Router properties validated against the schema
Core SHALL validate a typed router's `applicationProperties` against its schema on the file load (against the
file's schemas, as for an application) and on every Configuration API write (against the merged config plus a
batch's earlier `Schema` entries), and SHALL reject the file, or answer `422` or fail the manifest entry and write
nothing, when the schema is unknown or the properties do not validate, whatever the soft-validation mode.

#### Scenario: Unknown schema
- **WHEN** an admin sends `PUT /v1/routers/platform/r-bad` with `applicationTypeSchemaId: "https://dial.test/none"`
  and `applicationProperties: {"tier": "medium"}`
- **THEN** Core answers `422` with a warning on `applicationTypeSchemaId` and `GET /v1/routers/platform/r-bad`
  answers `404`

#### Scenario: Properties that break the schema
- **WHEN** the schema `https://dial.test/router-type` requires the string `tier` and an admin sends
  `PUT /v1/routers/platform/r-bad` with that schema and `applicationProperties: {"tier": 5}`
- **THEN** Core answers `422` with a warning on `applicationProperties`

#### Scenario: File router that breaks its schema
- **WHEN** the config file declares the schema `https://dial.test/router-type` and a router with that schema and
  `applicationProperties: {"tier": 5}`
- **THEN** the file load fails

#### Scenario: Schema and router in one batch
- **WHEN** an admin applies, in this input order, a `Router` using the schema `https://dial.test/batch-type` and the
  `Schema` with that `$id`
- **THEN** Core answers `200` with both `APPLIED`

### Requirement: Router properties sent to the router
For a router with `applicationTypeSchemaId`, Core SHALL send `X-DIAL-APPLICATION-PROPERTIES`, the JSON of the
properties the schema declares taken from `applicationProperties`, on requests to the router's own interfaces and
feature endpoints and on decide requests, unless the schema sets `dial:appendApplicationPropertiesHeader: false`.
A translator in front of a router interface, a Responses item call and the deployment a decision chose SHALL NOT
receive it.

#### Scenario: Proxied request
- **WHEN** a client sends `POST /openai/deployments/r3/chat/completions` to the typed router `r3` whose properties
  are `{"tier": "medium", "preferredModels": ["gpt-4"], "undeclared": 1}` and whose schema leaves
  `additionalProperties` unset
- **THEN** the router receives `X-DIAL-APPLICATION-PROPERTIES` whose JSON equals
  `{"tier": "medium", "preferredModels": ["gpt-4"]}`

#### Scenario: Decide request
- **WHEN** the typed router's `openaiChatCompletions` interface is in decision mode and its verdict names `gpt-4`
- **THEN** the decide request carries the same `X-DIAL-APPLICATION-PROPERTIES`, and the request `gpt-4` receives
  carries none

#### Scenario: Feature endpoint
- **WHEN** a typed router declares `features.rateEndpoint` and a client sends `POST /v1/r3/rate`
- **THEN** the rate request the router receives carries `X-DIAL-APPLICATION-PROPERTIES`

#### Scenario: Header turned off
- **WHEN** the typed router's schema sets `dial:appendApplicationPropertiesHeader: false`
- **THEN** the router receives no `X-DIAL-APPLICATION-PROPERTIES`

#### Scenario: Router without a schema
- **WHEN** a client calls router `r1`, which declares no `applicationTypeSchemaId`
- **THEN** the router receives no `X-DIAL-APPLICATION-PROPERTIES`

### Requirement: Router properties that no longer match
Core SHALL build a typed router's properties header once per request, before it connects to the router, and SHALL
answer `502` without sending anything to the router when the schema is gone or the stored properties no longer
validate against it, in proxy and in decision mode.

#### Scenario: Schema changed under a stored router
- **WHEN** a typed router is stored valid, its schema is then replaced by one that requires a property the router
  lacks, and a client calls the router
- **THEN** Core answers `502` and the router receives no request

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
