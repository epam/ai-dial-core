# app-to-model-accounting Specification

## Purpose
What happens when a deployment (application, interceptor, route target) calls Core back with the per-request key
Core sent it as `API-KEY`: what the key carries, how long it lives, how the child request inherits the
initiator's identity, trace and limits, and how the usage of every Core pass in the call tree is recorded and rolled
up to its ancestors. The origin request itself is the deployment-origin capability; this one covers the key's
lifecycle, the limits charged, the accounting records and the `statistics.usage_per_model` Core writes into a chat
completion answer. The buckets a per-request key sees (`GET /v1/bucket`) and the access its grants carry are the
resource-access-control capability; the background job that holds a Responses key is the responses-api capability.

## Requirements

### Requirement: One per-request key per Core pass
Core SHALL mint a fresh 32-character random key for every pass that forwards a request to an origin, other than to
a route upstream with its own `key`, store its record as a Redis string under `api_key_data:` +
`[<storage prefix>/]api_key_data/api_key_data/<key>` with the TTL `perRequestApiKey.ttl` (seconds, default 1800),
and send it to the origin as `API-KEY`. A background Responses request stores its key with the
`backgroundJob.jobTtlMs` TTL instead.

#### Scenario: Key stored with the default TTL
- **WHEN** a client sends `POST /openai/deployments/app/chat/completions` and settings have no `perRequestApiKey`
- **THEN** the origin request carries `API-KEY: <k>` where `<k>` is 32 characters, and Redis holds
  `api_key_data:api_key_data/api_key_data/<k>` with a TTL of at most 1800 s

#### Scenario: Each pass gets its own key
- **WHEN** the application calls `POST /openai/deployments/gpt-4/chat/completions` with `API-KEY: <k>`
- **THEN** the model's origin request carries `API-KEY: <k2>` with `<k2>` different from `<k>`

#### Scenario: Route upstream with its own key
- **WHEN** a route's only upstream declares `key: k1` and a client calls the route
- **THEN** the upstream request carries `k1` and Redis holds no new `api_key_data` record

### Requirement: Key TTL reset on update
Core SHALL reset a key's TTL to `perRequestApiKey.ttl` whenever it updates the key's record (a per-request share
granted with the key, attachments auto-shared from an answer), a background Responses key's included.

#### Scenario: Grant shortens a background key
- **WHEN** an application called by a background Responses request sends
  `POST /v1/ops/resource/per-request-permissions/grant` with the key it received, and settings have no
  `perRequestApiKey`
- **THEN** the key's Redis record has a TTL of at most 1800 s

### Requirement: Key contents for a root request
For a request authenticated by a project API key or a JWT, Core SHALL write into the key it mints: the client's
project key as `originalKey` (none for a JWT), the JWT claims as `extractedClaims`, Core's trace id as `traceId`,
the server span of this pass as `spanId`, the deployment (or route) name as `sourceDeployment`, `executionPath`
`[<deployment>]`, and `httpHeaders` holding `X-CONVERSATION-ID` and `X-JOB-TITLE` when the client sent them.

#### Scenario: Root key record
- **WHEN** a user with a JWT whose `sub` is `u1` sends `X-CONVERSATION-ID: c1` to
  `POST /openai/deployments/app/chat/completions`, Core's span is `s1` in trace `t1`
- **THEN** the key record has `traceId: "t1"`, `spanId: "s1"`, `sourceDeployment: "app"`, `executionPath: ["app"]`,
  the `u1` claims and `httpHeaders: {"X-CONVERSATION-ID": "c1"}`

### Requirement: Key contents for a child request
For a request authenticated by a per-request key, Core SHALL copy `originalKey`, `extractedClaims` and `traceId`
from the incoming key into the key it mints, copy `httpHeaders` from the incoming key when it holds any (else collect
them from this request as for a root), set `spanId` to this pass's span, `sourceDeployment` to the called deployment
and `executionPath` to the incoming path plus that deployment.

#### Scenario: Execution path grows
- **WHEN** `app` calls `POST /openai/deployments/gpt-4/chat/completions` with the key whose `executionPath` is `["app"]`
- **THEN** the key Core sends to `gpt-4`'s origin has `executionPath: ["app", "gpt-4"]`,
  `sourceDeployment: "gpt-4"` and the same `traceId`, `originalKey` and claims as the incoming key

#### Scenario: Headers collected when the incoming key has none
- **WHEN** the root client sent no `X-CONVERSATION-ID`, and `app` calls `gpt-4` with its key and
  `X-CONVERSATION-ID: c2`
