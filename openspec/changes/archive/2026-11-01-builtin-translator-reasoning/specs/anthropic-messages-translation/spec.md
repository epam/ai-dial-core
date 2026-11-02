# Spec Delta

## ADDED Requirements

### Requirement: Reasoning settings from thinking and effort
A builtin translation SHALL set `reasoning` only from the request after its Messages defaults, unless thinking is
disabled. `effort` is the named effort (next requirement), verbatim. `summary` is `"auto"` when `thinking` is present
and not null, unless its `display` is `omitted` or the effort is `none`. No effort comes from a thinking type or `budget_tokens`. With
neither named it sets no `reasoning`. The model's `openaiResponses` defaults then fill keys it left unset; the
scenarios use a model with none.

#### Scenario: Adaptive thinking with an effort
- **WHEN** the client sends `thinking: {"type": "adaptive"}` and `output_config: {"effort": "high"}`
- **THEN** the upstream request carries `reasoning: {"effort": "high", "summary": "auto"}`

#### Scenario: A token budget names no effort
- **WHEN** the client sends `thinking: {"type": "enabled", "budget_tokens": 32000}` and no effort
- **THEN** the upstream request carries `reasoning: {"summary": "auto"}`

#### Scenario: Omitted display asks for no summary
- **WHEN** the client sends `thinking: {"type": "adaptive", "display": "omitted"}`, once with
  `output_config: {"effort": "high"}` and once without an effort
- **THEN** the first upstream request carries `reasoning: {"effort": "high"}` and the second carries no `reasoning`

#### Scenario: Nothing named
- **WHEN** the client sends neither `thinking` nor an effort
- **THEN** the upstream request carries no `reasoning` field

### Requirement: Effort from a visible system message
The effort a builtin translation names SHALL be the `output_config.effort` of the last visible (not expired by
`clear_at`) `system` message naming one, else the request's `output_config.effort`.

#### Scenario: A visible system message's effort wins
- **WHEN** the request names `output_config: {"effort": "low"}` and its last message is a `system` message with
  `clear_at: "next_user_message"` and `output_config: {"effort": "max"}`
- **THEN** the upstream request carries `reasoning: {"effort": "max"}`

### Requirement: Disabled thinking turns reasoning off
For a request a builtin translator serves, the translation SHALL set `reasoning` to `{"effort": "none"}`, with no
other key, when `thinking.type` is `disabled`, whatever effort the request or a `system` message names. The model's
`openaiResponses` defaults may still add keys, as "Reasoning settings from thinking and effort" says; the scenarios
use a model with none.

#### Scenario: Disabled thinking outranks an effort
- **WHEN** the client sends `thinking: {"type": "disabled"}` and `output_config: {"effort": "high"}`
- **THEN** the upstream request carries `reasoning: {"effort": "none"}`

### Requirement: Unsupported reasoning effort is refused
For a request a builtin translator serves, Core SHALL answer `400` and send nothing upstream when the effort it would
send is not one of `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`. The message names the value (a value
that is not a string by its JSON text) and the accepted ones, worded as the translator service words it. An effort
that disabled thinking overrides is not checked.

#### Scenario: Unknown effort
- **WHEN** the client sends `output_config: {"effort": "xmax"}`
- **THEN** Core answers `400` with the message
  `Unsupported 'output_config.effort': 'xmax'. Expected one of: none, minimal, low, medium, high, xhigh, max`
- **AND** the upstream receives no request

#### Scenario: Non-string effort
- **WHEN** the client sends `output_config: {"effort": 5}`
- **THEN** Core answers `400` with the message
  `Unsupported 'output_config.effort': '5'. Expected one of: none, minimal, low, medium, high, xhigh, max`
- **AND** the upstream receives no request

#### Scenario: Unknown effort under disabled thinking
- **WHEN** the client sends `thinking: {"type": "disabled"}` and `output_config: {"effort": "xmax"}`
- **THEN** the upstream request carries `reasoning: {"effort": "none"}`

