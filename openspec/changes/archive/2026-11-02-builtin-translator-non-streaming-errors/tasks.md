# Tasks

Before coding, compare design.md with the code as builtin-translator-fixture-parity, builtin-translator-tools and
builtin-translator-reasoning left it (the fixture test's name and known-gaps list, the tool-name restore, the carrier
builder), and record any drift in design.md.

## 1. Non-streaming translation in the translator module

- [x] 1.1 Write the tests first in `ResponsesToMessagesTest`:
  - `testFailureTable` for the scenarios "Failure the request caused", "Request-fault code", "Rate limit inside a
    200" and "Vector store timeout", plus an unknown name giving 502 `api_error` and `type` read before `code`
    (the assertions of today's `testErrorTypeReadsTypeBeforeCode` move here, and that test is removed);
  - `testTextAndFunctionCall`, `testAliasedToolNameIsRestored`, `testArgumentsThatAreNotAnObjectBecomeEmpty`,
    `testRefusalBecomesText`, `testEmptyOutputGetsAnEmptyTextBlock` (requirement "Non-streaming message content");
  - `testEncryptedReasoningWithSummary`, `testEncryptedReasoningWithoutText`,
    `testUnencryptedReasoningGivesUnsignedBlocks`, `testOmittedThinking` ("Non-streaming thinking blocks");
  - `testModelFallsBackToTheRequestedModel`, `testNullValuedFields` ("Non-streaming message identity and fields");
  - `testMissingStatusIsReadAsCompleted` ("Missing status read as completed");
  - `testCutOffFunctionCallIsMaxTokens`, `testTranslatedUsage` ("Non-streaming stop reason and usage"; the content
    filter scenario stays in `testStopReasonPrecedence`);
  - `testFailedWithoutAnErrorObject`, `testUnfinishedStatusesAreErrors` (each of `queued`, `in_progress`,
    `cancelled`), asserting the `UpstreamFailureException` status, type and message.
- [x] 1.2 Add `UpstreamFailureException(status, type, message)` and `ResponsesToMessages.failure(error)`, which
  returns one, with the full `errors.py:_UPSTREAM_FAILURES` table. Remove `errorType`; its one caller,
  `ResponsesToMessagesStream`, calls `failure(error)` (design D2). Verify `testFailureTable` passes.
- [x] 1.3 Add `ResponsesToMessages.translateResponse` (D1, D4), reusing `stopReason`, `convertUsage`, the tool-name
  restore and the carrier builder; it throws for `failed`, `queued`, `in_progress` and `cancelled` only. Verify every
  test of 1.1 passes.
- [x] 1.4 In `TranslationFixtureParityTest`, replay a response fixture through `translateResponse`, an
  `UpstreamFailureException` becoming `{"error": {"status", "type", "message"}}` (D8). Remove the 36 response lines
  from `translator/src/test/resources/translation-fixtures-known-gaps.txt`. Verify the fixture test passes.
- [x] 1.5 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :translator:test`; both pass.

## 2. Stream failures

- [x] 2.1 Write the tests first in `ResponsesToMessagesStreamTest`: `testDialErrorChunkEndsTheStream` and
  `testDialErrorChunkWithoutMessage` (scenarios "DIAL error chunk" and "DIAL error chunk without a message"),
  `testIncompleteIsNotAnError` ("Incomplete answer is not an error"), `testEmitErrorEndsTheStream` (nothing
  follows an `error`), and `testFailedKeepsItsUsage` (a `response.failed` with usage 10 in, 8 out gives the same
  frames as without usage, and `failedUsage()` is its converted usage; `failedUsage()` is null after
  `response.completed`).
- [x] 2.2 In `ResponsesToMessagesStream`, check for a top-level `error` object before the `type` dispatch, make the
  error builder a public `emitError(type, message)`, and keep the converted usage of a `response.failed` as
  `failedUsage()` (D7). Route `TranslationFixtureParityTest`'s `emit_error` calls to
  it and remove the `stream/b9750ab0ed887b26.json` and `stream/f6f52a87e0be887d.json` lines from the known-gaps list.
  Verify 2.1 and the fixture test pass.
- [x] 2.3 Add `ResponsesToMessagesReadStreamTest#testUpstreamFailureEndsWithAnErrorEvent` (scenario "Upstream
  connection fails mid-stream": the mocked upstream fires its exception handler after one event; the reader gets an
  `error` frame with the fixed message, then its end handler, and no exception). Then change
  `ResponsesToMessagesReadStream.exceptionHandler` (D7). Verify the test passes.
