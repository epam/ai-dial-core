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

### Requirement: Identity headers for applications and deployments
For an application with `applicationTypeSchemaId`, Core SHALL add `X-DIAL-APPLICATION-ID` with the deployment name,
and `X-DIAL-APPLICATION-PROPERTIES` when the schema asks for it. For a router with `applicationTypeSchemaId`, Core
SHALL add `X-DIAL-APPLICATION-PROPERTIES` when the schema asks for it (see router-deployment).

#### Scenario: Schema application id
- **WHEN** an application with an `applicationTypeSchemaId` is called
- **THEN** the origin request carries `X-DIAL-APPLICATION-ID` equal to the application's name

#### Scenario: Typed router gets no application id
- **WHEN** a router with `applicationTypeSchemaId` is called
- **THEN** the origin request carries `X-DIAL-APPLICATION-PROPERTIES` and no `X-DIAL-APPLICATION-ID`

### Requirement: Deployment id on every origin request
On every request it forwards to a deployment's origin (any interface in any mode, the deployment feature endpoints and
Responses item operations), Core SHALL set `X-DIAL-DEPLOYMENT-ID` to the deployment name, replacing any value the
client sent.

#### Scenario: Pass-through interface with overrideName
- **WHEN** a client sends a chat completion with `X-DIAL-DEPLOYMENT-ID: spoofed` to model `m` that has
  `overrideName: real-m` and `interfaces.openaiChatCompletions.mode: passthrough`
- **THEN** the origin request carries `X-DIAL-DEPLOYMENT-ID: m` and `X-DIAL-OVERRIDE-NAME: real-m`

#### Scenario: Feature endpoint
- **WHEN** a client sends `POST /v1/deployments/m/tokenize` with `X-DIAL-DEPLOYMENT-ID: spoofed`
- **THEN** the origin request carries `X-DIAL-DEPLOYMENT-ID: m`

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
For a pass-through interface whose base url is a plugin url, Core SHALL send no HTTP request and SHALL call the
plugin it names with what an HTTP origin at that url would get: the client's method, the url's path after the name,
then the path and query Core appends, the headers of this capability (no `traceparent`), and the body. This SHALL
hold for deployments-POST, `/openai/v1/chat/completions`, `/openai/v1/responses` and `/anthropic/v1/messages` with its
`count_tokens`.

#### Scenario: Chat completion handed to the plugin
- **WHEN** application `app` declares `interfaces.openaiChatCompletions: {"base_url": "plugin://p1"}`,
  `plugins.p1` is of type `test-origin`, and a client sends `POST /openai/deployments/app/chat/completions?api-version=1`
- **THEN** the plugin receives `POST /openai/deployments/app/chat/completions?api-version=1` with the body Core would
  send, `X-DIAL-DEPLOYMENT-FEATURES`, and an `API-KEY` holding a per-request key, not the client's key

#### Scenario: Unified chat endpoint handed to the plugin
- **WHEN** the same client sends `POST /openai/v1/chat/completions` with `"model": "app"`
- **THEN** the plugin receives `POST /openai/v1/chat/completions`

#### Scenario: Upstream headers for a model
- **WHEN** a model with one upstream `{"endpoint": "http://cloud/v1", "key": "k1"}` declares
  `interfaces.openaiChatCompletions: {"base_url": "plugin://p1"}` and a client sends a chat completion for it
- **THEN** the plugin receives `X-UPSTREAM-ENDPOINT: http://cloud/v1` and `X-UPSTREAM-KEY: k1`

#### Scenario: Embeddings handed to the plugin
- **WHEN** application `app` declares `interfaces.openaiEmbeddings: {"base_url": "plugin://p1"}` and a client
  sends `POST /openai/deployments/app/embeddings`
- **THEN** the plugin receives `POST /openai/deployments/app/embeddings`

#### Scenario: Responses create handed to the plugin
- **WHEN** application `app` declares `interfaces.openaiResponses: {"base_url": "plugin://p1"}` and a client
  sends `POST /openai/v1/responses` with `"model": "app"`
- **THEN** the plugin receives `POST /openai/v1/responses`

