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

### Requirement: Decision mode
Core SHALL read a router's `mode`, `proxy` (the default when absent) or `decision`. In decision mode every interface the
router declares is decided at its decide URL: the interface's `base_url`, or the router's `baseUrl` when it declares
none, followed by `/decide`. No interface entry of any deployment SHALL accept `mode: decision`.

#### Scenario: Decision mode on an interface entry rejected
- **WHEN** an admin sends `PUT /v1/applications/platform/a` with `interfaces.openaiChatCompletions: {"mode": "decision"}`
- **THEN** Core answers `400` and stores nothing

#### Scenario: Decide URL per interface
- **WHEN** `routers.r3` declares `baseUrl: http://router`, `mode: decision`,
  `interfaces.openaiChatCompletions: {"base_url": "http://router/body"}` and `interfaces.openaiResponses: {}`
- **THEN** a chat completion for `r3` is decided by `POST http://router/body/decide` and a Responses request for `r3`
  by `POST http://router/decide`, and neither request is forwarded to the router

### Requirement: Decision settings
Core SHALL read a router's optional `decision` object with `body` (default `false`), `timeoutMs` (default 5000, must be
positive) and `maxDecisions` (see Decision limit), and SHALL reject it on a router not in decision mode (`decision
requires mode 'decision'`). On a router in decision mode Core SHALL reject a translated interface (`A router in mode
'decision' serves no interface through a translator`) and an embedded one (`... through a plugin`).

#### Scenario: Decision settings without decision mode rejected
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `decision: {"maxDecisions": 2}` and no `mode`
- **THEN** the config is rejected with a message containing `decision requires mode 'decision'`

#### Scenario: Translated interface on a decision router rejected
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `mode: decision` and
  `interfaces.anthropicMessages: {"mode": "translator", "translator": {"out": "openaiChatCompletions", "baseUrl": "http://t"}}`
- **THEN** the config is rejected with a message containing `A router in mode 'decision' serves no interface through a
  translator`

#### Scenario: Embedded interface on a decision router rejected
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `mode: decision` and
  `interfaces.anthropicMessages: {"mode": "embedded", "plugin": "p1"}`
- **THEN** the config is rejected with a message containing `A router in mode 'decision' serves no interface through a
  plugin`

### Requirement: Decide request
For a request to a router in decision mode, Core SHALL send `POST` to the interface's decide URL with the client's
headers as it forwards them to any origin, the router's `defaultHeaders` for the interface where the client sent
none, `X-DIAL-ROUTER` (router name), `X-DIAL-API` (interface type) and `API-KEY` (the decide request key), and an
empty body, or the client's request bytes unchanged when `decision.body` is `true`.

#### Scenario: Headers and empty body
- **WHEN** `routers.r1` declares `baseUrl: http://router`, `mode: decision`,
  `defaultHeaders: {"X-DIAL-ROUTER-TIER": "medium"}` and `interfaces.openaiChatCompletions: {}`, and a client with
  project key `p1` sends `POST /openai/deployments/r1/chat/completions` with `X-CONVERSATION-ID: c1`
- **THEN** the router receives `POST http://router/decide` with `X-CONVERSATION-ID: c1`, `X-DIAL-ROUTER-TIER: medium`,
  `X-DIAL-ROUTER: r1`, `X-DIAL-API: openaiChatCompletions`, an `API-KEY` that is not `p1`'s key and an empty body,
  and the model that serves the request receives no `X-DIAL-ROUTER-TIER` header

#### Scenario: Body on request
- **WHEN** the router declares `decision: {"body": true}` and the client sends body `{"model":"r1","messages":[]}`
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
the interface or does not exist is skipped before any call; one that fails to connect, answers a retriable status
after its upstream retries, or whose `200` answer breaks (not for a background job) is skipped while no byte reached
the client. Then Core decides again (Re-decide after the candidates). No fallback after a non-retriable answer or an
interceptor hand-off.

#### Scenario: Forbidden first candidate skipped
- **WHEN** the router answers `{"candidates": ["private-model", "gpt-4"]}` and the caller's roles do not admit
  `private-model`
- **THEN** `gpt-4` serves the request and nothing is sent to `private-model`

#### Scenario: Failing first candidate skipped
- **WHEN** the router answers `{"candidates": ["m1", "m2"]}` and `m1`'s only upstream answers `503`
- **THEN** `m2` serves the request, the client receives `200`, and one analytics row is written, for `m2`

