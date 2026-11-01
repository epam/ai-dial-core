# anthropic-messages-translation Specification

## Purpose
How Core serves the Anthropic Messages API on a model through a builtin translator: one Core pass that translates the
request to the model's own Responses interface and the answer back. It covers what the upstream receives, the event
stream the client gets, the limits charged, and the requests Core refuses.

## Requirements

### Requirement: One Core pass through a builtin translator
For `POST /anthropic/v1/messages` on a Model whose `anthropicMessages` interface is in `translator` mode with a
builtin translator (one declaring `builtin: "anthropicMessagesToOpenaiResponses"` instead of `baseUrl`), Core SHALL
translate the request to the Responses API, send it to the model's own `openaiResponses` interface and return the
answer translated back, all inside the client's request. Core SHALL send nothing to a translator service.

#### Scenario: Served in one pass
- **WHEN** model `gpt-builtin` declares `interfaces.openaiResponses.base_url: http://localhost:4848` and
  `interfaces.anthropicMessages: {"mode": "translator", "translator": "builtinResponses"}`, where
  `translators.builtinResponses` declares `builtin: anthropicMessagesToOpenaiResponses`, and a client sends a
  streaming `POST /anthropic/v1/messages` for it
- **THEN** the origin receives exactly one request, `POST /openai/v1/responses`, and the client gets `200` with an
  Anthropic event stream

### Requirement: Upstream request of a builtin translation
Core SHALL `POST` the translated request to the URI `POST /openai/v1/responses` resolves to for the model
(`overridePaths` and a legacy `responsesEndpoint` apply), with `model` = `overrideName` (else the name), `stream: true`,
`store: false` and `include: ["reasoning.encrypted_content"]`. It SHALL carry a per-request `API-KEY` and, for a model
with `upstreams`, the upstream's `X-UPSTREAM-ENDPOINT` and `X-UPSTREAM-KEY`. Core SHALL drop `anthropic-version` and
add no `X-DIAL-DEPLOYMENT-ID`.

#### Scenario: Upstream request
- **WHEN** `gpt-builtin` declares `overrideName: gpt-5-upstream` and one upstream with
  `responsesEndpoint: http://responses-upstream/v1/responses` and `key: upstreamKey`, and a client with
  `api-key: proxyKey1` and `anthropic-version: 2023-06-01` sends `{"model": "gpt-builtin", "max_tokens": 100,
  "stream": true, "system": "Be brief", "messages": [{"role": "user", "content": "Knock, knock"}]}`
- **THEN** the origin body has `model: gpt-5-upstream`, `stream: true`, `store: false`,
  `include: ["reasoning.encrypted_content"]`, `instructions: "Be brief"`, `max_output_tokens: 100` and
  `input[0].content[0].text: "Knock, knock"`; the origin request's `API-KEY` is not `proxyKey1`; it carries
  `X-UPSTREAM-ENDPOINT: http://responses-upstream/v1/responses` and `X-UPSTREAM-KEY: upstreamKey`, and no
  `anthropic-version` or `X-DIAL-DEPLOYMENT-ID`

### Requirement: System prompt in a translated request
Core SHALL send a string `system` as `instructions`. It SHALL send a block-list `system` as its non-empty `text`
blocks joined by a blank line (`\n\n`), dropping every other block. An empty string, or a list with no text, SHALL
send no `instructions`.

#### Scenario: System blocks joined
- **WHEN** a builtin-translated request has `system: [{"type": "text", "text": "first"}, {"type": "text", "text": "second"}]`
- **THEN** the origin body has `instructions: "first\n\nsecond"`

#### Scenario: System with no text
- **WHEN** a builtin-translated request has `system: [{"cache_control": {"type": "ephemeral"}}]`
- **THEN** the origin body has no `instructions`

### Requirement: Mid-conversation system text in a translated request
Core SHALL send a `system`-role turn as a `system` input message, at its place in `input`, made of its text and
`mid_conv_system` texts as `input_text` parts and dropping other blocks. A `system`-role turn with
`clear_at: "next_user_message"` SHALL NOT be sent once a later user turn exists. A `mid_conv_system` block in a user
or assistant turn SHALL become a `system` input message sent before that turn.

#### Scenario: System turn kept in place
- **WHEN** a builtin-translated request has `messages: [{"role": "system", "content": "hook-injected context"},
  {"role": "user", "content": "hi"}]`
- **THEN** the origin `input` is a `system` message with the part `hook-injected context`, then a `user` message with
  the part `hi`

#### Scenario: Expired system turn not sent
- **WHEN** the messages are a user turn `first`, a system turn with `clear_at: "next_user_message"`, an assistant turn
  `ok` and a user turn `second`
