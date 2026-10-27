# Review: codex-spend-limit-headers

## What shipped

- `SpendCaps.findSecondary()`: the fullest capped window other than the reported one, with ties going to the longer
  period.
- `controller/CodexLimitResponses`: strips the upstream's `x-codex-*` headers, sets the primary and secondary headers,
  and builds the `usage_limit_reached` 429 and the unreadable-spend 429.
- `ResponsesController`: `verifyLimit` now follows `MessagesController.verifyLimit`. It uses `limitWithSpendCaps`,
  or `readSpendCaps` for a translated deployment. A reached cap is rejected first. An unreadable spend record of a
  cost-limited caller gives `spendUnavailable()`, and any other failure keeps today's 500. The headers are set in
  both the streaming and the non-streaming answer paths, on a 200 only. There is no `isOriginalRequest()` gate, as
  the user decided.
- OpenAPI annotations for the six headers and the 429 on both Responses operations, and the regenerated
  `docs/open_api_core.yaml`.
- Docs: new `docs/codex-spend-limits.md`, and links from `docs/error_codes.md` and `docs/anthropic-spend-limits.md`.

## Drift from design.md

- The secondary selection is named `findSecondary()`. design.md said "for example `secondary()`"; methods are verb
  phrases per CODE_STYLE.
- The merged `docs/open_api_core.yaml` keeps the existing 429 description ("Rate limit exceeded"). The spec merge
  keeps manually maintained response descriptions, and the 429 description added for `/anthropic` in #2166 did not
  reach the YAML either. The new headers did reach it.
- After the PR review, the reached-cap 429 also carries `display_message`. The first version dropped it, and DIAL
  clients that show it would have lost the text.
- `ResponsesControllerTest` mocked `RateLimiter.limit`. Its 8 stubs now mock `limitWithSpendCaps`, and 4 tests also
  stub `response.headers()`, which the header code reads.

## Measurement (task 3.3)

`RateLimiterTest.testLimitWithSpendCaps_ReadsNoMoreRecordsThanLimit` counts `ResourceService.getResource` calls for
one capped request to a model with a token limit and no request limit:

| Path | Record reads |
|---|---|
| `limit()` (Responses before this change) | 2 (token, cost) |
| `limitWithSpendCaps()` (Responses after) | 2 (cost, token) |
| translated deployment before | 0 |
| `readSpendCaps()` (translated deployment after) | 1 (cost), the same as `/anthropic` |

No body copies or parses were added. The headers are set before the body is piped or written.

## Checks run

- `./gradlew checkstyleMain checkstyleTest`: clean.
- Passing test classes:
  - `SpendCapsTest`: 13 tests;
  - `CodexLimitResponsesTest`: 8;
  - `ResponsesSpendLimitApiTest`: 18, one per spec scenario;
  - `ResponsesControllerTest`: 16;
  - `ResponsesApiTest`: 8;
  - `ResponsesInterceptorControllerTest`: 14;
  - `MessagesSpendLimitApiTest`: 17;
  - `PerRequestKeyEventLoopTest`: 5;
  - `RateLimiterTest`: 45.
- `./gradlew :openapi-generator:replaceSpec`: regenerated the spec. `validateOpenApiSpec` could not finish on this
  Windows machine: the `npx @redocly/cli` process crashed with a libuv assertion
  (`!(handle->flags & UV_HANDLE_CLOSING)`). Running redocly directly reports only pre-existing errors on paths this
  change does not touch (`no-path-trailing-slash` ×2, `no-empty-servers` on the skeleton). CI runs the full
  validation.
- Deferred: the manual Codex CLI check (task 3.4) needs a Codex CLI install and a local Core with Redis.

## Spec-to-test map

| Scenario | Test |
|---|---|
| Two capped windows, streaming | `testStreamingTwoCappedWindows` |
| One capped window, non-streaming | `testNonStreamingOneCappedWindow` |
| Unversioned path | `testUnversionedPath` |
| Percent rounded down | `testPercentRoundedDown` |
| State before the request | `testHeadersCarryStateBeforeTheRequest` |
| Uncapped caller | `testUncappedCallerGetsNoHeaders` |
| Upstream error | `testUpstreamErrorHasNoCodexHeaders` |
| Upstream sends its own limits | `testUpstreamCodexHeadersRemovedForUncappedCaller` |
| Upstream headers replaced | `testUpstreamCodexHeadersReplaced` |
| Day cap reached | `testDayCapReachedRejectsBeforeUpstream` |
| Reached spend cap wins over a token limit | `testReachedSpendCapWinsOverTokenLimit` |
| Rolling token limit | `testRollingTokenLimitKeepsTodaysAnswer` |
| Cost-limited caller, spend unreadable | `testUnreadableSpendRejectsCostLimitedCaller` |
| Limit check fails otherwise | `testLimitCheckFailureOtherwiseKeeps500` |
| Application's call to the model | `testApplicationCallToModelGetsHeaders` |
| Application's call over the cap | `testApplicationCallOverCapGetsUsageLimitReached` |
| Translated deployment at 82% | `testTranslatedDeploymentGetsHeaders` |
| Translated deployment over the cap | `testTranslatedDeploymentOverCapRejectsBeforeTranslator` |
