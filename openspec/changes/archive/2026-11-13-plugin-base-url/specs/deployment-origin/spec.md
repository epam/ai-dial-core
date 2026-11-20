## MODIFIED Requirements

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
