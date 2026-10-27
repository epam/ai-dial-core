# responses-api Specification

## Purpose
The OpenAI Responses interface as Core serves it: creating a response in its stream / background combinations, the
`dial_` response id Core hands out and maps back to the deployment, the item operations on that id (retrieve, cancel,
delete), the background-job lifecycle Core runs to finish and account for background responses, the expiry of jobs and
id mappings, and the Responses interceptor hops. How the origin request is built is deployment-origin, the per-request
key is app-to-model-accounting, and URL / override-path resolution is deployment-interfaces-config.

## Requirements

### Requirement: Create routes
Core SHALL handle a Responses create request at either create route request-routing maps (`POST /openai/v1/responses`,
`POST /openai/responses`) the same way, and SHALL select the deployment by the body's `model` field.

#### Scenario: Versioned path
- **WHEN** a client sends `POST /openai/v1/responses` with `Content-Type: application/json` and body
  `{"model":"gpt-3-turbo","input":"hi"}`
- **THEN** Core forwards the request to the Responses origin of deployment `gpt-3-turbo`

#### Scenario: Unversioned path
- **WHEN** a client sends the same request to `POST /openai/responses`
- **THEN** Core answers exactly as for `POST /openai/v1/responses`

### Requirement: Content type required
Core SHALL answer `415` with body `Only application/json is supported` when the create request's `Content-Type` does
not contain `application/json`, without reading the body.

#### Scenario: Wrong content type
- **WHEN** a client sends `POST /openai/v1/responses` with `Content-Type: text/plain`
- **THEN** Core answers `415` with body `Only application/json is supported` and sends nothing to an origin

### Requirement: Rejected request bodies
Core SHALL answer `422` with body `Failed to receive body` when the create body is not a JSON object or carries a
`previous_response_id` or `conversation` field.

#### Scenario: previous_response_id rejected
- **WHEN** a client sends `{"model":"test","previous_response_id":"resp_123"}`
- **THEN** Core answers `422` with body `Failed to receive body` and sends nothing to an origin

#### Scenario: conversation rejected
- **WHEN** a client sends `{"model":"test","conversation":[]}`
- **THEN** Core answers `422` with body `Failed to receive body`

#### Scenario: Body not JSON
- **WHEN** a client sends the body `not-json`
- **THEN** Core answers `422` with body `Failed to receive body`

### Requirement: Deployment must serve Responses
When nothing serves the Responses interface for the selected deployment (which url serves it, and the `503`, are
deployment-interfaces-config), Core SHALL answer that `503` with body
`OpenAI responses not supported for this deployment type`; it SHALL answer `503` with body
`Upstream is missing required id` when the upstream chosen for the request has a missing or blank `id`.

#### Scenario: No Responses endpoint
- **WHEN** the body's `model` names a deployment with neither `responsesEndpoint` nor `interfaces.openaiResponses`
- **THEN** Core answers `503` with body `OpenAI responses not supported for this deployment type`

#### Scenario: Upstream without id
- **WHEN** the chosen upstream of the deployment declares no `id`
- **THEN** Core answers `503` with body `Upstream is missing required id` and sends nothing to the origin

### Requirement: Response id format
Core SHALL hand out response ids of the form `dial_<deploymentName>_<uuid>`, where `<uuid>` is 32 lowercase hex
characters and `<deploymentName>` is the name of the deployment that served the create request (it may contain `_`).

#### Scenario: Id built from the deployment name
- **WHEN** deployment `gpt-5.4-2026-03-05` serves a create request
- **THEN** the id the client receives matches `dial_gpt-5.4-2026-03-05_[0-9a-f]{32}`

### Requirement: Non-streaming create (stream false, background false)
When `stream` is absent or false and `background` is absent or false, Core SHALL read the whole origin body and, for a
`200` JSON object with a string `id`, replace `id` with a new dial id and send the rewritten body with the origin's
headers, `Content-Length` of the rewritten body and `X-UPSTREAM-ATTEMPTS`. Any other origin answer SHALL be relayed
with its body unchanged.

