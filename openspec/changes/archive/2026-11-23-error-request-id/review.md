# Review

## What shipped

- New static setting `tracing.errorRequestId`: `false` in code, `true` in the bundled `aidial.settings.json`.
- With it on, an error answer (status 400 or above) ends its message with ` (DIAL request id: <trace id>)`. The message is
  `error.message` of a JSON object, or a plain-text body with its trailing whitespace dropped. The answer gets
  `X-Request-ID` unless it already has one, and `Content-Length` matches the new body.
- The helper is `tracing/ErrorRequestId`. It leaves the body unchanged when it is empty, of another media type, still
  encoded, over 64 KiB, JSON without a string `error.message`, or already carrying the same id. It never touches
  answers below 400 or event streams.
- Covered:
  - Core's own errors, through `ProxyContext.respond` and `Proxy.respond`, which also reaches router 4xx answers and MCP
    JSON errors.
  - Relayed origin errors of `/chat/completions`, Messages, count tokens and Responses create, through
    `BaseDeploymentPostController.appendRequestId`.
- On a streaming Messages or Responses request, an error answer that is not an event stream is now read whole
  (`isReadWhole`) and sent with `Content-Length`. Responses skips the attachment collection only on that path.
- `Proxy.respond` logs each answer it gives an id to, at INFO:
  `Responding with error. Status: <status>. trace_id=<id>. Body: <body>`.
- Docs: `docs/tracing.md` (new section "Request id in error answers"), the `README.md` settings row,
  `docs/error_codes.md`, `docs/anthropic-spend-limits.md` and `docs/codex-spend-limits.md`.

## Verification

- `./gradlew checkstyleMain checkstyleTest` passes.
- These test classes pass:
  - `ErrorRequestIdTest`, `TracingSettingsTest` and the rest of `tracing.*`.
  - `ProxyTest`, `ProxyContextTest` and `DeploymentPostControllerTest`.
  - `ErrorRequestIdApiTest`, with one test per spec scenario.
  - `MessagesApiTest`, which covers "Setting off" and a streaming error still piped with the setting off.
  - `ResponsesApiTest`, `ChatCompletionApiTest`, `DeploymentPostApiTest`, `AliasInProcessApiTest`,
    `MessagesSpendLimitApiTest`, `ResponsesSpendLimitApiTest`, `ResponsesControllerTest`, `RouterDecisionApiTest` and
    `ToolSetApiTest`.
- `npx -y @fission-ai/openspec@1.14.3 validate --all --strict` passes.
- Cost check: no Redis or blob round trip is added. An answer below 400 costs one status check and is never read, copied
  or parsed. `Proxy.respond` looks up the trace id only for status 400 or above.
- The new read-whole path for streaming requests keeps the token accounting, the analytics row and the order of
  `finalizeRequest`. `collectTokenUsage` skips a model's non-200 answer.

## Drift from design.md

- The condition that Messages and Responses share is `BaseDeploymentPostController.isReadWhole`, and the four relay
  paths call `BaseDeploymentPostController.appendRequestId` instead of passing the five arguments each time. The Code
  map is corrected.
- `docs/tracing.md` and the "Error answers covered" requirement now also say that a route's configured `response`
  (`BaseRouteController`) gets no id. It is sent directly, not through `respond`.

## Not done

- Deferred: task 3.5 (a running Core with a failing upstream) and task 3.6 (Claude Code end to end).
- Lows left open:
  - A `text/plain` body that holds JSON gets the plain-text suffix, as the spec's media-type rule says.
  - A `text/plain` body with a non-UTF-8 charset is re-encoded as UTF-8.
  - Some unit tests overlap the ApiTests.
  - The `header(...)` helper is copied in four test classes.
- CI must still run the full suite.
- No GitHub issue is linked yet.
- The `deployment-origin` "Connect failure" body drift is a separate plain PR.
