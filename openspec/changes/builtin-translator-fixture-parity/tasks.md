# Tasks

Checks for every group: `./gradlew checkstyleMain checkstyleTest` and the group's test classes with `--tests`, using a
JDK 21 `JAVA_HOME` and the `GPR_USERNAME` / `GPR_PASSWORD` of CLAUDE.md. The full suite runs in CI.

Scenario tests. A scenario marked "fixture" is a case of `TranslationFixtureParityTest`, which replays that file from
`translator/src/test/resources/translation-fixtures/`.

- anthropic-messages-translation:
  - Served in one pass, Upstream request, Plain text answer, Provider response id not sent, Charged once:
    `BuiltinTranslatorApiTest.testStreamingIsTranslatedInOnePass`. Plain text answer is also fixture
    `stream/da44c004c8735875.json`.
  - System blocks joined: fixture `request/765fbaa997aea463.json`.
  - System with no text: fixture `request/1f8e4c6e4841bcc4.json`.
  - System turn kept in place: fixture `request/afa2661bad9b3ee0.json`.
  - Expired system turn not sent: fixture `request/41e05bd29d2956a4.json`.
  - Nested mid-conversation block joins its system turn: fixture `request/20107656bd532f6b.json` and
    `BuiltinTranslatorApiTest.testBlocksOnceRefusedAreTranslated`.
  - Mid-conversation block in a user turn: `MessagesToResponsesTest.testMidConversationBlockInAUserTurnPrecedesIt`.
  - Replayed compaction block: fixture `request/0b300ff50856df84.json` and
    `BuiltinTranslatorApiTest.testBlocksOnceRefusedAreTranslated`.
  - Empty user turn: fixture `request/306da35de39f454e.json`.
  - Small output budget raised: fixtures `request/5cc23e73bb3f4de2.json` and `request/abdf28b7b1fad8e5.json`.
  - Sampling fields: fixture `request/82c7fa71680699c2.json`.
  - Service tier: fixture `request/191335e211a48b07.json`.
  - Long user id dropped, not truncated: fixtures `request/a662b8fbe212ce16.json` (191 characters, dropped) and
    `request/714b6d8b8c44a049.json` (64 characters, sent).
  - Cache control dropped: fixture `request/337e1c4eb27cfb4f.json`.
  - Answer with no text: `ResponsesToMessagesStreamTest.testEmptyAnswerStillHasOneBlock`.
  - Unknown upstream events ignored: fixture `stream/9cf51fcd59442a65.json`.
  - Stream without response.created: fixture `stream/cd8d9d0142733f34.json`.
  - Error before anything else: `ResponsesToMessagesStreamTest.testFailedResponseIsAnErrorAndNothingFollowsIt`.
  - Output cap reached: fixture `stream/36539ea4f889555e.json`.
  - Refusal: fixture `stream/a8da0ae051c1bdf1.json`.
  - Stream without a terminal event: `BuiltinTranslatorApiTest.testStreamWithoutTerminalEventEndsInError` and
    fixture `stream/e4031d0ec9b5c17f.json`.
  - Failed response: fixture `stream/802dc8d0ed2ececc.json`.
  - Failure caused by the request: fixture `stream/fcca5a604050964a.json`.
  - Flat error event: fixture `stream/90a9f69669c97320.json`.
  - Upstream connection fails mid-stream: `ResponsesToMessagesReadStreamTest.testFlowControlAndFailuresReachTheUpstream`.
  - Cache reads come out of the input total: fixture `stream/da44c004c8735875.json`.
  - Reasoning tokens as thinking tokens: fixture `stream/0b453d98912f2d99.json`.
  - Limit reached: `BuiltinTranslatorApiTest.testLimitIsCheckedBeforeTheUpstream`.
  - Consent required by the Responses interface: `BuiltinTranslatorApiTest.testConsentOfTheTranslatedInterfaceIsRequired`.
  - Responses interface closed to per-request keys:
    `BuiltinTranslatorApiTest.testPerRequestKeyClosedOnTheTranslatedInterfaceIsRefused`.
  - Request with tools: `BuiltinTranslatorApiTest.testToolsAreRefused`.
  - Non-streaming request: `BuiltinTranslatorApiTest.testNonStreamingIsRefused`.
  - Image block: `BuiltinTranslatorApiTest.testUntranslatableContentIsRefused`.
  - Tool use block: `MessagesToResponsesTest.testNonTextContentIsRefused`.
  - Unknown role: `BuiltinTranslatorApiTest.testMalformedConversationIsRefused` and fixture
    `request/e8dbc15917afa8f9.json`.
  - Messages not an array: `BuiltinTranslatorApiTest.testMalformedConversationIsRefused`.
  - count_tokens on a builtin-translated model: `BuiltinTranslatorApiTest.testCountTokensIsNotServedYet`.
  - Model with an interceptor: `BuiltinTranslatorApiTest.testDeploymentWithInterceptorsIsRefused`.