- **THEN** the key Core sends to `gpt-4`'s origin has `httpHeaders: {"X-CONVERSATION-ID": "c2"}`

### Requirement: Interceptor state in the key
When Core forwards to an interceptor, the key it mints SHALL carry the deployment's interceptor list as
`interceptors`, the position of the called interceptor as `interceptorIndex` and the deployment the client called as
`initialDeployment`, so the interceptor's call to `POST /openai/deployments/interceptor/...` reaches the next
interceptor and, after the last, the initial deployment.

#### Scenario: Next interceptor
- **WHEN** deployment `m` has interceptors `["i1", "i2"]` and `i1` sends
  `POST /openai/deployments/interceptor/chat/completions` with the key it received (`interceptorIndex: 0`)
- **THEN** Core forwards to `i2` with a key carrying `interceptorIndex: 1`, `interceptors: ["i1", "i2"]` and
  `initialDeployment: "m"`

### Requirement: Attached resources and per-request shares in the key
Core SHALL record in the key the files, folders, deployments, prompts, skills and resource credentials the request
attaches (`attachedFiles`, `attachedFolders`, `attachedDeployments`, `attachedPrompts`, `attachedSkills`,
`attachedResourceCredentials`) and copy into `perRequestSharedResources` the incoming key's `perRequestReceivers`
entries for the called deployment and for `initialDeployment`.

#### Scenario: Attachment readable by the deployment
- **WHEN** a user's request to `app` attaches `files/<bucket>/a.txt` the user can read
- **THEN** `app` can `GET /v1/files/<bucket>/a.txt` with the key it received while the request runs

#### Scenario: Resource shared to the next deployment
- **WHEN** the incoming key's `perRequestReceivers` maps `gpt-4` to resource `r`, and the request calls `gpt-4`
- **THEN** the key sent to `gpt-4` has `r` in `perRequestSharedResources`

### Requirement: Authentication by a per-request key
Core SHALL accept a per-request key in `API-KEY` alone or with any `Authorization` header, taking the identity from
the key rather than the JWT, and SHALL NOT apply the original key's `allowedIpAddressRanges`. A project key in
`API-KEY` with a different `Authorization` value SHALL be answered `400` `Provided API key is not per request key`.

#### Scenario: Key with an unrelated Authorization header
- **WHEN** an application sends `API-KEY: <k>` and `Authorization: Bearer <other>`
- **THEN** the request is authenticated as the initiator recorded in `<k>`

#### Scenario: Project key with a foreign Authorization header
- **WHEN** a client sends `API-KEY: proxyKey1` (a project key) and `Authorization: Bearer xyz`
- **THEN** Core answers `400` with body `Provided API key is not per request key`

### Requirement: Key invalidated when its pass ends
Core SHALL delete the key it minted when the pass that minted it ends, whether the answer completed or an error was
answered; for a background Responses request whose origin accepted the job, the key SHALL be deleted when the job
ends instead, except as Client gone from an application states. A request with a deleted or expired key in
`API-KEY` SHALL be answered `401` with body `Unknown api key`.

#### Scenario: Key rejected after the answer
- **WHEN** `app` has answered its request, the key's Redis record is gone (Core deletes it after the answer is
  sent), and `app` sends `POST /openai/deployments/gpt-4/chat/completions` with that key
- **THEN** Core answers `401` with body `Unknown api key` and nothing is sent to `gpt-4`

#### Scenario: Key deleted on an error answer
- **WHEN** the origin answers `400`
- **THEN** the Redis record of the key Core sent it no longer exists

### Requirement: Child request context
For a request authenticated by a per-request key, Core SHALL use the key's `traceId` as the trace id, the key's
`spanId` as the parent span, the key's `sourceDeployment` as the calling deployment, and the user id, roles and
project from the key's claims, or the roles and project of its `originalKey` when it has no claims.

#### Scenario: Parent span from the key
- **WHEN** `app`, called in Core span `s1` of trace `t1`, calls `gpt-4` with its key
- **THEN** the analytics row of the `gpt-4` pass has `trace.trace_id: "t1"` and `trace.core_parent_span_id: "s1"`

### Requirement: Limits charged to the initiator
Core SHALL check and charge the token, request and cost limits of a `Model`, called by a client or with a
per-request key, against the initiator's bucket (`Users/<sub>/` for a JWT, `Keys/<project>/` for a project key) and
the initiator's roles; the calling deployment's own bucket is never charged. Only a `Model`'s answer is charged
token and cost usage.

