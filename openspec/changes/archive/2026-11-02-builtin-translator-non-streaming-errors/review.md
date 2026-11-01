# Review: builtin-translator-non-streaming-errors

## Implementation

- Tasks done: 21. Deferred: 5.2, the full suite in CI on the series PR. It needs CI and is not run locally.
- New files: `translator/.../UpstreamFailureException.java` (task 1.2) and `server/.../controller/anthropic/MessagesControllerTracingTest.java` (task 4.2, added in fix round 1).
- Changed: `ResponsesToMessages`, `ResponsesToMessagesStream`, `AnthropicLimitResponses`, `MessagesBaseController`, `MessagesController` and `ResponsesToMessagesReadStream`, their tests, `aidial.config.json` and `dial-config/anthropic-spend-limits.json` (test resources), `docs/dynamic-settings/translators.md`, and this change's `design.md`, spec delta and `tasks.md`.

Tests added:
- `ResponsesToMessagesTest`: `testFailureTable` (replaces `testErrorTypeReadsTypeBeforeCode`), plus 16 new cases: text and function calls, aliased tool names, arguments that are not an object, refusal, empty output, encrypted, unencrypted and omitted reasoning, model fallback, null fields, missing status, a cut-off function call, usage, failed without an error object, unfinished statuses.
- `ResponsesToMessagesStreamTest`: `testDialErrorChunkEndsTheStream`, `testDialErrorChunkWithoutMessage`, `testIncompleteIsNotAnError`, `testEmitErrorEndsTheStream`, `testFailedKeepsItsUsage`.
- `TranslationFixtureParityTest`: now replays the 36 response fixtures and the 2 `emit_error` stream fixtures. The known-gaps list is empty.
- `ResponsesToMessagesReadStreamTest`: `testUpstreamFailureEndsWithAnErrorEvent` and `testFlowControlReachesTheUpstream`, split from `testFlowControlAndFailuresReachTheUpstream`.
- `AnthropicLimitResponsesTest`: `testTypeForStatus`, `testExtractMessage`.
- `BuiltinTranslatorApiTest`: 13 new cases covering mid-stream failure and disconnect, connect failure, every upstream busy, Messages consent, non-streaming in one pass (replaces `testNonStreamingIsRefused`), a request with no `stream` field, reasoning affinity on non-streaming, a failed non-streaming answer, a non-JSON answer, upstream 500, upstream 400 to a streaming request, and a plain-text error body. 8 existing refusal and consent tests were extended.
- `MessagesSpendLimitApiTest`: `testBuiltinTranslatorKeepsTheSpendCapAnswer`, `testBuiltinNonStreamingAtEightyTwoPercent`, with a shared `assertSpendCapReached`.
- `MessagesApiTest`: `testPassThroughConnectFailureKeepsItsBody`, `testPassThroughUpstreamErrorIsRelayedUnchanged`.
- `MessagesControllerTracingTest`: `testFailedTwoHundredIsTracedAsFailed` (fix round 1).

Design drift (all logged under "## Implementation notes" in design.md):
1. The three MODIFIED requirements in the spec delta were rebased onto the current `openspec/specs` text, which changed when the fixture-parity, tools and reasoning changes were archived. The REMOVED requirement still exists in the specs.
2. The gone-upstream test is named `testCarrierOfGoneUpstreamIsRefused`. `testEveryUpstreamBusyKeepsRetryAfter` and `testConnectFailureIsAnAnthropicError` did not exist yet, so they were added.
3. The private `emitError(JsonNode)` was renamed `onFailure` so the public `emitError(type, message)` could take the name. As in the Python `streaming.py`, it returns nothing once the stream has ended.
4. `UpstreamFailureException` uses `getStatus`/`getType`/`getMessage`, because an exception cannot be a record. `failedUsage()` is named `getFailedUsage()`.
5. `ResponsesToMessagesReadStream` sets the upstream exception handler in its constructor, and `exceptionHandler(...)` keeps no handler. The end and failure handlers each run at most once, so the SSE parser is not released twice.
6. A failed non-streaming answer also sets the pricing usage node to the `{"usage":...}` it charges.
7. The arguments parser uses `FAIL_ON_TRAILING_TOKENS`, to match Python `json.loads`.
8. The upstream `stream` comes from `context.isStreamingRequest()`, not `request.isStreaming()`. The two differ only when an `anthropicMessages` default sets `stream`.
9. Checkstyle rejected `testUpstream400ToAStreamingRequestIsJson`, so it is `testUpstream400ToStreamingRequestIsJson`, in tasks.md too.
10. Test-harness changes: the DIAL id is checked as `dial_gpt-builtin_` followed by hex digits, because `ResourceBaseTest` stubs the id generator with a counter. The Content-Length check sends `Accept-Encoding: identity`, because with compression on the header carries the compressed length.
11. Fix round 1: `handleNonStreamingResponse` writes the span response attributes again when the answered status differs from the upstream's (task 4.2).

