# Tasks

Applied after `builtin-translator-fixture-parity` and `builtin-translator-tools`. The fixture parity test and its
known-gaps list come from the first; each list entry names the change that owns it. Integration tests go in
`server/src/test/java/com/epam/aidial/core/server/BuiltinTranslatorApiTest.java`; SSE inputs for them go under
`server/src/test/resources`.

## 1. Reasoning carrier

- [ ] 1.1 Write `translator/src/test/java/com/epam/aidial/core/translator/ReasoningCarrierTest.java` first: `encode` with
  and without an id, key order and escapes equal to a carrier copied from a recorded stream fixture (quote, backslash,
  newline, a CJK character); `decode` of a valid version-2 carrier with and without `id`, with leading and trailing
  whitespace, and each rejected case (a non-string `signature` / `data` such as `123`, not starting with `{`,
  malformed JSON, trailing text after the object, a second top-level key, an extra payload key, a duplicate key,
  `version` `1` / `2.0` / `"2"` / `true`, missing or empty `encrypted_content`, empty `id`, a `thinking` block whose
  carrier is in `data`); `wholeBlock` joining summary and content texts with `\n\n`, giving `redacted_thinking`
  with no text, and empty `thinking` when omitted. Verify it fails before 1.2.
- [ ] 1.2 Add `translator/src/main/java/com/epam/aidial/core/translator/ReasoningCarrier.java`, a port of
  `reasoning.py` (design decisions 1, 3, 4). Verify `ReasoningCarrierTest` passes.
- [ ] 1.3 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :translator:test --tests "com.epam.aidial.core.translator.ReasoningCarrierTest"`; both pass.

## 2. Request translation

- [ ] 2.1 Delete the known-gaps entries owned by `builtin-translator-reasoning` for request fixtures (69 lines when the list was first applied; the list is the authority) and
  add to `MessagesToResponsesTest` only the cases neither the fixtures nor the 2.2 tests reach: an expired `system`
  message's effort ignored, and `isThinkingOmitted` (design decision 8). Verify the parity test and the new cases fail
  before 2.3.
- [ ] 2.2 Write the request integration tests, failing before 2.3:
  - `testReasoningRequest`, parameterized over Messages body → expected upstream body fragment, on `gpt-builtin`
    (no deployment defaults): "Adaptive thinking with an effort", "A token budget names no effort", "Omitted display
    asks for no summary", "A visible system message's effort wins", "Nothing named", "Disabled thinking outranks an
    effort", "Unknown effort under disabled thinking", "A thinking block between two texts", "A redacted thinking
    block", "A Claude-signed thinking block", "An older carrier version", "Structured output format dropped".
  - `testUnknownEffortIsRefused`, parameterized: "Unknown effort", "Non-string effort".
  - `testThinkingBlockInUserTurnIsRefused`: "Thinking block in a user turn".
  - The unchanged scenarios of the three modified requirements ("Small output budget raised", "Sampling fields",
    "Service tier", "Long user id dropped, not truncated", "Cache control dropped", "Image block", "Image inside a tool
    result", "Replayed compaction block", "Empty user turn", "Text around a tool use") keep the tests changes 1 and 2
    gave them.
- [ ] 2.3 In `MessagesToResponses`: port `requested_effort`, `turn_effort` and `_convert_reasoning`; take `thinking`
  and `output_config` out of `DROPPED_FIELDS`, keeping the warning for `output_config.format`; replay `thinking` /
  `redacted_thinking` blocks of an assistant turn through `ReasoningCarrier.decode` in change 2's ordered loop (a
  replayed item flushes the pending text, a dropped one does not); keep refusing them in a user turn; add
  `isThinkingOmitted` (design decision 5). Verify the parity test passes with the request entries gone, and
  `MessagesToResponsesTest` and the 2.2 tests pass.
- [ ] 2.4 Run `./gradlew checkstyleMain checkstyleTest`,
  `./gradlew :translator:test --tests "com.epam.aidial.core.translator.*"` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.BuiltinTranslatorApiTest"`; all pass.

## 3. Stream translation

