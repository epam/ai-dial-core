# Tasks

## 1. Setting and helper

- [x] 1.1 In `TracingSettingsTest`, test that `errorRequestId` is `false` when `tracing` omits it and `true` when it is
  set. Then add the component to `TracingSettings`, add `"errorRequestId": true` to the bundled
  `server/src/main/resources/aidial.settings.json`, and pass the new argument in `DeploymentPostControllerTest`.
  Verify that the test passes and that the server tests compile.
- [x] 1.2 Write `ErrorRequestIdTest` (unit, server module) before the helper exists. It covers:
  - a JSON `error.message`, with the other members kept;
  - `application/json; charset=utf-8` and `application/problem+json`;
  - plain text with a trailing newline, and `text/plain; charset=utf-8`;
  - no `Content-Type`, with a body that starts with `{` and with one that does not;
  - `text/plain` whose body starts with `{`, which is treated as plain text;
  - unchanged bodies: `text/html`, `Content-Encoding: x-custom`, a body over 64 KiB, and `{"detail":"x"}`;
  - an empty body, which gets only `X-Request-ID`;
  - an existing `X-Request-ID`, which is kept;
  - status 399, and a 400 with `text/event-stream`: both untouched, with no `X-Request-ID`;
  - a body that already carries the suffix with the same id (untouched), and one with another id's suffix (gets this
    id too);
  - the setting off, and a null `TracingSettings`;
  - `testInvalidTraceIdUntouched`, which covers the spec scenario "No trace id" with an all-zeros trace id. It is a
    unit test because the test stack always installs an OpenTelemetry SDK, so an integration test never sees an
    invalid id.
- [x] 1.3 Implement `server/src/main/java/com/epam/aidial/core/server/tracing/ErrorRequestId.java` as design.md D8 to
  D10 say (`isEnabled`, `append`), and verify that `ErrorRequestIdTest` passes.
- [x] 1.4 Document the setting:
  - In `docs/tracing.md` and in the `README.md` settings table, describe `errorRequestId`: what it adds, which answers
    it covers, the bodies it leaves alone, that a DIAL request id is the `trace_id` in Core's logs and analytics rows,
    the fixed suffix format a client can strip, and that an origin's `X-Request-ID` is kept. Say that an error
    answered before authentication (401, 405, 413) is found by searching the log message text for `trace_id=<id>`,
    because the JSON `traceId` field of that line is empty (design.md D11). Add
    `"errorRequestId": true` to the example in `docs/tracing.md`.
  - In `docs/error_codes.md`, note the suffix and `X-Request-ID`.
  - In `docs/anthropic-spend-limits.md` and `docs/codex-spend-limits.md`, say that the quoted error messages end with
    the DIAL request id when the setting is on.
- [x] 1.5 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.tracing.*" --tests "com.epam.aidial.core.server.controller.DeploymentPostControllerTest"`,
  and verify that both pass.

## 2. Core's own errors

- [x] 2.1 In `ProxyContextTest`, test that `respond(400, <a JSON error string with no Content-Type>)` sends the suffix
  in `error.message` with `X-Request-ID`, and that `respond(200, …)` sends the body unchanged. Then call the helper in
  `ProxyContext.respond(int, Buffer)`, and verify that the test passes.
- [x] 2.2 In `ProxyTest`, with mocked span ids as in `testHandle_ResponseTraceHeadersAreReturnedWhenEnabled`, test
  these cases:
  - a too-large body answers `413` with `Request body is too large (DIAL request id: <id>)`;
  - `405` answers an empty body with `X-Request-ID`;
  - `handleError` with an `HttpException` adds the suffix;
  - with a `ProxyContext` in `ContextManager` whose trace id differs from the span's, the suffix uses the context's
    trace id.

  Then call the helper and add the D11 INFO line in `Proxy.respond(HttpServerRequest, HttpStatus, String)`, with the
  trace id that design.md D1 gives, and verify that the test passes.
- [x] 2.3 Add `ErrorRequestIdApiTest`. It extends `ResourceBaseTest` and turns on `tracing.errorRequestId` and
  `tracing.responseTraceHeaders` in `additionalSettingsOverrides`. Every request sends `Accept-Encoding: identity`, and
  every test reads `<trace id>` from the answer's `X-DIAL-TRACE-ID`. Cover these spec scenarios:
  - "Core's own plain-text error" (`testOwnPlainTextError`);
  - "Core's own JSON error" (`testOwnJsonError`, a model whose endpoint is a closed port);
  - "Error before authentication" (`testErrorBeforeAuthentication`). It builds its own request without credentials,
    as `MessagesApiTest.post` does, because `ResourceBaseTest.send` adds `api-key`;
  - "Body size limit" (`testBodySizeLimit`);
  - "Core's own error" (`testOwnErrorRequestIdHeader`).

  In `MessagesApiTest`, which runs with the code default, cover "Setting off" (`testUnknownModelWithoutRequestId`), and
  that an error answer to a streaming request is then still piped as it arrives (`testStreamingErrorPipedWithoutRequestId`).
  Verify that the tests pass.