- **THEN** the origin `input` holds the `first`, `ok` and `second` messages and no `system` message

#### Scenario: Nested mid-conversation block joins its system turn
- **WHEN** a system turn holds `[{"type": "text", "text": "a"}, {"type": "mid_conv_system", "content": [{"type": "text", "text": "b"}]}]`
- **THEN** the origin `input` has one `system` message with the parts `a` and `b`

#### Scenario: Mid-conversation block in a user turn
- **WHEN** a user turn holds `[{"type": "text", "text": "carry on"}, {"type": "mid_conv_system", "content": "injected"}]`
- **THEN** the origin `input` is a `system` message with the part `injected`, then a `user` message with the part
  `carry on`

### Requirement: Conversation text in a translated request
Core SHALL send a user turn as a `user` input message with one `input_text` part per non-empty text. It SHALL send an
assistant turn as an `assistant` input message whose `content` is the turn's non-empty texts joined by a newline. A
`compaction` block SHALL count as a text holding its `content` string. A turn with no non-empty text SHALL send no
message.

#### Scenario: Replayed compaction block
- **WHEN** a builtin-translated request has an assistant turn `[{"type": "compaction", "content": "Earlier: we fixed X."}]`
  followed by a user turn `continue`
- **THEN** the origin `input` is `{"type": "message", "role": "assistant", "content": "Earlier: we fixed X."}`, then a
  `user` message with the part `continue`

#### Scenario: Empty user turn
- **WHEN** a builtin-translated request has a user turn whose `content` is `""`
- **THEN** the origin `input` holds no message for it

### Requirement: Generation settings in a translated request
Core SHALL send `max_tokens` as `max_output_tokens`, raising 1 to 15 to 16. It SHALL send `temperature` and `top_p`
as they are, `service_tier` `auto` as `auto` and `standard_only` as `default` (other values dropped), and
`metadata.user_id` as `user` when at most 64 characters long (a longer one dropped). Core SHALL drop `cache_control`
and every field with no Responses counterpart, `thinking` and `output_config` included, and not refuse the request.

#### Scenario: Small output budget raised
- **WHEN** a builtin-translated request has `max_tokens: 1`
- **THEN** the origin body has `max_output_tokens: 16`, and with `max_tokens: 0` it has `max_output_tokens: 0`

#### Scenario: Sampling fields
- **WHEN** a builtin-translated request has `temperature: 0.5`, `top_p: 0.9`, `top_k: 40` and `stop_sequences: ["STOP"]`
- **THEN** the origin body has `temperature: 0.5` and `top_p: 0.9`, and no `top_k`, `stop_sequences` or `stop`

#### Scenario: Service tier
- **WHEN** a builtin-translated request has `service_tier: "standard_only"`
- **THEN** the origin body has `service_tier: "default"`

#### Scenario: Long user id dropped, not truncated
- **WHEN** a builtin-translated request has a `metadata.user_id` longer than 64 characters
- **THEN** the origin body has no `user`, and a `metadata.user_id` of exactly 64 characters is sent unchanged as
  `user`

#### Scenario: Cache control dropped
- **WHEN** a builtin-translated request has a top-level `cache_control: {"type": "ephemeral"}`
- **THEN** the origin body is the same as without it

### Requirement: Translated event stream
Core SHALL answer `200` with the upstream's Responses events translated to Anthropic events: `message_start` and
`ping` first, then for each text part `content_block_start`, one `content_block_delta` per text delta and
`content_block_stop`, with at most one block open at a time, then `message_delta` and `message_stop`. An answer with
no text SHALL still hold one empty text block. Every event SHALL carry an `event:` line and the same `type` in its data.

#### Scenario: Plain text answer
- **WHEN** the upstream streams `response.created`, an `output_text` part with the deltas of `Who’s there?` and
  `response.completed`
- **THEN** the client gets `message_start`, `ping`, `content_block_start`, one `content_block_delta` per delta,
  `content_block_stop`, `message_delta` and `message_stop`, and the deltas join to `Who’s there?`

#### Scenario: Answer with no text
- **WHEN** the upstream stream reaches `response.incomplete` without having sent any text
- **THEN** the client gets `message_start`, `ping`, `content_block_start` and `content_block_stop` of one empty text
  block, then `message_delta` and `message_stop`

#### Scenario: Unknown upstream events ignored
- **WHEN** the upstream stream carries an event type the translation does not know, such as
  `response.output_text.annotation.added`
- **THEN** the client gets no event for it

