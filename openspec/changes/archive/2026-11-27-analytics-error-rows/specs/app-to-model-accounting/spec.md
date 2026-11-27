# Spec Delta

## MODIFIED Requirements

### Requirement: Passes without an analytics row
Core SHALL NOT write an analytics row for a pass that forwards to an interceptor, so usage an interceptor's body
reports is not recorded, nor, unless `analytics.errorRows` is on ("Error rows for answers Core gives itself"), for these
answers Core produces itself: a refusal, `No route`, `Service is not available`, a connect failure.

#### Scenario: Model behind interceptors
- **WHEN** model `m` has interceptors `["i1", "i2"]` and a client calls `m`
- **THEN** one row is written, for `m`, and none for `i1` or `i2`

#### Scenario: No route
- **WHEN** `analytics.errorRows` is off, a model's only upstream answers `503` and `maxRetryAttempts` is 1
- **THEN** the client gets `502` and no row is written

### Requirement: Client gone from an application
When writing to the client fails during the chunked relay of an application's answer (not a `Model` or an
interceptor), Core SHALL reset the origin request and SHALL NOT write the pass's analytics row (but see "Error rows
for a relay that breaks"), delete the key it
minted (it expires with its TTL) or, for a root pass, delete the trace's token-stats record (it expires with
`deploymentStats.ttlMs`). A buffered non-streaming chat completion does not notice a failed write and ends as answered.

#### Scenario: Client disconnects from an application
- **WHEN** `analytics.errorRows` is off and a client streams from root `app` and closes its connection mid-stream
- **THEN** no `app` row is written, and the key Core sent `app` and `deployment_cost_stats_tmp:<traceId>` still
  exist in Redis

## ADDED Requirements

### Requirement: Error rows for answers Core gives itself
With `analytics.errorRows` on, Core SHALL write one analytics row for every error answer it gives itself on
`/openai/deployments/{id}/...`, `/openai/v1/chat/completions`, `/openai/v1/responses`, `/anthropic/v1/messages`, a deployment's
feature calls, its MCP proxy and routes: every answer with a status outside 200-299 that Core logs at WARN as
`Responding with error`. `response.status` SHALL be the status the client got.

#### Scenario: Every upstream busy
- **WHEN** `analytics.errorRows` is on and a model's only upstream answers `429` with `maxRetryAttempts` 1
- **THEN** the client gets `503` and one row is written with `deployment` the model and `response.status: "503"`

#### Scenario: Origin answers 502
- **WHEN** `analytics.errorRows` is on and a model's only upstream answers `502` with `maxRetryAttempts` 1
- **THEN** the client gets `502` and one row is written with `response.status: "502"`

#### Scenario: Origin answers 503
- **WHEN** `analytics.errorRows` is on and a model's only upstream answers `503` with `maxRetryAttempts` 1
- **THEN** the client gets `502`, not `503`, and one row is written with `response.status: "502"`

#### Scenario: Origin answers 504
- **WHEN** `analytics.errorRows` is on and a model's only upstream answers `504` with `maxRetryAttempts` 1
- **THEN** the client gets `502` and one row is written with `response.status: "502"`

#### Scenario: Two upstreams, both busy
- **WHEN** `analytics.errorRows` is on, a model has two upstreams and `maxRetryAttempts: 2`, and both answer `429`
- **THEN** the origin receives two requests, the client gets `503`, and one row is written with `response.status: "503"`

#### Scenario: Last attempt busy, another upstream failed
- **WHEN** `analytics.errorRows` is on, a model has two upstreams in tiers 0 and 1 and `maxRetryAttempts: 2`, the first
  answers `502` and the second answers `429`
- **THEN** the client gets `502`, because not every upstream is busy, and one row is written with `response.status: "502"`

#### Scenario: Last attempt failed after a busy one
- **WHEN** `analytics.errorRows` is on, a model has two upstreams in tiers 0 and 1 and `maxRetryAttempts: 2`, the first
  answers `429` and the second answers `502`
- **THEN** the client gets `502` and one row is written with `response.status: "502"`

#### Scenario: Connection refused
- **WHEN** `analytics.errorRows` is on and the model's endpoint refuses the connection
- **THEN** the client gets `502` and one row is written with `response.status: "502"`

#### Scenario: Host that does not resolve
- **WHEN** `analytics.errorRows` is on and the model's endpoint names a host that does not resolve
- **THEN** the client gets `502` and one row is written with `response.status: "502"`