#### Scenario: Id replaced in a JSON answer
- **WHEN** the origin answers `200` with `{"id":"upstream-resp-id","status":"completed"}` to `{"model":"test"}`
- **THEN** the client receives `200` with `{"id":"dial_test_<uuid>","status":"completed"}` and a `Content-Length`
  equal to that body's length

#### Scenario: Error answer relayed unchanged
- **WHEN** the origin answers `400` with `{"error":{"message":"bad"}}`
- **THEN** the client receives `400` with the body `{"error":{"message":"bad"}}` unchanged

### Requirement: Response id mapping record
When the create body has `store` absent or true, Core SHALL, for each dial id it hands out, write a blob resource in
bucket `response_mappings` at `<deploymentName>/<uuid>` holding `{"upstreamResponseId","upstreamKey","deploymentName",
"initiatorBucket"}`: the origin's id, the serving upstream's `id`, the deployment, and `Users/<sub>/` for a JWT user or
`Keys/<project>/` for an API key.

#### Scenario: Mapping written for a stored response
- **WHEN** user `test-user` creates a response on deployment `test`, upstream `id: endpoint`, and the origin answers
  `{"id":"upstream-resp-id"}`
- **THEN** Core writes `response_mappings/test/<uuid>` with `upstreamResponseId: upstream-resp-id`,
  `upstreamKey: endpoint`, `deploymentName: test`, `initiatorBucket: Users/test-user/`, and the client gets
  `dial_test_<uuid>`

### Requirement: Unstored responses
When the create body has `store: false`, Core SHALL still replace the origin's id with a fresh dial id but SHALL NOT
write a mapping, so every later item operation on that id answers `404`. With `background: true` as well, the job is
never finished and Core records no usage for it: a non-streaming create still writes the job record, which polling
and expiry skip for want of a mapping, so it is never deleted; a streaming create writes no record.

#### Scenario: store false
- **WHEN** a client creates a response with `{"model":"test","store":false}` and then sends
  `GET /openai/v1/responses/<the returned id>`
- **THEN** the create answer carries a `dial_test_<uuid>` id and the GET answers `404`

### Requirement: Streaming create (stream true, background false)
When `stream` is true, Core SHALL relay the origin's SSE stream as it arrives and, in every event whose data has a
`response` object with a string `id`, replace that id with one dial id, writing the mapping when the first such event
arrives. The stream SHALL end after a `response.completed` or `response.incomplete` event.

#### Scenario: Ids rewritten in every event
- **WHEN** the origin streams `event: response.created` and `event: response.completed`, each with data
  `{"response":{"id":"upstream-resp-stream"}}`
- **THEN** the client receives both events with `"id":"dial_test_<uuid>"`, the same id in both, and no event carries
  `upstream-resp-stream`

### Requirement: Background create without streaming (stream false, background true)
When `background` is true and `stream` is not, Core SHALL relay the origin's answer with its id replaced as for a
non-streaming create, and before ending the client response SHALL write a background job record for that dial id and
schedule it for polling. The per-request key's longer TTL is app-to-model-accounting's.

#### Scenario: Queued answer returned at once
- **WHEN** a client sends `{"model":"test","background":true}` and the origin answers `200`
  `{"id":"upstream-resp-id","status":"queued"}`
- **THEN** the client receives `{"id":"dial_test_<uuid>","status":"queued"}`, Core writes the blob
  `background_jobs/dial_test_<uuid>`, and the job is polled `backgroundJob.initialPollIntervalMs` later

#### Scenario: Job record write fails
- **WHEN** writing the background job record fails
- **THEN** the client still receives the rewritten answer, no job is scheduled, Core invalidates the per-request key
  and records no usage

### Requirement: Background create with streaming (stream true, background true)
When `background` and `stream` are both true, Core SHALL write the background job record when it writes the mapping
for the first event, and when the stream ends SHALL delete that record and cancel its schedule; usage is accounted at
stream end only if this deletion removed the record. If the client disconnects mid-stream, the record SHALL stay and
the job is finished by polling.

#### Scenario: Stream completes
- **WHEN** a client sends `{"model":"test","stream":true,"background":true}` and the origin streams
  `response.created` then `response.completed`
- **THEN** Core writes `background_jobs/dial_test_<uuid>` on the first event, and when the stream ends deletes it and
  removes the job from the poll schedule