#### Scenario: Initiator over quota
- **WHEN** user `u1` has used the `gpt-4` per-minute token limit of its role, and `app`, called by `u1`, calls `gpt-4`
- **THEN** Core answers `app`'s call `429` and nothing is sent to `gpt-4`

#### Scenario: Usage charged to the user
- **WHEN** `app`, called by user `u1`, calls `gpt-4` and it answers `200` with `usage.total_tokens: 15`
- **THEN** the `gpt-4/tokens` limit record under `Users/u1/` grows by 15

#### Scenario: Root call charged
- **WHEN** user `u1` calls `gpt-4` directly and it answers `200` with `usage.total_tokens: 15`
- **THEN** the `gpt-4/tokens` limit record under `Users/u1/` grows by 15

### Requirement: Which requests are limit-checked
On deployments-POST Core SHALL limit-check only a `Model`; `POST /openai/v1/chat/completions`,
`/openai/v1/responses` and `/anthropic/v1/messages` SHALL check the limits of the deployment they call whatever its
kind. A request for an interface the deployment serves in `translator` mode SHALL be neither checked nor charged:
the translator's call back to Core is. `/anthropic/v1/messages` alone still refuses it `429` when the initiator's
day, week or month cost limit is already reached.

#### Scenario: Translated interface not charged
- **WHEN** user `u1` has used the `m` per-minute token limit of its role, and `m` serves `anthropicMessages` in
  `translator` mode, and `u1` sends `POST /anthropic/v1/messages` for `m`
- **THEN** Core forwards the request to the translator rather than answering `429`, and answers the translator's
  call back to `POST /openai/deployments/m/chat/completions` `429`

#### Scenario: Application not checked on deployments-POST
- **WHEN** user `u1`'s role declares `limits.app.requestHour: 1` and `u1` has already sent one request to `app` this hour, and `u1` sends
  `POST /openai/deployments/app/chat/completions`
- **THEN** Core forwards the request to `app`

### Requirement: Deployment closed to per-request keys
Core SHALL answer `403` `Deployment <id> is not accessible by <sourceDeployment>` to a deployments-POST,
`/openai/v1/chat/completions`, `/openai/v1/responses` or `/anthropic/v1/messages` request with a per-request key
for a deployment whose features resolved for the interface have `accessibleByPerRequestKey: false`. The last
interceptor's call to the initial deployment and a translator's call back carry such a key, so a deployment with
interceptors, or a translated interface, is refused there.

#### Scenario: Application call refused
- **WHEN** `gpt-4` declares `features.accessibleByPerRequestKey: false` and `app` calls it with its key
- **THEN** Core answers `403` with body `Deployment gpt-4 is not accessible by app`

### Requirement: Consent along the call tree
For a deployment whose resolved features have `consentRequired: true`, Core SHALL read the consent stored under the
initiator's bucket for the root deployment (the first `executionPath` entry for a per-request key, else the
deployment itself) and answer `403` `User didn't accept consent to call the deployment: <id>`: `<id>` is the root
when no consent is stored, else the called deployment when the consent lacks an `executionPath` entry or lacks the
called deployment marked `consentRequired`.

#### Scenario: No consent yet
- **WHEN** user `u1` calls `secured-app` (`consentRequired: true`) without having posted `/v1/consent/secured-app`
- **THEN** Core answers `403` with body `User didn't accept consent to call the deployment: secured-app`

#### Scenario: Child outside the accepted tree
- **WHEN** user `u1`'s stored consent for `A` lists `A`, `B`, `C`, `X` and `Y`, and `D` calls `X`
  (`consentRequired: true`) with a key whose `executionPath` is `["A", "D"]`
- **THEN** Core answers `403` with body `User didn't accept consent to call the deployment: X`, because `D` is not
  in the consent

#### Scenario: Consent behind interceptors
- **WHEN** model `m` (`consentRequired: true`) has interceptors `["i1", "i2"]`, user `u1` has posted only
  `/v1/consent/m`, and `i2` sends `POST /openai/deployments/interceptor/chat/completions` with its key
- **THEN** Core answers `403` with body `User didn't accept consent to call the deployment: i1`, because the root
  of that key's `executionPath` is `i1`

### Requirement: Token-stats span per pass
Core SHALL add each forwarding pass, other than a deployment feature call or a Responses item operation, as a span
`{tokenUsage, parentSpanId, deploymentName, usagePerModel: []}` keyed by its span id to the Redis string
`deployment_cost_stats_tmp:[<prefix>/]<traceId>`, refreshing its TTL
`deploymentStats.ttlMs` (default 3600000, must be positive) on every write; a trace whose root is a background
Responses job uses max(ttlMs, `backgroundJob.jobTtlMs` + `backgroundJob.scanIntervalMs`).