#### Scenario: No response headers
- **WHEN** `analytics.errorRows` is on and the origin accepts the request but sends no response headers within
  `client.idleTimeout`
- **THEN** the client gets `502` and one row is written with `response.status: "502"`

#### Scenario: Rate limit
- **WHEN** `analytics.errorRows` is on and a request exceeds the role's limit for the model
- **THEN** the client gets `429` and one row is written with `deployment` the model and `response.status: "429"`

#### Scenario: Rate limit of a child pass
- **WHEN** `analytics.errorRows` is on, application `app` calls a model with the key Core sent it, and the request exceeds
  the role's limit for the model
- **THEN** `app` gets `429` and one row is written for the model, with `parent_deployment: "app"`

#### Scenario: Forbidden deployment
- **WHEN** `analytics.errorRows` is on and the key's role is not in the model's `userRoles`
- **THEN** the client gets `403` and one row is written with `response.status: "403"`

#### Scenario: Unknown deployment
- **WHEN** `analytics.errorRows` is on and a client calls an unknown deployment
- **THEN** the client gets `404` and one row is written with `response.status: "404"` and no `deployment`

#### Scenario: Wrong content type
- **WHEN** `analytics.errorRows` is on and a client sends a body that is not `application/json`
- **THEN** the client gets `415` and one row is written with `response.status: "415"`

#### Scenario: Body that is not JSON
- **WHEN** `analytics.errorRows` is on and a client sends a body that is not valid JSON
- **THEN** the client gets `400` and one row is written with `response.status: "400"`

#### Scenario: Other surfaces
- **WHEN** `analytics.errorRows` is on and the only upstream of a model, or the endpoint of an application, answers `429`
  with one attempt, for a call to `/openai/deployments/{id}/...`, `/openai/v1/chat/completions`, `/openai/v1/responses` or
  `/anthropic/v1/messages`
- **THEN** the client gets `503` and one row is written for each call

#### Scenario: Decided request fails
- **WHEN** `analytics.errorRows` is on, router `r1`'s decision chooses `m1`, and `m1`'s only upstream answers `502`
- **THEN** the client gets an error, the router row of `r1` is written as with the setting off, and one row is written for `m1`

#### Scenario: Decided application with an encoded name
- **WHEN** `analytics.errorRows` is on, a router's decision chooses application `applications/<bucket>/My App` by its
  decoded url, and nothing listens at the application's endpoint
- **THEN** the client gets `502` and one row is written for the application, with `deployment` its encoded url
  `applications/<bucket>/My%20App`

#### Scenario: Feature call whose origin refuses the connection
- **WHEN** `analytics.errorRows` is on and a client calls `/v1/deployments/{id}/tokenize` of a model whose tokenize endpoint
  refuses the connection
- **THEN** the client gets `502` and one row is written with `response.status: "502"`

#### Scenario: Feature call to an unknown deployment
- **WHEN** `analytics.errorRows` is on and a client calls `/v1/deployments/{id}/tokenize` of an unknown deployment
- **THEN** the client gets `404` and one row is written with `response.status: "404"` and no `deployment`

#### Scenario: Feature call that needs no origin
- **WHEN** `analytics.errorRows` is on and a client calls a feature that a model has no endpoint for and that needs none
- **THEN** the client gets `200` and one row is written, the row written with the setting off

#### Scenario: MCP call to an unknown toolset
- **WHEN** `analytics.errorRows` is on and a client sends an MCP request to an unknown toolset
- **THEN** the client gets `404` and one row is written with `response.status: "404"`

#### Scenario: MCP server that cannot be reached
- **WHEN** `analytics.errorRows` is on and the MCP server of a toolset refuses the connection
- **THEN** the client gets `502` or `503` and one row is written with the same `response.status`

#### Scenario: Route refused for a role
- **WHEN** `analytics.errorRows` is on and a client whose role a route does not allow calls it
- **THEN** the client gets `403` and one row is written with `response.status: "403"`

#### Scenario: Route that is not found
- **WHEN** `analytics.errorRows` is on and a client calls a path that no route serves
- **THEN** the client gets `404` and one row is written with `response.status: "404"`

#### Scenario: Route whose only upstream is busy
- **WHEN** `analytics.errorRows` is on and a route's only upstream answers `429` with `maxRetryAttempts` 1
- **THEN** the client gets `503` and one row is written with `response.status: "503"`