#### Scenario: Messages handed to the plugin
- **WHEN** application `app` declares `interfaces.anthropicMessages: {"base_url": "plugin://p1"}` and a client
  sends `POST /anthropic/v1/messages` and then `POST /anthropic/v1/messages/count_tokens` with `"model": "app"`
- **THEN** the plugin receives `POST /anthropic/v1/messages` and then `POST /anthropic/v1/messages/count_tokens`

#### Scenario: Path after the plugin name
- **WHEN** application `app` declares `interfaces.openaiChatCompletions: {"base_url": "plugin://p1/v2"}` and a client
  sends `POST /openai/deployments/app/chat/completions?api-version=1`
- **THEN** plugin `p1` receives `POST /v2/openai/deployments/app/chat/completions?api-version=1`

### Requirement: Embedded origin answer
Core SHALL treat the plugin's answer as an HTTP origin's answer, under every other requirement of this capability:
retriable statuses, the relay, usage and the analytics row. The row's `response.upstream_uri` SHALL be the plugin url
the interface resolves to (`plugin://<plugin name>` when no path follows the name) for a deployment without
`upstreams`, and the attempt's upstream endpoint for one with them.

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

#### Scenario: Upstream uri with a path
- **WHEN** application `app` declares `interfaces.openaiChatCompletions: {"base_url": "plugin://p1/v2"}` and the
  `test-origin` plugin answers a chat completion for it `200`
- **THEN** the analytics row for the POST has `response.upstream_uri: plugin://p1/v2`

### Requirement: Embedded origin failure
Core SHALL answer the connect-failure `502`, without trying another upstream, when the plugin cannot serve: no
`plugins` entry has its name, the entry's provider is not loaded or does not serve requests, or creating the plugin
from its settings fails. A plugin call that fails, or completes with no answer, SHALL count as a failure to receive
the response headers.

#### Scenario: Missing plugin entry
- **WHEN** application `app` declares `interfaces.openaiChatCompletions: {"base_url": "plugin://missing"}`,
  no `plugins.missing` exists, and a client sends a chat completion for it
- **THEN** the client gets `502` with `message` and `display_message` `Failed to connect to upstream server`, the
  answer of "Connect failure"

#### Scenario: Provider that does not serve requests
- **WHEN** the interface names `plugins.p2` of type `test-echo`, whose provider only validates settings
- **THEN** the client gets `502` with the same body

#### Scenario: Plugin call fails
- **WHEN** the `test-origin` plugin serving application `app` fails the call instead of answering
- **THEN** the client gets `502` with the message `No route`, and one attempt is made

#### Scenario: Plugin call completes with no answer
- **WHEN** the `test-origin` plugin serving application `app` completes the call with `null`
- **THEN** the client gets `502` with the message `No route`

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

### Requirement: Calling a deployment from an origin plugin
An origin plugin SHALL be able to call a deployment while it answers a request. Core SHALL serve a `POST` the plugin
names to a deployments-POST action, `/openai/v1/chat/completions`, `/openai/v1/responses` or `/openai/responses` (not
in background), or `/anthropic/v1/messages`, with the body tree the plugin passes, as a request authenticated by the
key of the request the plugin answers, and SHALL hand the plugin the answer: status and headers first, then the body
as it arrives.

#### Scenario: Call reaches a model
- **WHEN** application `app` is served by plugin `p1` of type `test-origin`, a client sends a chat completion to
  `app` with `X-Test-Call: /openai/deployments/<model>/chat/completions`, and `<model>` is served by plugin `p2`
- **THEN** `p2` receives `POST /openai/deployments/<model>/chat/completions` with the body `p1` passed, and the client
  gets `p2`'s answer

#### Scenario: Call to a model behind an HTTP origin
- **WHEN** the call names a model served by an HTTP origin
- **THEN** that origin receives the chat completion with the body `p1` passed, an `API-KEY` holding a per-request key,
  and a `traceparent` of the client's trace

#### Scenario: Unknown target
- **WHEN** `p1` calls `/openai/deployments/missing/chat/completions`
- **THEN** `p1` gets the answer `404`, and the client gets that `404`

