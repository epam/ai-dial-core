# deployment-origin Specification

## Purpose
How Core calls the endpoint that serves a deployment for the deployments-POST family
(`POST /openai/deployments/{id}/chat/completions`, `/completions`, `/embeddings`) once the deployment is resolved,
the limits are checked and the request body has been through the enhancement chain: the request Core sends to the
origin, the choice of upstream and the retry over upstreams, how the origin's answer is relayed to the client, and
what Core records when the answer is complete. The Responses and Anthropic Messages controllers send the same origin
request; their relays differ and are not covered here. Limits, interceptors, the enhancement chain and the
per-request key's own lifecycle are other capabilities.

## Requirements

### Requirement: Origin URI and method
Core SHALL send the client's HTTP method to the URI resolved for the deployment and the interface (which url or
override path serves it is the deployment-interfaces-config capability): a base url with no override path gets the
ingress path with the `/deployments/{id}/` segment, whatever id the client used, rewritten to the deployment's name,
or `overrideName` when set, and a pre-`interfaces` `endpoint` is the complete URL. The client's query string SHALL
be appended as `?<query>` in every case.

#### Scenario: Base url with the ingress path
- **WHEN** a deployment `m` declares `interfaces.openaiChatCompletions.baseUrl: http://origin` and a client sends
  `POST /openai/deployments/m/chat/completions?api-version=2024-10-21`
- **THEN** the origin receives `POST http://origin/openai/deployments/m/chat/completions?api-version=2024-10-21`

#### Scenario: Override name in the path
- **WHEN** the same deployment has `overrideName: real-m`
- **THEN** the origin receives `POST http://origin/openai/deployments/real-m/chat/completions?api-version=2024-10-21`

#### Scenario: Legacy endpoint used as is
- **WHEN** a deployment declares only `endpoint: http://origin/v1/chat` and a client sends
  `POST /openai/deployments/m/chat/completions?api-version=2024-10-21`
- **THEN** the origin receives `POST http://origin/v1/chat?api-version=2024-10-21`

