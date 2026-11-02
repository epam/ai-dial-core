# Tasks: builtin translator, tools

Prerequisite: `builtin-translator-fixture-parity` is applied (the capability, the fixture test and its known-gaps
list). Translator module tests run with `./gradlew :translator:test --tests "com.epam.aidial.core.translator.*"`,
which includes that change's fixture test.

## 1. Alias map

- [x] 1.1 Write `translator/src/test/.../ToolNamesTest.java` first. It covers: a conforming name untouched;
  `mcp__dial-knowledge-base__search_documents_by_semantic_similarity_v2` →
  `mcp__dial-knowledge-base__search_documents_by_semantic__7bc2a2fa`; the same long name with that alias taken →
  `mcp__dial-knowledge-base__search_documents_by_semanti_7bc2a2fa_1` (64 characters, head cut again); a name starting
  with a digit gets the `t_` prefix; a non-ASCII name gets `_` for each character outside `[a-zA-Z0-9_-]`; `a` next to
  a real `a_ca978112` → `a_ca978112_1`; the same name gets one alias at all three sites of `applyResponses`;
  `restore` passes an unknown name and a non-string through; a nameless function tool is left alone. Verify the test
  fails before 1.2.
- [x] 1.2 Add `ToolNames` to `translator/` as design D1 describes, ported from `tool_names.py:ToolNames`. Verify
  `ToolNamesTest` passes.
- [x] 1.3 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :translator:test --tests
  "com.epam.aidial.core.translator.ToolNamesTest"`. Verify both pass.

## 2. Request translation

- [x] 2.1 Write the request tests first.
  - In `MessagesToResponsesTest`, replace `testToolsAreRefused` and the `tool_use` refusal test
    `testNonTextContentIsRefused` with tests for: a custom tool → function tool (`$schema` stripped while the client's
    `input_schema` keeps it, default schema when absent, `strict: true` only when the tool sets it and `strict: false`
    otherwise, `description` only when non-empty, `name: null` when absent); `tools: []` sends no `tools`;
    `bash_20250124`, `mcp_toolset` and an unknown type sent as they are; `web_search_20250305` and `web_search`
    refused, each named; `tools` as an object, a string entry, a number as `name`, a number as `type` and
    `tool_choice: "auto"` refused, each naming its field; every `tool_choice` type and `disable_parallel_tool_use`
    true and false; `tool_use` → `function_call` with `arguments` `{"city": "Kyiv"}`, `{}` for absent or empty input,
    and `{"a": [1, {"b": "é"}]}` → `{"a": [1, {"b": "é"}]}` (array separator and lowercase escape); text runs
    before and after a `tool_use` as separate assistant messages; `tool_result` with string content, text blocks
    (`""` and `a` give `a`), a `tool_reference` block, no content, and `is_error`; an `image` and an unknown block
    inside a `tool_result` refused, each named; a `tool_use` block in a user turn and a `tool_result` block in an
    assistant turn refused; `function_call_output` items placed before `mid_conv_system` items and the user message
    (with fixture `cc99849f4693ddae`, for "Mid-conversation system text after tool results").
  - In `BuiltinTranslatorApiTest`, turn `testToolsAreRefused` (added by `builtin-translator-fixture-parity`; its
    request is now translated) into `testOutOfScopeToolContentIsRefused`, for "Web search tool", "Responses-style web
    search tool" and "Image inside a tool result". It asserts `400`, the named type, and that nothing reached the
    upstream.
  - In `BuiltinTranslatorApiTest`, extend `testMalformedConversationIsRefused` with "Tool choice as a string".
  - In `BuiltinTranslatorApiTest`, add `testToolHistoryIsTranslated`, for "Assistant turn with a tool use", "Text
    around a tool use" and "Tool results answer the calls before the user text". The upstream answers with text.

  Verify the new tests fail before 2.2.
- [x] 2.2 Implement design D2–D5, D7 and D8 in `MessagesToResponses`: a `ToolNames` parameter on `translateRequest`,
  the `json.dumps`-style writer with its `ponytail:` comment, and aliasing last. In
  `MessagesController.prepareTranslatedUpstreamRoute`, create the request's `ToolNames`, keep it on the controller and
  pass it to `translateRequest`; update the test callers. Verify `MessagesToResponsesTest` and `BuiltinTranslatorApiTest`
  pass.
- [x] 2.3 Remove the 36 request fixtures listed in design "Fixtures" from the known-gaps list, whatever owner their
  line names (12 of them are listed under `builtin-translator-reasoning`). The other 27 tool-and-reasoning request
  fixtures stay, owned by the reasoning change. Verify the fixture test passes, which proves the 36 now pass and the
  rest still fail.
- [x] 2.4 Run `./gradlew checkstyleMain checkstyleTest`, `./gradlew :translator:test --tests
  "com.epam.aidial.core.translator.*"` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.BuiltinTranslatorApiTest"`. Verify all pass.

