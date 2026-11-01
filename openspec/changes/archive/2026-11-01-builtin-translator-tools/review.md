# Review: builtin-translator-tools

## Implementation

Tasks done: 14. Deferred: 4.3, measuring Core passes and Core CPU per request for a Claude Code-shaped streaming corpus with tools, builtin path against the translator service. It needs the perf environment.

Files changed:
- translator: `ToolNames.java` (new), `MessagesToResponses.java`, `ResponsesToMessagesStream.java`
- server: `controller/anthropic/MessagesController.java`
- tests: `ToolNamesTest.java` (new), `MessagesToResponsesTest.java`, `ResponsesToMessagesStreamTest.java`, `TranslationFixtureParityTest.java`, `translation-fixtures-known-gaps.txt`, `BuiltinTranslatorApiTest.java`, `ResponsesToMessagesReadStreamTest.java`
- docs: `docs/dynamic-settings/translators.md`; change: `design.md`, `tasks.md`

Tests added:
- `ToolNamesTest` (new): testLongNameIsAliased, testTakenAliasGetsCounterWithinTheLengthLimit, testHeadIsSanitised, testSameNameGetsOneAliasAtEverySite, testAliasNeverLandsOnRealName, testNamelessFunctionToolIsLeftAlone, testRestorePassesOtherValuesThrough.
- `MessagesToResponsesTest`: testCustomToolBecomesFunctionTool, testToolWithoutSchemaGetsAnEmptyObjectSchema, testEmptyToolListSendsNoTools, testOtherToolTypesAreForwardedAsSent, testWebSearchToolsAreRefused, testMalformedToolFieldsAreRefused, testToolChoiceIsTranslated, testToolUseBecomesFunctionCall, testTextAroundToolUseIsSplit, testToolResultBecomesFunctionCallOutput, testNonTextBlockInToolResultIsRefused, testToolResultsPrecedeMidConversationTextAndUserMessage, testToolBlocksInTheWrongTurnAreRefused. These replace testToolsAreRefused and testNonTextContentIsRefused.
- `ResponsesToMessagesStreamTest`: testFunctionCallOpensToolUseBlockAfterClosingText, testArgumentsFromDoneEventWhenNoDeltaCame, testArgumentsFromOutputItemDoneWhenNothingElseCarriedThem, testDeltaForCallWhoseBlockIsNotOpenIsDropped, testCutOffToolCallStopsOnMaxTokens, testAliasedNameIsRestored.
- `BuiltinTranslatorApiTest`: testToolHistoryIsTranslated, testClaudeCodeToolRequestIsTranslated, testLongToolNameIsAliasedAndRestored, testOutOfScopeToolContentIsRefused (replaces testToolsAreRefused); testMalformedConversationIsRefused extended with "Tool choice as a string".
- `TranslationFixtureParityTest`: stream fixtures seed ToolNames from input `tool_names` and assert each recorded alias. 36 request and 6 stream fixtures left the known-gaps list.

Design drift (recorded in design.md, "## Implementation notes"):
1. Design cites `MessagesToResponses.java:texts`. The method was `turnTexts`; this change replaces it with `addUserTurn`/`addAssistantTurn`, since user and assistant turns order their items differently.
2. Spec deltas needed no rebase. builtin-translator-fixture-parity is archived; a block diff of the five MODIFIED blocks against openspec/specs shows only this change's edits. The REMOVED "Tools are refused" exists. The reasoning change already has the tool_result clause and the "Image inside a tool result" scenario, so task 4.2 edited nothing there.
3. Known-gaps owners: design expected 12 of the 36 request fixtures under builtin-translator-reasoning; only 6ef7fe30f79cfccb was, because the archived owner rule counts `output_config` as reasoning only when it holds effort. All 36 were removed; the fixture test showed exactly these 36 request and 6 stream fixtures start passing.
4. `ToolNames.alias` always takes the taken set; the harness passes `Set.of()`. No single-argument overload. `alias`, `restore` and `applyResponses` are package-private.
5. The server's `ResponsesToMessagesReadStreamTest` builds the stream too and now passes `new ToolNames()`.
6. Deliberate differences from the reference: a non-object entry inside tool_result content is refused (Python skips it); an untranslated `tool_choice` type is dropped with a warning log; only a string `arguments` value is filled in from the `*.done` events.
7. Known edge, not handled: a client tool sent with type "function" is forwarded as the client's own node, and aliasing renames it in place, so the stored client body would show the alias.

## Verification

Environment: JAVA_HOME = JDK 21.0.8 (Eclipse Adoptium), Git Bash at the repo root.

