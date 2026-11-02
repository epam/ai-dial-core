# Review: builtin-translator-reasoning

## Implementation

Tasks done: 16. Deferred: 5.2 (the full suite passes in CI on the series PR). It needs the series PR's CI run.

Files changed:

- translator: `ReasoningCarrier.java` (new), `MessagesToResponses.java`, `ResponsesToMessagesStream.java`
- server: `controller/anthropic/MessagesController.java`
- tests: `ReasoningCarrierTest.java` (new), `MessagesToResponsesTest.java`, `ResponsesToMessagesStreamTest.java`, `TranslationFixtureParityTest.java`, `translation-fixtures-known-gaps.txt`, `BuiltinTranslatorApiTest.java`, `controller/anthropic/ResponsesToMessagesReadStreamTest.java`, server test `aidial.config.json`
- docs: `docs/dynamic-settings/translators.md`
- change: `design.md`, `tasks.md`

Tests added:

- `ReasoningCarrierTest`, 12 tests: encode spelling and escapes, decode accept and reject branches, `wholeBlock`, `hasEncryptedContent`.
- `MessagesToResponsesTest`: `testExpiredSystemMessageNamesNoEffort`, `testThinkingIsOmittedOnlyWhenDisplayIsOmitted`.
- `ResponsesToMessagesStreamTest`: `testSummaryPartsOfOneItemAreSeparated`.
- `BuiltinTranslatorApiTest`: `testReasoningRequest` (parameterized, 13 cases, including "Structured output format dropped"), `testUnknownEffortIsRefused` (parameterized: `xmax`, `5`), `testThinkingBlockInUserTurnIsRefused`, `testReasoningStream` (parameterized, 12 stream scenarios), `testCarrierHoldsStampedValues`, `testReplayedReasoningKeepsItsUpstream`, `testTranslatorServiceCarrierReplays`, `testCarrierOfGoneUpstreamIsRefused`, `testCarriersFromTwoUpstreamsAreRefused`.
- `TranslationFixtureParityTest`: 92 known-gap fixtures (68 request, 24 stream) now pass, and their lines are deleted.

Design drift, recorded under "## Implementation notes" in design.md:

1. The change owned 68 request lines and 24 stream lines in the known-gaps list, not the 69 request lines the proposal counted. All 92 are deleted, and no entry owned by this change is left.
2. Decision 3: the carrier is written by an `ObjectWriter` with `WRITE_HEX_UPPER_CASE` off, not by `JsonNode.toString()`. Other control characters come out as `\u001b`, as in Python, so the known difference in decision 3 and its "Deliberate differences" bullet no longer apply.
3. `ReasoningCarrier` is a package-private utility class and also has `hasEncryptedContent`.
4. A system message's effort counts only when it is truthy by Python's rules (reuses `isPythonFalsy`). The refusal names a non-string effort by its JSON text.
5. The stream has one `onOutputItemDone` handler and a new `emitWhole` helper, mirroring Python's `_on_output_item_done` and `_emit_whole`.
6. Stream test inputs are built inline with test helpers, as the test class already does, not read from resource files.
7. The test was renamed to `testCarrierOfGoneUpstreamIsRefused` because checkstyle rejects "OfAGone". tasks.md was updated to match.
8. `ResponsesToMessagesReadStreamTest` only gains the new constructor argument. The spec delta needed no rebase: all three MODIFIED blocks already start from the current text in `openspec/specs/anthropic-messages-translation`.

Implementer's checks: checkstyle passed after one rename (an abbreviation in a test method name); `:translator:test` and the targeted `:server:test` classes passed; `validate builtin-translator-reasoning --strict` valid; `validate --all --strict` 16 passed, 2 failed (`spec/user-limits` and `spec/resource-subscription` have placeholder Purpose sections; this predates the change, and their files are untouched).

## Verification

### Run 1 (after implementation): passed

