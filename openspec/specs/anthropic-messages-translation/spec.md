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
Core SHALL send a `system`-role turn as a `system` input message at its place in `input`, made of its text and
`mid_conv_system` texts as `input_text` parts, other blocks dropped. One with `clear_at: "next_user_message"` SHALL
NOT be sent once a later user turn exists. A `mid_conv_system` block SHALL become a `system` input message sent
before its assistant turn, or in a user turn after the turn's `function_call_output` items and before its `user`
message.

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

#### Scenario: Mid-conversation system text after tool results
- **WHEN** a builtin-translated request has a user turn `[{"type": "text", "text": "carry on"}, {"type":
  "mid_conv_system", "content": [{"type": "text", "text": "injected"}]}, {"type": "tool_result", "tool_use_id":
  "toolu_1", "content": "42"}]`
- **THEN** the origin `input` is `{"type": "function_call_output", "call_id": "toolu_1", "output": "42"}`, then a
  `system` message with the part `injected`, then a `user` message with the part `carry on`

### Requirement: Conversation text in a translated request
Core SHALL send a user turn as a `user` input message with one `input_text` part per non-empty text. It SHALL send
each run of an assistant turn's non-empty texts that no `tool_use` block interrupts as an `assistant` input message
whose `content` is those texts joined by a newline. A `compaction` block SHALL count as a text holding its `content`
string. A turn with no non-empty text SHALL send no message.

#### Scenario: Replayed compaction block
- **WHEN** a builtin-translated request has an assistant turn `[{"type": "compaction", "content": "Earlier: we fixed X."}]`
  followed by a user turn `continue`
- **THEN** the origin `input` is `{"type": "message", "role": "assistant", "content": "Earlier: we fixed X."}`, then a
  `user` message with the part `continue`

#### Scenario: Empty user turn
- **WHEN** a builtin-translated request has a user turn whose `content` is `""`
- **THEN** the origin `input` holds no message for it

#### Scenario: Text around a tool use
- **WHEN** a builtin-translated request has an assistant turn `[{"type": "text", "text": "Reading it."}, {"type":
  "tool_use", "id": "toolu_1", "name": "Read", "input": {}}, {"type": "text", "text": "Done."}]`
- **THEN** the origin `input` holds an `assistant` message `Reading it.`, the `function_call` item, and an `assistant`
  message `Done.`, in that order

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
no content block SHALL still hold one empty text block. Every event SHALL carry an `event:` line and the same `type`
in its data.

#### Scenario: Plain text answer
- **WHEN** the upstream streams `response.created`, an `output_text` part with the deltas of `Who’s there?` and
  `response.completed`
- **THEN** the client gets `message_start`, `ping`, `content_block_start`, one `content_block_delta` per delta,
  `content_block_stop`, `message_delta` and `message_stop`, and the deltas join to `Who’s there?`

#### Scenario: Answer with no text
- **WHEN** the upstream streams `response.created` and then `response.incomplete` with no output
- **THEN** the client gets `message_start`, `ping`, `content_block_start` and `content_block_stop` of one empty text
  block, then `message_delta` and `message_stop`

#### Scenario: Unknown upstream events ignored
- **WHEN** the upstream stream carries an event type the translation does not know, such as
  `response.output_text.annotation.added`
- **THEN** the client gets no event for it

#### Scenario: Answer with only a tool call
- **WHEN** the upstream streams `response.created`, one `function_call` item and `response.completed`
- **THEN** the client's only content block is the `tool_use` block, with no empty text block

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
`message_delta.delta.stop_reason` SHALL be, first match winning: `max_tokens` for an `incomplete` response whose
reason is `max_output_tokens`, `refusal` for the reason `content_filter`, `tool_use` for a stream that carried a
function call, `refusal` for a stream that carried a refusal, and `end_turn` otherwise. `stop_sequence` SHALL be null.
A refusal SHALL reach the client as text.

#### Scenario: Output cap reached
- **WHEN** the upstream ends with `response.incomplete` with `incomplete_details.reason: max_output_tokens`
- **THEN** the client's `message_delta` has `stop_reason: max_tokens`

#### Scenario: Refusal
- **WHEN** the upstream streams `response.refusal.delta` events and `response.completed`
- **THEN** the client gets a text block holding the refusal and `stop_reason: refusal`

#### Scenario: Turn ends on a tool call
- **WHEN** the upstream streams a `function_call` item and ends with `response.completed`
- **THEN** the client's `message_delta` has `stop_reason: tool_use`

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

### Requirement: Non-streaming requests are refused
Core SHALL answer `400` to a builtin-translated request whose `stream` is absent or `false`, with a message that names
non-streaming requests, and SHALL send nothing upstream.

#### Scenario: Non-streaming request
- **WHEN** a client sends a builtin-translated request with `stream: false`
- **THEN** Core answers `400` with a message containing `non-streaming`, and the origin receives no request