- [x] 2.4 Add to `BuiltinTranslatorApiTest`:
  - `testMidStreamFailureBecomesAnErrorEvent`: the scenarios "Failed response" and "Failed stream charged" at the HTTP
    boundary, after a text delta, with no `message_stop` in the body; the `response.failed` carries usage 10 in, 8 out
    and the caller's day token usage of `gpt-builtin` grows by 18;
  - `testUpstreamDisconnectMidStreamEndsWithErrorEvent` ("Upstream connection fails mid-stream"): a chunked SSE answer
    with `setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)`, whose first half (what the mock writes before
    it disconnects) holds `response.created` and a text delta; the client reads a `200` body to its end with no reset,
    the last event is `error` with the fixed message, and there is no `message_stop`.
  Where builtin-translator-fixture-parity left it out, assert the type and message of the final event in
  `testStreamWithoutTerminalEventEndsInError` (scenario "Stream without a terminal event"). Then make
  `MessagesController` keep the `ResponsesToMessagesStream` it builds and, in `handleResponse`, set the token usage
  and the pricing usage node from `failedUsage()` before `collectTokenUsage` (D7). Verify all three tests pass.
- [x] 2.5 Run `./gradlew checkstyleMain checkstyleTest`, `./gradlew :translator:test` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.controller.anthropic.ResponsesToMessagesReadStreamTest"
  --tests "com.epam.aidial.core.server.BuiltinTranslatorApiTest"`; all pass.

## 3. Anthropic body for Core errors

- [x] 3.1 Write the tests first:
  - `AnthropicLimitResponsesTest#testTypeForStatus` (every row of the requirement "Anthropic error type from the
    status") and `testExtractMessage` (a DIAL error body, a string `error`, a top-level `message`, plain text, empty);
  - in `BuiltinTranslatorApiTest`: `testUntranslatableContentIsRefused` asserts the Anthropic body and
    `Content-Type: application/json` ("Refused content block"); `testConnectFailureIsAnAnthropicError` with no server
    on port 4848 ("Connect failure"); builtin-translator-reasoning's `testCarrierOfAGoneUpstreamIsRefused` also
    asserts `api_error` and the "no longer available" message ("Replay of an upstream that is gone"), and its
    `testCarriersFromTwoUpstreamsAreRefused` asserts `invalid_request_error` and the "Conflicting encrypted content
    affinity" message ("Carriers from two upstreams"); `testEveryUpstreamBusyKeepsRetryAfter` ("Every upstream
    busy"); `testConsentOfTheTranslatedInterfaceIsRequired` asserts `permission_error` ("Consent missing");
    `testDeploymentWithInterceptorsIsRefused` asserts `overloaded_error` ("Deployment with interceptors");
    `testConsentOfTheMessagesInterfaceIsAnAnthropicError` for a new test model `gpt-builtin-messages-consent` with
    `consentRequired` on its `anthropicMessages` interface ("Consent missing on the Messages interface", D6);
  - `MessagesSpendLimitApiTest#testBuiltinTranslatorKeepsTheSpendCapAnswer` ("Reached spend cap unchanged"), with a
    `claude-builtin` model added to `dial-config/anthropic-spend-limits.json` (an inline builtin translator, an
    `openaiResponses` base url on port 4848, an entry in the `capped` role); assert the same status, body and
    headers as `testSpendCapReachedRejectsBeforeUpstream`;
  - `MessagesApiTest#testPassThroughConnectFailureKeepsItsBody` ("Pass-through model keeps its error answers": the
    `ErrorData` body with `code: "BAD_GATEWAY"`).
- [x] 3.2 Make `AnthropicLimitResponses.errorBody` package-private and add `typeForStatus` and `extractMessage` there
  (D5). Verify the two `AnthropicLimitResponsesTest` tests pass.
- [x] 3.3 Override the three `respond` overloads in `MessagesController` (D5) and resolve the builtin translator right
  after `findDeployment` in `MessagesBaseController.setupDeployment` (D6). Verify every test of 3.1 passes.
- [x] 3.4 In `docs/dynamic-settings/translators.md`, section "Builtin translators", say that a model served by a
  builtin translator answers errors in the Anthropic shape (`{"type":"error","error":{...}}`) with the status and
  `Retry-After` of today, that answers given before the deployment is known keep their shape, and that pass-through
  models are unchanged. Verify the section reads correctly against the specs.
