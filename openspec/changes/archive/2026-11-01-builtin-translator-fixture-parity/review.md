# Review: builtin-translator-fixture-parity

## Implementation

Tasks done: 16. Deferred: 6.2 (run the full test suite), which needs CI.

Main code changed:
- `translator/.../MessagesToResponses.java`: `model` comes from the argument, an `include: [reasoning.encrypted_content]` array is added, and new helpers (`turnTexts`, `systemParts`, `addSystemTexts`, `addText`) handle non-text blocks in system turns, `mid_conv_system` blocks and compaction without a summary.
- `server/.../anthropic/MessagesController.java`: `prepareTranslatedUpstreamRoute` calls `translateRequest(request.getTree(), request.getModel()).put("stream", true)`.

Test resources: `translator/src/test/resources/translation-fixtures/` (manifest.json plus 186 request, 41 response and 60 stream fixtures, copied verbatim from openai-translator-bedrock@5e70209) and `translation-fixtures-known-gaps.txt` (172 lines).

Tests added:
- `TranslationFixtureParityTest.testFixture` (parameterized, 287 fixtures: 77 pass, 38 skipped out of scope, 172 skipped as known gaps), `testEveryRecordedFixtureIsCopied`, `testKnownGapsNameInScopeFixtures`.
- `MessagesToResponsesTest`: `testNonTextBlockInSystemTurnIsDropped`, `testMidConversationBlockInUserTurnPrecedesIt`, `testMidConversationBlockInAssistantTurnPrecedesIt`, `testCompactionWithoutSummarySendsNothing`.
- `BuiltinTranslatorApiTest`: `testBlocksOnceRefusedAreTranslated`, `testToolsAreRefused`, `testMalformedConversationIsRefused`, `testLimitIsCheckedBeforeTheUpstream`, `testApplicationIsNotServedByBuiltinTranslator`, `testPerRequestKeyClosedOnTheTranslatedInterfaceIsRefused`.
- `TranslatorTest.inlineBuiltinTranslatorStaysInline`.
- Extended: `BuiltinTranslatorApiTest.testStreamingIsTranslatedInOnePass` (include field, exactly one origin request), `MessagesToResponsesTest.testTextConversation` (model from the argument, no stream, include), `ResponsesToMessagesReadStreamTest.testFlowControlAndFailuresReachTheUpstream` (no frame and no end after the upstream failure).

Other files: server test `aidial.config.json` (models `gpt-builtin-limited`, `gpt-builtin-closed`), `docs/dynamic-settings/translators.md`, `docs/dynamic-settings/models.md`, `CLAUDE.md`, `design.md`, `tasks.md`.

Design drift: the code matched every design.md citation. No earlier change of the series is archived, so the spec deltas needed no rebase; each MODIFIED block differs from `openspec/specs` only by the intended edits. Recorded in design.md "## Implementation notes":
1. The application `app-builtin` (task 4.1) is registered inside `testApplicationIsNotServedByBuiltinTranslator` through `ApplicationService.putApplication`, not in the shared test config. A config entry broke `ApplicationDeploymentApiTest.testOpenAiApi` and `CustomApplicationApiTest.testOpenAiApplicationListing`, which compare the whole listing.
2. Two test names failed checkstyle `AbbreviationAsWordInName` and were renamed (`testMidConversationBlockInUserTurnPrecedesIt`, `testApplicationIsNotServedByBuiltinTranslator`); tasks.md and design.md use the new names.
3. Decision 7 now holds the real known-gaps counts: 172 lines; tools 41 (35 request, 6 stream); reasoning 93 (69 request, 24 stream); non-streaming-errors 38. The estimate was about 178; six reasoning stream fixtures already pass because they expect no thinking block.
4. Task 2.2 found no drift. Only two text-path request fixtures failed (compaction, nested `mid_conv_system`), both fixed by task 2.1. All 21 text stream fixtures passed unchanged; `ResponsesToMessagesStream` and `ResponsesToMessages` were not touched.
5. The parity harness counts any exception from a replay as a mismatch, so a known gap that throws is skipped like one that fails by comparison.

## Verification