## Verification

### Implementer checks
- `checkstyleMain checkstyleTest` (JDK 21): passed.
- `:translator:test`: passed, 376 tests. `TranslationFixtureParityTest`: 287 fixtures, 249 in scope all pass, 38 skipped as out of scope, none skipped as a known gap.
- `:server:test`: `ResponsesToMessagesReadStreamTest` 5/5, `AnthropicLimitResponsesTest` 24/24, `MessagesControllerLimitTest` 7/7, `MessagesApiTest` 22/22, `MessagesSpendLimitApiTest` 19/19, `BuiltinTranslatorApiTest` 61/61. Two combined runs failed with "Failed to start Redis service" (16 and 14 tests) while another session's Gradle run in the `ai-dial-core-plugin` worktree held embedded Redis port 16370. Each class passed once the port was free. No code was changed for this.
- `openspec validate builtin-translator-non-streaming-errors --strict`: valid.

### First verification: passed
- checkstyle: passed.
- `:translator:test`: `ResponsesToMessagesTest` 21, `ResponsesToMessagesStreamTest` 20, `TranslationFixtureParityTest` 289 (38 skipped, all out of scope), 0 failures.
- `:server:test`: `BuiltinTranslatorApiTest` 61, `MessagesApiTest` 22, `MessagesSpendLimitApiTest` 19, `AnthropicLimitResponsesTest` 24, `ResponsesToMessagesReadStreamTest` 5, `MessagesControllerLimitTest` 7, 0 failures. The first attempt had 48 failures, all "Failed to start Redis service" at `ResourceBaseTest.init`, from the same port conflict. None was an assertion failure. The rerun passed.
- `:config` and `:storage` were not run (not touched).
- openspec validate: valid. git status: no stray files.
- Cost-claim check: the code matches design.md "Cost per request". The non-streaming builtin path does one parse, an in-place stamp, one translation and one serialization, plus the documented usage parse in `collectTokenUsage`. Uncounted small copies: `body.getBytes()`, `Buffer.buffer(String)`, and the small `{"usage":...}` body on the failure path. Pass-through models are unchanged. No blocking calls. No new Redis call per request; the only new `collectTokenUsage` plus analytics row is on a mid-stream upstream disconnect, as documented. Every new Future chain has a failure handler. The stream adds one `error` lookup per event.

### Fix round 1 verification: passed
- checkstyle (JDK 21.0.8): passed.
- `:translator:test --rerun`: `MessagesToResponsesTest` 27, `ReasoningCarrierTest` 12, `ResponsesToMessagesStreamTest` 20, `ResponsesToMessagesTest` 21, `ToolNamesTest` 7, `TranslationFixtureParityTest` 289 (38 skipped, out of scope), 0 failures.
- `:server:test`: `BuiltinTranslatorApiTest` 61, `MessagesApiTest` 22, `MessagesSpendLimitApiTest` 19, `AnthropicLimitResponsesTest` 24, `ResponsesToMessagesReadStreamTest` 5, `MessagesControllerTracingTest` 1, `MessagesControllerLimitTest` 7, `MessagesCacheWiringTest` 2, 0 failures.
- openspec validate: valid. git status: only the two task-owned untracked files.
- Cost-claim check: matches all four claims (Core passes, body work, errors, stream). The new `setResponseAttributes` call is guarded by `enrich()`. The `respond(HttpStatus, String)` override returns `Future.succeededFuture()`. Its only caller that uses the result (`MessagesBaseController:94`, the 415) runs before `builtinTranslator` is set, so it has no effect.
- Note: `docs/dynamic-settings/translators.md` still says `translation-fixtures-known-gaps.txt` "lists the fixtures it does not match yet", but the list is now empty. The wording needs tidying at archive time.