- deployment-interfaces-config:
  - Builtin translator served through the model's own interface: `BuiltinTranslatorApiTest.testStreamingIsTranslatedInOnePass`.
  - Inline builtin translator kept inline: `TranslatorTest.inlineBuiltinTranslatorStaysInline`.
  - Missing baseUrl rejected on read: `TranslatorTest.registryEntryMissingBaseUrlIsRejectedOnRead`.
  - Both baseUrl and builtin rejected on read: `ConfigPostProcessorTest.testTranslatorNeedsExactlyOneOfBaseUrlOrBuiltin`.
  - Unknown builtin rejected on read: `ConfigPostProcessorTest.testUnknownBuiltinTranslatorIsRejectedAsItIsRead`.
  - Registered builtin with another output: `ConfigPostProcessorTest.testSemanticThrowsOnRegistryBuiltinTranslatorNotMatchingItsMapping`.
  - Inline builtin under another interface: `ConfigPostProcessorTest.testSemanticThrowsOnInlineBuiltinTranslatorUnderAnotherInterface`.
  - Application with a builtin translator: `BuiltinTranslatorApiTest.testApplicationIsNotServedByABuiltinTranslator`.
  - All other scenarios: unchanged, with the tests that cover them on `development`.
- app-to-model-accounting and deployment-origin: their scenarios are unchanged.

## 1. Fixtures and the parity test

- [ ] 1.1 Copy the Python translator's recorded fixtures into `translator/src/test/resources/translation-fixtures/`:
  `request/`, `response/`, `stream/` and `manifest.json` from `openai-translator-bedrock/build/translation-fixtures`.
  The manifest must name `translator_commit` `5e70209dae60f8de2c090972da32de38058e8600` and `dirty: false`. Copy
  only; the recorder stays in the Python repository. Verify there are 186, 41 and 60 files, the manifest's `counts`.
- [ ] 1.2 Create `translator/src/test/resources/translation-fixtures-known-gaps.txt` with a header comment. The header
  gives the line format (`<kind>/<file> <change id>`), the owner rule of design.md decision 7, the only-shrinks rule,
  and how to refresh: rerun the recorder in the Python repository, delete `translation-fixtures/`, copy the new output
  in, run the parity test and update this list.
- [ ] 1.3 Change `MessagesToResponses.translateRequest` to `translateRequest(JsonNode request, String model)`:
  - it takes `model` from the argument and no longer writes `stream`;
  - it writes `include: ["reasoning.encrypted_content"]` next to `store: false`.

  In `MessagesController.prepareTranslatedUpstreamRoute`, pass `request.getModel()` and put `stream: true` on the
  translated tree. Update `MessagesToResponsesTest` for the new signature, the absent `stream` and the `include`
  field. In `BuiltinTranslatorApiTest.testStreamingIsTranslatedInOnePass`, assert the origin body's `include` and
  that the origin received exactly one request. Verify `MessagesToResponsesTest` and `BuiltinTranslatorApiTest` pass.
- [ ] 1.4 Write `TranslationFixtureParityTest` in `translator/src/test/java/com/epam/aidial/core/translator/` as design.md
  decisions 6 and 7 describe:
  - the count check against the manifest;
  - the out-of-scope skip;
  - request and stream replay, with response and `emit_error` failing as unsupported;
  - `message.id` normalisation and comparison after a round trip;
  - the known-gaps rules, with a separate test for lines that name a missing or out-of-scope fixture.
