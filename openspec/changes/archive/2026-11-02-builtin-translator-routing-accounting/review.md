# Review: builtin-translator-routing-accounting

## Implementation

- Tasks done: 27. Deferred: none.
- Main code change: `server/src/main/java/com/epam/aidial/core/server/AiDial.java` gets a Lombok-settable `logStore` field (D8). If the field is unset, `start()` creates a `GfLogStore`. `MessagesController`, `ResponsesToMessagesReadStream` and the translator module are unchanged.
- Other files: `ResourceBaseTest.java`, new `log/RecordingLogStore.java`, new `BuiltinTranslatorAccountingApiTest.java`, new `dial-config/builtin-translator-accounting.json`, `MessagesSpendLimitApiTest.java`, `controller/anthropic/MessagesControllerLimitTest.java`, `TranslationFixtureParityTest.java`, deleted `translation-fixtures-known-gaps.txt`, `docs/dynamic-settings/translators.md`, `docs/dynamic-settings/roles.md`, `docs/anthropic-spend-limits.md`, plus this change's `design.md`, `tasks.md` and `specs/anthropic-messages-translation/spec.md`.
- Tests added:
  - `BuiltinTranslatorAccountingApiTest`: `testOneClientViewRowWithCost`, `testUpstreamErrorWritesOneRow`, `testBilledAsItsResponsesPass`, `testCachedTokensBilledAsItsResponsesPass`, `testReasoningTokensBilledAsItsResponsesPass`, `testSecondTurnPinnedToFirstUpstream`, `testMessagesPrefixIsNotCached`, `testRetriableAnswerRetriedOnNextUpstream`, `testClientGoneUpstreamReadToEnd`
  - `MessagesSpendLimitApiTest#testBuiltinTranslatedModelGetsSpendHeaders`
  - `MessagesControllerLimitTest#testVerifyLimit_builtinFailsClosedWhenSpendIsUnreadable`
- Design drift (also recorded in design.md, "## Implementation notes"):
  1. Every scenario passed on its first run against the code at 167bf86b. The only runtime change is the `logStore` field (D8).
  2. Task 6.3 was already done by change 4 (`ResponsesToMessagesReadStreamTest#testFlowControlReachesTheUpstream` and `#testUpstreamFailureEndsWithAnErrorEvent`). It is ticked with a note and nothing was added.
  3. Change 1 had already narrowed the translators.md rule to a translator service, so task 3.4 adds a builtin paragraph with the four D3 differences instead. The run also fixed stale text: the table said a translator service's client call does not read costLimit, but `SpendCapCheck.verify` reads the spend caps on that pass. The matching sentence in roles.md was corrected and now names the builtin translator too.
  4. builtin-translator-non-streaming-errors is archived, so the spec delta now points to the requirement in openspec/specs that holds "Reached spend cap unchanged" instead of naming the change.
  5. Existing behaviour, left unchanged: on a cache miss, `UpstreamRouteProvider` logs "cached upstream doesn't exist any longer in config: id=null, endpoint=null" for every auto-caching model.

## Verification

### First run: passed

- Checkstyle (`checkstyleMain checkstyleTest`, JDK 21.0.8): passed.
- One Gradle invocation with `--rerun`, BUILD SUCCESSFUL in 2m 58s:
  - `BuiltinTranslatorAccountingApiTest` 9/9
  - `MessagesSpendLimitApiTest` 20/20
  - `controller.anthropic.MessagesControllerLimitTest` 8/8
  - `BuiltinTranslatorApiTest` 62/62
  - `DeploymentPostApiTest` 12/12
  - `log.GfLogStoreTest` 50/50
  - `controller.anthropic.ResponsesToMessagesReadStreamTest` 5/5
  - `TranslationFixtureParityTest`: 288 tests, 0 failures, 38 skipped. The skips are the out-of-scope fixture aborts; the known-gap skip path is deleted.
  - `:config:test` and `:storage:test` were not run because those modules are unchanged.