### Requirement: Client headers forwarded and dropped
Core SHALL copy the client's request headers to the origin request except the hop-by-hop set (`Connection`,
`Keep-Alive`, `Host`, `Proxy-Authenticate`, `Proxy-Authorization`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`,
`Content-Length`, `API-KEY`, `x-api-key`, plus the `additionalHopByHopHeaders` setting), `traceparent` and
`tracestate`, `X-DIAL-APPLICATION-PROPERTIES` and `X-DIAL-APPLICATION-ID`, and `Authorization` unless the deployment
has `forwardAuthToken: true`.

#### Scenario: Authorization dropped by default
- **WHEN** a client sends `Authorization: Bearer t`, `X-Conversation-Id: c1` and `X-DIAL-APPLICATION-ID: x` to a
  deployment without `forwardAuthToken`
- **THEN** the origin request carries `X-Conversation-Id: c1` and carries neither `Authorization` nor
  `X-DIAL-APPLICATION-ID`

#### Scenario: Authorization forwarded on request
- **WHEN** the deployment has `forwardAuthToken: true` and the client sends `Authorization: Bearer t`
- **THEN** the origin request carries `Authorization: Bearer t`

### Requirement: Headers Core adds for every origin
Core SHALL add to every origin request `API-KEY` with the per-request key of this request (app-to-model-accounting),
`X-DIAL-DEPLOYMENT-FEATURES` with the JSON of the features resolved for the interface
(deployment-interfaces-config),
`X-DIAL-OVERRIDE-NAME` with `overrideName` when the deployment has one, and `Content-Length` equal to the length of
the body it sends, which is the client's body after the enhancement chain.

#### Scenario: Per-request key and features
- **WHEN** Core forwards a chat completion to any deployment
- **THEN** the origin request carries `API-KEY` with a key that is not the client's key, a JSON
  `X-DIAL-DEPLOYMENT-FEATURES` header and a `Content-Length` equal to the forwarded body

#### Scenario: Override name header
- **WHEN** the deployment has `overrideName: real-m`
- **THEN** the origin request carries `X-DIAL-OVERRIDE-NAME: real-m`

### Requirement: Upstream headers for a model
For a `Model` with `upstreams`, Core SHALL add `X-UPSTREAM-ENDPOINT`, `X-UPSTREAM-KEY` and `X-UPSTREAM-EXTRA-DATA`
of the upstream chosen for this attempt, and `X-DIAL-CACHE-BREAKPOINT-PATH` and `X-DIAL-CACHE-EXTRA-METADATA` when
the route holds a cached upstream entry.

#### Scenario: Model upstream headers
- **WHEN** a model has one upstream `{endpoint: http://adapter/v1, key: k1, extraData: {"a":1}}`
- **THEN** the origin request carries `X-UPSTREAM-ENDPOINT: http://adapter/v1`, `X-UPSTREAM-KEY: k1` and
  `X-UPSTREAM-EXTRA-DATA` with that JSON

### Requirement: Identity headers for applications and translated interfaces
For an application with `applicationTypeSchemaId`, Core SHALL add `X-DIAL-APPLICATION-ID` with the deployment name,
and `X-DIAL-APPLICATION-PROPERTIES` when the schema asks for it. For an interface in translator mode, Core SHALL add
`X-DIAL-DEPLOYMENT-ID` with the deployment name.

#### Scenario: Schema application id
- **WHEN** an application with an `applicationTypeSchemaId` is called
- **THEN** the origin request carries `X-DIAL-APPLICATION-ID` equal to the application's name

### Requirement: Upstream choice and the attempt bound
Core SHALL choose the upstream for each attempt from the model's `upstreams` through the tiered, weighted balancer,
honouring a client `X-UPSTREAM-ID` header that names one of them, and SHALL make at most
`min(maxRetryAttempts, number of upstreams)` attempts per request (`maxRetryAttempts` defaults to 1). A deployment
without `upstreams` SHALL get one synthetic upstream whose endpoint and id are its serving endpoint.

#### Scenario: Default is one attempt
- **WHEN** a model has two upstreams and no `maxRetryAttempts`, and the first upstream answers 503
- **THEN** Core makes no second attempt and the client gets `502` with the message `No route`

#### Scenario: Retry within the bound
- **WHEN** the same model has `maxRetryAttempts: 2` and the first upstream answers 503 and the second 200
- **THEN** the origin receives two requests and the client gets the 200 with `X-UPSTREAM-ATTEMPTS: 2`

### Requirement: Retriable answers
Core SHALL treat an origin status of 429, 502, 503 or 504, and any status listed in the `retriableErrorCodes`
config, as retriable: the upstream is marked failed for the `Retry-After` seconds the origin sent or a default
cool-down, and the next attempt goes to another upstream. A failure to receive the response headers after the
request was sent SHALL count as a 502 for this purpose.

#### Scenario: 429 then another upstream
- **WHEN** a model has two upstreams and `maxRetryAttempts: 2`, and the first upstream answers `429` with
  `Retry-After: 7`
- **THEN** Core sends the request to the second upstream, and the first one is not chosen again until the 7 seconds
  have passed

#### Scenario: Connection dropped before headers
- **WHEN** the first upstream closes the connection before sending response headers and a second upstream exists
  within the attempt bound
- **THEN** Core sends the request to the second upstream

### Requirement: No upstream left
When no attempt remains or the balancer has no available upstream, Core SHALL answer the client `503` with the
message `Service is not available` when every upstream is cooling down after a 429, adding `Retry-After` in seconds
when an upstream supplied one, and `502` with the message `No route` otherwise.

#### Scenario: Every upstream busy
- **WHEN** a model's only upstream answers `429` with `Retry-After: 5` and `maxRetryAttempts` is 1
- **THEN** the client gets `503`, the message `Service is not available` and a `Retry-After` header

#### Scenario: No route
- **WHEN** a model's only upstream answers `503` and `maxRetryAttempts` is 1
- **THEN** the client gets `502` with the message `No route`

### Requirement: Connect failure
When Core cannot open a connection to the origin, it SHALL answer the client `502` with a JSON body
`{"error":{"code":"502","message":"Failed to connect to upstream server","display_message":"Failed to connect to upstream server"}}`
without trying another upstream.

#### Scenario: Origin port closed
- **WHEN** the deployment's endpoint points at a port nothing listens on
- **THEN** the client gets `502` with that body and the origin is not retried

### Requirement: Non-retriable answers relayed
Core SHALL relay an origin status that is not retriable as it is: a 4xx leaves the upstream's state unchanged, a
200 marks the upstream succeeded, and any other 5xx marks it failed while the answer is still relayed.

#### Scenario: 400 relayed
- **WHEN** the origin answers `400` with a JSON body
- **THEN** the client gets `400` with that body, and the next request may use the same upstream

#### Scenario: 500 relayed and marked
- **WHEN** the origin answers `500`
- **THEN** the client gets `500` and the next request prefers another upstream of the model

### Requirement: Response relay
Core SHALL relay the origin's status and response headers minus the hop-by-hop set, `traceparent` and
`tracestate`, as a chunked response (a non-streaming chat completion is the exception below) that carries
`X-UPSTREAM-ATTEMPTS` with the number of attempts made, and
`X-Accel-Buffering: no` when the origin's `Content-Type` contains `text/event-stream`. Streamed bodies are passed on
as they arrive.

#### Scenario: SSE relayed as it arrives
- **WHEN** the origin answers `200` with `Content-Type: text/event-stream` and sends events over several seconds
- **THEN** the client sees each event before the next arrives, with `X-Accel-Buffering: no` and
  `X-UPSTREAM-ATTEMPTS: 1`, and no `Content-Length`

### Requirement: Non-streaming chat completion buffered for usage_per_model
For a non-streaming `/chat/completions` answer (one whose `Content-Type` is not `text/event-stream`) Core SHALL read
the whole origin body and record its usage before answering, rewrite `statistics.usage_per_model` as the
app-to-model-accounting capability specifies, and answer with a `Content-Length` of the rewritten body instead of
chunking. `/completions` and `/embeddings` answers are relayed chunked as they arrive.

#### Scenario: Buffered answer carries Content-Length
- **WHEN** a model answers a non-streaming chat completion `200` with a JSON body
- **THEN** the client's response carries `Content-Length` equal to the length of the body it receives, no
  `Transfer-Encoding: chunked`, and `X-UPSTREAM-ATTEMPTS: 1`

#### Scenario: Embeddings not buffered
- **WHEN** a model answers `POST /openai/deployments/<model>/embeddings` `200` with a JSON body
- **THEN** the client's response is chunked and carries no `Content-Length`

### Requirement: Records after the answer
After the answer is complete Core SHALL parse the token usage from the last `usage` object in the body (the raw
event stream for SSE) of a `Model` that answered 200 and of any other deployment whose body reports usage, then
write one analytics log row for
the POST, whatever the origin's status. How the usage is charged and rolled up, what the row carries, the answers
that write no row, and the invalidation of the per-request key are the app-to-model-accounting capability.

#### Scenario: Model usage from the stream
- **WHEN** a model streams a `200` answer whose last event carries `usage.prompt_tokens: 10`,
  `usage.completion_tokens: 5` and `usage.total_tokens: 15`
- **THEN** one analytics row is written for the POST with `token_usage.total_tokens: 15`

#### Scenario: Error answer still logged
- **WHEN** the origin answers `400`
- **THEN** no limits are charged and one analytics row is written with `response.status: "400"`

### Requirement: Client gone during the relay
When writing to the client fails during the relay, Core SHALL reset the client connection; for a `Model` it SHALL
keep reading the origin's stream to its end to collect the usage, then write the analytics row; for any other
deployment it SHALL reset the origin request.

#### Scenario: Client disconnects mid-stream from a model
- **WHEN** the client closes its connection while a model streams a `200` answer
- **THEN** the origin stream is read to its end, the usage is charged, and one analytics row is written

#### Scenario: Client disconnects mid-stream from an application
- **WHEN** the client closes its connection while an application streams a `200` answer
- **THEN** the origin request is reset
