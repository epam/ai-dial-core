# Proposal: builtin translator, tools

## Why

The builtin translator (`translators.<name>.builtin: "anthropicMessagesToOpenaiResponses"`) serves Anthropic
Messages on a model's own `openaiResponses` endpoint in one Core pass instead of two. Today it answers `400` to any
request with `tools` or `tool_choice`, and to a history with `tool_use` or `tool_result` blocks. Claude Code sends
tools on every request, so none of its traffic can use the one-pass path yet; it still goes through the translator
service.

This change ports the tool part of the Python translator service: tool definitions, `tool_choice`, reversible
tool-name aliasing, tool calls and results in the history, and tool calls on the stream.

## What Changes

- **Request, `tools`.** A custom tool (no `type`, or `custom`) becomes a Responses function tool (`name`,
  `description`, `parameters` from `input_schema` without its `$schema` key, `strict: true` only when the tool sets
  it, otherwise `strict: false`). Every other tool type (`bash_*`, `computer_*`, `text_editor_*`, `web_fetch_*`,
  `code_execution_*`, `mcp_toolset`, types Core does not know) is forwarded exactly as sent, so the deployment answers
  for a tool it cannot run. No tool is dropped. A tool whose `type` starts with `web_search` is still refused with
  `400`: web search is out of this series. A malformed `tools` or `tool_choice` (not an array, an entry that is not an
  object, a `name` or `type` that is not a string, a `tool_choice` that is not an object) is refused with `400` naming
  the field, as the reference does.
- **Request, `tool_choice`.** `auto` → `"auto"`, `any` → `"required"`, `none` → `"none"`, `tool` →
  `{"type": "function", "name": …}`, any other type is omitted, and `disable_parallel_tool_use` becomes
  `parallel_tool_calls` with the opposite value.
- **Request, aliasing.** A function name the upstream may reject (it must match `^[a-zA-Z_][a-zA-Z0-9_-]{2,63}$`;
  Claude Code's `mcp__<server>__<tool>` names are often longer) goes upstream as `<head>_<8 hex of sha256>` in
  `tools`, `tool_choice` and replayed calls alike. The map lives for one request. Core restores the original name in
  what the client gets, so the client never sees an alias.
- **Request, history.** `tool_use` blocks become `function_call` items at their place in the assistant turn.
  `tool_result` blocks become `function_call_output` items placed before the rest of their user turn. A text result
  is a string, and `is_error` adds an `Error: ` prefix. An image or any other non-text block inside a `tool_result`
  is refused with `400`, as it is at the top of a turn.
- **Stream.** A Responses `function_call` item becomes a `tool_use` block: `content_block_start` with `input: {}`,
  each argument delta as an `input_json_delta`, the whole arguments from the `*.done` event when no delta came, and
  `content_block_stop`. A tool call sets `stop_reason: "tool_use"` unless the output cap or a content filter ended
  the answer. Usage is unchanged.
- **Docs.** `docs/dynamic-settings/translators.md` says the builtin translator translates tools and aliases long
  names, and that web search tools are still refused.

Not breaking: a request that got `400` before now reaches the model. No config or setting changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `anthropic-messages-translation`: created by `builtin-translator-fixture-parity`. This change removes its
  requirement "Tools are refused" and adds the tool requirements: function tools, other tool types, the web search
  refusal, malformed tool fields, tool choice, alias format, alias collisions, alias restoring, tool calls and tool
  results in the history, and tool calls on the stream. Tools make five of that change's requirements wrong as
  written, so this change modifies them. "Mid-conversation system text in a translated request": in a user turn the
  `system` message goes after the turn's `function_call_output` items. "Conversation text in a translated request": a
  `tool_use` splits an assistant turn's text.
  "Translated event stream": the empty text block appears only when there is no block at all. "Stop reason of a
  translated stream": adds `tool_use`. "Content blocks without a translation are refused": lets `tool_use` and
  `tool_result` through, and refuses non-text blocks inside a `tool_result`.

## Out of scope

- The non-streaming answer, including its `tool_use` blocks (the response-kind tool fixtures), and the Anthropic error
  body shape: `builtin-translator-non-streaming-errors`. Until then a non-streaming request is still refused, and an
  error here states only its status code and message.
- Reasoning, including a `thinking` block next to a `tool_use` block in the history and reasoning output deferred
  around a tool block on the stream: `builtin-translator-reasoning`.
- Analytics, the cache key and upstream identity: `builtin-translator-routing-accounting`.
- For the whole series: web search (tools whose `type` starts with `web_search`, `allowed_tools` choice,
  `max_tool_calls`), images,
  documents and `search_result` blocks (also inside a `tool_result`), structured output, `count_tokens`,
  interceptors on the builtin path, the Chat Completions mapping, and CPU tuning of the stream.
- Translating server tool types to Responses counterparts (`code_execution` ↔ `code_interpreter`, `bash` ↔ `shell`,
  …). The Python translator does not do it either: each needs its response-side item translated back too.

## Impact

- Motivation: PR [#2136](https://github.com/epam/ai-dial-core/pull/2136), the first version of the builtin
  translator, which refused tools. This change covers that gap. Reasoning, non-streaming, errors and accounting are
  the other changes of the series.
- Reference: the Python translator's
  [responses-translations.md](https://gitlab.deltixhub.com/Deltix/openai-apps/poc/openai-translator-bedrock/-/blob/development/responses-translations.md),
  sections "tools", "tool_choice", the `tool_use` / `tool_result` rows of "Content blocks", and the function-call rows
  of "Streaming" and "stop_reason". Covered: all of them for the streaming path, except the web search rows.
  Left out: the web search rows (a later series) and the `function_call` row of "Response" (change 4).
- Parity oracle: the recorded fixtures (Python commit `5e70209`). 36 in-scope request fixtures and 6 stream fixtures
  with tool features pass after this change and leave the known-gaps list, whatever owner their line names. 12 of
  the 36 carry only reasoning fields that Core drops and Python sent nothing for (`output_config: {}`, or adaptive
  thinking with `display: "omitted"`), so change 1's owner rule lists them under `builtin-translator-reasoning`.
  Another 27 request and 2 stream fixtures need reasoning too and finish with that change. The 6 response fixtures
  with `function_call` finish with `builtin-translator-non-streaming-errors`.
- Code: `translator/` (`MessagesToResponses`, `ResponsesToMessagesStream`, one new class for the alias map);
  `server/.../controller/anthropic/MessagesController.java` (hands the request's alias map to the stream);
  `BuiltinTranslatorApiTest`; `docs/dynamic-settings/translators.md`.
- Depends on `builtin-translator-fixture-parity` (the capability, the fixture test and its known-gaps list). Ships in
  the series' one PR.
- Per request: still one Core pass, with no extra body copy or parse and no blob round trip. The work added is one
  SHA-256 per tool name that needs an alias, and the upstream cache key now hashes each tool definition; with
  auto-caching on, each tool is a cache breakpoint, so a lookup that finds no cached prefix can run one more Redis
  batch per 64 breakpoints (design, "Cost per request"). Claude Code traffic can now take the one-pass path.