#### Scenario: Client disconnects
- **WHEN** the client closes the connection after `response.created`
- **THEN** `background_jobs/dial_test_<uuid>` remains and the scheduler polls the job until it is terminal

### Requirement: Background job record
Core SHALL store a background job as a blob resource in bucket `background_jobs` named by the dial id, holding the
per-request key encrypted with the credential encryption service, the trace and span ids, whether the request was the
trace root, the initiator's identity (user id, roles, project, claims), the request headers, method, URI, timestamp and
body, the deployment name and the upstream endpoint.

#### Scenario: Key never stored in clear
- **WHEN** a background job is saved for a request whose per-request key is `k`
- **THEN** the blob `background_jobs/<dialId>` carries `perRequestKey` as a Base64 ciphertext, not `k`, and
  `requestBody` equal to the body Core sent to the origin

### Requirement: Polling request
Core SHALL poll a background job by sending `GET <backgroundJob.localBaseUrl>/openai/v1/responses/<dialId>` with header
`API-KEY` set to the job's per-request key and a timeout of `backgroundJob.pollTimeoutMs`, so the poll passes through
Core's own item route, its access check and its interceptors. `localBaseUrl` defaults to
`http://localhost:<actual server port>`.

#### Scenario: Poll goes through Core
- **WHEN** a job `dial_m_abc` is due and `localBaseUrl` is not set on a Core listening on port 8080
- **THEN** Core sends `GET http://localhost:8080/openai/v1/responses/dial_m_abc` with `API-KEY: <the job's key>`

### Requirement: Terminal and non-terminal poll answers
Core SHALL treat a `200` poll answer as terminal when its JSON `status` is a string other than `queued` and
`in_progress` (e.g. `completed`, `failed`, `cancelled`, `incomplete`), and SHALL then finish the job; for `queued` or
`in_progress` it SHALL poll again after `min(initialPollIntervalMs * pollBackoffFactor^attempts, maxPollIntervalMs)`.

#### Scenario: In progress then completed
- **WHEN** polls of a job answer `{"status":"in_progress"}`, `{"status":"in_progress"}`, then `{"status":"completed"}`
- **THEN** with factor `2.0` Core waits `initialPollIntervalMs` before the first and second polls and twice that
  before the third, and the job finishes after the third poll

### Requirement: Poll failures
Core SHALL count a poll that fails (non-`200` status, connection error, timeout, a body that is not a JSON object) as a
failure and retry after `initialPollIntervalMs`; it SHALL give up on the job at once when the status is in
`backgroundJob.pollGiveUpStatusCodes`, or after `maxSequentialPollFailures` consecutive failures. A non-terminal answer
SHALL reset the failure count. Giving up SHALL finish the job without a result.

#### Scenario: Give-up status
- **WHEN** a poll answers `404` and `pollGiveUpStatusCodes` is `[401, 403, 404]`
- **THEN** Core polls no more, deletes `background_jobs/<dialId>`, invalidates the job's per-request key and writes an
  analytics log row

#### Scenario: Retried status
- **WHEN** polls answer `500` with `maxSequentialPollFailures: 3`
- **THEN** Core polls three times and then finishes the job without a result

### Requirement: Job scheduling across instances
Core SHALL keep due jobs in the Redis sorted set `background_job_schedule:{background_jobs:<storage prefix>}:queue`
scored by next poll time, with per-job state (`owner`, `attempts`, `errors`) in the hash
`background_job_state:{background_jobs:<storage prefix>}:<dialId>`. An instance SHALL claim a due job by moving its
score to now + `leaseTimeoutMs`, so another instance picks it up if the claimer stops.

#### Scenario: Lease expires
- **WHEN** an instance claims a job and stops before rescheduling it
- **THEN** after `leaseTimeoutMs` the job is due again and any instance claims and polls it

### Requirement: Scheduler pacing
Each instance SHALL check for due jobs every `backgroundJob.schedulerTickIntervalMs` and poll at most
`backgroundJob.maxParallelJobs` jobs at once, leaving the rest due for a later tick.

#### Scenario: Parallel limit reached
- **WHEN** `maxParallelJobs` is `100` and 100 polls of this instance are in flight
- **THEN** the instance claims no further job until one of them finishes