- `openspec validate builtin-translator-routing-accounting --strict`: valid. `validate --all --strict`: 14 of 16 pass. The two failures are spec/resource-subscription and spec/user-limits, both on a placeholder Purpose section; this change does not touch them.
- `git status --short`: three untracked files, each owned by a task (2.1 and 1.1). No commits were made.
- Cost-claim check: design.md "Effect per request" says the change adds no work per request and the `logStore` field is read once at startup. The code matches. The only main-code diff is the field and a null check in `start()`. It adds no blocking call on the event loop, no body parse, no Redis or blob call and no Future chain. The design's comparison with the HTTP translator path (one Core pass, one analytics row, one spend check and charge instead of an outer read plus callback check, charge and aggregated-cost write) is a description of the path, not a new cost. No timing is claimed. The blocking pieces (`RecordingLogStore` CopyOnWriteArrayList append, `Thread.sleep` in `await`) are in test sources and run on the JUnit thread.

### Final run: passed

Same classes and results as the first run. No fix round ran in between.

## Review findings

| Lens | Severity | File:line | Finding |
|------|----------|-----------|---------|
| conformance | low | server/src/test/java/com/epam/aidial/core/server/BuiltinTranslatorAccountingApiTest.java:57 | No test checks that the row's request.body carries the anthropicMessages interface `defaults`, which the "Analytics row of a builtin-translated request" requirement demands. The test config has no defaults. Fix: add e.g. `"defaults": {"temperature": 0.3}` and assert it in `testOneClientViewRowWithCost`. |
| conformance | low | server/src/test/java/com/epam/aidial/core/server/BuiltinTranslatorAccountingApiTest.java:110 | No test can tell tokens counted from the Anthropic usage apart from the upstream `total_tokens`, because every fixture reports total = input + output. Fix: add a case whose upstream omits or misreports `total_tokens`, and assert that the day token record grows by 18. |
| correctness | low | docs/dynamic-settings/translators.md:136 | The costLimit cell and the Rules bullet at line 126 claim spend caps are read on every translator service call. Only the Messages and Responses controllers do this, through `SpendCapCheck.verify`. `ChatCompletionsController.verifyLimit` (:162) returns SUCCESS for a translated interface without reading anything. Fix: limit the wording to `/anthropic/v1/messages` and `/openai/v1/responses`. |
| simplicity | low | server/src/test/java/com/epam/aidial/core/server/BuiltinTranslatorAccountingApiTest.java:268 | Duplicates helpers from `BuiltinTranslatorApiTest`: `sseEvents`, `eventStream`, `eventNames`, `header`, the `origin(...)` builder and the text event JSON. `header` now exists in three classes. Fix: move them to `ResourceBaseTest` (or `TestWebServer`), or put the tests in `BuiltinTranslatorApiTest`. |

## Outcome

- No fix rounds ran.
- High or medium findings open: none.
- Low findings, all four fixed after the review:
  - The accounting config gives the `anthropicMessages` interface `defaults: {"temperature": 0.3}`, and `testOneClientViewRowWithCost` asserts the row's request body carries it.
  - `testTokensCountedFromTheAnthropicUsage`: an upstream usage without `total_tokens` is charged 18 tokens.
  - In `translators.md`, the costLimit cell and the Rules bullet limit the spend-cap read to `/anthropic/v1/messages` and `/openai/v1/responses`. `ChatCompletionsController.verifyLimit` reads nothing for a translated interface.
  - `header`, `sseEvents`, `eventStream` and `eventNames` moved into `ResourceBaseTest`, and the copies in `BuiltinTranslatorApiTest`, `BuiltinTranslatorAccountingApiTest`, `MessagesSpendLimitApiTest` and `ResponsesSpendLimitApiTest` were deleted.
  - Checks after these fixes: checkstyle and `:translator:test` passed. `BuiltinTranslatorAccountingApiTest` 10, `BuiltinTranslatorApiTest` 62, `MessagesSpendLimitApiTest` 20, `ResponsesSpendLimitApiTest` 18, `MessagesApiTest` 22, `ResponsesApiTest` 8, `DeploymentPostApiTest` 12 and `controller.anthropic.*` passed, 0 failures.
- CI must still confirm the full test suite. Only checkstyle and the targeted classes above ran locally.