### Requirement: Content blocks without a translation are refused
Core SHALL answer `400` to a builtin-translated request whose user turn holds a content block other than `text`,
`mid_conv_system`, `compaction` or `tool_result`, whose assistant turn holds one other than `text`,
`mid_conv_system`, `compaction` or `tool_use`, or whose `tool_result` content holds one other than `text` or
`tool_reference`, with a message that names the block type and the role of its turn, and SHALL send nothing upstream.

#### Scenario: Image block
- **WHEN** a client sends a builtin-translated request whose user turn holds a block of type `image`
- **THEN** Core answers `400` with a message containing `'image'`, and the origin receives no request

#### Scenario: Tool use block
- **WHEN** a builtin-translated request has a user turn holding a `tool_use` block
- **THEN** Core answers `400` with a message containing `'tool_use' in the user turn`

#### Scenario: Image inside a tool result
- **WHEN** a builtin-translated request has a user turn whose `tool_result` content is `[{"type": "image", "source":
  {"type": "base64", "media_type": "image/png", "data": "AA=="}}]`
- **THEN** Core answers `400` with a message containing `'image'`, and the origin receives no request

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

### Requirement: Custom tools go upstream as function tools
Core SHALL send each `tools` entry with no `type` or with `type: "custom"` as a Responses function tool: `name` as
sent (`null` when absent), `description` when non-empty, `parameters` from `input_schema` without its top-level
`$schema` key (`{"type": "object", "properties": {}}` when absent), and `strict` `true` only when the tool sets
`strict: true`, else `false`. An empty `tools` list SHALL send no `tools`.

#### Scenario: Claude Code tools become function tools
- **WHEN** a client streams a builtin-translated request with `tools` `[{"name": "Read", "description": "Read a
  file", "input_schema": {"$schema": "http://json-schema.org/draft-07/schema#", "type": "object", "properties":
  {"file_path": {"type": "string"}}}}, {"name": "Bash", "input_schema": {"type": "object"}, "strict": true}]`
- **THEN** the origin body's `tools` start with `{"type": "function", "name": "Read", "description": "Read a file",
  "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}}}, "strict": false}` and
  `{"type": "function", "name": "Bash", "parameters": {"type": "object"}, "strict": true}`

### Requirement: Other tool types go upstream as sent
Core SHALL forward every `tools` entry whose `type` is set, is not `custom` and does not start with `web_search`
exactly as the client sent it: `bash_*`, `computer_*`, `text_editor_*`, `web_fetch_*`, `code_execution_*`, `mcp_toolset` and
types Core does not know alike. Core SHALL drop no tool, so a deployment that cannot run one rejects the request
itself.

#### Scenario: Versioned and toolset tools forwarded
- **WHEN** the same request also carries `{"type": "bash_20250124", "name": "bash"}` and
  `{"type": "mcp_toolset", "mcp_server_name": "github"}` in `tools`
- **THEN** the origin body's `tools` hold both objects unchanged, after the two function tools

### Requirement: Web search tools are refused
Core SHALL answer `400` to a builtin-translated request whose `tools` hold an entry whose `type` starts with
`web_search`, with a message naming that type, and SHALL send nothing upstream.

#### Scenario: Web search tool
- **WHEN** a client streams a builtin-translated request with `tools: [{"type": "web_search_20250305", "name":
  "web_search"}]`
- **THEN** Core answers `400` with a message containing `web_search_20250305`, and the origin receives no request

#### Scenario: Responses-style web search tool
- **WHEN** a client streams a builtin-translated request with `tools: [{"type": "web_search"}]`
- **THEN** Core answers `400` with a message containing `web_search`, and the origin receives no request

### Requirement: Malformed tool fields are refused
Core SHALL answer `400` to a builtin-translated request whose `tools` is present, non-null and not an array, holds an
entry that is not an object, or holds an entry whose `name` or `type` is present, non-null and not a string, or whose
`tool_choice` is present, non-null and not an object, with a message that names the field, and SHALL send nothing
upstream.

#### Scenario: Tool choice as a string
- **WHEN** a client streams a builtin-translated request with `tool_choice: "auto"`
- **THEN** Core answers `400` with a message containing `'tool_choice'`, and the origin receives no request

### Requirement: Tool choice is translated
Core SHALL send a `tool_choice` of type `auto` as `"auto"`, `any` as `"required"`, `none` as `"none"` and `tool` as
`{"type": "function", "name": <its name>}`, and no `tool_choice` for any other type. Whatever the type, a
`disable_parallel_tool_use` SHALL be sent as `parallel_tool_calls` with the opposite value.

#### Scenario: Forced tool without parallel calls
- **WHEN** the request carries `tool_choice: {"type": "tool", "name": "Read", "disable_parallel_tool_use": true}`
- **THEN** the origin body has `tool_choice: {"type": "function", "name": "Read"}` and `parallel_tool_calls: false`

### Requirement: Tool names the upstream rejects are aliased
Core SHALL send a function name that does not match `^[a-zA-Z_][a-zA-Z0-9_-]{2,63}$` as an alias: the name with
every character outside `[a-zA-Z0-9_-]` replaced by `_`, prefixed `t_` if it then starts with neither a letter nor
`_`, cut so that `_` and the first 8 hex digits of the SHA-256 of the UTF-8 name fit in 64 characters, then joined to
them. It SHALL do so
in `tools`, a function `tool_choice` and replayed `function_call` items alike.