### Requirement: Scan of stored jobs
At startup and every `backgroundJob.scanIntervalMs`, Core SHALL list every blob in `background_jobs` and schedule each
one for an immediate poll unless it is already scheduled, so jobs survive a Redis restart; a job whose blob is at least
`backgroundJob.jobTtlMs` old SHALL instead be expired.

#### Scenario: Redis lost the schedule
- **WHEN** `background_jobs/dial_m_abc` exists, the Redis schedule is empty, and a scan runs
- **THEN** `dial_m_abc` is scheduled with score now and polled on the next tick

#### Scenario: Existing schedule kept
- **WHEN** a scan finds a job already in the schedule with a future score
- **THEN** the score is not changed

### Requirement: Job expiry
Core SHALL expire a job found by the scan with age at least `backgroundJob.jobTtlMs` by deleting its record and
finishing it without a result. Expiry SHALL NOT delete the response mapping, so `GET` by id still reaches the origin.

#### Scenario: Job older than the TTL
- **WHEN** a scan finds `background_jobs/dial_m_abc` created more than `86400000` ms ago (the default `jobTtlMs`)
- **THEN** Core deletes the record, invalidates the job's per-request key, writes an analytics log row without usage,
  and records no usage against limits

### Requirement: Job finished once
Core SHALL finish a background job exactly once, by whichever path first deletes its record: a terminal poll, a
client `GET` by id that returns a terminal body, the end of a background stream, giving up, or expiry. Finishing with a
result whose `usage` is not empty SHALL add that usage to the initiator's limits (models only) and to the trace's
token stats; every finish SHALL invalidate the per-request key and write one analytics log row.

#### Scenario: Client GET finishes the job
- **WHEN** model `m` serves a background job and a client sends `GET /openai/v1/responses/dial_m_abc` while its job
  record exists and the origin answers `200`
  `{"status":"completed","usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}`
- **THEN** Core deletes `background_jobs/dial_m_abc`, grows the initiator's `m/tokens` limit record by 15 once, and
  removes the job from the poll schedule

#### Scenario: Record already gone
- **WHEN** a poll returns a terminal body but the record was deleted concurrently
- **THEN** Core records no usage, does not invalidate the key and writes no analytics log row

### Requirement: Item routes
Core SHALL serve `GET /openai/v1/responses/{id}`, `POST /openai/v1/responses/{id}/cancel` and
`DELETE /openai/v1/responses/{id}`, where `{id}` is a dial id. An id with no mapping SHALL get `404` with body
`{"error":{"message":"Response with id '<id>' not found.","display_message":"Response with id '<id>' not found.",
"type":"invalid_request_error"}}`; an id not of the form `dial_<deploymentName>_<uuid>` (no `dial_` prefix, or no `_`
after it) SHALL get `500`.

#### Scenario: Unknown id
- **WHEN** a client sends `GET /openai/v1/responses/dial_test-deployment_unknown` and no mapping exists for it
- **THEN** Core answers `404` with the `invalid_request_error` body naming `dial_test-deployment_unknown`

#### Scenario: Id without the dial prefix
- **WHEN** a client sends `GET /openai/v1/responses/resp_123`
- **THEN** Core answers `500` with body `Failed to process response operation`

### Requirement: Only the initiator reads a response
Core SHALL answer `403` with body `Access denied` to an item operation when the caller's initiator bucket
(`Users/<sub>/` or `Keys/<project>/`) differs from the mapping's `initiatorBucket`.

#### Scenario: Another user
- **WHEN** the mapping of `dial_test-deployment_x` has `initiatorBucket: Users/other-user/` and user `test-user` sends
  `GET /openai/v1/responses/dial_test-deployment_x`
- **THEN** Core answers `403` with body `Access denied` and sends nothing to an origin

### Requirement: Item request to the origin
Core SHALL send an item operation to the mapping's deployment with the client's method, no body, the client's query
string, and the origin's id in place of the dial id, at `<responses base>/<upstreamResponseId>` (`/cancel` appended for
cancel) unless an override path applies; it SHALL pin the upstream to the mapping's `upstreamKey` and send only
`API-KEY` (a fresh per-request key), `X-UPSTREAM-KEY`, `X-UPSTREAM-ENDPOINT` and `X-UPSTREAM-EXTRA-DATA`.

