# Design: builtin translator, tools

## Context

See proposal.md for why. The requirements are in `specs/anthropic-messages-translation/spec.md`. This design starts
from the code as `builtin-translator-fixture-parity` leaves it. That change adds the `anthropic-messages-translation`
capability, the `include` field, and the fixture test with its known-gaps list. The list names the change that owns
each failing fixture, and the test fails when a listed fixture passes. This change also modifies five of that change's
requirements, where tools make their text wrong: the place of mid-conversation system text in a user turn, assistant
text runs, the empty text block, the stop reason and the refused content blocks. File references are to the working tree, which carries PR #2136 on top of `development`.

What the code does today:

- `translator/.../MessagesToResponses.java:translateRequest` throws `UntranslatableRequestException` when `tools` or
  `tool_choice` is non-null, an empty `tools` list included. `MessagesToResponses.java:texts` throws on any content
  block other than `text` (other than `text`, `mid_conv_system` and `compaction` after the fixture-parity change), so
  `tool_use` and `tool_result` are refused there.
- `server/.../controller/anthropic/MessagesController.java:prepareTranslatedUpstreamRoute` translates once, turns
  `UntranslatableRequestException` into a `400`, then runs `ResponsesController.buildEnhancementFunctions` on the
  translated body. `MessagesController.java:processResponse` creates a `ResponsesToMessagesStream` for each upstream
  attempt whose response head is a `200` event stream.
- `translator/.../ResponsesToMessagesStream.java:handle` ignores `response.output_item.*` and
  `response.function_call_arguments.*`. `ResponsesToMessagesStream.java:onFinal` calls
  `ResponsesToMessages.java:stopReason` with `sawFunctionCall = false`. `stopReason` already ranks `max_tokens` and
  `refusal` above `tool_use`.
- `server/.../function/request/ResponsesApiRequest.java:buildCacheKeys` hashes `tools[i]`, `instructions` and
  `input[i]` of the translated body (through `BuildUpstreamCacheFn(OPENAI_RESPONSES)`), so translated tools join the
  upstream cache key with no change here.
- `server/.../util/EncryptedContentAffinityUtil.java:resolveAndUnwrap` rewrites only wrapped `id` / `encrypted_content`
  values. `function_call` and `function_call_output` items carry neither, so the affinity unwrap leaves them alone.

The reference is `to_responses.py:_convert_tools`, `_function_tool`, `_tool_parameters`, `_convert_tool_choice`,
`_convert_user_message`, `_tool_result_output`, `_convert_assistant_message`; `tool_names.py:ToolNames`;
`streaming.py:ResponsesToAnthropicStream._on_output_item_added`, `_on_arguments_delta`, `_flush_arguments` and
`_on_output_item_done`.

## Goals / Non-Goals

**Goals:**
- The 36 request and 6 stream fixtures listed under "Fixtures" pass and leave the known-gaps list.
- Claude Code's tool requests are served on the builtin path in one Core pass.

**Non-Goals:**
- A `thinking` block next to a `tool_use` block, and reasoning deferred around a tool block on the stream: the
  reasoning change ports both (`streaming.py:_flush_pending_reasoning` runs at a function call's
  `output_item.done`).
- The non-streaming `tool_use` blocks (`from_responses.py`): the non-streaming change. It reuses the alias map below
  to restore names.

## Decisions

### D1. The alias map is a small per-request class in the translator module

`ToolNames` ports `tool_names.py:ToolNames`. It holds two maps, original → alias and alias → original, and has three
methods. `alias(name, taken)` builds the candidate `<head>_<8 hex>`; while it collides, it builds
`<head>_<8 hex>_<n>` for n = 1, 2, …, cutting the head so the alias stays within 64 characters
(`tool_names.py:_candidate`). `restore(name)` maps an
alias back. `applyResponses(body)` rewrites the three name sites. `MessagesController` creates one per request in
`prepareTranslatedUpstreamRoute` and passes it to `MessagesToResponses.translateRequest` as a new last parameter. It hands
the same instance to every `ResponsesToMessagesStream` it creates in `processResponse`, one per attempt. The map is
written only during translation and only read afterwards, so a retry restores names exactly like the first attempt.

