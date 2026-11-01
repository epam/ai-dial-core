# Design: builtin translator, reasoning

## Context

See proposal.md, Why. This change is written against the builtin path as `builtin-translator-fixture-parity` and
`builtin-translator-tools` leave it; the citations below are to the code in the working tree today.

What the request side has now:

- `MessagesToResponses.java:translateRequest` drops `thinking` and `output_config` with a warning (`DROPPED_FIELDS`)
  and `texts` refuses any block but `text` with a 400. After change 1 the request carries
  `include: ["reasoning.encrypted_content"]`; after change 2 an assistant turn is converted in order, flushing the
  pending text before each `function_call` item, as `to_responses.py:_convert_assistant_message` does.
- `MessagesController.java:prepareTranslatedUpstreamRoute` applies the Messages defaults, translates, then runs
  `ResponsesController.java:buildEnhancementFunctions`. That chain holds
  `ResolveEncryptedContentAffinityFn.java:apply`, which unwraps every `dialenc_` id and `dialenc:` ciphertext of a
  `reasoning` input item back to the provider's value (`EncryptedContentAffinityUtil.java:resolveAndUnwrap`), answers
  400 for two different upstreams (`conflictingAffinityException`) and 409 for an upstream the model does not have
  (`upstreamUnavailableException`), and records the upstream id. `prepareTranslatedUpstreamRoute` then passes
  `ResponsesApiRequest.java:getEncryptedUpstreamId` to `UpstreamRouteProvider.java:get`, which pins the route. So a
  reasoning item that reaches the translated body with Core's stamped values is already routed and unwrapped exactly
  as on `POST /openai/v1/responses`. This change only has to put it there.

What the stream side has now:

- `ResponsesToMessagesReadStream.java:onEvent` runs `EncryptedContentWrapFn.java:apply` on each upstream event before
  `ResponsesToMessagesStream.java:handle`. The wrap stamps `item.id` and `item.encrypted_content` on
  `response.output_item.added` / `.done`, every item of `response.output` on terminal events, and `item_id` on the
  `response.reasoning_summary_*` events (`EncryptedContentAffinityUtil.java:wrapOutputItem`, `wrapSseItemId`), when
  the model has configured upstreams (`hasConfiguredUpstreams`). So whatever the state machine reads from a reasoning
  item is already the value a Responses client would get.
- `ResponsesToMessagesStream.java:handle` ignores every reasoning event (the `default` branch).
- `MessagesController.java:processResponse` builds the state machine from `requestedModel` and a DIAL message id
  (plus the alias map after change 2).

The reference: `reasoning.py:signature`, `_decode`, `replay_item`, `encrypted_block`;
`to_responses.py:_convert_reasoning`, `_convert_assistant_message`; `common.py:requested_effort`, `turn_effort`,
`thinking_omitted`, `visible_messages`; `streaming.py:_on_reasoning_delta`, `_open_reasoning`, `_finish_reasoning`,
`_flush_pending_reasoning`, `_on_output_item_done`, `_on_final`; `app.py:_translate_messages` (where
`omit_thinking` is computed). Where the code and a fixture disagree, the fixture wins.

## Goals / Non-Goals

**Goals:**

- Every reasoning-dependent request and stream fixture passes and leaves the known-gaps list (proposal, Impact).
- A carrier from the translator service replays through the builtin translator, and one from the builtin translator
  replays through the service, with no conversion.
- Replayed reasoning is routed by the existing Responses chain, with no second affinity path.

**Non-Goals:**

- Any change to `EncryptedContentWrapFn`, `ResolveEncryptedContentAffinityFn` or `EncryptedContentAffinityUtil`.
- The non-streaming answer's thinking blocks: change 4 reuses the carrier class this change adds.
- The `include` field: change 1 sends it.

## Decisions

### 1. One carrier class in the `translator` module

`ReasoningCarrier` ports `reasoning.py`: `encode(item)` (the carrier string), `decode(block)` (the replay item, or
null), and `wholeBlock(item, omitThinking)` (`encrypted_block`). The request translation, the stream and, in change 4,
the non-streaming answer all use it.

Rejected: writing the format inline in `MessagesToResponses` and `ResponsesToMessagesStream`. Two copies of a format
that must stay identical to Python's would drift.

### 2. The carrier holds Core's stamped values verbatim

The translator never decodes `dialenc_` / `dialenc:`. On the way out the carrier takes the values after
`EncryptedContentWrapFn` (already the order in `ResponsesToMessagesReadStream`); on the way back the replay item
carries them into the translated body, and `ResolveEncryptedContentAffinityFn` unwraps them and pins the route.