- [x] 2.4 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.ProxyTest" --tests "com.epam.aidial.core.server.ProxyContextTest" --tests
  "com.epam.aidial.core.server.ErrorRequestIdApiTest" --tests "com.epam.aidial.core.server.MessagesApiTest"`, and
  verify that they pass.

## 3. Relayed errors

- [x] 3.1 In `ErrorRequestIdApiTest`, first cover the `/chat/completions` and embeddings scenarios:
  - "Plain text from the origin" (`testRelayedPlainText`);
  - "Other JSON members kept" (`testRelayedJsonMembersKept`);
  - "HTML error page" (`testHtmlUnchanged`);
  - "JSON without a string error message" (`testJsonWithoutMessageUnchanged`);
  - "Encoding Core did not decode" (`testUndecodedEncodingUnchanged`);
  - "Event-stream error answer" (`testEventStreamErrorUnchanged`);
  - "Origin's own id kept" (`testOriginRequestIdKept`);
  - "Empty error body" (`testEmptyBodyHeaderOnly`);
  - "Embeddings error relayed as today" (`testEmbeddingsErrorUnchanged`);
  - "Error through an alias" (`testAliasErrorOnce`, with the alias set up as in `AliasInProcessApiTest`).

  Then call the helper in `BaseChatCompletionController.handleNonStreamingChatCompletionResponse`, and verify that the
  tests pass.
- [x] 3.2 In `ErrorRequestIdApiTest`, cover these scenarios:
  - "Error answer to a streaming Messages request" (`testStreamingMessagesError`);
  - "Success answer untouched" (`testSuccessUntouched`);
  - "Error answer of count tokens" (`testCountTokensError`).

  Then change `MessagesController.processResponse`, `MessagesController.handleNonStreamingResponse` and
  `MessagesCountTokensController.forwardResponse` as the Code map says, and verify that the tests pass.
- [x] 3.3 In `ErrorRequestIdApiTest`, cover "Error answer to a non-streaming Responses request"
  (`testResponsesNonStreamingError`) and "Error answer to a streaming Responses request"
  (`testResponsesStreamingError`). Then change `ResponsesController.handleProxyResponse` and
  `ResponsesController.handleNonStreamingResponse`, including skipping the attachment collection for an error answer
  to a streaming request (design.md D6). Verify that the tests pass.
- [x] 3.4 In `ErrorRequestIdApiTest`, cover "Router's 4xx answer" (`testRouterErrorAnswer`, a decision-mode router
  set up as in `RouterDecisionApiTest`) and "MCP error answer" (`testMcpErrorAnswer`, a toolset MCP server set up as
  in `ToolSetApiTest`). Both reach the helper through `ProxyContext.respond`, which group 2 changed, so they need no
  new code. Verify that the tests pass.
- [ ] 3.5 (deferred: needs a running Core with an upstream that can be made to fail) Call `POST /anthropic/v1/messages`
  and `POST /openai/v1/responses` with a model that does not exist, and with a request the upstream rejects. Check the
  suffix and `X-Request-ID`, and check that 2xx answers and event streams are byte for byte what they were before this
  change.
- [ ] 3.6 (deferred: needs Claude Code pointed at that Core with `ANTHROPIC_BASE_URL`) Trigger a 400, 403 and 429, and
  check that `(DIAL request id: …)` shows in Claude Code's error line.
- [x] 3.7 Run `npx -y @fission-ai/openspec@1.14.3 validate --all --strict` and verify that it passes.
- [x] 3.8 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.ErrorRequestIdApiTest" --tests "com.epam.aidial.core.server.MessagesApiTest" --tests
  "com.epam.aidial.core.server.ResponsesApiTest" --tests "com.epam.aidial.core.server.ChatCompletionApiTest" --tests
  "com.epam.aidial.core.server.DeploymentPostApiTest" --tests "com.epam.aidial.core.server.AliasInProcessApiTest"
  --tests "com.epam.aidial.core.server.MessagesSpendLimitApiTest" --tests
  "com.epam.aidial.core.server.ResponsesSpendLimitApiTest" --tests
  "com.epam.aidial.core.server.controller.ResponsesControllerTest" --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest" --tests "com.epam.aidial.core.server.ToolSetApiTest"`, and verify
  that they pass. Their exact error
  bodies stay as they are, because the code default is off.

## Workflow follow-up

- Open a draft PR with `proposal.md` and the spec delta before applying, as CLAUDE.md asks for a change that is not
  small.
- After apply: write `review.md`, then run `npx -y @fission-ai/openspec@1.14.3 archive error-request-id -y` and
  `validate --specs --strict`, then make one commit that stages explicit paths.