### Requirement: Reasoning replay carrier
Core SHALL hand the client each encrypted reasoning item as a carrier: the JSON text
`{"dial_translator_reasoning_wrapper":{"version":2,"id":<id>,"encrypted_content":<ciphertext>}}` with no spaces,
`id` left out when the item has none. The values are the item's `id` and `encrypted_content` as a
`POST /openai/v1/responses` to the same model answers them, never decoded or rewritten. It is the translator
service's format, so a conversation moves between the two either way.

#### Scenario: The carrier holds the values Core stamped
- **WHEN** a model with configured upstreams `up-a` and `up-b` streams a reasoning item with id `rs_1` and
  `encrypted_content` `cipher-xyz`
- **THEN** the carrier's `id` and `encrypted_content` equal the ones a `POST /openai/v1/responses` to the same model
  answers for that item from the same upstream (the `dialenc_…` id and the `dialenc:…` ciphertext)

### Requirement: Thinking blocks replayed as reasoning items
In an assistant turn, Core SHALL send a `thinking` block whose `signature`, or a `redacted_thinking` block whose
`data`, holds a valid carrier as a `reasoning` input item at the block's place among the turn's text and tool calls.
The item has the carrier's `id` and `encrypted_content`, and a `summary` with one `summary_text` holding the block's
non-empty `thinking` text, else an empty `summary`.

#### Scenario: A thinking block between two texts
- **WHEN** an assistant turn holds text `before`, a `thinking` block with `thinking` `plan` and a carrier of id
  `rs_1` and `encrypted_content` `cipher-xyz`, and text `after`
- **THEN** the upstream `input` holds, in order, an assistant message `before`, the item
  `{"type": "reasoning", "id": "rs_1", "encrypted_content": "cipher-xyz", "summary": [{"type": "summary_text", "text": "plan"}]}`
  and an assistant message `after`

#### Scenario: A redacted thinking block
- **WHEN** an assistant turn holds a `redacted_thinking` block whose `data` is a carrier with `encrypted_content`
  `cipher-xyz` and no `id`
- **THEN** the upstream `input` holds `{"type": "reasoning", "encrypted_content": "cipher-xyz", "summary": []}`

### Requirement: Thinking blocks without a carrier are dropped
Core SHALL drop, without an error, a `thinking` or `redacted_thinking` block that holds no valid carrier: a Claude
signature, an empty or missing value, a value that is not a string, text that is not a JSON object, text after the
JSON object, keys other than the wrapper's, a duplicate key, a version other than the integer `2`, an empty
`encrypted_content` or an empty `id`.

#### Scenario: A Claude-signed thinking block
- **WHEN** an assistant turn holds text `before`, a `thinking` block with a Claude `signature`, and text `after`
- **THEN** Core answers `200` and the upstream `input` holds one assistant message `before\nafter` and no
  `reasoning` item

#### Scenario: An older carrier version
- **WHEN** an assistant turn holds a `redacted_thinking` block whose `data` is a carrier with `"version": 1`
- **THEN** the upstream `input` holds no `reasoning` item

### Requirement: Replayed reasoning keeps its upstream
On a model with configured upstreams, Core SHALL send a request that replays carriers to the upstream their stamped
values name, each item restored to the provider's own id and ciphertext, as it routes a Responses request that
replays the same items. A carrier naming an upstream the model no longer has is answered `409`, carriers naming two
different upstreams `400`, and nothing is sent upstream.

#### Scenario: Turn 2 goes to turn 1's upstream
- **WHEN** a model with upstreams `up-a` and `up-b` streams turn 1 with an encrypted reasoning item `rs_1` /
  `cipher-xyz`, and the client sends turn 2 replaying turn 1's `thinking` block, several times
- **THEN** every turn-2 upstream request has the `X-UPSTREAM-ENDPOINT` and `X-UPSTREAM-KEY` of turn 1's upstream
- **AND** its `reasoning` item has `id` `rs_1` and `encrypted_content` `cipher-xyz`

#### Scenario: A carrier from the translator service
- **WHEN** turn 2 replays a carrier holding the id and ciphertext Core's Responses pass stamped for `up-b` on the same
  model, as the translator service writes it
- **THEN** the upstream request goes to `up-b` with `id` `rs_1` and `encrypted_content` `cipher-xyz`