### Requirement: Message identity in a translated stream
The `message_start` message SHALL carry an id Core generates, never the upstream's response id. Its `model` SHALL be
the `model` of the upstream's `response.created` event, else the model the client asked for. A stream with no
`response.created` SHALL still send `message_start` and `ping` before its first content block or its terminal
answer. An `error` that arrives before anything else SHALL be sent alone, with no `message_start`.

#### Scenario: Provider response id not sent
- **WHEN** the upstream's `response.created` carries `id: resp_075fcfcf…`
- **THEN** no event the client gets contains `resp_075fcfcf`

#### Scenario: Stream without response.created
- **WHEN** the upstream stream starts with `response.content_part.added`
- **THEN** the client gets `message_start` and `ping` before the first `content_block_start`, with the requested
  model as `model`

#### Scenario: Error before anything else
- **WHEN** the upstream stream starts with `response.failed`
- **THEN** the client gets one `error` event and nothing else

### Requirement: Stop reason of a translated stream
`message_delta.delta.stop_reason` SHALL be `max_tokens` for an `incomplete` response whose reason is
`max_output_tokens`, `refusal` for the reason `content_filter` or a stream that carried a refusal, and `end_turn`
otherwise. `stop_sequence` SHALL be null. A refusal SHALL reach the client as text.

#### Scenario: Output cap reached
- **WHEN** the upstream ends with `response.incomplete` with `incomplete_details.reason: max_output_tokens`
- **THEN** the client's `message_delta` has `stop_reason: max_tokens`

#### Scenario: Refusal
- **WHEN** the upstream streams `response.refusal.delta` events and `response.completed`
- **THEN** the client gets a text block holding the refusal and `stop_reason: refusal`

### Requirement: Truncated or failed stream ends in an error event
A translated stream SHALL send nothing after its `message_stop` or `error`. An upstream stream that ends with no
terminal event SHALL end in an `api_error`. A `response.failed` or `error` event SHALL become an `error` with the
upstream's message and the type its `type` or `code` names: `invalid_request_error`, `rate_limit_error` for
`rate_limit_exceeded`, `timeout_error` for `vector_store_timeout`, else `api_error`. A mid-stream upstream
connection failure SHALL reset the client connection.

#### Scenario: Stream without a terminal event
- **WHEN** the upstream stream ends after text deltas and before `response.completed`
- **THEN** the client's last event is `error` with `type: api_error` and the message
  `The upstream stream ended before the response completed`, and the client gets no `message_stop`

#### Scenario: Failed response
- **WHEN** the upstream sends `response.failed` with `error: {"code": "server_error", "message": "upstream exploded"}`
- **THEN** the client gets an `error` event with `type: api_error` and `message: upstream exploded`, and no event
  after it

#### Scenario: Failure caused by the request
- **WHEN** the upstream sends `response.failed` with an error whose `type` is `invalid_request_error`
- **THEN** the client's `error` event has `type: invalid_request_error`

#### Scenario: Flat error event
- **WHEN** the upstream sends `{"type": "error", "code": "rate_limit_exceeded", "message": "slow down"}`
- **THEN** the client gets an `error` event with `type: rate_limit_error` and `message: slow down`

#### Scenario: Upstream connection fails mid-stream
- **WHEN** the upstream connection fails after some events of a translated stream
- **THEN** the translated stream emits no further event, and the failure is passed on to the client response, which
  Core resets

### Requirement: Usage of a translated stream
`message_delta.usage` SHALL hold `cache_read_input_tokens` as the upstream's `cached_tokens`,
`cache_creation_input_tokens` as its `cache_write_tokens`, each capped by what remains of `input_tokens`, and
`input_tokens` as the upstream's `input_tokens` minus both. `output_tokens` SHALL be the upstream's, and
`output_tokens_details.thinking_tokens` its reasoning tokens capped at `output_tokens`, sent only when non-zero.

#### Scenario: Cache reads come out of the input total
- **WHEN** the upstream reports `input_tokens: 12` with `input_tokens_details.cached_tokens: 4` and `output_tokens: 7`
- **THEN** the client's `message_delta.usage` has `input_tokens: 8`, `cache_read_input_tokens: 4`,
  `cache_creation_input_tokens: 0` and `output_tokens: 7`

#### Scenario: Reasoning tokens as thinking tokens
- **WHEN** the upstream reports `output_tokens_details.reasoning_tokens` greater than zero
- **THEN** the client's usage has `output_tokens_details.thinking_tokens` equal to it

### Requirement: Limits of a builtin translation charged once
Core SHALL check the initiator's limits for the model before it sends the translated request. It SHALL charge them
once, on this request, from the translated usage, whose input, cache-read and cache-write tokens add up to the
upstream's input total.

