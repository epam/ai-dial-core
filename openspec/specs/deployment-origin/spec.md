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
When writing to the client fails during the chunked relay (a buffered non-streaming chat completion does not notice
a failed write), Core SHALL reset the client connection; for a `Model` it SHALL
keep reading the origin's stream to its end to collect the usage, then write the analytics row; for any other
deployment it SHALL reset the origin request.

#### Scenario: Client disconnects mid-stream from a model
- **WHEN** the client closes its connection while a model streams a `200` answer
- **THEN** the origin stream is read to its end, the usage is charged, and one analytics row is written

#### Scenario: Client disconnects mid-stream from an application
- **WHEN** the client closes its connection while an application streams a `200` answer
- **THEN** the origin request is reset

### Requirement: Embedded origin request
For an interface in `embedded` mode Core SHALL send no HTTP request and SHALL call the plugin the entry names with the
request it would send to an HTTP origin: the client's method, the path and query it would append to a base url, the
headers of this capability (no `traceparent`: Core starts no client span for it), and the body. This SHALL hold for
deployments-POST, `/openai/v1/chat/completions`, `/openai/v1/responses` and `/anthropic/v1/messages` with its
`count_tokens`.

#### Scenario: Chat completion handed to the plugin
- **WHEN** application `app` declares `interfaces.openaiChatCompletions: {"mode": "embedded", "plugin": "p1"}`,
  `plugins.p1` is of type `test-origin`, and a client sends `POST /openai/deployments/app/chat/completions?api-version=1`
- **THEN** the plugin receives `POST /openai/deployments/app/chat/completions?api-version=1` with the body Core would
  send, `X-DIAL-DEPLOYMENT-FEATURES`, and an `API-KEY` holding a per-request key, not the client's key

#### Scenario: Unified chat endpoint handed to the plugin
- **WHEN** the same client sends `POST /openai/v1/chat/completions` with `"model": "app"`
- **THEN** the plugin receives `POST /openai/v1/chat/completions`

#### Scenario: Upstream headers for a model
- **WHEN** a model with one upstream `{"endpoint": "http://cloud/v1", "key": "k1"}` declares
  `interfaces.openaiChatCompletions: {"mode": "embedded", "plugin": "p1"}` and a client sends a chat completion for it
- **THEN** the plugin receives `X-UPSTREAM-ENDPOINT: http://cloud/v1` and `X-UPSTREAM-KEY: k1`

#### Scenario: Embeddings handed to the plugin
- **WHEN** application `app` declares `interfaces.openaiEmbeddings` in `embedded` mode with plugin `p1` and a client
  sends `POST /openai/deployments/app/embeddings`
- **THEN** the plugin receives `POST /openai/deployments/app/embeddings`

#### Scenario: Responses create handed to the plugin
- **WHEN** application `app` declares `interfaces.openaiResponses` in `embedded` mode with plugin `p1` and a client
  sends `POST /openai/v1/responses` with `"model": "app"`
- **THEN** the plugin receives `POST /openai/v1/responses`

#### Scenario: Messages handed to the plugin
- **WHEN** application `app` declares `interfaces.anthropicMessages` in `embedded` mode with plugin `p1` and a client
  sends `POST /anthropic/v1/messages` and then `POST /anthropic/v1/messages/count_tokens` with `"model": "app"`
- **THEN** the plugin receives `POST /anthropic/v1/messages` and then `POST /anthropic/v1/messages/count_tokens`

### Requirement: Embedded origin answer
Core SHALL treat the plugin's answer as an HTTP origin's answer, under every other requirement of this capability:
retriable statuses, the relay, usage and the analytics row. The row's `response.upstream_uri` SHALL be
`plugin://<plugin name>` for a deployment without `upstreams`, and the attempt's upstream endpoint for one with them.

#### Scenario: Streamed answer from a plugin
- **WHEN** the `test-origin` plugin `p1` serving application `app` answers `200` with
  `Content-Type: text/event-stream`, two content events and a last event with `usage.total_tokens: 15`
- **THEN** the client gets the events with `X-UPSTREAM-ATTEMPTS: 1`, and one analytics row is written for the POST
  with `response.upstream_uri: plugin://p1`

#### Scenario: Model usage charged
- **WHEN** the plugin serving model `m` answers a chat completion `200` with `usage.total_tokens: 15`
- **THEN** the caller's token limit record for `m` grows by `15`, as `GET /v1/deployments/m/limits` reports in
  `minuteTokenStats.used`

#### Scenario: Retriable status from a plugin
- **WHEN** a model with `maxRetryAttempts: 2` and upstreams `http://cloud-a` (tier 0) and `http://cloud-b` (tier 1)
  is served by plugin `p1`, and the plugin answers `429` to the call carrying `X-UPSTREAM-ENDPOINT: http://cloud-a`
- **THEN** Core calls the plugin again with `X-UPSTREAM-ENDPOINT: http://cloud-b` and relays that answer with
  `X-UPSTREAM-ATTEMPTS: 2`

### Requirement: Embedded origin failure
Core SHALL answer the connect-failure `502`, without trying another upstream, when the plugin cannot serve: no
`plugins` entry has its name, the entry's provider is not loaded or does not serve requests, or creating the plugin
from its settings fails. A plugin call that fails SHALL count as a failure to receive the response headers.

#### Scenario: Missing plugin entry
- **WHEN** application `app` declares `interfaces.openaiChatCompletions: {"mode": "embedded", "plugin": "missing"}`,
  no `plugins.missing` exists, and a client sends a chat completion for it
- **THEN** the client gets `502` with `message` and `display_message` `Failed to connect to upstream server`, the
  answer of "Connect failure"

#### Scenario: Provider that does not serve requests
- **WHEN** the interface names `plugins.p2` of type `test-echo`, whose provider only validates settings
- **THEN** the client gets `502` with the same body

#### Scenario: Plugin call fails
- **WHEN** the `test-origin` plugin serving application `app` fails the call instead of answering
- **THEN** the client gets `502` with the message `No route`, and one attempt is made

### Requirement: Client gone from an embedded origin
When writing to the client fails during the relay of a plugin's answer for a deployment that is not a `Model`,
Core SHALL cancel the plugin's answer where it would reset the origin request; for a `Model` it SHALL read the
plugin's answer to its end, as for an HTTP origin. Core SHALL also cancel an answer it drops unread, after a
retriable status.

#### Scenario: Client disconnects mid-stream from an embedded application
- **WHEN** the client closes its connection while the `test-origin` plugin serving application `app` streams a `200`
  answer
- **THEN** the plugin's answer is cancelled

#### Scenario: Retried answer cancelled
- **WHEN** the plugin answers `429` to the first attempt of the retry scenario above
- **THEN** that first answer is cancelled before the second call