Rejected:
- A process-wide map. The Python translator dropped one because an earlier request's alias could rename a later
  request's tool, and an entry still in use could be evicted.
- A bare `Map` with static helpers in `MessagesToResponses`. It saves one class, but the collision rule would then
  live apart from the map it guards, and the stream would need its own reverse lookup.
- Storing the map in `ProxyContext`. The controller already lives for exactly one request.

### D2. Aliasing runs last over the translated body, before the Responses chain

As in `to_responses.py:to_responses_request`, `applyResponses` runs once, after the body is built. It first collects
every name that needs no alias (the `taken` set), then aliases the rest. The sites are function `tools`, a
`{"type": "function"}` `tool_choice`, and `function_call` input items. Tools of other types carry no name to rewrite.

The Messages defaults (`ApplyDefaultDeploymentSettingsFn(ANTHROPIC_MESSAGES)`) are applied before the translation,
so tools an operator adds there get aliases too. The Responses defaults (`ApplyDefaultDeploymentSettingsFn(OPENAI_RESPONSES)`)
are applied after it, inside the chain, and are not aliased. They are written in the Responses shape by the operator,
who chooses names the upstream accepts.

The cache key hashes the aliased body. An alias depends only on the name when nothing collides, so the key of a
conversation's prefix stays the same from turn to turn.

### D3. `arguments` is serialised the way Python's `json.dumps` does it