#### Scenario: Target the caller may not use
- **WHEN** `p1` calls a model whose `userRoles` the caller's roles do not include
- **THEN** `p1` gets the answer `403`, and the client gets that `403`

#### Scenario: Target with an interceptor
- **WHEN** `p1` calls a model that declares an interceptor served by an HTTP origin
- **THEN** the interceptor receives the body `p1` passed

#### Scenario: Responses and Messages calls
- **WHEN** `p1` calls `/openai/v1/responses`, and then `/anthropic/v1/messages`, with `"model": "<model>"` in the body
- **THEN** `p2` receives `POST /openai/v1/responses`, and then `POST /anthropic/v1/messages`

#### Scenario: Target fails mid-stream
- **WHEN** `p1` relays the streamed answer of a call to a model whose HTTP origin closes its connection mid-stream
- **THEN** the answer of `p1`'s call fails, and the client's connection is closed before the answer completes

#### Scenario: Call outside the create operations
- **WHEN** `p1` calls `/v1/ops/resource/share/list`, or `/openai/v1/responses` with `"background": true`
- **THEN** the call fails, no deployment is called and no row is written for it, and the client gets `502` with the
  message `No route`

### Requirement: Depth limit of calls from an origin plugin
Core SHALL bound a chain of calls from origin plugins. When the execution path of the request the plugin answers (the
deployments, and the routers in decision mode, from the client's request down to the plugin's own) already holds 32
names, Core SHALL serve no deployment for the call and SHALL answer it `400` with the message
`Call depth limit reached: 32`.

#### Scenario: Plugin calling its own deployment
- **WHEN** a client sends a chat completion to `app`, and `p1` answers every request for `app` by calling
  `/openai/deployments/app/chat/completions` and relaying the answer
- **THEN** `p1` receives 32 requests, the call of the 32nd gets the answer `400` with the message
  `Call depth limit reached: 32`, and the client gets that `400`

### Requirement: Calls end with the plugin's pass
When the plugin's pass ends, when a router in decision mode falls back from the plugin's deployment to another
candidate (router-deployment), or, for a router's decide request, when the decide call ends, Core SHALL cancel the
answers of its calls that are still open, and SHALL answer a call made after that `401` with the message
`Unknown api key`.

#### Scenario: Call after the plugin's pass ended
- **WHEN** `p1`'s pass has ended and its analytics row is written, and `p1` then makes a call
- **THEN** that call's answer is `401`

#### Scenario: Call after a fallback from the plugin's deployment
- **WHEN** a router in decision mode names candidates `app` and then a model, the `test-origin` plugin serving `app`
  fails the call, and while Core serves the model the plugin makes a call
- **THEN** that call's answer is `401`, and the call reaches no deployment

### Requirement: Caller in the plugin request
With each request Core SHALL hand an origin plugin the caller's identity: the user id of a caller with a JWT, the
project of a caller with an API key, and the caller's roles.

#### Scenario: Caller with an API key
- **WHEN** a client with API key `proxyKey1` (project `EPM-RTC-GPT`, role `default`) sends a chat completion to an
  application served by plugin `p1`
- **THEN** `p1` receives project `EPM-RTC-GPT`, roles `["default"]` and no user id

#### Scenario: Caller with a JWT
- **WHEN** a client authenticated by a JWT sends the same request
- **THEN** `p1` receives the token's user id and the roles it grants

### Requirement: Client gone before the plugin answers
When the client of a pass that an origin plugin serves goes away before the plugin answers, Core SHALL tell the plugin
that the request is cancelled. When the plugin then fails its answer, Core SHALL end the pass without trying another
upstream and without an analytics row; an answer it still gives SHALL be handled as an HTTP origin's answer after its
client went away. This SHALL NOT apply to a router's decide and notice requests, which router-deployment governs.

#### Scenario: Plugin told
- **WHEN** a client sends a chat completion to `app`, served by plugin `p1`, and closes its connection before `p1`
  answers
- **THEN** `p1` is told its request is cancelled