## 3. Tool calls on the stream

- [x] 3.1 Write the stream tests first.
  - In `ResponsesToMessagesStreamTest`, add tests for: `output_item.added` with a `function_call` opens a `tool_use`
    block (`id` from `call_id`, `input: {}`) and closes an open text block first; deltas become `input_json_delta`, an
    empty one included; `function_call_arguments.done` sends the whole arguments only when no non-empty delta came,
    and `output_item.done` never sends them a second time; a delta for a call whose block is not open is dropped;
    `output_item.done` closes the block; `stop_reason` is `tool_use`, and `max_tokens` when `response.incomplete` cuts
    the call off; an alias from the request's `ToolNames` is restored in `content_block_start`.
  - In `BuiltinTranslatorApiTest`, add `testClaudeCodeToolRequestIsTranslated`, for the scenarios "Claude Code tools
    become function tools", "Versioned and toolset tools forwarded", "Forced tool without parallel calls", "Streamed
    tool call" and "Turn ends on a tool call". The upstream answers with text, then a call to `Read` with two argument
    deltas, then usage 10 / 8. The test asserts the upstream body, the client's event sequence and `stop_reason`, and
    `assertUsed(..., 18)`: limits are charged as for text.
  - In `BuiltinTranslatorApiTest`, add `testLongToolNameIsAliasedAndRestored`, for "Long MCP name aliased", "Alias
    taken by a real name", "Streamed call under its alias", "Arguments only in the done event" and "Answer with only a
    tool call". The upstream answers with one call to the alias, its arguments only in
    `response.function_call_arguments.done`.

  Verify they fail before 3.2.
- [x] 3.2 Implement design D6 in `ResponsesToMessagesStream`, with the constructor taking the request's `ToolNames`
  and `onFinal` passing `sawFunctionCall` to `ResponsesToMessages.stopReason`. In `MessagesController.processResponse`,
  pass the request's `ToolNames` to each `ResponsesToMessagesStream` it builds. Verify `ResponsesToMessagesStreamTest`
  and `BuiltinTranslatorApiTest` pass.
- [x] 3.3 Teach the fixture test to seed a `ToolNames` from a stream fixture's input `tool_names` by calling `alias`
  and asserting each recorded alias. Request fixtures keep the `tool_names` echo (design "Fixtures"). Then remove the
  6 stream fixtures in design "Fixtures" from the known-gaps list. Verify the fixture test passes.
- [x] 3.4 Run `./gradlew checkstyleMain checkstyleTest`, `./gradlew :translator:test --tests
  "com.epam.aidial.core.translator.*"` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.BuiltinTranslatorApiTest"`. Verify all pass.

## 4. Docs, spec copies and measurement

- [x] 4.1 Update `docs/dynamic-settings/translators.md` (the `anthropicMessagesToOpenaiResponses` bullet). Say that
  tools and `tool_choice` are translated; that server tool types are forwarded as sent and the deployment answers
  for them; that names the upstream may reject are aliased and restored; and that tools whose `type` starts with
  `web_search`, images and documents (also inside a `tool_result`), non-streaming requests and `count_tokens` are
  still refused. Verify the bullet matches the spec delta.
- [x] 4.2 Before archiving, compare the five MODIFIED blocks in `specs/anthropic-messages-translation/spec.md` with
  the archived text of `builtin-translator-fixture-parity`, and carry over any wording that changed there, keeping
  only this change's edits. Then check that the MODIFIED "Content blocks without a translation are refused" in
  `builtin-translator-reasoning` holds this change's clause for blocks inside a `tool_result` (other than `text` or
  `tool_reference`) and the "Image inside a tool result" scenario, and add them there if not. Verify with
  `npx -y @fission-ai/openspec@1.14.3 validate builtin-translator-tools --strict` and the same for
  `builtin-translator-reasoning`.
- [ ] 4.3 (deferred: needs the perf environment) Measure a Claude Code-shaped streaming corpus with tools on a
  builtin-translated model against the same model behind the translator service. Compare Core passes and Core CPU per
  request (one pass instead of two).
- [x] 4.4 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.BuiltinTranslatorApiTest"` and `./gradlew :translator:test`. Verify all pass.

## Workflow follow-up

- Archive after `builtin-translator-fixture-parity` and before `builtin-translator-reasoning`, once review.md exists.