`to_responses.py:_convert_assistant_message` sends `json.dumps(block.get("input") or {})`. Python's `json.dumps`
defaults write `", "` between object entries and between array values, `": "` between a key and its value, and
escape non-ASCII characters as lowercase `\uXXXX` (`ensure_ascii=True`). The fixtures show only the object case
(`"{\"city\": \"Kyiv\"}"`): no in-scope fixture's `arguments` holds an array, a float or an escape, so task 2.1
covers those cases with a nested input. `MessagesToResponses` gets one Jackson writer that does the same. It is a
`MinimalPrettyPrinter` subclass that writes `", "` as the object entry and array value separators and `": "` as the
key separator, with `JsonWriteFeature.ESCAPE_NON_ASCII` on and `JsonWriteFeature.WRITE_HEX_UPPER_CASE` off (Jackson
2.21.7, `gradle.properties`). An `input` that is absent,
`null`, or an empty object, array or string, or `0` or `false` (Python's falsy values), is sent as `{}`.

Rejected: compact Jackson output, with a harness rule that parses `arguments` before comparing. It saves a few lines,
but the oracle would need a second normalisation. The upstream would also see a different prompt from the one the
translator service sends for the same history, so a session moving between the two would miss the upstream's prompt
cache once.

Known ceiling: a float is spelled the Java way (`1.0E-5` where Python writes `1e-05`), and U+007F stays unescaped.
The string differs but its JSON value does not. The code carries a `ponytail:` comment naming this.

### D4. Tool types other than custom and web search are sent as they are

`_convert_tools` forwards them with `model_dump(exclude_unset=True)`, which is every key the client sent. Core puts
the client's own node into the translated `tools`, with no copy. A function tool's `parameters` is likewise the
client's `input_schema` node itself (`to_responses.py:_tool_parameters` returns it unchanged too); when it has a
top-level `$schema`, `parameters` is a new object holding the other top-level entries, so `$schema` is never removed
from the client's node.

Core only writes to nodes it built: aliasing renames the `name` of function tools, the function `tool_choice` and
`function_call` items, all new nodes. The one thing in the Responses chain that changes a shared node is the upstream
cache key: `CacheKeyBuilder.java:update` calls `JsonUtil.java:sort`, which replaces each object inside an array with a
key-sorted copy in place (`arrayNode.set(i, sort(...))`). The client's body, serialised after the chain
(`MessagesController.java:prepareTranslatedUpstreamRoute`, `context.setRequestBody(request.serialize())`) for pricing
and the analytics row, can therefore show such objects with their keys sorted. No value changes, and the plain
Responses path already sends and stores its own body after the same sort.

Rejected:
- Refusing them, as today. That blocks requests a deployment may serve.
- Dropping them. The model would answer as if it had never been offered the tool (the Python `CLAUDE.md`, "No tool is
  ever dropped").
- Mapping them to Responses counterparts. Each one also needs its response-side item translated back, and the
  reference does not do it either.
- A deep copy of each forwarded tool and of each `input_schema`. Claude Code sends dozens of tool schemas on every
  request, and the copy would only protect key order in the stored client body.

### D5. Web search tools stay refused

A tool whose `type` starts with `web_search` is refused: the same prefix as `to_responses.py:_convert_tools`,
`_names_web_search` and `_max_tool_calls`, so the Responses built-in `{"type": "web_search"}` is refused too instead of
being forwarded and run upstream. `MessagesToResponses` throws `UntranslatableRequestException` naming the type, and
the existing `400` path answers.
Rejected: `_web_search_tool`. A web search sends back `web_search_call` items, `url_citation` annotations and
`server_tool_use` usage. All three need translating back, and they are out of this series.

### D6. Tool calls on the stream follow `streaming.py`

The open function block's key is `f:<output_index>`, next to the existing `m:` text key. The stream adds a
`sawFunctionCall` flag and a set of call keys whose arguments have reached the client.

- `response.output_item.added` with a `function_call` item opens a `tool_use` block, closing whatever is open.
- Each `response.function_call_arguments.delta` for the open call is sent as an `input_json_delta`, an empty one
  included. Only a non-empty delta marks the call as sent.
- `response.function_call_arguments.done` and `response.output_item.done` send the whole `arguments` only for a call
  not yet marked.
- `response.output_item.done` then closes the call's block.
- A delta for a call whose block is not open is dropped, as for text.

The brief says the arguments are "taken from the `*.done` events". The fixtures say otherwise: deltas are sent as
they arrive (`b199620dab510b59`), and the `*.done` events fill in only when no delta came (`9e7b27ef0e55a351`). The
fixtures win. Rejected: holding the arguments until `*.done`. It delays a long tool input and contradicts the oracle.

### D7. Content forms of a `tool_result`

`_tool_result_output` is ported for text:
- string content stays as it is;
- `text` blocks are joined with `\n`, and an empty one is skipped;
- `tool_reference` blocks are dropped;
- missing content gives `""`;
- `is_error` adds the `Error: ` prefix.

Any other block inside a `tool_result` (`image`, `document`, `search_result`, or a type Core does not know) is refused
with `400` naming it. That is the fixture-parity change's rule for a block with no translation, applied one level
down, and the spec states it in the modified "Content blocks without a translation are refused". It is a deliberate
difference from the reference, which sends images and documents as a `function_call_output` content array and drops
other blocks with a warning. Images and documents are a later series.

The order of items in a user turn is the reference's: every `function_call_output` first, then the `system` items from
`mid_conv_system` blocks (fixture-parity work), then the residual user message (`to_responses.py:_convert_user_message`,
fixture `cc99849f4693ddae`). In an assistant turn the `system` items still go before the whole turn. The spec states
this in the modified "Mid-conversation system text in a translated request".

### D8. Smaller differences and corrections to the brief

- A custom tool with no `name` is sent with `"name": null` (fixture `e16487dcbfe90e10`). A `tool` choice with no name
  is sent as `{"type": "function", "name": null}`. The upstream judges both.
- Malformed tool fields are refused with `400` naming the field: a `tools` that is not an array, an entry that is not
  an object, a `name` or `type` that is present, non-null and not a string, and a `tool_choice` that is not an object.
  The reference answers `400` for each from its pydantic request model (`anthropic_api.py:MessagesRequest`, `Tool`;
  `common.py:parse_request`), and change 1 refuses a `messages` that is not an array the same way. Without the check a
  string tool entry would become a function tool named `null`, and `tool_choice: "auto"` would be dropped silently.
  The message wording is Core's own; no fixture records one.
- Other field types the pydantic model checks are not ported: an `input_schema` that is not an object is sent as
  `parameters` unchanged, and a `description` or `strict` of another type follows the rules above as it is (a
  non-empty `description` is sent, `strict` is `true` only for `true`). The upstream judges the schema.
- The brief lists `clear_tool_uses_*` among the tool types. It is an edit type inside `context_management`, not a
  tool, and it is dropped with that field (fixture `5f39bbc94235f6b8`), which the fixture-parity change already
  covers.
- A `tool_use` block in a user turn and a `tool_result` block in an assistant turn are refused with `400`, by the
  same modified requirement. The reference drops them with a warning.

## Fixtures

Python commit `5e70209`. A fixture is "tool" here when its `features` contain `field:tools`, `field:tool_choice`,
`tool_use` or `tool_result`. The split below is by what passes once this change lands: a tool fixture still needs the
reasoning change when Python's output depends on reasoning (a `reasoning` key or reasoning item in the body, an
`output_config.effort` error, a `thinking` block in the history, or reasoning on the stream).

Change 1's known-gaps owner rule is coarser: any `field:thinking` or `field:output_config` makes the owner
`builtin-translator-reasoning`. 12 of the 36 request fixtures below carry only reasoning fields that Core drops and
Python sent nothing for, so change 1's list names the reasoning change for them: 14001a47b6ce91bc, 188c043ffd1a84c8,
48ff724e4a5d02aa, 58bb44046f4133c9, 8632ce83897bd3b5, 8e6ed727c22cc759, 94aaa2d1c5aa38e5, a38bc77809a749b9,
a4408dc7a87ebca8, ada56916cdee9fb9 and f08d86a9c8186e0c (`output_config: {}`), and 6ef7fe30f79cfccb
(`output_config: {}`, `thinking: {"type": "adaptive", "display": "omitted"}`, `tools: []`). Task 2.3 removes all 36
whatever owner their line names.

- Leave the known-gaps list with this change, request (36): 04320812394f4615, 14001a47b6ce91bc, 188c043ffd1a84c8,
  1a4a0cb3ed053b75, 29df02ff22d58b76, 47c3cc7fa43e51a9, 48ff724e4a5d02aa, 4cc69bc54b560dd2, 58bb44046f4133c9,
  6ef7fe30f79cfccb, 6ffa33973ba9ebc3, 754c57387cbb32da, 7b07054bb40fa343, 7c9554ff2f718fb9, 8632ce83897bd3b5,
  8e6ed727c22cc759, 94aaa2d1c5aa38e5, 94f36a07a706178d, 9d971f93a3ddd5b9, a38bc77809a749b9, a4408dc7a87ebca8,
  ada56916cdee9fb9, af08976913d565df, b47c25ba2be01825, b6d62f21f20cc7c6, bca67803b2bccfa7, bf5f7cd21361c662,
  cc99849f4693ddae, ce4880e43e3698e7, dca1af90512c24e6, e01f9391a0828416, e16487dcbfe90e10, f08d86a9c8186e0c,
  fa3e6e89fce0d0cf, fd02f55c17fe3b30, ff219911c1f9da5f.
- Leave it with this change, stream (6): 9e7b27ef0e55a351, b199620dab510b59, d0fe7b7b9867b7b5, ed9b9b392603dae8,
  f34a530f8e81e6f5, f4f5960703e43647.
- Tool and reasoning, so they stay in the list, already owned by the reasoning change under change 1's rule: request
  (27) 171961a52217ceb6, 19ab72ea457a9bcf, 1c2548b8ba928670, 1d4e46e395abcdfa, 1f010fbda5773122, 2d515502608df9a9,
  2e8d255982c21425, 35b63d6b977b5e59, 35f3dc2cc16684b8, 47aa290459c3b5ee, 4e850be98fe5fbdf, 544aa5911dea137f,
  6bbb6f2b0f498e29, 6d52ee8bb92363ba, 70c6cf1e90c3d83f, 907056cb0a304237, b94f30bddf05e8ba, c0420311496405b5,
  ca3a2464d84b4268, d06277a35d48c659, dd5eab4a1a8de572, e46d4f210915659a, eca323df1624c3ae, f61a93ded53bea3b,
  f9427e0c31d95396, fd8641573730634f, fe6f04a92b1eb13d; stream (2) 9466205ab633d265, 9e9c99eb4cb2d82a.
- Response kind with `function_call` (6), for the non-streaming change: 02025bcdf8ce458d, 0670d93a7ae3830d,
  384ae4f41d7c3eb2, 5a43905f8a52a05c, 7113b3c4a8b09a84, b4cce6d7e720bb07.

The alias map in the harness:
- Request fixtures keep change 1's `tool_names` echo. Every request fixture passes `null` and records `null`, or passes
  `{}` and records `{}` (an error output records none). Where the input is `null`, Python aliased into a map it then
  threw away (`8632ce83897bd3b5`), so no request fixture can tell the echo from a real map. Alias correctness is
  checked by `ToolNamesTest`, the stream fixture below and `testLongToolNameIsAliasedAndRestored`.
- The stream fixture `f34a530f8e81e6f5` seeds `{original: alias}`. The harness fills a `ToolNames` by calling
  `alias(original)` for each entry and asserts the result equals the recorded alias.

## Cost per request

- Core passes: unchanged, one.
- Body copies and parses: the translation reads the Messages tree Core has already parsed and builds the Responses
  tree, as it does today. Forwarded tools and `input_schema` nodes are shared, not copied (D4); an `input_schema` with
  `$schema` gets one new top-level object. Joining a `tool_result`'s text blocks builds one new string. On the stream,
  each function-call event is parsed once, in `ResponsesToMessagesReadStream.onEvent`, like every other event.
- Redis and blob round trips: no blob call added. Redis only through the upstream cache: each translated tool is a
  `tools[i]` cache key (`ResponsesApiRequest.java:buildCacheKeys`), and with auto-caching on every key is a breakpoint
  (`UpstreamCacheService.java:buildCacheBreakpointContext`). `UpstreamCacheService.java:getCacheEntry` checks
  breakpoints from the last one back, 64 per Redis batch (`BATCH_SIZE`), and stops at the first hit; tools come first
  in the hashing order, so a lookup that finds no cached prefix runs up to one more batch per 64 breakpoints. Today's
  builtin path refuses these requests; the translator-service path does the same hashing in its Responses pass.
- CPU: one SHA-256 per tool name that needs an alias, and one small serialisation per replayed `tool_use`. Each tool
  definition is also sorted, serialised and fed to the SHA-1 cache-key digest (`CacheKeyBuilder.java:update`) when
  the model supports caching.

Nothing here is measured on its own. The saving that matters is that Claude Code traffic (which always carries tools)
now takes the one-pass path. That is measured as a Core pass count and Core CPU per request against the translator
service, on the perf environment (tasks.md, task 4.3).

## Risks / Trade-offs

- [The upstream rejects a forwarded server tool type with its own `400`] → This is intended: the client sees the
  deployment's answer instead of a model that silently lacks the tool. `docs/dynamic-settings/translators.md` says so.
- [Claude Code's WebSearch tool, or any tool whose `type` starts with `web_search`, still gets a `400` on the builtin
  path] → It is documented, and a deployment that needs it keeps the translator service until web search is ported.
- [An alias collides with a name the upstream also uses for a built-in] → It cannot: only function tools are
  aliased, and an alias ends in a hex hash.

## Migration Plan

None. No config or setting changes. Rolling back the change makes requests with tools get `400` again.

## Open Questions

None. Two copies of requirement text must stay in step, and task 4.2 checks both before archiving:
- The five MODIFIED blocks in the spec delta copy the fixture-parity change's draft text. Archiving this change
  replaces each block by its header, so wording changed there must be carried over.
- `builtin-translator-reasoning` modifies "Content blocks without a translation are refused" again. Its draft starts
  from change 1's text and lacks this change's clause for blocks inside a `tool_result` and the "Image inside a tool
  result" scenario, so archiving this change and then that one would drop both. Task 4.2 carries them into that
  change's block.

## Spec review

Each finding was checked against the change 1 and reasoning drafts, the Core code, the Python reference and the
fixtures at `5e70209`.

1. fidelity, medium: `6ef7fe30f79cfccb` passes but was kept in the known-gaps list. **Accepted.** Its recorded body
   has no `reasoning` key, change 1 drops `thinking` and `output_config`, and only the `tools: []` refusal fails it
   today. Moved it to the leaving set, which is now 36 request fixtures with 27 left over, in "Fixtures", Goals,
   proposal Impact and task 2.3.
2. fidelity, medium: tools make "Mid-conversation system text in a translated request" wrong. **Accepted.**
   `_convert_user_message` builds `tool_outputs + system_items` and then the user message, and fixture
   `cc99849f4693ddae` records that order. Added a MODIFIED block: in a user turn the `system` message goes after the
   turn's `function_call_output` items and before its `user` message, and in an assistant turn it still goes before
   the turn. Its new scenario mirrors the fixture. "Four" became "five" in proposal and Context, D7 points to the
   block, and task 4.2 compares it.
3. fidelity, medium: `BuiltinTranslatorApiTest.testToolsAreRefused` from change 1 was left in place.
   **Accepted.** Task 2.1 now turns it into `testOutOfScopeToolContentIsRefused`, next to the
   `MessagesToResponsesTest` replacements, which are now named, including `testNonTextContentIsRefused`.
4. fidelity, medium: the reasoning change's MODIFIED "Content blocks without a translation are refused" drops this
   change's `tool_result` clause and scenario. **Accepted.** Checked: its draft lacks both. Open Questions now
   records the gap as a fact, and task 4.2 adds the clause and the "Image inside a tool result" scenario to that
   block and validates both changes. The reasoning change's files are not edited here, because this step revises
   only `builtin-translator-tools`. Its proposal also still counts 28 tool fixtures where 27 is now right.
5. fidelity, low: web search was matched by `web_search_` and not by the reference's `web_search` prefix.
   **Accepted.** `_convert_tools`, `_names_web_search` and `_max_tool_calls` all use `startswith("web_search")`.
   The requirement, "Other tool types", the REMOVED reason, D5, Risks, proposal and docs task 4.1 now say "`type`
   starts with `web_search`". There is a new "Responses-style web search tool" scenario for `{"type":
   "web_search"}`.
6. fidelity, low: the collision suffix broke the 64-character limit. **Accepted.** In `_candidate` the suffix
   becomes `_<hash>_<n>` and the head is cut again. The requirement and D1 now say so, and task 1.1 adds the
   64-character colliding MCP alias, checked against the Python code.
7. fidelity, low: empty `text` blocks in a `tool_result` were not skipped. **Accepted.** The requirement now says
   "non-empty `text` blocks", and task 2.1 tests `""` and `a` → `a`.
8. fidelity, low: malformed tool input was unspecified. **Accepted with a refusal.** The reference answers `400`
   from its pydantic model, and no fixture records such an input. Added the requirement "Malformed tool fields are
   refused" (non-array `tools`, non-object entry, non-string `name` or `type`, non-object `tool_choice`) with the
   scenario "Tool choice as a string". D8 records it and the field types left to the upstream (`input_schema`,
   `description`, `strict`). Proposal, task 2.1 unit cases and `testMalformedConversationIsRefused` cover it.
9. fidelity, low: D4 left out `parameters` and the cache-key work. **Accepted.** `JsonUtil.sort` does rewrite
   array elements in place, and the chain runs before the client body is serialised. D4 now shares forwarded tools
   and `input_schema`, with a new top-level object only when `$schema` is removed, and accepts sorted keys inside
   arrays in the stored client body. It drops the deep copy for the same reason and records that as a rejected
   alternative. "Cost per request" and the proposal add the per-tool sort, serialisation and SHA-1, the
   breakpoint batches (64 per Redis batch, `UpstreamCacheService.getCacheEntry`) and the `tool_result` join. Task
   2.1 now checks that the client's `input_schema` keeps `$schema`, replacing "the copy is not the client's node".
10. simplicity, medium: same as 3. **Accepted.** It is the same edit as 3: the change 1 test becomes
    `testOutOfScopeToolContentIsRefused` in task 2.1, so no fourth test is added next to it.
11. simplicity, medium: same as 2. **Accepted.** It is the same MODIFIED block and scenario. The fixture
    `cc99849f4693ddae` and the 2.1 ordering case test it.
12. simplicity, medium: the server wiring had to happen in groups 2 and 3, so 4.1's fail-before check could not be
    observed. **Accepted.** Task 2.2 now creates the `ToolNames` in `MessagesController` and passes it to
    `translateRequest`, and task 3.2 passes it to each stream. The integration tests moved to 2.1 (history,
    refusals) and 3.1 (tool calls, aliasing), where each fails before its implementation step. Old 4.2 is removed,
    and the group-end checks in 2.4 and 3.4 now run `BuiltinTranslatorApiTest`.
13. simplicity, low: the design's "reasoning" rule differed from change 1's owner rule. **Accepted.** The 11
    fixtures (12 with `6ef7fe30f79cfccb`) carry only `output_config: {}` or omitted adaptive thinking, and change
    1 lists them under the reasoning owner. "Fixtures" names them, task 2.3 removes all 36 whatever owner their
    line names, and the proposal counts say so. The 27 that stay are already reasoning-owned, so no line changes
    owner.
14. simplicity, low: task 2.3 changed the harness but no fixture could show the difference. **Accepted.** Every
    request fixture passes `null`/`null` or `{}`/`{}`, so the echo already yields every recorded map. The old task
    2.3 is dropped and "Fixtures" says the echo stays. Alias correctness rests on `ToolNamesTest`, stream fixture
    `f34a530f8e81e6f5` and `testLongToolNameIsAliasedAndRestored`.
15. simplicity, low: D3's `json.dumps` spelling was barely covered. **Accepted.** No in-scope `arguments` holds an
    array, float or escape. D3 now cites Python's `json.dumps` defaults, including the array value separator, and
    task 2.1 adds `{"a": [1, {"b": "é"}]}` → `{"a": [1, {"b": "é"}]}`. The finding's expected output kept a
    literal `é`; Python writes `é`.
16. simplicity, low: "every other character" was ambiguous. **Accepted.** It now says "every character outside
    `[a-zA-Z0-9_-]`", and so does task 1.1.
17. simplicity, low: "An alias map SHALL serve only the request that made it" named an internal. **Accepted.**
    Deleted. "Aliased names are restored" already limits restoring to aliases issued for the same request, and D1
    keeps the per-request map.
18. simplicity, low: the proposal and task read as if `strict` were omitted. **Accepted.** The proposal and task
    2.1 now say "`strict: true` only when the tool sets it, otherwise `strict: false`", as the spec does.