- [x] 3.5 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.controller.anthropic.AnthropicLimitResponsesTest" --tests
  "com.epam.aidial.core.server.controller.anthropic.MessagesControllerLimitTest" --tests
  "com.epam.aidial.core.server.BuiltinTranslatorApiTest" --tests "com.epam.aidial.core.server.MessagesApiTest" --tests
  "com.epam.aidial.core.server.MessagesSpendLimitApiTest"`; all pass.

## 4. Non-streaming serving and upstream errors

- [x] 4.1 Write the tests first:
  - in `BuiltinTranslatorApiTest`, replace `testNonStreamingIsRefused` with `testNonStreamingIsTranslatedInOnePass`
    (scenarios "Non-streaming text answer", "Answer headers", "DIAL id and upstream model", "Null-valued fields" and
    "Non-streaming answer charged once": the upstream gets `stream: false` and `store: false`, exactly one request reaches it, the
    day token usage grows by 18, `X-UPSTREAM-ATTEMPTS: 1` and `Content-Length` are set). This is the measurement of
    the one-pass claim;
  - `testRequestWithoutStreamFieldIsNonStreaming` (scenario "Non-streaming upstream request": the origin body has
    `stream: false` for `stream: false` and for no `stream` field);
  - `testNonStreamingReasoningIsStampedForAffinity` ("Stamped for
    upstream affinity"), `testFailedNonStreamingAnswerIsAnAnthropicError` ("Failed answer" and "Failed answer
    charged": usage 12 in, 7 out, the day token usage grows by 19), `testNonJsonAnswerIsAnApiError` ("Body that is
    not a Responses object"), `testUpstream500IsAnAnthropicError` ("Upstream 500", usage unchanged),
    `testUpstream400ToStreamingRequestIsJson`, `testPlainTextUpstreamErrorBody` (the upstream sends
    `Content-Type: text/plain`; the client gets `application/json`);
  - `MessagesSpendLimitApiTest#testBuiltinNonStreamingAtEightyTwoPercent` ("Spend headers");
  - `MessagesApiTest#testPassThroughUpstreamErrorIsRelayedUnchanged` ("Pass-through model's upstream error
    relayed").
- [x] 4.2 Remove the non-streaming refusal from `MessagesController.prepareTranslatedUpstreamRoute`, set `stream` on
  the translated tree from `request.isStreaming()`, and add the branch of D3 to `processResponse`:
  `handleNonStreamingResponse` takes the status to answer and the body to charge, keys `putLimitHeaders` on that
  status, sets `Content-Type: application/json` after `copyResponse`, runs `collectTokenUsage` before it sets the
  status to answer, and answers an `UpstreamFailureException` with its own status, type and message. When the status
  to answer is not the upstream's, write the GenAI response attributes again after setting it, so the span records the
  client's status (`MessagesControllerTracingTest#testFailedTwoHundredIsTracedAsFailed`). Verify every test of 4.1
  passes, and that `testStreamingIsTranslatedInOnePass` still passes.
- [x] 4.3 Update the `@ApiResponse` list of `POST /anthropic/v1/messages` in `MessagesController`: add `409` and
  `504`; for 400, 403, 404, 409, 500, 502, 503 and 504 say that a model served by a builtin translator answers with
  the Anthropic error body (`AnthropicErrorData`); extend the 429 description with the `rate_limit_error` of a
  failure the upstream reports inside a `200`. Do not edit `docs/open_api_core.yaml`; CI regenerates it. Verify
  `./gradlew :server:compileJava` passes.
- [x] 4.4 In `docs/dynamic-settings/translators.md`, change the scope bullet of `anthropicMessagesToOpenaiResponses`:
  non-streaming requests are translated; failed or unfinished answers are errors, charged the usage they report.
  Verify the bullet no longer says "streaming only" or lists non-streaming requests among the refusals.
- [x] 4.5 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.BuiltinTranslatorApiTest" --tests "com.epam.aidial.core.server.MessagesApiTest" --tests
  "com.epam.aidial.core.server.MessagesSpendLimitApiTest" --tests
  "com.epam.aidial.core.server.controller.anthropic.MessagesControllerTracingTest"`; all pass.

## 5. Integration checks

- [x] 5.1 Confirm `translation-fixtures-known-gaps.txt` holds no fixture line (the owner rule gives
  builtin-translator-routing-accounting none), and that `TranslationFixtureParityTest` runs all 249 in-scope fixtures
  with none skipped as a known gap. Run `./gradlew :translator:test`; it passes.
- [ ] 5.2 Full suite in CI on the series PR (deferred: needs CI; the full suite is not run locally).

## Workflow follow-up

- Write review.md and archive after builtin-translator-reasoning and before builtin-translator-routing-accounting.