#### Scenario: Every candidate forbidden
- **WHEN** the router answers `{"candidates": ["private-a", "private-b"]}` every time and the caller may use neither
- **THEN** the router receives three decide requests and Core answers `403`

#### Scenario: Non-retriable answer relayed
- **WHEN** the router answers `{"candidates": ["m1", "m2"]}` and `m1` answers `400` with a JSON error
- **THEN** the client receives that `400` and nothing is sent to `m2`

#### Scenario: Broken body before the client got a byte
- **WHEN** the router answers `{"candidates": ["m1", "m2"]}` for a non-streamed chat completion, and `m1` sends `200`
  headers and drops the connection in the middle of its body
- **THEN** `m2` serves the request, the client receives `200`, and the row's `decision.attempts` holds
  `{"deployment": "m1", "status": 502, "reason": "body", "received": true}`

#### Scenario: Broken stream before its first chunk
- **WHEN** the router answers `{"candidates": ["m1", "m2"]}` for a streamed chat completion, and `m1` sends `200`
  `text/event-stream` headers and drops the connection before any event
- **THEN** `m2`'s stream reaches the client with one `Content-Type` header

### Requirement: Decision failures
For the first decide request of a request, Core SHALL answer `504` when it exceeds `decision.timeoutMs` (connecting included), `502` when the
router is unreachable, answers `5xx`, or names a candidate that does not exist or is a router, or when the router is
re-entered through a deployment it chose, and SHALL relay a router `4xx` status and body to the client. In every
case Core SHALL send nothing to any candidate and SHALL write no analytics row for the request.

#### Scenario: Slow router
- **WHEN** the router declares `decision: {"timeoutMs": 200}` and takes 2 s to answer
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
- **THEN** that call is answered `502` without a decide request of its own, and the router's next decision sees
  `app` failed with `502`

### Requirement: Decision record
For a request the router answered with a verdict, a background job included, Core SHALL also write a router row:
`deployment` the router, `parent_deployment` and `execution_path` as for a direct call to it, the client's
`response.status`, no bodies or upstream, and a `decision` object with `router`, `api`, `candidates`, `deployment`
(the one that served), `decide_ms` and `attempts`, one per earlier try with Core's answer as `status` (see Decision
record across decisions).

#### Scenario: Row of a decided request
- **WHEN** the router `r1` answers `{"candidates": ["m1", "m2"]}`, `m1` is skipped for a `503` and `m2` serves
- **THEN** the `m2` row has `execution_path: ["r1", "m2"]`, `parent_deployment: "r1"` and no `decision`, and the router
  row has `deployment: "r1"`, `execution_path: ["r1"]`, no `parent_deployment`, no bodies, `decision.router: "r1"`,
  `decision.api: "openaiChatCompletions"`, `decision.candidates: ["m1", "m2"]`, `decision.deployment: "m2"`, a numeric
  `decision.decide_ms` and `decision.attempts: [{"deployment": "m1", "status": 502, "reason": "upstream"}]`

#### Scenario: Row of a decided background job
- **WHEN** a client sends `POST /openai/v1/responses` with `{"model": "r3", "background": true, "input": "hi"}`, the
  router `r3` answers `{"candidates": ["gpt-4"]}`, the origin accepts the job and later answers the poll with
  `status: completed`
- **THEN** the create is served by `gpt-4` with the dial id replaced as for any background create, and when the job
  ends Core writes the `gpt-4` row, with `execution_path: ["r3", "gpt-4"]` and `parent_deployment: "r3"`, and the
  router row, with `deployment: "r3"`, `decision.api: "openaiResponses"` and `decision.deployment: "gpt-4"`

#### Scenario: Router row of a candidate behind an interceptor
- **WHEN** the router `r1` answers `{"candidates": ["m-int"]}`, `m-int` has `interceptors: ["i2"]` and `i2` calls
  Core back
- **THEN** Core writes one router row, with `decision.deployment: "m-int"`, and one `m-int` row, with
  `execution_path: ["r1", "i2", "m-int"]`, `parent_deployment: "r1"` and no `decision`

#### Scenario: Router row after a global interceptor
- **WHEN** the config declares `globalInterceptors: ["i1"]`, a client calls `r1` and the router answers
  `{"candidates": ["gpt-4"]}`