### Final verification: passed
Same runs and results as the fix round 1 verification.

## Review findings

| Lens | Severity | File:line | Finding |
|------|----------|-----------|---------|
| correctness | medium | server/.../anthropic/MessagesController.java:429 | Non-streaming builtin path: `collectTokenUsage` traces `gen_ai.response.status` from the upstream's 200 and nothing writes it again, so a failed, unfinished or non-Responses 200 is traced as `completed` while the client gets a 4xx or 5xx. |
| correctness | low | server/.../anthropic/MessagesController.java:444 | `getFailedUsage()` is applied only in `handleResponse`. On a client disconnect, `handleResponseError` charges `context.getTokenUsage()`, so a stream ending in `response.failed` is charged zero. Suggested fix: move the override into `parseTokenUsage`. |
| conformance | low | server/.../BuiltinTranslatorApiTest.java:815 | No HTTP-level test proves that a failure inside a 200 keeps its own status and type. `server_error` gives 502 `api_error`, which a fixed 502 or `typeForStatus` would also give. A `vector_store_timeout` case (504 `timeout_error`) would tell them apart. |
| conformance | low | docs/dynamic-settings/translators.md:103 | The errors bullet lists only 400/403/404/429/503. It leaves out 401 `authentication_error`, 413 `request_too_large`, 422 `invalid_request_error` and 529 `overloaded_error`. |
| simplicity | low | server/.../anthropic/MessagesController.java:386 | `errorBody(typeForStatus(status), extractMessage(text, status))` is built in two places (lines 186 and 386). D5 wants one builder. |
| simplicity | low | translator/.../ResponsesToMessagesStream.java:130 | The `event.has("error") ? ... : event` arm in `case "error"` is dead after the top-level error check. With `"error":null` it ignores the event's flat code and message. |
| simplicity | low | server/.../BuiltinTranslatorApiTest.java:969 | The private `header(Response, String)` is the third copy of a case-insensitive header lookup (also in `MessagesSpendLimitApiTest:483` and `ResponsesSpendLimitApiTest:442`). |

## Fix round 1

- Fixed: correctness/medium `MessagesController.java:429`. After `response.setStatusCode(status)`, `handleNonStreamingResponse` now calls `GenAiTraceAttributes.setResponseAttributes(context, interfaceType(), null)` again when `status != proxyResponse.statusCode()`. The charge still keys on the upstream's 200, as D3 requires. Added `MessagesControllerTracingTest.testFailedTwoHundredIsTracedAsFailed`: an upstream 200 with status failed and `rate_limit_exceeded` is charged once, answered 429 and traced as `failed`. The test fails with the fix disabled. Updated tasks.md 4.2 and the 4.5 run list, and design.md "## Implementation notes".
- Rejected: none.
- Still open: none at high or medium. The six low findings were not taken up in this round.

## Outcome

- High or medium findings still open: none.
- Low findings, after the review:
  - Fixed: the failed-stream usage override moved from `handleResponse` into `MessagesController.parseTokenUsage`, which `handleResponseError` reaches too, so a client that disconnects is charged it.
  - Fixed: `BuiltinTranslatorApiTest.testFailureInsideTwoHundredKeepsItsStatusAndType` (`vector_store_timeout` gives 504 `timeout_error`).
  - Fixed: `translators.md` lists the full status table.
  - Fixed: one `AnthropicLimitResponses.errorBody(int status, String text)` for both funnels.
  - Fixed: `case "error" -> onFailure(event, frames)`, as Python's `data.get("error") or data`. `ResponsesToMessagesStreamTest.testErrorEventWithNullErrorReadsItsFlatFields` covers it.
  - Left: the third copy of the header helper. Making `ResourceBaseTest.toResponse` case-insensitive touches every integration test, which is more than a tidy-up warrants here.
  - Checks after these fixes: checkstyle and `:translator:test` passed; `BuiltinTranslatorApiTest` 62, `MessagesApiTest` 22, `MessagesSpendLimitApiTest` 19 and `controller.anthropic.*` passed, 0 failures.
  - The known-gaps wording in `translators.md` is task 7 of `builtin-translator-routing-accounting`.
- CI must still confirm: the full suite on the series PR (task 5.2), including modules and classes not run locally (`:config`, `:storage`, and server classes outside the touched set). It also runs the integration classes without the local embedded-Redis port conflict.