#### Scenario: Child span recorded under the root
- **WHEN** root `app` (span `s1`, trace `t1`) calls `gpt-4` (span `s2`)
- **THEN** `deployment_cost_stats_tmp:t1` holds spans `s1` and `s2`, `s2` with `parentSpanId: "s1"` and
  `deploymentName: "gpt-4"`

#### Scenario: Feature call gets no span
- **WHEN** a client sends `POST /v1/deployments/app/tokenize` and `app` calls `gpt-4` with the key it received
- **THEN** `deployment_cost_stats_tmp:<traceId>` holds the `gpt-4` span and no span for the `tokenize` pass

#### Scenario: Invalid TTL refused
- **WHEN** settings set `deploymentStats.ttlMs: 0`
- **THEN** Core fails to start with `deploymentStats.ttlMs must be positive, but got: 0`

### Requirement: Trace record deleted when the root ends
Core SHALL delete the trace's token-stats record when the root pass ends (or, for a background root, when its job
ends) and SHALL leave it when a child pass ends, except as Client gone from an application states. A trace rooted
in a deployment feature call or a Responses item operation, which add no span, is never deleted and expires with
`deploymentStats.ttlMs`.

#### Scenario: Root ends
- **WHEN** root `app` answers its client
- **THEN** `deployment_cost_stats_tmp:<traceId>` no longer exists

### Requirement: Usage roll-up to the ancestors
When a pass reports usage, Core SHALL store it as that span's own `tokenUsage` (adding the report's `aggCost` to the
span's accumulated `aggCost`),
and for every ancestor span add the report's `aggCost` to the ancestor's `aggCost` and append
`{model: <reporting deployment>, usage}` to its `usagePerModel`, without merging by name and without adding raw
token counts to the ancestor.

#### Scenario: Two reports from the same model
- **WHEN** `app` calls `gpt-4` twice and each answers `200` with `usage.total_tokens: 10` and cost `0.1`
- **THEN** `app`'s span has `aggCost` `0.2`, zero own tokens, and two `usagePerModel` entries for `gpt-4` of 10
  tokens each

### Requirement: Aggregated cost records
For every ancestor span with a deployment name that receives a positive `aggCost` increment, Core SHALL add the
increment to the `<deployment>/aggregated-costs` limit record in the initiator's bucket; it SHALL NOT add it to the
initiator's `costs` record or the ancestor's `<deployment>/costs` record.

#### Scenario: Chain credited per ancestor
- **WHEN** user `u1` calls `router-app`, which calls `inner-app`, which calls `gpt-4` costing `0.40`
- **THEN** `router-app/aggregated-costs` and `inner-app/aggregated-costs` under `Users/u1/` each grow by `0.40`

### Requirement: One analytics row per Core pass
Core SHALL write one analytics row per pass that relays an origin's answer, a `Model` pass whose client disconnected
during the relay included, with `deployment`, `execution_path` from the key it minted, `parent_deployment` set to
the nearest preceding `execution_path` entry that is not one of the deployment's interceptors (absent for a root),
and `trace.trace_id`, `trace.core_span_id` and, for a child, `core_parent_span_id`.

#### Scenario: Parent deployment of a model called by an app
- **WHEN** root `app` calls `gpt-4`
- **THEN** two rows are written: `app` with `execution_path: ["app"]` and no `parent_deployment`, and `gpt-4` with
  `execution_path: ["app", "gpt-4"]` and `parent_deployment: "app"`

#### Scenario: Conversation id carried to the child row
- **WHEN** the client sent `X-CONVERSATION-ID: c1` to `app` and `app` calls `gpt-4` without that header
- **THEN** the `gpt-4` row carries conversation id `c1`

### Requirement: Passes without an analytics row
Core SHALL NOT write an analytics row for a pass that forwards to an interceptor, so usage an interceptor's body
reports is not recorded, nor for these answers Core produces itself: a refusal, `No route`, `Service is not
available`, a connect failure.

#### Scenario: Model behind interceptors
- **WHEN** model `m` has interceptors `["i1", "i2"]` and a client calls `m`
- **THEN** one row is written, for `m`, and none for `i1` or `i2`

#### Scenario: No route
- **WHEN** a model's only upstream answers `503` and `maxRetryAttempts` is 1
- **THEN** the client gets `502` and no row is written