- [ ] 3.1 Delete the known-gaps entries owned by `builtin-translator-reasoning` for stream fixtures (24 lines when the list was first applied) and add
  to `ResponsesToMessagesStreamTest` only the case neither the fixtures nor the 3.2 test reaches: summary parts 0 and
  1 of one item joined by `\n\n` (design decision 8). Verify it fails before 3.3.
- [ ] 3.2 Write `testReasoningStream`, parameterized over an SSE resource → expected client events on `gpt-builtin`,
  failing before 3.3: "Summary streamed, then the carrier", "Summary and raw reasoning text in one item",
  "Unencrypted reasoning", "Encrypted reasoning with no summary", "A summary only in the completed item", "A carrier
  during a tool call", "A carrier after its thinking block closed", "A carrier only in the terminal event", "An
  unencrypted item-done, then an encrypted terminal copy", "No second carrier from the terminal copy", "The carrier
  is kept without text" (request with `thinking.display: "omitted"`), "Unencrypted reasoning is not shown".
- [ ] 3.3 In `ResponsesToMessagesStream`: add the `omitThinking` constructor argument and the reasoning state and
  handlers of design decision 6, flushing held carriers after a function call's `response.output_item.done` and at the
  terminal event. In `MessagesController`: read `MessagesToResponses.isThinkingOmitted` from the Messages body after
  the defaults in `prepareTranslatedUpstreamRoute`, keep it beside `requestedModel`, and pass it to the constructor in
  `processResponse` (design decision 7). Verify `ResponsesToMessagesStreamTest` and `testReasoningStream` pass, and
  the parity test passes with no entry owned by `builtin-translator-reasoning` left in the known-gaps list.
- [ ] 3.4 Run `./gradlew checkstyleMain checkstyleTest`,
  `./gradlew :translator:test --tests "com.epam.aidial.core.translator.*"` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.BuiltinTranslatorApiTest"`; all pass.

## 4. Replay across turns and upstreams, docs

- [ ] 4.1 Add the test model `gpt-builtin-two-upstreams` to `server/src/test/resources/aidial.config.json`
  (`openaiResponses` on `http://localhost:4848`, `anthropicMessages` through `builtinResponses`, upstreams `up-a` and
  `up-b` with distinct endpoints and keys) and its role limit beside `gpt-builtin`'s. Verify the existing
  `BuiltinTranslatorApiTest` tests still pass.
- [ ] 4.2 Write the affinity integration tests on `gpt-builtin-two-upstreams`. They need no new production code: they
  check that groups 2 and 3 hand the existing Responses chain the stamped values.
  - `testCarrierHoldsStampedValues`: "The carrier holds the values Core stamped" (expected values from
    `EncryptedContentAffinityUtil.wrapOutputItem` for the upstream whose `X-UPSTREAM-KEY` the request carried).
  - `testReplayedReasoningKeepsItsUpstream`: "Turn 2 goes to turn 1's upstream" (turn 2 sent five times).
  - `testTranslatorServiceCarrierReplays`: "A carrier from the translator service" (values from
    `EncryptedContentAffinityUtil.wrapOutputItem` for `up-b`, inside the version-2 wrapper).
  - `testCarrierOfAGoneUpstreamIsRefused`: "The carrier's upstream is gone".
  - `testCarriersFromTwoUpstreamsAreRefused`: "Carriers from two upstreams".
  Verify all five pass, and `ResponsesApiTest` passes unchanged.
- [ ] 4.3 Update the `anthropicMessagesToOpenaiResponses` bullet in `docs/dynamic-settings/translators.md`: `thinking`
  and `output_config.effort` become `reasoning` (an effort outside `none … max` is refused), reasoning streams as
  `thinking` / `redacted_thinking` blocks, and their carriers use the translator service's format, so a deployment can
  move between a builtin translator and a translator service mid-conversation; drop `thinking` from the list of
  dropped fields. Verify the section names no reasoning field as dropped. `CLAUDE.md` needs no change (change 1 adds
  the module row).
- [ ] 4.4 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.BuiltinTranslatorApiTest" --tests "com.epam.aidial.core.server.ResponsesApiTest"`;
  both pass.

## 5. Integration check

- [ ] 5.1 Run `npx -y @fission-ai/openspec@1.14.3 validate builtin-translator-reasoning --strict`; it passes.
- [ ] 5.2 The full suite passes in CI on the series PR (deferred: needs the series PR's CI run).