- `checkstyleMain checkstyleTest` (all modules): BUILD SUCCESSFUL.
- `:translator:test --rerun --tests com.epam.aidial.core.translator.*`: MessagesToResponsesTest 27, ReasoningCarrierTest 12, ResponsesToMessagesStreamTest 15, ResponsesToMessagesTest 5, ToolNamesTest 7, TranslationFixtureParityTest 289 (76 skipped, 0 failures). The skipped tests are the 38 known-gaps lines owned by builtin-translator-non-streaming-errors. No line owned by this change is left.
- `:server:test --rerun`: BuiltinTranslatorApiTest 49, ResponsesApiTest 8, MessagesApiTest 20, controller.anthropic.MessagesControllerLimitTest 7, controller.anthropic.MessagesCacheWiringTest 2, controller.anthropic.ResponsesToMessagesReadStreamTest 4; 0 failures.
- `openspec validate builtin-translator-reasoning --strict`: valid.
- `git status --short`: 13 modified tracked files, 2 untracked (`ReasoningCarrier.java`, `ReasoningCarrierTest.java`). No stray files.
- Cost-claim check: the code matches design.md's cost section. Core passes stay at one. `MessagesController` reads `thinkingOmitted` with `MessagesToResponses.isThinkingOmitted(request.getTree())` from the tree that is already parsed and passes it to the `ResponsesToMessagesStream` constructor, so the client body is not parsed again (decision 7). The only new parse is `STRICT_JSON.readTree` on a replayed signature or data string (`ReasoningCarrier.parsePayload`). `ReasoningCarrier.encode` runs once per carried reasoning key (guarded by `reasoningCarried`); held carriers go in a `LinkedHashMap` that is flushed after a function_call `output_item.done` and at the terminal event. No blocking call on the event loop, no Redis or blob call, no new Future chain, and no change to `EncryptedContentWrapFn`, `ResolveEncryptedContentAffinityFn` or `EncryptedContentAffinityUtil`.

### Run 2 (final): passed

Same commands and results as run 1, and the same cost-claim conclusion. No code changed between the runs (no fix round).

## Review findings

| Lens | Severity | File:line | Finding |
|------|----------|-----------|---------|
| conformance | low | translator/src/main/java/com/epam/aidial/core/translator/MessagesToResponses.java:180 | Named effort `none` with non-disabled thinking sends `reasoning.summary: "auto"`. Python `_convert_reasoning` sends no summary when effort is `none`. The spec (line 5) has the same gap, "Deliberate differences" does not record it, and no fixture covers it. Fix: skip the summary for effort `none` and extend the spec plus a test, or record it as a deliberate difference. |
| correctness | low | translator/src/main/java/com/epam/aidial/core/translator/ResponsesToMessagesStream.java:299 | `onFinal` assumes `response.output` is an array. On an object, `get(i)` returns null and `item.path(...)` throws an NPE. SseParser swallows it, so the terminal `message_delta`/`message_stop` is lost: the client gets api_error and token usage is not recorded. Fix: guard with `output.isArray()` or use `output.path(i)`. |
| simplicity | low | translator/src/main/java/com/epam/aidial/core/translator/ResponsesToMessagesStream.java:58 | `reasoningStarted` always holds the same keys as `lastPartByReasoningKey`. Delete it and use `lastPartByReasoningKey.containsKey(key)` at line 224. |
| simplicity | low | translator/src/main/java/com/epam/aidial/core/translator/ResponsesToMessagesStream.java:225 | The `redacted_thinking` block is built here and in `ReasoningCarrier.wholeBlock` (ReasoningCarrier.java:139), two copies of one format. Fix: add `ReasoningCarrier.redactedBlock(item)` and use it in both places. |
| simplicity | low | translator/src/main/java/com/epam/aidial/core/translator/ResponsesToMessagesStream.java:301 | `onFinal` spells the key `"r:" + i` inline, a second copy of the `reasoningKey(event)` format. If one copy changes without the other, the terminal copy sends a second carrier. Fix: `reasoningKey(int outputIndex)`. |
| simplicity | low | translator/src/main/java/com/epam/aidial/core/translator/MessagesToResponses.java:162 | `!thinking.isMissingNode() && !thinking.isNull()` re-implements `hasNonNull`, which the same method uses at line 182. |
| simplicity | low | translator/src/test/java/com/epam/aidial/core/translator/MessagesToResponsesTest.java:344 | `testThinkingIsOmittedOnlyWhenDisplayIsOmitted` duplicates coverage that `testReasoningRequest` and `testReasoningStream` already give in both directions. Decision 8 calls the case unreached, which is wrong. Fix: delete the test and correct decision 8 and task 2.1. |

## Outcome

- No fix round ran.
- High or medium findings still open: none.
- Low findings: all 7 fixed after the review, before archiving. A named effort `none` now sends `{"effort": "none"}` with no summary, as to_responses.py:_convert_reasoning does (spec sentence extended, `testNamedEffortNoneSendsNoSummary` replaces the redundant `isThinkingOmitted` test). `onFinal` skips a non-array `output`. `reasoningStarted` is gone (`lastPartByReasoningKey` answers it), `ReasoningCarrier.redactedBlock` builds the one `redacted_thinking` block, `reasoningKey(int)` owns the key format, and `hasThinking` uses `hasNonNull`. Checkstyle, `:translator:test`, `BuiltinTranslatorApiTest`, `ResponsesApiTest` and `ResponsesToMessagesReadStreamTest` re-ran green.
- CI must still confirm: task 5.2, the full suite on the series PR. Locally, only checkstyle and the targeted translator and server test classes ran.