### Requirement: Client gone from an application
When writing to the client fails during the chunked relay of an application's answer (not a `Model` or an
interceptor), Core SHALL reset the origin request and SHALL NOT write the pass's analytics row, delete the key it
minted (it expires with its TTL) or, for a root pass, delete the trace's token-stats record (it expires with
`deploymentStats.ttlMs`). A buffered non-streaming chat completion does not notice a failed write and ends as answered.

#### Scenario: Client disconnects from an application
- **WHEN** a client streams from root `app` and closes its connection mid-stream
- **THEN** no `app` row is written, and the key Core sent `app` and `deployment_cost_stats_tmp:<traceId>` still
  exist in Redis

### Requirement: Usage in the analytics row
Core SHALL write in the row of a `Model` that answered 200 its own `token_usage` (none for another status), with
`deployment_price` and `price` both the DIAL cost when it is positive, else the `cost` and `aggCost` the upstream's
`usage` object carries, if any; in another
deployment's row the usage its body reports (zeros when none) with `price` the span's accumulated `aggCost`; and
`usage_per_model` as the span's entries when there are any.

#### Scenario: Application row
- **WHEN** `app` reports no usage and its `gpt-4` call cost `0.40` with 15 tokens
- **THEN** the `app` row has `token_usage.total_tokens: 0`, `token_usage.price: 0.40` and one `usage_per_model`
  entry `{"index": 0, "model": "gpt-4", "total_tokens": 15, ...}`

### Requirement: usage_per_model in the streamed answer
For a streamed `/chat/completions` answer (deployments-POST or `/openai/v1/chat/completions`: a
`text/event-stream` answer to a request with `stream: true`) Core SHALL remove `statistics.usage_per_model` from
every event the origin sends and, when the span has entries, write before `data: [DONE]` one event
`{"object":"chat.completion.chunk","choices":[],"statistics":{"usage_per_model":[...]}}` whose entries carry
`index`, `model` and token counts but no cost.

#### Scenario: Usage chunk before the terminator
- **WHEN** a client streams from `app`, which called `gpt-4` once using 15 tokens
- **THEN** the event before `data: [DONE]` is
  `{"object":"chat.completion.chunk","choices":[],"statistics":{"usage_per_model":[{"index":0,"model":"gpt-4","prompt_tokens":10,"completion_tokens":5,"total_tokens":15}]}}`

#### Scenario: Nothing to report
- **WHEN** a client streams from a model that calls nothing
- **THEN** no `statistics.usage_per_model` appears in any event

### Requirement: usage_per_model in the non-streamed answer
For a non-streamed `/chat/completions` answer (one whose `Content-Type` is not `text/event-stream`) Core SHALL set
`statistics.usage_per_model` to the span's entries, shaped as in the streamed answer, or remove it (and a
`statistics` object it leaves empty) when there are none. `/completions` and `/embeddings` answers SHALL NOT be
rewritten.

#### Scenario: Non-streamed answer rewritten
- **WHEN** a client sends a non-streaming chat completion to `app`, which called `gpt-4` once using 15 tokens, and
  `app` answers with `statistics.usage_per_model: [{"model": "fake", "total_tokens": 1}]`
- **THEN** the client's body has `statistics.usage_per_model` equal to
  `[{"index":0,"model":"gpt-4","prompt_tokens":10,"completion_tokens":5,"total_tokens":15}]`

#### Scenario: Non-streamed answer stripped
- **WHEN** a model answers a non-streaming chat completion whose body carries `statistics.usage_per_model` and the
  model called nothing
- **THEN** the client's body has no `statistics.usage_per_model`

### Requirement: Answers relayed without the usage_per_model rewrite
Core SHALL relay as it arrives, with neither the removal nor the added event, the answer of a pass that forwards to
an interceptor and a `text/event-stream` answer to a request without `stream: true`.

#### Scenario: Interceptor answer untouched
- **WHEN** model `m` has interceptor `i1`, and `i1` streams an answer to the client whose events carry
  `statistics.usage_per_model`
- **THEN** the client receives those events unchanged and no added `statistics.usage_per_model` event

### Requirement: Child reporting after the root ended
When a child pass reports usage after its root has ended, Core SHALL still charge the initiator's limits and write
the child's analytics row with its own usage, and SHALL NOT recreate the trace record, roll the usage up or write
aggregated cost records.

#### Scenario: Root stopped behind an interceptor
- **WHEN** the client stops the root request and the model the interceptor called then answers `200` with usage
- **THEN** the initiator's limits grow by the usage, the model's row is written with `usage_per_model` absent, and
  `deployment_cost_stats_tmp:<traceId>` does not exist