#### Scenario: Charged once
- **WHEN** the upstream reports `input_tokens: 10` and `output_tokens: 8` for a builtin-translated request
- **THEN** the day token usage of the model in `GET /v1/deployments/gpt-builtin/limits` grows by 18

#### Scenario: Limit reached
- **WHEN** the caller has used the model's token limit and sends another builtin-translated request
- **THEN** Core answers `429` and sends nothing upstream

### Requirement: Access checked on both interfaces
For a builtin-translated request, Core SHALL check consent and per-request-key access against the features of both the
`anthropicMessages` interface and the model's `openaiResponses` interface. It SHALL answer `403` without sending
anything upstream when either has `consentRequired: true` and the caller's consent is missing, or when either has
`accessibleByPerRequestKey: false` and the request carries a per-request key.

#### Scenario: Consent required by the Responses interface
- **WHEN** model `gpt-builtin-consent` declares `features.consentRequired: true` on `interfaces.openaiResponses` only,
  and a user who has not accepted its consent sends `POST /anthropic/v1/messages` for it
- **THEN** Core answers `403`

#### Scenario: Responses interface closed to per-request keys
- **WHEN** model `gpt-builtin-closed` declares `features.accessibleByPerRequestKey: false` on
  `interfaces.openaiResponses` only, and an application calls `POST /anthropic/v1/messages` for it with its
  per-request key
- **THEN** Core answers `403` and the origin receives no `POST /openai/v1/responses`

### Requirement: Tools are refused
Core SHALL answer `400` to a builtin-translated request that carries a non-null `tools` or `tool_choice`, with a
message that names tools, and SHALL send nothing upstream.

#### Scenario: Request with tools
- **WHEN** a client sends a streaming builtin-translated request with `tools: [{"name": "get_weather", "input_schema": {"type": "object"}}]`
- **THEN** Core answers `400` with a message containing `Tools`, and the origin receives no request

### Requirement: Non-streaming requests are refused
Core SHALL answer `400` to a builtin-translated request whose `stream` is absent or `false`, with a message that names
non-streaming requests, and SHALL send nothing upstream.

#### Scenario: Non-streaming request
- **WHEN** a client sends a builtin-translated request with `stream: false`
- **THEN** Core answers `400` with a message containing `non-streaming`, and the origin receives no request

### Requirement: Content blocks without a translation are refused
Core SHALL answer `400` to a builtin-translated request whose user or assistant turn holds a content block other than
`text`, `mid_conv_system` or `compaction`, with a message that names the block type and the role of its turn, and
SHALL send nothing upstream.

#### Scenario: Image block
- **WHEN** a client sends a builtin-translated request whose user turn holds a block of type `image`
- **THEN** Core answers `400` with a message containing `'image'`, and the origin receives no request

#### Scenario: Tool use block
- **WHEN** a builtin-translated request has an assistant turn holding a `tool_use` block
- **THEN** Core answers `400` with a message containing `'tool_use' in the assistant turn`

### Requirement: Malformed conversations are refused
Core SHALL answer `400` to a builtin-translated request whose `messages` is not an array, with a message that names
`messages`, or that holds a turn whose role is not `user`, `assistant` or `system`, with the message
`Unknown message role: '<role>'`. Core SHALL send nothing upstream for either.

#### Scenario: Unknown role
- **WHEN** a client sends a builtin-translated request with a turn of role `tool`
- **THEN** Core answers `400` with the message `Unknown message role: 'tool'`, and the origin receives no request

#### Scenario: Messages not an array
- **WHEN** a client sends a builtin-translated request with `messages: "hi"`
- **THEN** Core answers `400` with a message containing `'messages'`, and the origin receives no request

### Requirement: count_tokens not served through a builtin translator
Core SHALL answer `503` to `POST /anthropic/v1/messages/count_tokens` for a model whose `anthropicMessages` interface
is served by a builtin translator, and SHALL send nothing upstream.

#### Scenario: count_tokens on a builtin-translated model
- **WHEN** a client sends `POST /anthropic/v1/messages/count_tokens` for `gpt-builtin`
- **THEN** Core answers `503`

### Requirement: Builtin translation refused for a model with interceptors
Core SHALL answer `503` to a builtin-translated request for a model that has interceptors, with a message that names
interceptors, and SHALL send nothing to the interceptors or upstream.

#### Scenario: Model with an interceptor
- **WHEN** model `gpt-builtin-intercepted` declares `interceptors: ["interceptor1"]` and a builtin-translated
  `anthropicMessages` interface, and a client sends `POST /anthropic/v1/messages` for it
- **THEN** Core answers `503` with a message containing `interceptors`