#### Scenario: Long MCP name aliased
- **WHEN** a builtin-translated request declares tools `Read` and
  `mcp__dial-knowledge-base__search_documents_by_semantic_similarity_v2`, names the second in `tool_choice`, and
  replays a `tool_use` of it
- **THEN** the origin body names it `mcp__dial-knowledge-base__search_documents_by_semantic__7bc2a2fa` in `tools`, in
  `tool_choice` and in the `function_call` item, and names `Read` unchanged

### Requirement: Aliases never collide within a request
Core SHALL give a name the same alias at every site of a request. An alias SHALL differ from every function name in
the request (function tools, a function `tool_choice`, replayed `function_call` items) that needs no alias and from
the request's other aliases: on a collision the suffix SHALL become
`_<8 hex>_<n>`, n = 1, 2, … until the alias is free, with the head cut so that the whole alias fits in 64 characters.
With no collision a name SHALL get the same alias in every request.

#### Scenario: Alias taken by a real name
- **WHEN** a builtin-translated request declares tools `a` and `a_ca978112`
- **THEN** the origin body names them `a_ca978112_1` and `a_ca978112`

### Requirement: Aliased names are restored for the client
Core SHALL give the client the original name wherever the upstream answer names an alias issued for the same request,
and SHALL pass any other name through unchanged.

#### Scenario: Streamed call under its alias
- **WHEN** the upstream streams a `function_call` item named
  `mcp__dial-knowledge-base__search_documents_by_semantic__7bc2a2fa` for that request
- **THEN** the client's `content_block_start` names the tool
  `mcp__dial-knowledge-base__search_documents_by_semantic_similarity_v2`

### Requirement: Tool uses in the history go upstream as function calls
Core SHALL send each `tool_use` block of an assistant turn, at its place in the turn, as a `function_call` item with
`call_id` from its `id`, its `name`, and `arguments` set to its `input` serialised as the reference translator does:
`{"city": "Kyiv"}` with a space after each `:` and `,`, non-ASCII characters escaped, and `{}` when `input` is absent
or empty.

#### Scenario: Assistant turn with a tool use
- **WHEN** a builtin-translated request replays an assistant turn `[{"type": "text", "text": "Reading it."}, {"type":
  "tool_use", "id": "toolu_1", "name": "Read", "input": {"file_path": "a.txt"}}]`
- **THEN** the origin `input` holds `{"type": "message", "role": "assistant", "content": "Reading it."}` followed by
  `{"type": "function_call", "call_id": "toolu_1", "name": "Read", "arguments": "{\"file_path\": \"a.txt\"}"}`

### Requirement: Tool results in the history go upstream as function call outputs
Core SHALL send each `tool_result` block of a user turn as a `function_call_output` item with `call_id` from its
`tool_use_id`, ahead of the turn's other items. Its `output` SHALL be its string content, or its non-empty `text`
blocks joined by a newline with `tool_reference` blocks dropped, empty when there is neither, and prefixed `Error: ` when
`is_error` is true.

#### Scenario: Tool results answer the calls before the user text
- **WHEN** after an assistant turn calling `toolu_1` and `toolu_2` the next user turn is `[{"type": "tool_result",
  "tool_use_id": "toolu_1", "content": [{"type": "text", "text": "42"}]}, {"type": "tool_result", "tool_use_id":
  "toolu_2", "content": "file not found", "is_error": true}, {"type": "text", "text": "and now?"}]`
- **THEN** the origin `input` continues with `{"type": "function_call_output", "call_id": "toolu_1", "output": "42"}`,
  `{"type": "function_call_output", "call_id": "toolu_2", "output": "Error: file not found"}` and then the `user`
  message with the part `and now?`

### Requirement: Tool calls stream as tool_use blocks
Core SHALL stream each upstream `function_call` output item as a `tool_use` block: `content_block_start` with `id`
from `call_id`, the client's name for the tool and `input: {}`, closing any open block first; each argument delta as
an `input_json_delta`, or, when no non-empty delta came, the whole `arguments` once from the call's first `*.done`
event that carries them; and `content_block_stop` at the call's `response.output_item.done`.

#### Scenario: Streamed tool call
- **WHEN** the upstream streams text `Reading the file.`, then a `function_call` item `{"call_id": "call_1", "name":
  "Read"}` with argument deltas `{"file_path":` and ` "a.txt"}`, its `response.function_call_arguments.done` and
  `response.output_item.done`, then `response.completed`
- **THEN** the client gets the text block and its `content_block_stop`, then `content_block_start` with
  `{"type": "tool_use", "id": "call_1", "name": "Read", "input": {}}`, two `input_json_delta` events carrying the two
  deltas, and `content_block_stop`

#### Scenario: Arguments only in the done event
- **WHEN** the upstream sends no argument delta for a call and its `response.function_call_arguments.done` carries
  `{"query": "incident code"}`
- **THEN** the client gets one `input_json_delta` with `{"query": "incident code"}`, and no second one at the call's
  `response.output_item.done`