Rejected: unwrapping in the translator and handing the upstream id to the controller. It would be a second affinity
path beside the Responses one, and its 400 / 409 answers could drift from the Responses API's. Also rejected: raw
provider values in the carrier with affinity kept elsewhere. The translator service's carriers hold stamped values, so
a session moving between the two would break.

### 3. Encoding matches `json.dumps(..., ensure_ascii=False, separators=(",", ":"))`

An `ObjectNode` with keys in Python's order (`version`, `id` when the item has a non-empty string id,
`encrypted_content`) under the one wrapper key, written by Jackson's `JsonNode.toString()`, as the module already
writes its SSE frames (`ResponsesToMessagesStream.java:frame`): compact, non-ASCII left as is,
`"` `\` and `\b \t \n \f \r` escaped as Python escapes them. One known difference: another control character is
written `\u001B` by Jackson and `\u001b` by Python. The decoded payload is the same and both sides parse the carrier,
so replay works either way; no fixture has such a character.

Rejected: building the string by hand. Escaping is where it would go wrong.

### 4. Decoding is as strict as Python's

A value is a carrier when it is a string that, after leading whitespace, starts with `{`, parses with duplicate keys
and trailing text rejected (`JsonParser.Feature.STRICT_DUPLICATE_DETECTION` and
`DeserializationFeature.FAIL_ON_TRAILING_TOKENS` on a reader used only here; Jackson's `readTree` otherwise ignores
what follows the object, where Python's `json.loads` fails with "Extra data"), has exactly the wrapper key, and its
payload has only `version`, `id`, `encrypted_content`, with `version` an integer JSON number equal to 2 (not `2.0`,
`"2"` or `true`), `encrypted_content` a non-empty string, and `id` absent or a non-empty string. A `signature` /
`data` that is not a string (`reasoning.py:replay_item`; fixtures `request/0c33b635b30740a5.json`,
`request/277fcd5b6dcb4014.json`) is no carrier. A parse failure, including Jackson's nesting limit, means no carrier. Anything else is dropped without a log line, as Python drops it.
The field read is `data` for `redacted_thinking` and `signature` for `thinking`; only a `thinking` block's non-empty
`thinking` text becomes the summary.

Rejected: a lenient decode (accept other versions, ignore extra keys). A block Python drops would replay through Core
and not through the service, so the two translators would answer the same conversation differently.

### 5. Request reasoning in `MessagesToResponses`

Ports `requested_effort`, `turn_effort` and `_convert_reasoning`. `thinking` and `output_config` leave
`DROPPED_FIELDS`; `output_config.format` keeps its warning (structured output is a later series), and
`thinking.budget_tokens` is dropped at debug level, as in Python. An unsupported effort throws
`UntranslatableRequestException`, which the controller already answers with 400; the body shape is change 4's. The
Messages defaults run before the translation (`MessagesController.java:prepareTranslatedUpstreamRoute`, the
`ApplyDefaultDeploymentSettingsFn` for `ANTHROPIC_MESSAGES`), as they do before the body reaches the translator
service, so a `thinking` or effort from the deployment's `anthropicMessages` defaults is translated like a client's.
The translated body then runs `ResponsesController.java:buildEnhancementFunctions`, whose
`ApplyDefaultDeploymentSettingsFn` for `OPENAI_RESPONSES` deep-merges the model's `openaiResponses` defaults into keys
the translation left unset (`JsonUtil.java:applyDefault`, `copyObjects`). So `responsesDefaults` of
`{"reasoning": {"summary": "detailed"}}` sends `{"effort": "none", "summary": "detailed"}` under disabled thinking,
and a `reasoning` when the client named nothing. The translator service's Responses pass runs the same chain, so the
two answer alike; the spec states the rule for the translation and leaves the merge to "Defaults per interface" in
`deployment-interfaces-config`.

A replayed reasoning item flushes the pending text, like a `tool_use` block after change 2; a dropped block does not,
so the text on both sides of it joins into one message (`_convert_assistant_message`).

### 6. Stream state as in `streaming.py`

`ResponsesToMessagesStream` gains Python's four pieces of reasoning state: the keys whose thinking block opened, the
last part per key (for the `\n\n` separator), the keys already carried, and the held items in arrival order (a
`LinkedHashMap`, as Python's dict). Reasoning keys are `r:<output_index>`, beside the existing `m:` and change 2's
`f:` keys. Held carriers go out after a function call's `response.output_item.done` closes its block (change 2's
branch) and at the terminal event, after the open block closes. `reasoning_summary_part.added`, `.part.done` and
`reasoning_text.done` emit nothing.

`EncryptedContentWrapFn` does not stamp `item_id` on `response.reasoning_text.delta`. That does not matter: the state
machine keys reasoning on `output_index`, and the carrier comes from `output_item.done` or the terminal output, which
are stamped.

### 7. The omitted-display flag comes from the controller

`MessagesToResponses.isThinkingOmitted(tree)` ports `thinking_omitted`. `prepareTranslatedUpstreamRoute` reads it from
the Messages body after the defaults, keeps it beside `requestedModel`, and `processResponse` passes it to the
`ResponsesToMessagesStream` constructor.

Rejected: reading it in `processResponse` from `context.getRequestBody()`. That parses the client body a second time.

### 8. Tests

The fixture parity test from change 1 is the oracle for the mapping detail. Each spec scenario gets an integration
test in `BuiltinTranslatorApiTest`: one parameterized test for the request scenarios (Messages body → expected
fragment of the upstream body, or the 400), one parameterized test for the stream scenarios (an SSE resource → the
expected client events), and one test per affinity scenario. A new test model `gpt-builtin-two-upstreams`
(`openaiResponses` on the mock server, `anthropicMessages` through `builtinResponses`, upstreams `up-a` and `up-b`)
and its role limit serve the carrier and affinity tests. Expected stamped values come from
`EncryptedContentAffinityUtil.wrapOutputItem`, which is deterministic for the same upstream, deployment and value.
`ReasoningCarrier` gets a unit test for each decode branch. `MessagesToResponsesTest` and
`ResponsesToMessagesStreamTest` get only the cases neither the fixtures nor the integration tests reach (an expired
`system` message's effort, a named effort `none` with thinking, the switch between summary parts), as change 1's decision 8 keeps one
oracle per mapping row.

Rejected: adding `anthropicMessages` to `responses-cache`. `ResponsesApiTest` owns that model.

### 9. Three requirements are modified, not worked around

Change 1's "Generation settings in a translated request" says `thinking` and `output_config` are dropped. Change 2's
"Content blocks without a translation are refused" refuses `thinking` and `redacted_thinking` in an assistant turn,
and change 2's "Conversation text in a translated request" says only a `tool_use` block splits an assistant turn's
text. All three are false once this change lands, so the delta modifies them. Archive replaces a MODIFIED block by its
header, so the two blocks change 2 already modified start from change 2's text word for word: the refusal block adds
only `thinking` and `redacted_thinking` to the assistant-turn list and a `thinking`-in-a-user-turn scenario (that block
is still refused: Python drops it, no fixture has it, and change 1 refuses untranslated blocks rather than drop them),
keeping change 2's `tool_result` content clause and its "Image inside a tool result" scenario; the text block adds
"or replayed reasoning item" to what splits a run, and that a dropped thinking block does not. A thinking block in a
user turn keeps the first version's message, `Content block 'thinking' in the user turn …`.

Rejected: adding only requirements and leaving the blocks as they are. The merged spec would say both "dropped" and
"becomes `reasoning`", and both "joined into one message" and "split by the reasoning item".

### Deliberate differences

From today's behaviour: `thinking` and `output_config.effort` are translated instead of dropped; `thinking` and
`redacted_thinking` blocks in an assistant turn are replayed or dropped instead of answered with 400; reasoning output
reaches the client.

From the reference:

- The message id is a DIAL id (unchanged from the first version; the fixture comparison normalises it).
- A non-string effort: Python's request model rejects it while parsing, with a validation message. Core answers 400
  with the effort refusal, naming the value's JSON text.
- The refusal quotes the value in single quotes always. Python uses `repr`, which switches to double quotes for a value
  that holds a single quote.
- The carrier's escape of rare control characters (decision 3).

### Cost per request

- Core passes: one, unchanged.
- Parses of a forwarded body: none added. The request translation already builds a new tree.
- Request-side work grows with the replayed ciphertext, on every turn: a carrier holds the upstream's whole
  ciphertext and a session's turns accumulate carriers. Each replayed carrier is parsed once
  (`ReasoningCarrier.decode`). `ResolveEncryptedContentAffinityFn` then unwraps each replayed item
  (`EncryptedContentAffinityUtil.java:resolveAndUnwrap`, `unwrapId`, `unwrapContent`: two base64 decodes, two small
  JSON parses and a substring copy of the ciphertext). The builtin path reaches that unwrap for the first time,
  because replayed thinking blocks were refused with 400 until now. The ciphertext is also copied into the translated
  tree, re-serialized with the Messages body (`MessagesController.java:prepareTranslatedUpstreamRoute`,
  `request.serialize()`) and serialized with the translated body (`translated.serialize()`).
- Response side: the state machine writes one JSON string per encrypted reasoning item, not per event, holding the
  whole ciphertext. That string is escaped a second time into its SSE frame (`ResponsesToMessagesStream.java:frame`),
  then buffered by `BufferingReadStream` and parsed by `MessagesSseListener`
  (`MessagesController.java:processResponse`), like every client frame.
- Redis and blob round trips: none added.
- The client's stream grows by the upstream's ciphertext in the carriers, as through the translator service.

No measurement task: the change adds no pass, no parse of a forwarded body and no round trip. The work that grows
with the ciphertext is the work the same items cost through the translator service today, whose Responses pass runs
the same unwrap and whose Messages pass forwards the same carriers.

## Risks / Trade-offs

- [The translator service changes its carrier format] → The fixtures pin Python commit `5e70209`. A new format needs a
  matching Core change before sessions can move between the two again; `docs/dynamic-settings/translators.md` names the
  shared format.
- [A carrier written on a model without configured upstreams replays after upstreams were added] → Its values are
  unstamped, so nothing pins the route, as for a Responses request today.
- [A carrier stamped for another deployment] → `ResolveEncryptedContentAffinityFn` logs a warning and pins to the named
  upstream if this model has it, as for a Responses request.
- [Changes 2 and 3 both add a constructor argument to `ResponsesToMessagesStream` and both edit the assistant-turn
  loop] → This change is applied after change 2 and builds on its loop and constructor.

## Migration Plan

none. No config changes. A model already on the builtin translator starts translating reasoning when the build is
deployed; rolling back drops it again, and the carriers clients hold stay valid for the translator service.

## Spec review

| # | Lens | Severity | Verdict | What changed |
|---|------|----------|---------|--------------|
| 1 | fidelity | high | accepted | The MODIFIED "Content blocks without a translation are refused" now starts from change 2's text word for word (both turn clauses, the `tool_result` content clause, "Image block", "Image inside a tool result") and adds only `thinking` / `redacted_thinking` to the assistant-turn list and the "Thinking block in a user turn" scenario. Tasks 2.2 lists "Image inside a tool result" among the unchanged scenarios; the proposal sentence that claimed the block held either way is replaced. |
| 2 | fidelity | medium | accepted | Added a MODIFIED "Conversation text in a translated request", change 2's block with "no `tool_use` block or replayed reasoning item" interrupting a run and a sentence that a dropped thinking block does not split it (moved there from "Thinking blocks without a carrier are dropped"). Change 2's three scenarios are kept and listed as unchanged in tasks 2.2. Proposal and decision 9 name three modified requirements. |
| 3 | fidelity | medium | accepted | Verified: `MessagesController.java:prepareTranslatedUpstreamRoute` applies the `ANTHROPIC_MESSAGES` defaults first and `ResponsesController.java:buildEnhancementFunctions` deep-merges the `OPENAI_RESPONSES` defaults afterwards (`JsonUtil.java:applyDefault`, `copyObjects`). "Reasoning settings from thinking and effort" now says the translation sets `reasoning` only from the request after its Messages defaults, and the model's `openaiResponses` defaults then fill unset keys; "Disabled thinking turns reasoning off" says the translation sets no other key and the defaults may still add some. The scenarios use a model with no deployment defaults (tasks 2.2 notes `gpt-builtin` has none). Decision 5 records the merge with an example. To keep the requirement under the validator's 500-character limit, the `system`-message effort rule moved to its own requirement, "Effort from a visible system message", with its existing scenario. |
| 4 | fidelity | low | accepted | Verified in `reasoning.py:_decode` / `replay_item` and the two fixtures. Decision 4 now enables `DeserializationFeature.FAIL_ON_TRAILING_TOKENS` beside duplicate detection and drops a non-string `signature` / `data`. The spec's list of dropped values adds "a value that is not a string" and "text after the JSON object"; tasks 1.1 adds both decode cases and trailing whitespace as accepted. |
| 5 | fidelity | low | accepted | The cost section now states that request-side work grows with the replayed ciphertext on every turn (carrier parse, the unwrap in `ResolveEncryptedContentAffinityFn`, reached on the builtin path for the first time, and the copies into and out of the translated body) and that the response side escapes the ciphertext twice and buffers it. No measurement task: the same items cost the same work through the translator service today. The proposal's per-request line says so too. |
| 6 | fidelity | low | accepted | Verified in `streaming.py:_finish_reasoning`. "Late reasoning carriers" is reworded: a carrier whose thinking block is not open goes out as a whole block (`redacted_thinking` when its text was streamed), at once when no block is open, else held. The dedup rule moved to its own requirement, "One carrier per reasoning item" (the first copy with encrypted content wins, later copies are ignored), with "No second carrier from the terminal copy", to keep both under the validator's 500-character limit. Added the scenarios "A carrier after its thinking block closed" and "An unencrypted item-done, then an encrypted terminal copy", both in tasks 3.2. |
| 7 | simplicity | high | accepted | Same defect as finding 1; fixed by the same edit. |
| 8 | simplicity | medium | accepted | Same defect as finding 2; fixed by the same edit, including the moved "dropped block does not split" rule and the updated proposal Capabilities and decision 9. |
| 9 | simplicity | low | accepted | Tasks 2.1 and 3.1 now only delete the known-gaps entries and add the cases neither the fixtures nor the integration tests reach (an expired `system` message's effort, `isThinkingOmitted`, the switch between summary parts). `ReasoningCarrierTest` stays. Decision 8 says why. |
| 10 | simplicity | low | accepted | "A carrier only in the terminal event" now pins the input (only `response.created` and a `response.completed` listing an encrypted item with an empty summary) and the output (one `redacted_thinking` block, then `message_delta`), matching `_on_final` → `_finish_reasoning` → `encrypted_block`. |
| 11 | simplicity | low | accepted | (1) Added the scenario "Structured output format dropped" to "Generation settings" (`output_config` with `effort` and `format` → `reasoning: {"effort": "high"}`, no `text`), in `testReasoningRequest`. (2) Took the finding's second option: the carrier requirement no longer has the untested parenthetical and defines the values as the ones a `POST /openai/v1/responses` to the same model answers, which covers a model without configured upstreams with no new test model. (3) Added the scenario "Non-string effort" (`"effort": 5` → 400 naming `'5'`); `testUnknownEffortIsRefused` is parameterized over both effort scenarios. |

## Implementation notes

Compared with the code after `builtin-translator-fixture-parity` and `builtin-translator-tools` (commit `09c127bf`).
The context above still holds: the request side, the Responses chain, `EncryptedContentWrapFn` and the stream state
machine are as described. Differences found while applying, and what was done:

- Known-gaps list: 68 request lines and 24 stream lines were owned by this change when it was applied (the proposal
  counted 69 request lines when the list was first written). All 92 are deleted and the parity test passes, so no
  entry owned by `builtin-translator-reasoning` is left.
- Decision 3: the carrier is written by an `ObjectWriter` with `JsonWriteFeature.WRITE_HEX_UPPER_CASE` off
  (`ReasoningCarrier.java:encode`), the switch `MessagesToResponses.PYTHON_JSON` already uses, instead of
  `JsonNode.toString()`. A control character other than `\b \t \n \f \r` is then written `\u001b`, as Python writes
  it, so the carrier is the same text as the translator service's. The known difference in decision 3, and the
  matching bullet of "Deliberate differences", no longer apply. `ReasoningCarrierTest` pins it.
- Decision 1: `ReasoningCarrier` is a package-private utility class. Beside `encode`, `decode` and `wholeBlock` it has
  `hasEncryptedContent` (Python's `encrypted_content`), which the stream uses for both the item-done and the terminal
  copies.
- Decision 5: a `system` message's effort counts only when Python's truthiness says so (`turn_effort` uses
  `if value := output_config.get("effort")`), through the existing `MessagesToResponses.isPythonFalsy`. The refusal
  names a non-string effort by its JSON text (`'5'`, `'{"a":1}'`), as the spec says.
- Decision 6: function-call completion and reasoning completion share one handler, `onOutputItemDone`, as
  `streaming.py:_on_output_item_done` does; the terminal path and the empty-answer block use a new `emitWhole`
  (Python's `_emit_whole`).
- Decision 8: the stream scenarios' upstream events are built inline with helpers in `BuiltinTranslatorApiTest`
  (`summaryDelta`, `reasoningDone`, `completed`, …), as the class's existing tests build theirs with `sseEvents`,
  rather than read from resource files under `server/src/test/resources`; the expected client events are JSON trees.
  The carrier a test expects is built from `EncryptedContentAffinityUtil.wrapOutputItem` and the translator
  service's format written out in the test, so the wire format is checked, not echoed.
- Task 4.2: the test for "The carrier's upstream is gone" is `testCarrierOfGoneUpstreamIsRefused`; checkstyle's
  abbreviation rule rejects `OfAGone`.
- `ResponsesToMessagesReadStreamTest` gains the constructor's new `omitThinking` argument (`false`); nothing else in
  it changes.
