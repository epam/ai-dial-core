## ADDED Requirements

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