#### Scenario: No retry after the plugin fails
- **WHEN** the same happens for a model with two upstreams served by `p1`, and `p1` then fails its answer
- **THEN** `p1` is called once, and no analytics row is written

### Requirement: Cancelling a call
When Core cancels the plugin's answer ("Client gone from an embedded origin"), or the plugin cancels the answer of a
call, Core SHALL end the child pass as one whose client went away ("Client gone during the relay").

#### Scenario: Client gone while a call streams
- **WHEN** `p1`, serving application `app`, relays the streamed answer of a call to application `app2`, served by
  plugin `p2`, and the client of `app`'s pass closes its connection
- **THEN** both `p1`'s answer and `p2`'s answer are cancelled

#### Scenario: Plugin cancels a call
- **WHEN** `p1` cancels the answer of its call to `app2` after the first event and answers the client itself
- **THEN** `p2`'s answer is cancelled, and the client gets `p1`'s answer

### Requirement: Log records of an origin plugin
Core's JSON log line SHALL carry each SLF4J key-value pair of a record in the record's `Attributes`, under the pair's
key, after Core's own attributes: a string, number, boolean, list, map or JSON tree as that JSON value, null as null,
any other value as its text. A pair whose key Core sets itself SHALL be left out. The pairs SHALL NOT be copied to the
current span.

#### Scenario: A plugin's record with its fields
- **WHEN** a plugin logs `routed` with the pairs `model` = `"gpt-a"`, `attempt` = `2`, `stream` = `false`,
  `candidates` = `["a", "b"]` and `tags` = null
- **THEN** the line's `Attributes` hold `"model": "gpt-a"`, `"attempt": 2`, `"stream": false`,
  `"candidates": ["a", "b"]` and `"tags": null`, and its `Body` is `routed`

#### Scenario: Core's attribute is kept
- **WHEN** a record carries the pair `loggerName` = `"x"`
- **THEN** the line's `loggerName` attribute is the record's logger name

#### Scenario: Not on the span
- **WHEN** a record with the pair `model` = `"gpt-a"` is logged while a span records
- **THEN** the span gets Core's attributes and no `model` attribute

### Requirement: Translator plugin request
For an interface in `translator` mode whose translator's `baseUrl` is a plugin url, Core SHALL send no HTTP request
and SHALL call the plugin it names as a translator service at that url: the client's method, the url's path after the
name, then the ingress path and query, the headers of this capability and the body. "Embedded origin request",
"Embedded origin answer" and "Embedded origin failure" SHALL apply, and its calls through its host SHALL be served as
any origin plugin's are.

#### Scenario: Messages request handed to the translator plugin
- **WHEN** model `m` declares `baseUrl: http://adapter`, `interfaces.openaiChatCompletions: {}` and
  `interfaces.anthropicMessages: {"mode": "translator", "translator": {"out": "openaiChatCompletions", "baseUrl":
  "plugin://p1"}}`, `plugins.p1` is of type `test-origin`, and a client sends `POST /anthropic/v1/messages` with
  `"model": "m"`
- **THEN** the plugin receives `POST /anthropic/v1/messages` with an `API-KEY` holding a per-request key, and
  `http://adapter` receives no request until the plugin calls

#### Scenario: Translator plugin calls the output interface
- **WHEN** the plugin then calls `POST /openai/deployments/m/chat/completions` through its host and answers the
  client with the converted answer
- **THEN** `http://adapter` receives `POST /openai/deployments/m/chat/completions`, the client gets the plugin's
  answer, and the analytics log has one row for each of the two passes, the second with `parent_deployment: m`

#### Scenario: Named translator with a plugin
- **WHEN** `translators.a2c` is `{"in": "anthropicMessages", "out": "openaiChatCompletions", "baseUrl":
  "plugin://p1"}` and the model's `interfaces.anthropicMessages` is `{"mode": "translator", "translator": "a2c"}`
- **THEN** the plugin receives the client's `POST /anthropic/v1/messages`

#### Scenario: Translator's plugin missing
- **WHEN** the translator's `baseUrl` is `plugin://missing` and no `plugins.missing` exists
- **THEN** the client gets `502` with the message `Failed to connect to upstream server`