- [ ] 1.5 Run the test once and write every in-scope fixture that fails into the known-gaps list, sorted by owner then
  path: a fixture design.md decision 7 assigns to no later change gets `builtin-translator-fixture-parity`, every
  other gets its owner by decision 7. Verify `./gradlew :translator:test --tests
  "com.epam.aidial.core.translator.TranslationFixtureParityTest"` passes, with 287 cases and 38 skipped as out of
  scope.

## 2. Text-path parity

- [ ] 2.1 In `MessagesToResponses`, port the text blocks of design.md decision 4:
  - `_system_parts` for a `system`-role turn: string content, text, nested `mid_conv_system`, and other blocks dropped
    with a warning;
  - `mid_conv_system` in a user turn, sent before the user message, and in an assistant turn, sent before the turn;
  - `compaction` as text when its `content` is a non-empty string.

  First add a `MessagesToResponsesTest` case for each branch: an image block in a system turn is dropped,
  `testMidConversationBlockInAUserTurnPrecedesIt` (the scenario's request), a `mid_conv_system` block in an assistant
  turn, and a `compaction` block with `null` content sends nothing. Add
  `BuiltinTranslatorApiTest.testBlocksOnceRefusedAreTranslated`: a streaming request whose turns hold a `compaction`
  block and a system turn with a nested `mid_conv_system` block gets `200`, and the origin `input` holds the
  translated `system` and `assistant` items. Delete the known-gaps lines of the fixtures that now pass. Verify the new
  cases, `TranslationFixtureParityTest` and `BuiltinTranslatorApiTest` pass.
- [ ] 2.2 Make every fixture still listed with `builtin-translator-fixture-parity` pass, deleting its line. Fix a
  failure in `MessagesToResponses`, `ResponsesToMessagesStream` or `ResponsesToMessages` by porting the Python function
  the failure points at, never by editing the harness or the fixture. Record any such fix in a "Drift" note at the end
  of design.md. Verify no line names `builtin-translator-fixture-parity`, 48 request and 21 stream fixtures pass,
  `TranslationFixtureParityTest`, `ResponsesToMessagesStreamTest` and `ResponsesToMessagesTest` pass, and
  `ResponsesToMessagesReadStreamTest` and `BuiltinTranslatorApiTest` pass.

## 3. Known-gaps list

- [ ] 3.1 Check the counts per owner in `translation-fixtures-known-gaps.txt` against the estimate of design.md
  decision 7: about 41 tools, 99 reasoning and 38 non-streaming-errors. Where they differ, write the real counts into
  decision 7. Verify `TranslationFixtureParityTest` passes.
- [ ] 3.2 Check by hand that the list can only shrink, then undo each edit:
  - delete one line: the test fails, naming that fixture;
  - add a line for a passing fixture such as `request/295dc13faf5d447f.json`: the test fails;
  - add a line for a file that does not exist: the test fails.

  Verify `TranslationFixtureParityTest` passes again with the list as committed.

## 4. Refusals, limits and config on the server

- [ ] 4.1 In `server/src/test/resources/aidial.config.json`, add:
  - an application `app-builtin` with `interfaces.openaiResponses.base_url: http://localhost:4848` and the builtin
    `anthropicMessages` interface;
  - a model `gpt-builtin-limited`, a copy of `gpt-builtin` without `overrideName`, with a role limit low enough that
    one translated answer of 18 tokens uses it up;
  - a model `gpt-builtin-closed`, a copy of `gpt-builtin` with `features.accessibleByPerRequestKey: false` on
    `interfaces.openaiResponses` only.

  Then add these tests to `BuiltinTranslatorApiTest`:
  - `testToolsAreRefused`: 400, the message contains `Tools`, and the origin receives no request;
  - `testMalformedConversationIsRefused`: a role `tool` gets 400 with the exact message `Unknown message role: 'tool'`,
    `messages: "hi"` gets 400 with a message containing `'messages'`, and the origin receives nothing;
  - `testLimitIsCheckedBeforeTheUpstream`: the first request is served, the second gets 429, and the origin receives
    one request;
  - `testApplicationIsNotServedByABuiltinTranslator`: 503 and the origin receives nothing;
  - `testPerRequestKeyClosedOnTheTranslatedInterfaceIsRefused`: an application registered at
    `http://localhost:4848/app` calls Core back with the `API-KEY` it received, as
    `DeploymentPostApiTest.testAutoShareAnnotationCitationAttachment` does, sending a streaming
    `POST /anthropic/v1/messages` for `gpt-builtin-closed`; that call gets 403, and the origin receives no
    `POST /openai/v1/responses`.

  These test existing behaviour; a failure is drift to record in design.md before any code change. Verify
  `BuiltinTranslatorApiTest` passes, and so do the tests that list or count the shared test config's deployments:
  `ListingTest`, `DeploymentApiTest`, `ConfigApiTest`, `ConfigModelListTest` and `MessagesApiTest`.
- [ ] 4.2 Add `inlineBuiltinTranslatorStaysInline` to `config/src/test/java/com/epam/aidial/core/config/TranslatorTest.java`
  (read and write back: the translator stays an inline object with `builtin` and no `baseUrl`). Verify
  `./gradlew :config:test --tests "com.epam.aidial.core.config.TranslatorTest"` passes, and so do
  `ConfigPostProcessorTest` and `DeploymentEndpointUtilTest`.
- [ ] 4.3 In `ResponsesToMessagesReadStreamTest.testFlowControlAndFailuresReachTheUpstream`, assert that the stream
  emits no frame after the upstream failure. Verify `ResponsesToMessagesReadStreamTest` passes.

## 5. Docs

- [ ] 5.1 Update the "Builtin translators" section of `docs/dynamic-settings/translators.md`:
  - what `anthropicMessagesToOpenaiResponses` translates now: the system prompt, system turns, `mid_conv_system` and
    `compaction` blocks, and text;
  - that it asks the upstream for `include: ["reasoning.encrypted_content"]`;
  - what is refused: tools, other content blocks, a malformed `messages`, non-streaming requests and `count_tokens`;
  - that its text translation matches the Python translator's fixtures recorded at commit `5e70209`, and that
    `translator/src/test/resources/translation-fixtures-known-gaps.txt` lists the fixtures it does not match yet.

  Verify each statement against the anthropic-messages-translation spec delta.
- [ ] 5.2 In `docs/dynamic-settings/models.md`, update the `interfaces` field list:
  - `translator`: the inline form takes `out` and one of `baseUrl` or `builtin`;
  - `mode`: the role-limits exemption applies to a translator service only, while a builtin translator is checked and
    charged once.

  Both link to translators.md "Builtin translators". Verify each statement against the deployment-interfaces-config and
  app-to-model-accounting spec deltas.
- [ ] 5.3 Add the `translator` module to the module table in `CLAUDE.md` (`com.epam.aidial.core.translator`: pure
  Java and Jackson translation between Anthropic Messages and OpenAI Responses for builtin translators,
  parity-tested against the Python translator's recorded fixtures), and add `./gradlew :translator:test` to the test
  commands. Verify the command runs as written.

## 6. Integration checks

- [ ] 6.1 Run `./gradlew checkstyleMain checkstyleTest`, `./gradlew :translator:test`, `./gradlew :config:test --tests
  "com.epam.aidial.core.config.TranslatorTest"`, and `./gradlew :server:test` with `--tests` for
  `BuiltinTranslatorApiTest`, `ConfigPostProcessorTest`, `DeploymentEndpointUtilTest`, `MessagesControllerLimitTest`,
  `ResponsesToMessagesReadStreamTest`, `ConfigEntityCodecTest`, `ListingTest`, `DeploymentApiTest`, `ConfigApiTest`,
  `ConfigModelListTest` and `MessagesApiTest`. Verify all pass.
- [ ] 6.2 Run the full test suite (deferred: needs CI)