#### Scenario: The carrier's upstream is gone
- **WHEN** turn 2 replays a carrier whose stamped values name upstream `up-gone`, which the model does not have
- **THEN** Core answers `409` and the upstream receives no request

#### Scenario: Carriers from two upstreams
- **WHEN** turn 2 replays one carrier stamped for `up-a` and one stamped for `up-b`
- **THEN** Core answers `400` and the upstream receives no request

### Requirement: Streamed reasoning becomes a thinking block
Core SHALL turn a reasoning item's non-empty summary and raw reasoning text deltas into a `thinking` block, opened
at the first such delta with empty `thinking` and `signature`. Each delta is a `thinking_delta`; the first delta of
each further summary or content part starts with `\n\n`. When the item completes with encrypted content, a
`signature_delta` with its carrier comes before `content_block_stop`; without encrypted content the block closes
unsigned.

#### Scenario: Summary streamed, then the carrier
- **WHEN** the upstream streams summary deltas `Let me` and ` think` for reasoning item `rs_1`, then
  `response.output_item.done` for it with `encrypted_content`, then answer text
- **THEN** the client gets `content_block_start` with `{"type": "thinking", "thinking": "", "signature": ""}`, two
  `thinking_delta` events, a `signature_delta` with the item's carrier, `content_block_stop`, then the text block

#### Scenario: Summary and raw reasoning text in one item
- **WHEN** the upstream streams a summary delta `summary` and a `response.reasoning_text.delta` `detail` for the same
  item
- **THEN** the client gets the `thinking_delta` events `summary` and `\n\ndetail` in one block

#### Scenario: Unencrypted reasoning
- **WHEN** the upstream streams a summary delta, then `response.output_item.done` for the item without
  `encrypted_content`
- **THEN** the thinking block stops with no `signature_delta`

### Requirement: Encrypted reasoning without streamed text
When an encrypted reasoning item completes and none of its text was streamed, Core SHALL send it as one whole block:
`thinking` with its non-empty summary and content texts joined by `\n\n` and the carrier as `signature`, or
`redacted_thinking` with the carrier as `data` when it has no text.

#### Scenario: Encrypted reasoning with no summary
- **WHEN** the upstream sends `response.output_item.done` for a reasoning item with `encrypted_content` and an empty
  `summary`, with no reasoning delta before it
- **THEN** the client gets `content_block_start` with `{"type": "redacted_thinking", "data": <carrier>}`, then
  `content_block_stop`

#### Scenario: A summary only in the completed item
- **WHEN** the upstream sends `response.output_item.done` for a reasoning item with `encrypted_content` and summary
  text `plan`, with no reasoning delta before it
- **THEN** the client gets `content_block_start` with `{"type": "thinking", "thinking": "plan", "signature": <carrier>}`,
  then `content_block_stop`

### Requirement: Late reasoning carriers
Core SHALL never put a carrier inside an open text or tool block. A carrier whose thinking block is not open SHALL
go out as a whole block, `redacted_thinking` if the item's text was streamed and otherwise as "Encrypted reasoning
without streamed text" says: at once when no block is open, else held until a tool block completes or the terminal
event, whichever is first. Encrypted items seen only in the terminal event go before `message_delta`.

#### Scenario: A carrier during a tool call
- **WHEN** the upstream streams a summary for reasoning item 0, opens a function call, completes reasoning item 0
  with `encrypted_content`, then streams the call's arguments and completes the call
- **THEN** the arguments reach the `tool_use` block, and after its `content_block_stop` the client gets a
  `redacted_thinking` block with the carrier

#### Scenario: A carrier after its thinking block closed
- **WHEN** the upstream streams summary `plan` for reasoning item 0, answer text `hi` for message item 1 with its
  `response.content_part.done`, then `response.output_item.done` for item 0 with `encrypted_content`, then a
  `function_call` item 2
- **THEN** the client gets the `thinking` block `plan` stopped with no `signature_delta`, the text block `hi`, a
  `redacted_thinking` block with item 0's carrier, and then the `tool_use` block

#### Scenario: A carrier only in the terminal event
- **WHEN** the upstream streams `response.created`, then `response.completed` whose `output` lists reasoning item 0
  with `encrypted_content` and an empty `summary`, and nothing else