- **THEN** the router row has `execution_path: ["i1", "r1"]` and no `parent_deployment`

#### Scenario: Router row of a secured key
- **WHEN** a client with a secured key calls `r1` and the router answers `{"candidates": ["gpt-4"]}`
- **THEN** Core writes both rows, and the router row carries the `decision` object

#### Scenario: Router row when every candidate fails
- **WHEN** the router `r-two`, with `decision: {"maxDecisions": 2}`, answers `{"candidates": ["m1"]}` to both decide requests and
  `m1` answers `503` each time
- **THEN** the client receives `502` and Core writes only the router row, with `response.status: "502"`,
  `decision.decisions: 2`, `decision.deployment: "m1"` and `decision.attempts: [{"deployment": "m1", "status": 502,
  "reason": "upstream"}]`, the last try being the one `deployment` names

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

#### Scenario: Proxied request
- **WHEN** a client sends `POST /openai/deployments/r3/chat/completions` to the typed router `r3` whose properties
  are `{"tier": "medium", "preferredModels": ["gpt-4"], "undeclared": 1}` and whose schema leaves
  `additionalProperties` unset
- **THEN** the router receives `X-DIAL-APPLICATION-PROPERTIES` whose JSON equals
  `{"tier": "medium", "preferredModels": ["gpt-4"]}`

#### Scenario: Decide request
- **WHEN** the typed router is in decision mode and its verdict names `gpt-4`
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

