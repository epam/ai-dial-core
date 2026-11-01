# anthropic-messages-translation delta: tools

## ADDED Requirements

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
Core SHALL give a name the same alias at every site of a request. An alias SHALL differ from every name in the
request that needs no alias and from the request's other aliases: on a collision the suffix SHALL become
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

## MODIFIED Requirements

### Requirement: Mid-conversation system text in a translated request
Core SHALL send a `system`-role turn as a `system` input message, at its place in `input`, made of its text and
`mid_conv_system` texts as `input_text` parts and dropping other blocks. A `system`-role turn with
`clear_at: "next_user_message"` SHALL NOT be sent once a later user turn exists. A `mid_conv_system` block in an
assistant turn SHALL become a `system` input message sent before that turn. A `mid_conv_system` block in a user turn
SHALL become a `system` input message sent after that turn's `function_call_output` items and before its `user`
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

## REMOVED Requirements

### Requirement: Tools are refused
**Reason**: Tools are translated now, by the requirements this change adds. Only tools whose `type` starts with
`web_search` stay refused, under "Web search tools are refused".
**Migration**: None. A request that got `400` for its tools is now sent to the model.