#### Scenario: Legacy endpoint
- **WHEN** the mapping names `upstream-id-123` on a deployment with `responsesEndpoint: http://adapter/responses` and a
  client sends `POST /openai/v1/responses/dial_test-deployment_123/cancel`
- **THEN** the origin receives `POST http://adapter/responses/upstream-id-123/cancel`

#### Scenario: Interfaces base url
- **WHEN** the deployment declares `interfaces.openaiResponses` with base url `http://adapter` and the client sends
  `GET /openai/v1/responses/dial_test-deployment_123`
- **THEN** the origin receives `GET http://adapter/openai/v1/responses/upstream-id-123`

### Requirement: Item operation preconditions
Core SHALL answer `503` with body `Deployment for response_id does not support Responses API` when the mapping's
deployment no longer serves Responses, and the route provider's `400` when the mapping's `upstreamKey` matches no
upstream of the deployment, without calling an origin.

#### Scenario: Upstream removed from config
- **WHEN** the mapping's `upstreamKey` is `missing-upstream-key` and the deployment has no upstream with that id
- **THEN** Core answers `400` with body `Unknown upstream id missing-upstream-key` and sends nothing to an origin

### Requirement: Item answer relayed
For an item answer that is not an SSE stream, Core SHALL relay the origin's status, its `Content-Type` and a
`Content-Length` of the body it sends. For a `200`
JSON object it SHALL replace `id` with the dial id when `id` equals the mapping's `upstreamResponseId`; any other body
SHALL be sent unchanged.

#### Scenario: Id restored on GET
- **WHEN** the origin answers `200` `{"id":"upstream-id-123","status":"completed"}` with `Content-Type:
  application/json`
- **THEN** the client receives `200` `{"id":"dial_test-deployment_123","status":"completed"}` with
  `Content-Type: application/json`

#### Scenario: Empty body
- **WHEN** the origin answers `200` with an empty body and no `Content-Type`
- **THEN** the client receives `200` with an empty body, `Content-Length: 0` and no `Content-Type`

### Requirement: Retrieve as a stream
When the origin answers a `GET` by id with `Content-Type: text/event-stream`, Core SHALL relay the SSE stream with the
`response.id` in each event replaced by the dial id. Core SHALL pass the query (e.g. `stream=true&starting_after=42`)
to the origin unchanged and SHALL NOT interpret it, so resuming a stream is the origin's behaviour.

#### Scenario: Resumed stream
- **WHEN** a client sends `GET /openai/v1/responses/dial_test-deployment_stream?stream=true` and the origin streams
  `response.created` and `response.completed` with `"id":"upstream-id-stream"`
- **THEN** the origin is called at `http://adapter/responses/upstream-id-stream?stream=true` and the client receives
  both events with `"id":"dial_test-deployment_stream"`

### Requirement: Delete
Core SHALL answer `409` with body `Cannot delete response while background job is in progress` to `DELETE` while a
job record exists for the id, and SHALL delete the mapping only when the origin answers `200`.

#### Scenario: Job still running
- **WHEN** `background_jobs/dial_test-deployment_del` exists and a client sends
  `DELETE /openai/v1/responses/dial_test-deployment_del`
- **THEN** Core answers `409`, sends nothing to the origin and keeps the mapping

#### Scenario: Delete succeeds
- **WHEN** no job record exists and the origin answers the `DELETE` with `200`
- **THEN** Core deletes `response_mappings/test-deployment/del` and a later `GET` of the id answers `404`

#### Scenario: Origin refuses
- **WHEN** the origin answers the `DELETE` with `400`
- **THEN** the client receives the `400` body unchanged and the mapping is kept

### Requirement: Cancel
Core SHALL forward `POST .../cancel` to the origin and relay its answer, without changing the job record or schedule;
a cancelled background job SHALL finish when a later poll or client `GET` sees its terminal `status` (`cancelled`).