Implementer checks:
- `./gradlew checkstyleMain checkstyleTest`: passed.
- `./gradlew :translator:test`: passed. MessagesToResponsesTest 25/25, ResponsesToMessagesStreamTest 14/14, ResponsesToMessagesTest 5/5, ToolNamesTest 7/7, TranslationFixtureParityTest 289 run, 0 failed, 168 skipped (out of scope or known gaps).
- `./gradlew :server:test` for BuiltinTranslatorApiTest and ResponsesToMessagesReadStreamTest: passed (16/16, 4/4).
- Fail-before: the new translator tests did not compile before 1.2 and 2.2; 3 server tests failed before 2.2 and 2 before 3.2.
- OpenSpec: `validate builtin-translator-tools --strict` valid; `builtin-translator-reasoning --strict` valid. `validate --all --strict`: 17 passed, 2 failed (main specs user-limits and resource-subscription, placeholder Purpose sections, not touched by this change).
- `git status --short`: only files of this change.

Verification run 1 (independent): passed, no failures.
- Checkstyle: BUILD SUCCESSFUL, 0 errors in server and translator main/test reports.
- translator: ToolNamesTest 7, MessagesToResponsesTest 25, ResponsesToMessagesStreamTest 14, TranslationFixtureParityTest 289 (168 skipped), ResponsesToMessagesTest 5; 0 failures.
- server: BuiltinTranslatorApiTest 16, ResponsesToMessagesReadStreamTest 4; 0 failures (run with `--rerun`, first invocation was UP-TO-DATE).
- OpenSpec `validate builtin-translator-tools --strict`: valid.
- git status: only the two new untracked files plus the modified files. config and storage unchanged, not run.

Cost-claim check: the code matches design.md "Cost per request" (one Core pass; no extra body copy or parse; no blob call; Redis only through the existing upstream cache, one more Redis batch per 64 breakpoints on a miss; added CPU is one SHA-256 per aliased name, one small serialisation per replayed tool_use, SHA-1 cache-key hashing per tool definition).
- `MessagesController.prepareTranslatedUpstreamRoute` translates `request.getTree()` into a single upstream request; no new parse.
- `toolNames` is created there and read in `processResponse` only on the `builtinTranslator != null` branch, so never null.
- `translateTools` adds non-custom tools by reference; `toolParameters` copies (one `setAll`) only when the schema has `$schema`.
- `ResponsesToMessagesStream.handle(JsonNode)` receives the parsed event; new handlers parse nothing.
- No new Redis, blob or other I/O, no blocking call, no new Future chain.
- Side note: `JsonUtil.sort` (via `CacheKeyBuilder.update`) replaces array elements in place, so objects inside arrays of shared client nodes can get keys reordered when cache keys are built. The JSON value is unchanged; existing behaviour.

Verification run 2 (final): passed, same test classes and results as run 1, same cost-claim result.

## Review findings

| Lens | Severity | File:line | Finding |
|------|----------|-----------|---------|
| conformance | low | translator/src/main/java/com/epam/aidial/core/translator/ToolNames.java:91 | Spec says an alias SHALL differ from every name in the request that needs no alias, but `taken` holds only function tool names, a function `tool_choice` and `function_call` items. A forwarded non-function tool named `a_ca978112` next to function tool `a` yields two tools named `a_ca978112`. The Python reference behaves the same, so the spec overclaims. Fix: narrow the spec to function names, or add non-function tool names to `taken` with a test. |
| simplicity | low | translator/src/main/java/com/epam/aidial/core/translator/ResponsesToMessagesStream.java:44 | `streamedArguments` HashSet does the job of one boolean: every access is behind `key.equals(openKey)`. Fix: `openArgumentsSent` boolean reset in `openBlock`. |
| simplicity | low | server/src/test/java/com/epam/aidial/core/server/BuiltinTranslatorApiTest.java:516 | New `eventData` helper repeats the `data: ` parsing loop of `deltaText` (line 493). Fix: implement `deltaText` on top of `eventData`. |

## Fix round 1

No fix round ran. All three findings are low severity and were left as is.

## Outcome

- High/medium findings open: none.
- Low findings: all 3 fixed after the review, before archiving. The collision requirement now names the function names `taken` holds, `streamedArguments` is the boolean `openArgumentsSent` reset in `openBlock`, and `deltaText` reuses `eventData`. Checkstyle, `:translator:test`, `BuiltinTranslatorApiTest` and `ResponsesToMessagesReadStreamTest` re-ran green.
- Deferred: task 4.3 (perf measurement, needs the perf environment).
- CI must still confirm: the full test suite across all modules (only checkstyle and the targeted translator and server classes ran locally). `validate --all --strict` still fails on the unrelated user-limits and resource-subscription main specs.