- **THEN** the client's only content block is `content_block_start` with
  `{"type": "redacted_thinking", "data": <carrier>}` and its `content_block_stop`, followed by `message_delta`

### Requirement: One carrier per reasoning item
Core SHALL send one carrier per encrypted reasoning item, from the first of its copies (`response.output_item.done`,
then the terminal event's) that has encrypted content, and SHALL ignore later copies.

#### Scenario: No second carrier from the terminal copy
- **WHEN** the upstream completes reasoning item 0 with `encrypted_content` `first` while a text block is open, and
  `response.completed` lists item 0 with `encrypted_content` `second`
- **THEN** the client gets exactly one carrier for item 0, and it holds `first`

#### Scenario: An unencrypted item-done, then an encrypted terminal copy
- **WHEN** the upstream streams summary `plan` for reasoning item 0, `response.output_item.done` for item 0 without
  `encrypted_content`, answer text `hi`, and `response.completed` listing item 0 with `encrypted_content` `late`
- **THEN** the client gets the `thinking` block stopped with no `signature_delta`, the text block `hi`, then a
  `redacted_thinking` block whose carrier holds `late`, then `message_delta`

### Requirement: Omitted thinking display
When the request's `thinking.display` is `omitted`, Core SHALL stream no reasoning text. Each encrypted reasoning
item becomes a whole `thinking` block with empty `thinking` and its carrier as `signature`, or `redacted_thinking`
when the item has no text; an unencrypted reasoning item produces no block.

#### Scenario: The carrier is kept without text
- **WHEN** the request has `thinking: {"type": "adaptive", "display": "omitted"}` and the upstream streams summary
  deltas `plan`, then completes the item with `encrypted_content` and summary `plan`
- **THEN** the client gets no `thinking_delta` and one `thinking` block with `thinking` `""` and the carrier as
  `signature`

#### Scenario: Unencrypted reasoning is not shown
- **WHEN** the request has `thinking.display: "omitted"` and the upstream streams summary deltas and answer text for
  an item without `encrypted_content`
- **THEN** the client gets only the text block

## MODIFIED Requirements

### Requirement: Generation settings in a translated request
Core SHALL send `max_tokens` as `max_output_tokens`, raising 1 to 15 to 16. It SHALL send `temperature` and `top_p`
as they are, `service_tier` `auto` as `auto` and `standard_only` as `default` (other values dropped), and
`metadata.user_id` as `user` when at most 64 characters long (a longer one dropped). Core SHALL drop `cache_control`,
`output_config.format` and every field with no Responses counterpart, and not refuse the request. `thinking`
and `output_config.effort` become `reasoning`.

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

#### Scenario: Structured output format dropped
- **WHEN** a builtin-translated request has `output_config: {"effort": "high", "format": {"type": "json_schema",
  "schema": {"type": "object"}}}`
- **THEN** the origin body has `reasoning: {"effort": "high"}` and no `text`

### Requirement: Content blocks without a translation are refused
Core SHALL answer `400` to a builtin-translated request whose user turn holds a content block other than `text`,
`mid_conv_system`, `compaction` or `tool_result`, whose assistant turn holds one other than `text`,
`mid_conv_system`, `compaction`, `tool_use`, `thinking` or `redacted_thinking`, or whose `tool_result` content holds
one other than `text` or `tool_reference`, with a message that names the block type and the role of its turn, and
SHALL send nothing upstream.

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

#### Scenario: Thinking block in a user turn
- **WHEN** a builtin-translated request has a user turn holding a `thinking` block
- **THEN** Core answers `400` with a message containing `'thinking' in the user turn`, and the origin receives no
  request

### Requirement: Conversation text in a translated request
Core SHALL send a user turn as a `user` input message with one `input_text` part per non-empty text. It SHALL send
each run of an assistant turn's non-empty texts that no `tool_use` block or replayed reasoning item interrupts as an
`assistant` input message whose `content` is those texts joined by a newline. A `thinking` or `redacted_thinking`
block dropped for want of a carrier SHALL not split a run. A `compaction` block SHALL count as a text holding its
`content` string. A turn with no non-empty text SHALL send no message.

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