### Implementer checks
- `./gradlew checkstyleMain checkstyleTest` (JDK 21.0.8): passed after the two test renames.
- `:translator:test`: passed (MessagesToResponsesTest 14, ResponsesToMessagesStreamTest 8, ResponsesToMessagesTest 5, TranslationFixtureParityTest 289 with 0 failures, 210 skipped).
- `:config:test --tests TranslatorTest`: passed (10).
- `:server:test`: BuiltinTranslatorApiTest 13, ConfigPostProcessorTest 55, DeploymentEndpointUtilTest 49, MessagesControllerLimitTest 7, ResponsesToMessagesReadStreamTest 4, ConfigEntityCodecTest 5, ListingTest 20, DeploymentApiTest 9, ConfigApiTest 2, ConfigModelListTest 5, MessagesApiTest 20: all passed. `ApplicationDeploymentApiTest.testOpenAiApi` passed.
- `CustomApplicationApiTest`: 20 of 21 pass. `testApplicationListing` expects folder `gpt` and gets `gpt\`, a Windows path-separator failure outside the changed code; not re-run on a clean checkout. A full `ApplicationDeploymentApiTest` run hit Windows file-lock errors in its deployment tests.
- `openspec validate builtin-translator-fixture-parity --strict`: valid.
- `git status --short`: every untracked file belongs to a task.
- Manual only-shrinks check (task 3.2): removing `stream/b9750ab0ed887b26.json` from the list fails that fixture; listing the passing `request/295dc13faf5d447f.json` fails with "passes now"; listing a missing file fails `testKnownGapsNameInScopeFixtures`; the restored list is byte-identical and passes.

### First verification: passed
- Checkstyle: BUILD SUCCESSFUL (JDK 21.0.8.9).
- translator (`--rerun`): MessagesToResponsesTest 14/14, ResponsesToMessagesStreamTest 8/8, ResponsesToMessagesTest 5/5, TranslationFixtureParityTest 289 cases, 0 failures, 210 skipped (77 fixtures pass plus 2 list checks).
- config: TranslatorTest 10/10.
- server (`--rerun`): BuiltinTranslatorApiTest 13/13, ResponsesToMessagesReadStreamTest 4/4, MessagesControllerLimitTest 7/7, ConfigPostProcessorTest 55/55, DeploymentEndpointUtilTest 49/49, ConfigEntityCodecTest 5/5, ListingTest 20/20, DeploymentApiTest 9/9, ConfigApiTest 2/2, ConfigModelListTest 5/5, MessagesApiTest 20/20, CanonicalIdListingTest 4/4.
- openspec strict validation: valid.
- Untracked files match tasks 1.1, 1.2 and 1.4; fixture counts match the manifest and commit 5e70209.
- Known-gaps list: 172 lines (tools 41, reasoning 93, non-streaming-errors 38 = 36 response + 2 stream); none names this change; matches decision 7.
- Cost-claim check: matches. design.md "Effect per request" claims one Core pass, no extra body copies or parses, no Redis or blob change, and one upstream effect (`include` makes reasoning models add encrypted reasoning, which Core parses, wraps and drops); none of it measured. In the code, `getModel()` reads the already parsed tree and `put("stream", true)` edits the ObjectNode just built; the new helpers walk the same JsonNode once and append to the same ArrayNode. No blocking call on the event loop, no Redis or blob access, no new Future chain, nothing newly parsed. Note: a body without `model` now yields `""` instead of null; Messages requests always carry a model and `EnhanceDeploymentRequestFn` still applies `overrideName`.

### Final verification: passed
Same commands and results as the first verification.

## Review findings

| Lens | Severity | File:line | Finding |
|---|---|---|---|
| conformance | low | openspec/changes/builtin-translator-fixture-parity/design.md:315 | Implementation note 3 says every BuiltinTranslatorApiTest test counts origin requests through `origin(...)`, but `testNonStreamingIsRefused`, `testUntranslatableContentIsRefused`, `testDeploymentWithInterceptorsIsRefused` and `testConsentOfTheTranslatedInterfaceIsRequired` use a bare `TestWebServer(4848)` and assert only the status. The "origin receives no request" clause is covered only indirectly (the mock answers 500). |
| conformance | low | openspec/changes/builtin-translator-fixture-parity/design.md:231 | Open Questions still reconciles builtin-translator-reasoning's stream count as 29 vs 30; the list holds 69 request and 24 stream reasoning lines (8 reasoning-rule fixtures already pass). builtin-translator-reasoning tasks.md 2.1 and 3.1 still say to delete 70 and 29 entries. |
| simplicity | low | translator/src/main/java/com/epam/aidial/core/translator/MessagesToResponses.java:107 | `translateSystem` keeps an inline copy of the skip-empty-text rule (lines 102, 107-110) that the new `addText` helper (line 227) also implements; a later change to `addText` would miss the top-level `system` prompt. |
| simplicity | low | server/src/test/java/com/epam/aidial/core/server/BuiltinTranslatorApiTest.java:250 | New `origin()`, `refusingOrigin()` and `eventStream()` helpers are used only by some tests; `testStreamWithoutTerminalEventEndsInError` and four refusal tests still hand-roll the 4848 origin, and those refusal tests never assert zero origin requests. |
| simplicity | low | server/src/test/resources/aidial.config.json:400 | `gpt-builtin-closed` carries `overrideName` and `upstreams`, and `gpt-builtin-limited` carries `upstreams` (line 396); no test reads these fields. |

## Outcome

No fix round ran.

- High/medium findings open: none.
- Low findings: all five fixed after the review, before archiving. The four refusal tests and the truncated-stream test use `refusingOrigin()` / `origin(...)`, and the refusal tests assert zero origin requests. `translateSystem` uses `addText`. The unused `overrideName` and `upstreams` are gone from the two test models. The reasoning counts here and in builtin-translator-reasoning (proposal, tasks 2.1 and 3.1) now give the applied list counts, 69 request and 24 stream lines.
- CI must still confirm: the full test suite (task 6.2), including `CustomApplicationApiTest.testApplicationListing` and the `ApplicationDeploymentApiTest` deployment tests, which failed locally on Windows path-separator and file-lock errors.