#### Scenario: Cancel then poll
- **WHEN** a client cancels `dial_m_abc` and the next poll answers `{"status":"cancelled"}`
- **THEN** the cancel answer is the origin's, and the job finishes on that poll

### Requirement: No input items listing
Core SHALL NOT serve an input-items listing for a response: `GET /openai/v1/responses/{id}/input_items` matches no
Responses route and is handled by the config routes.

#### Scenario: Input items requested
- **WHEN** a client sends `GET /openai/v1/responses/dial_m_abc/input_items` and no config route matches the path
- **THEN** Core answers `404` with body `Route is not found`

### Requirement: Mapping expiry
Core SHALL delete a response mapping 30 days after its blob's creation time (its last-modified time where the store
gives no creation time), checked by a housekeeping pass every 24 hours that starts at a random offset of up to 4 hours;
the period is not configurable. After deletion, item operations on the id SHALL answer `404`.

#### Scenario: Mapping older than 30 days
- **WHEN** the housekeeping pass finds `response_mappings/m/abc` created 31 days ago
- **THEN** Core deletes it and `GET /openai/v1/responses/dial_m_abc` answers `404`

### Requirement: Nested mappings never expire
The housekeeping pass SHALL delete only items directly inside a top-level folder, `response_mappings/<deployment>/<uuid>`,
so the mapping of a deployment whose name contains `/` (a custom application, `applications/<bucket>/<path>`) is
never deleted.

#### Scenario: Custom application mapping
- **WHEN** the housekeeping pass finds `response_mappings/applications/<bucket>/app/abc` created 31 days ago
- **THEN** Core does not delete it

### Requirement: Background job settings
Core SHALL read the `backgroundJob` block of the static settings with these code defaults: `initialPollIntervalMs`
`10000`, `maxPollIntervalMs` `300000`, `pollBackoffFactor` `2.0`, `maxSequentialPollFailures` `10`, `jobTtlMs`
`86400000`, `leaseTimeoutMs` `300000`, `maxParallelJobs` `100`, `scanIntervalMs` `600000`, `schedulerTickIntervalMs`
`5000`, `pollTimeoutMs` `300000`, `pollGiveUpStatusCodes` `[401, 403, 404]`, `localBaseUrl` unset.

#### Scenario: Block omitted
- **WHEN** the settings file has no `backgroundJob` block
- **THEN** jobs are first polled 10 s after creation, expire after 24 h, and give up on `401`, `403` or `404`

### Requirement: Create through interceptors
When the selected deployment has interceptors, Core SHALL send the create request to the first interceptor's Responses
URI with a per-request key in `API-KEY` and `X-DIAL-DEPLOYMENT-ID: <deployment>`, relaying its answer without
rewriting ids; a call back to `POST /openai/v1/responses` with that key SHALL go to the next interceptor, and from the
last one to the initial deployment whatever the body's `model`.

#### Scenario: Last interceptor calls back
- **WHEN** deployment `m` has interceptors `[a, b]` and interceptor `b` calls `POST /openai/v1/responses` with its
  per-request key and `{"model":"other"}`
- **THEN** Core forwards the request to deployment `m`'s origin and hands out a `dial_m_<uuid>` id

#### Scenario: Interceptor missing from config
- **WHEN** the deployment lists interceptor `guard` and the config has no interceptor `guard`
- **THEN** Core answers `404` with body `Interceptor is not found`

### Requirement: Item operations through interceptors
When the mapping's deployment has interceptors, Core SHALL send the item operation to the first interceptor with the
client's method and query, with the dial id (not the origin's id) in the URI, e.g. `<base>/openai/v1/responses/<dialId>`
or the interceptor's override path with `{id}` rendered as the dial id; the interceptor's call back by id SHALL go to the
next interceptor, and from the last one to the origin.

#### Scenario: Interceptor override paths
- **WHEN** interceptor `guard` declares `interfaces.openaiResponses` base url `http://adapter/root/` with
  `overridePaths` `getOpenaiResponsesById: /get/{id}` and a client sends
  `GET /openai/v1/responses/dial_m_123?stream=true&starting_after=42`, the mapping's deployment `m` listing `guard`
- **THEN** the interceptor receives `GET http://adapter/root/get/dial_m_123?stream=true&starting_after=42`