#### Scenario: Origin's error relayed on these surfaces
- **WHEN** `analytics.errorRows` is on and the origin of a feature call, an MCP request or a route answers `500`
- **THEN** the client gets `500` and one row is written for each, the row written with the setting off

#### Scenario: Setting off
- **WHEN** `analytics.errorRows` is `false` and any of the above happens
- **THEN** no row is written, as before

### Requirement: Error rows for a relay that breaks
With `analytics.errorRows` on, Core SHALL write one analytics row for a pass of a `Model` whose origin's answer breaks after its
headers, and for a pass of an application whose relay breaks. `response.status` SHALL be the status Core had set for the
answer, as for the row of a client that left a `Model`.

#### Scenario: Origin closes after the headers
- **WHEN** `analytics.errorRows` is on and a model's origin sends `200` and the event-stream headers, then closes the
  connection with no body
- **THEN** one row is written with `response.status: "200"`, and the client gets no complete answer

#### Scenario: Origin breaks mid-stream
- **WHEN** `analytics.errorRows` is on and a model's origin sends two events, then closes the connection
- **THEN** one row is written, and the client's stream ends without `[DONE]`

#### Scenario: Client leaves a model
- **WHEN** `analytics.errorRows` is on and a client closes its connection while a model streams a `200` answer
- **THEN** the origin stream is read to its end and one row is written, the same row as with the setting off

#### Scenario: Application's origin breaks
- **WHEN** `analytics.errorRows` is on and an application's origin breaks its stream after the headers
- **THEN** one row is written for the application

#### Scenario: Client leaves an application
- **WHEN** `analytics.errorRows` is on and a client closes its connection mid-stream from an application
- **THEN** one row is written for the application, and the key Core sent the application still exists in Redis

#### Scenario: Background job
- **WHEN** `analytics.errorRows` is on and the stream of a Responses request with `background: true` breaks after its job
  was recorded
- **THEN** this pass writes no row: the terminal row of a background job is written when the job ends

#### Scenario: Background request before its job is recorded
- **WHEN** `analytics.errorRows` is on and the stream of a Responses request with `background: true` breaks before an event
  carried the response id, so no job was recorded
- **THEN** one row is written for the pass

#### Scenario: Setting off, the origin breaks
- **WHEN** `analytics.errorRows` is off and a model's origin breaks its stream after the headers
- **THEN** no row is written, as before

### Requirement: No error row for what Monitoring does not log
With `analytics.errorRows` on, Core SHALL NOT write an error row for a `401` or a `413`, which Core does not log at WARN,
nor for an origin's error answer that Core relays, which already has its row.

#### Scenario: Unknown key
- **WHEN** `analytics.errorRows` is on and a client calls with an unknown API key
- **THEN** the client gets `401` and no row is written

#### Scenario: Body too large
- **WHEN** `analytics.errorRows` is on and a client sends a body larger than `resources.maxSize`
- **THEN** the client gets `413` and no row is written

#### Scenario: Relayed origin error
- **WHEN** `analytics.errorRows` is on and a model's only upstream answers `500`
- **THEN** the client gets `500` and one row is written, with `response.status: "500"`, the row written with the setting off

### Requirement: One row per pass, and the passes that keep none
Core SHALL write at most one analytics row for a pass. With `analytics.errorRows` on it SHALL NOT write an error row for the
call to an interceptor, nor for the pass of a router in decision mode once the router is resolved and until a candidate it
chose is resolved (router-deployment "Decision failures").

#### Scenario: Relayed error of a pass served in process
- **WHEN** `analytics.errorRows` is on, a client calls `/openai/v1/responses` with alias `a` of model `m`, and `m`'s origin
  answers `404` with a `text/plain` body
- **THEN** the client gets `404` and two rows are written, one for `a` and one for `m`, both with `response.status: "404"`

#### Scenario: Retry that succeeds
- **WHEN** `analytics.errorRows` is on, a model has two upstreams and `maxRetryAttempts: 2`, the first answers `502` and
  the second `200`
- **THEN** one row is written, with `response.status: "200"`

#### Scenario: Interceptor fails
- **WHEN** `analytics.errorRows` is on, a model has interceptor `i1`, and `i1` answers `502`
- **THEN** no row is written for the call to `i1`

#### Scenario: Router decision fails
- **WHEN** `analytics.errorRows` is on and a router's decision cannot be fetched
- **THEN** the client gets `502` and no row is written