### Requirement: Router properties kept to the router
Core SHALL NOT send `X-DIAL-APPLICATION-PROPERTIES` to a translator in front of a router interface (the router receives
it when the translator calls Core back), on a Responses item call (as for an application), or to the deployment a
decision chose (the properties are the router's). Core SHALL drop a client-sent header of that name, as for every
deployment.

#### Scenario: Client-sent properties dropped
- **WHEN** a client sends `X-DIAL-APPLICATION-PROPERTIES: {"tier": "premium"}` to the typed router `r3`
- **THEN** the router receives only the header Core built from `r3`'s properties

#### Scenario: Translator in front of a typed router
- **WHEN** a typed router serves `anthropicMessages` through a translator and a client sends a Messages request to it
- **THEN** the translator's request carries no `X-DIAL-APPLICATION-PROPERTIES`

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
A router SHALL get the caller's identity from `GET /v1/user/info` called with the `API-KEY` Core sent it; Core sends
it no identity header of its own.

#### Scenario: Project key caller's identity through the key
- **WHEN** a client with project key `p1` sends `POST /openai/deployments/r1/chat/completions`, and the router calls
  `GET /v1/user/info` with the `API-KEY` it received
- **THEN** the user info answer has `project: "p1"`

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

### Requirement: Decide request key reads the attachments
When the decide request carries the client's body, its key SHALL also grant read access to the files and folders the
body attaches, as the chosen deployment's key does, and Core SHALL answer `403` without a decide request when the
caller may not read one of them.

#### Scenario: Router reads an attachment while deciding
- **WHEN** the router declares `decision: {"body": true}`, the client attaches a file of its own bucket to a chat
  completion, and the router reads that file with the decide request's `API-KEY`
- **THEN** Core answers the read `200`

### Requirement: Decision limit
A router's `decision` object SHALL accept an optional `maxDecisions`, the number of decide requests one client request
may need, the first included, from 1 to 10; it defaults to `3`. Core SHALL reject a value outside that range with
`decision.maxDecisions must be between 1 and 10`.

#### Scenario: Limit out of range rejected
- **WHEN** the config declares `routers.r1` with `mode: decision` and `decision: {"maxDecisions": 0}`, or the same with
  `"maxDecisions": 11`
- **THEN** the config is rejected with a message containing `decision.maxDecisions must be between 1 and 10`

### Requirement: Re-decide after the candidates
When every candidate of a verdict has failed, the client is still connected and fewer than `decision.maxDecisions` decide
requests were sent, Core SHALL send another decide request, built as the first one with a new key, plus
`X-DIAL-ROUTER-ATTEMPTS`, the JSON array of every attempt so far, and try the new verdict's candidates in the same
way, a candidate that already failed included. When the re-decide fails or no decision is left, Core SHALL answer
the last candidate's failure.

#### Scenario: Router decides again with the history
- **WHEN** `r1`'s first verdict is `{"candidates": ["m1"]}`, `m1`'s only upstream answers `503`, and its second verdict
  is `{"candidates": ["m2"]}`
- **THEN** the second decide request carries `X-DIAL-ROUTER-ATTEMPTS: [{"deployment":"m1","status":502,"reason":"upstream"}]`
  and an `API-KEY` that differs from the first decide request's and has `executionPath: ["r1"]`, `m2` serves the
  request and the client receives `200`

#### Scenario: Limit reached
- **WHEN** `decision.maxDecisions` is `2` and both verdicts name only `m1`, whose only upstream answers `503`
- **THEN** the router receives two decide requests, `m1` is called twice, and the client receives `502`

#### Scenario: Re-decide fails
- **WHEN** the first candidate fails with `503` after its upstreams and the router answers the second decide request
  `500`
- **THEN** the client receives the first candidate's `502`, not the router's error

#### Scenario: Refusal in the second verdict skipped
- **WHEN** the second verdict is `{"candidates": ["private-model", "m2"]}` and the caller may not use `private-model`
- **THEN** `m2` serves the request and nothing is sent to `private-model`

#### Scenario: Body sent again
- **WHEN** the router declares `decision: {"body": true}` and Core decides again
- **THEN** the second decide request's body is the client's request bytes, as the first one's was

### Requirement: Router headers set by Core only
Core SHALL drop a client-sent `X-DIAL-ROUTER-ATTEMPTS` or `X-DIAL-ROUTER-NOTICE` from every decide and notice request
and SHALL set them only as this capability states.

#### Scenario: Forged history dropped
- **WHEN** a client sends `X-DIAL-ROUTER-ATTEMPTS: [{"deployment":"x"}]` and `X-DIAL-ROUTER-NOTICE: true` to a
  decision-mode router
- **THEN** the first decide request carries neither header

### Requirement: Mid-stream notice
When a candidate's answer fails after bytes of it reached the client, outside an interceptor hand-off, Core SHALL end
the client's response as for any broken answer and SHALL send the decide URL, without waiting, a notice request
with the decide request's client and default headers, `X-DIAL-ROUTER`, `X-DIAL-API`, `X-DIAL-ROUTER-NOTICE: true` and
`X-DIAL-ROUTER-ATTEMPTS`, no `API-KEY` and an empty body. Core SHALL ignore its answer; it is not a decision.

#### Scenario: Stream broken after the first chunk
- **WHEN** the verdict is `{"candidates": ["m1"]}`, the client streams `POST /openai/deployments/r1/chat/completions`,
  and `m1` sends one SSE chunk and then drops the connection
- **THEN** the client receives that chunk and a broken connection, and the router receives a notice request with no
  `API-KEY` and an empty body whose `X-DIAL-ROUTER-ATTEMPTS` ends with
  `{"deployment":"m1","status":200,"reason":"stream","received":true,"partial":true}`

#### Scenario: Client gone, no notice
- **WHEN** the client closes its connection in the middle of the streamed answer
- **THEN** the router receives no notice and no second decide request

### Requirement: Serving row of a decided request
The row of the deployment that served a decided request SHALL have no `decision` object and, unless an interceptor
chain served it, the router row's span as its parent span.

#### Scenario: Serving row is the router row's child
- **WHEN** the router `r1` answers `{"candidates": ["gpt-4"]}` and `gpt-4` serves
- **THEN** the `gpt-4` row's `trace.core_parent_span_id` equals the router row's `trace.core_span_id`, and the router
  row has no `trace.core_parent_span_id`

### Requirement: Decision record across decisions
For a request that needed more than one decide request, the router row's `decision` object SHALL carry `decisions`,
the number of decide requests sent; `candidates` SHALL list every verdict's candidates in order and `decide_ms` SHALL
be their sum. An attempt SHALL carry `received: true` when the candidate's upstream had answered; the field is left
out when false, and `decisions` is left out for a request decided once.

#### Scenario: Row of a re-decided request
- **WHEN** the first verdict `{"candidates": ["m1"]}` fails on `m1`'s `503` and the second verdict `{"candidates":
  ["m2"]}` serves
- **THEN** the router row has `decision.decisions: 2`, `decision.candidates: ["m1", "m2"]`,
  `decision.deployment: "m2"` and `decision.attempts: [{"deployment": "m1", "status": 502, "reason": "upstream"}]`,
  and one `m2` row is written

### Requirement: Router row cost
The router row SHALL show the cost of the request below it: `token_usage.price` the cost of the deployment that served
and of every call under it, calls the router made while deciding included, and `usage_per_model` those calls, the
deployment that served included. Its token counts SHALL be zero, and it SHALL have no `deployment_price`. Core SHALL
NOT add the cost to an aggregated-cost record of the router, and the client's `statistics.usage_per_model` SHALL be
unchanged.

#### Scenario: Router row shows the cost under it
- **WHEN** the router `r1` answers `{"candidates": ["gpt-4"]}` and `gpt-4` serves with a priced usage
- **THEN** the router row's `token_usage.price` equals the `gpt-4` row's `token_usage.price`, its `usage_per_model`
  lists `gpt-4`, its `total_tokens` is `0`, and `r1` has no aggregated-cost record

#### Scenario: Call made while deciding counted on the router row
- **WHEN** the router, while deciding for `r1`, calls `POST /openai/deployments/m1/chat/completions` with the decide
  request's key and then answers `{"candidates": ["m2"]}`
- **THEN** the router row's `token_usage.price` covers the `m1` and `m2` calls and its `usage_per_model` lists both

### Requirement: Router name in the answer
For a request served through a decision, Core SHALL replace the `model` of the answer with the router's name on chat
and legacy completions, Responses (a stored one read back included) and Messages: the top-level `model` of a buffered
answer and of each streamed chunk or event, `response.model` and `message.model` in events, where present. Embeddings,
a buffered legacy completion and a buffered answer relayed through interceptors SHALL keep the upstream's `model`.

#### Scenario: Buffered chat answer names the router
- **WHEN** the router `r1` answers `{"candidates": ["gpt-4"]}` and `gpt-4` answers a buffered chat completion with
  `"model": "gpt-4-real"`
- **THEN** the client receives `"model": "r1"`, and the serving row's `deployment` is `gpt-4`

#### Scenario: Streamed chat answer names the router
- **WHEN** the router `r1` answers `{"candidates": ["gpt-4"]}` and `gpt-4` streams chunks with `"model": "gpt-4-real"`
- **THEN** every chunk the client receives has `"model": "r1"`

#### Scenario: Responses answer names the router
- **WHEN** a client sends `POST /openai/v1/responses` with `{"model": "r3", "input": "hi"}`, the router answers
  `{"candidates": ["gpt-4"]}` and `gpt-4` answers with `"model": "gpt-4-real"`
- **THEN** the client receives `"model": "r3"`

#### Scenario: Stored Responses answer read back names the router
- **WHEN** a client creates a response through `r3` with `"store": true`, `gpt-4` answers with `"model": "gpt-4-real"`,
  and the client then sends `GET /openai/v1/responses/{id}`
- **THEN** the answer to the `GET` has `"model": "r3"`

#### Scenario: Streamed Messages answer names the router
- **WHEN** a client streams `POST /anthropic/v1/messages` with `"model": "r1"`, the router answers
  `{"candidates": ["claude"]}` and `claude`'s `message_start` event carries `"message": {"model": "claude-real"}`
- **THEN** the client's `message_start` event carries `"message": {"model": "r1"}`

#### Scenario: Buffered Messages answer names the router
- **WHEN** the router `r1` answers `{"candidates": ["claude"]}` and `claude` answers a buffered message with
  `"model": "claude-real"`
- **THEN** the client receives the whole message with `"model": "r1"`

#### Scenario: Streamed answer through the candidate's interceptor names the router
- **WHEN** the router `r1` answers `{"candidates": ["m-int"]}`, `m-int` has `interceptors: ["i2"]`, and the client
  streams the answer, whose chunks carry `"model": "m-int-real"`
- **THEN** every chunk the client receives has `"model": "r1"`

#### Scenario: Embeddings keep the upstream model
- **WHEN** the router answers `{"candidates": ["ada"]}` for `POST /openai/deployments/r1/embeddings` and `ada` answers
  with `"model": "ada-real"`
- **THEN** the client receives `"model": "ada-real"`

#### Scenario: Undecided answer unchanged
- **WHEN** a client calls `gpt-4` directly and it answers with `"model": "gpt-4-real"`
- **THEN** the client receives `"model": "gpt-4-real"`
