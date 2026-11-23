# Design

## Context

Core sends most of its own error answers through one respond method of the request context. The checks that run
before a request context exists (HTTP version, method, body size, authentication) and failures nobody caught go
through the proxy's own respond method, which logs nothing. An error answer from an origin is relayed by the
controller of the route. For `/chat/completions`, an answer that is not an event stream is read whole and then sent.
For Messages and Responses, only the answer to a non-streaming request is read whole, and the answer to a streaming
request is piped to the client as it arrives, even when it is a JSON error. Console log lines, analytics rows and
audit logs carry the trace id of the request context. OTel spans and log records carry the current span's trace id,
which is the same id for a client's own request.

## Decisions

### D1. The id is the trace id Core logs the request under, not a new id
Admins already search by `trace_id` in the console log, the analytics row, the audit logs and the
`Responding with error` warning. The suffix uses the request context's trace id, which is the value those records
carry. A pass called back with a per-request key keeps the trace id of the request that started the call tree, as its
log lines do. In the proxy's own respond method, Core uses the request context's trace id when there is a context,
and the current span's trace id when there is none. For a client's own request, both equal `X-DIAL-TRACE-ID`.

**Rejected: a new id per request** - it would have to be written into every record before it could be searched.
**Rejected: the current span's trace id everywhere** - for a per-request-key pass whose caller sent no `traceparent`,
it differs from the trace id in that pass's log lines.

### D2. The label says "DIAL request id", not "trace id"
Users know the phrase "request id", and Codex already prints `request id:`. "DIAL" tells the user and the admin whose
id it is. There may be an id from the provider next to it, in the message or in `X-Request-ID`. The docs say that a
DIAL request id is the `trace_id` in Core's logs.

**Rejected: "trace id"** - users do not know the term, and admins learn the mapping once from the docs.

### D3. The suffix goes at the end of `error.message`, or at the end of a plain-text body
Both clients show the message text, and neither shows anything else. Clients recognize some errors by their wording
(Claude Code reacts to `prompt is too long`), so Core adds text only at the end. It drops a plain-text body's trailing
whitespace first, so that a body ending in a newline does not put the suffix on a line of its own. `display_message`
is left alone: it is the text written for the end user, neither client reads it, and whatever DIAL Chat shows from it
stays as it is.

**Rejected: a top-level `request_id` field** - neither client shows it.
**Rejected: a prefix** - it would break clients that match the start of a message.

### D4. On by default, behind the static setting `tracing.errorRequestId`
The users who need the id cannot change Core's settings, so it has to be on by default. An operator whose clients
compare error text exactly can turn it off. As with `responseTraceHeaders`, the bundled `aidial.settings.json` sets
it to `true` and the code default is `false`, so the test settings, which replace the bundled file, keep today's
answers.

**Rejected: no setting** - a client that breaks on the new text would have no way back.
**Rejected: off by default** - almost no deployment would turn it on, and the user who needs the id cannot.
**Rejected: reuse `responseTraceHeaders`** - headers are invisible to the user and changed text is not, so an
operator may want one without the other.

### D5. Core treats DIAL Chat like any other client
DIAL Chat users also need an id to report an error. The suffix has one fixed format: ` (DIAL request id: `, then 32
hex digits and `)` at the end of the message. So DIAL Chat can strip it if its team wants to.

**Rejected: skip the suffix for requests that carry a DIAL Chat header such as `x-dial-client-channel-id`** - Chat
users would lose the id, and the text would depend on a header any client can send.

### D6. An error answer to a streaming request is read whole when it is not an event stream
Claude Code and Codex always send streaming requests. The suffix needs the end of the message, and the answer needs a
`Content-Length` that matches the new body. When the request id applies, Messages and Responses read an answer of
status 400 or above that is not an event stream the same way they read a non-streaming answer. `/chat/completions`
already reads every answer that is not an event stream. The same answers to non-streaming requests are already read
whole, and error bodies are expected to be short. On this new path only (a streaming request), Responses skips the
attachment collection, because error bodies have no output items. So the new path adds no parse, and no warning for a
body that is not JSON. A non-streaming request keeps today's attachment collection.

**Rejected: only set `X-Request-ID` on piped answers** - Claude Code shows no header.
**Rejected: write the suffix after the piped body** - it would break a JSON body, and `Content-Length` would be
wrong.

### D7. An origin's `X-Request-ID` is kept
The provider's id lets the provider's support find their side of the request, and the DIAL id is in the message
anyway. On the model routes (`/chat/completions`, Messages, Responses), retriable statuses (429, 502, 503, 504) are
never relayed. Core tries the next upstream and then sends its own error, which carries the DIAL id in
`X-Request-ID`. So Codex's 429 line, which shows only the header, always shows the DIAL id. There, the origin's id
reaches the client only on a relayed status such as 400, 403 or 404, and Codex then shows the message as well. MCP
retries only 429, so an MCP 502, 503 or 504 with a JSON body is relayed with the origin's `X-Request-ID`.

**Rejected: overwrite it** - the provider's id would be lost.
**Rejected: send both values** - clients read one value.

### D8. Core leaves alone the bodies it cannot change safely
Core changes only a JSON object with a string `error.message` and plain text. It leaves alone an empty body, another
media type, a body over 64 KiB and a body that still has a `Content-Encoding`. Core's HTTP client already decodes
gzip and deflate and removes the header, so that last check only catches an encoding the client does not decode. Text
appended to bytes that are still encoded would corrupt them. The 64 KiB limit keeps the parse on the event loop
short. Error bodies are expected to be a few KiB.

**Rejected: no size limit** - a large error body would be parsed and written again on the event loop.

### D9. The same id is added once
An error that passes through several Core passes of one request carries the same trace id at each pass: the target
pass of an alias, the inner pass behind an interceptor, an application that calls Core back with its per-request key.
Core skips a body that already contains the suffix with this id. A body with another id gets this pass's id too. That
happens when an application calls Core with its own API key and no `traceparent`, and both ids are then useful.

**Rejected: mark the answer with a header** - an application or an interceptor between two passes may drop headers.

### D10. One helper changes the body, and each path calls it where it builds the body today
The two respond methods and the four relay paths each call one static helper. The helper takes the setting, the
trace id, the status, the response and the body. It sets `X-Request-ID` and returns the body to send. A relay path
calls it after it has copied the origin's headers, so that it sees the origin's `Content-Type`, `Content-Encoding`
and `X-Request-ID`. It then sets `Content-Length` from the returned body as it does today. The respond methods need
no `Content-Length` step: `Content-Length` is a hop-by-hop header that is never copied, and Vert.x sets it when the
answer ends. The analytics row keeps the origin's body, as it does today.

**Rejected: a wrapper of the HTTP response that changes the body when it ends** - the behaviour would be hidden, and
a piped body cannot be changed at its end.

### D11. The proxy's own respond method logs the error answers it gives an id to, at INFO
Most of these answers have no request context: authentication failures and the early checks. For them, the console
log line's `traceId` field is empty, no analytics row is written, and nothing logs them today. Without a log line,
the id of a 401, 405 or 413 could only be found in exported traces. The line is
`Responding with error. Status: <status>. trace_id=<id>. Body: <body>`, in the `trace_id=` form the audit logs use.
Its `traceId` field stays empty, so an admin finds these errors by searching the message text for `trace_id=<id>`,
and the docs say so. When a context exists (a controller's failure that reaches `handleError`), the line's `traceId`
field also carries the id.

**Rejected: WARN, like the request context's line** - every 401 from a scanner without credentials would raise a
warning.
**Rejected: no line** - the id would lead nowhere in a deployment that does not export traces.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| Core's own error body | the message | the message and ` (DIAL request id: <id>)` | Request id in an error message; Other capabilities' error bodies |
| Router 4xx and MCP JSON error answers, which Core passes on through its respond method | as received | the suffix added | Error answers covered |
| `X-Request-ID` on an error answer | only the origin's, when relayed | the trace id, unless the answer has one | X-Request-ID on an error answer |
| Error answer, not an event stream, to a streaming Messages or Responses request | relayed chunked as it arrives | read whole, sent with `Content-Length` | Error answer to a streaming request read whole |
| Relayed error body of `/chat/completions`, Messages, count tokens, Responses create | as the origin sent it | the suffix added | Error answers covered |
| The request context's `Responding with error` warning | logs the body | logs the body with the suffix | none (a log line, not a spec boundary) |
| The proxy's own error answers | no log line | one INFO line with `trace_id=` | none (a log line, D11) |

## Effect per request

| Answer | Today | After | How it is measured |
|---|---|---|---|
| Status below 400, event streams | sent as today | unchanged; one status check | the "Success answer untouched" test checks the bytes |
| Core's own error | one write | a search of the body for the suffix, and a parse and write of a JSON body (at most 64 KiB) | unit tests; no measurement, as this is the error path only |
| Relayed error that is already read whole | one copy of the body | the same, plus the search and the parse | as above |
| Relayed error to a streaming Messages or Responses request | piped | read whole (one body copy), the token usage parse that the non-streaming path does, the search and the parse | as above |

No Redis or blob round trip is added. No answer below 400 is read, copied or parsed more than today.

## Risks / Trade-offs

- [A client compares an error message exactly] → it no longer matches. Mitigation: `tracing.errorRequestId: false`.
  Core's tests run with the code default `false`, so existing tests keep their exact bodies.
- [A JSON error body is written again by Jackson] → whitespace, escape sequences and number formats can change. Member
  order is kept. Clients parse the JSON.
- [An origin sends a large error body that is not an event stream to a streaming request] → Core now holds it in
  memory before answering. The answers to non-streaming requests are already read whole, and error bodies are expected
  to be short.
- [A burst of rejected requests before authentication] → one INFO line each (D11).

## Migration Plan

None. The setting is on by default. An operator who wants today's error text sets `tracing.errorRequestId` to
`false` in `aidial.settings.json` and restarts Core. Rollback is the same step.

## Code map

- `server/src/main/java/com/epam/aidial/core/server/tracing/TracingSettings.java:from`: add the `errorRequestId`
  component, read with `getBoolean("errorRequestId", false)` like `responseTraceHeaders`. The record's constructor
  gains an argument, so `server/src/test/java/com/epam/aidial/core/server/controller/DeploymentPostControllerTest.java`
  (`new TracingSettings(true, false, List.of())`) needs one more argument.
- `server/src/main/resources/aidial.settings.json`: `"errorRequestId": true` in `tracing`.
- New `server/src/main/java/com/epam/aidial/core/server/tracing/ErrorRequestId.java`: the helper of D10.
  `isEnabled(TracingSettings, String traceId)` is true when the setting is on and
  `io.opentelemetry.api.trace.TraceId.isValid` holds; a null `TracingSettings` counts as off.
  `append(TracingSettings, String traceId, int status, HttpServerResponse response, Buffer body)` does nothing below
  400 or for `text/event-stream`. It reuses `JsonUtil.java:tryParse` and `JsonUtil.java:serialize`, and reads
  `HttpHeaders.CONTENT_TYPE` and `HttpHeaders.CONTENT_ENCODING`.
- `server/src/main/java/com/epam/aidial/core/server/ProxyContext.java:respond(int, Buffer)`: call `append` with
  `getTracingSettings()` and `getTraceId()` before `end`. This also covers what reaches it from
  `McpProxyController.java:handleResponse` (an MCP JSON answer, through `respond(responseStatus, result)`) and from
  `RouterDecisionClient.java:parseVerdict` (a router's 4xx answer as an `HttpException`, through
  `ProxyContext.java:respond(HttpException)`).
- `server/src/main/java/com/epam/aidial/core/server/Proxy.java:respond(HttpServerRequest, HttpStatus, String)`: call
  `append` with `tracingSettings` and the trace id that D1 gives (`ContextManager.java:getProxyContext`, else
  `Span.current().getSpanContext().getTraceId()`), and log the D11 line when the id was given. This covers
  `Proxy.java:handleRequest` (the early checks) and `Proxy.java:handleError` (authentication failures, failures
  nobody caught, and `VertxHostDelegate`'s failures). `Proxy.java:putTraceHeaders` shows the validity check that the
  helper repeats.
- `server/src/main/java/com/epam/aidial/core/server/controller/BaseChatCompletionController.java:handleNonStreamingChatCompletionResponse`:
  call `append` on the body that `maybeInjectUsagePerModel` returns, before `Content-Length`. `handleProxyResponse`
  already sends every answer that is not an event stream here.
- `server/src/main/java/com/epam/aidial/core/server/controller/anthropic/MessagesController.java:processResponse`:
  also take the non-streaming path for an answer of status 400 or above whose `Content-Type` is not
  `text/event-stream`, when `ErrorRequestId.isEnabled`. In `handleNonStreamingResponse`, call `append` after
  `putLimitHeaders`, and set `Content-Length` from and send the returned body. `context.setResponseBody` keeps the
  origin's body.
- `server/src/main/java/com/epam/aidial/core/server/controller/anthropic/MessagesCountTokensController.java:forwardResponse`:
  call `append` after `copyResponse`, before `Content-Length`.
- `server/src/main/java/com/epam/aidial/core/server/controller/ResponsesController.java:handleProxyResponse`: the
  same change to the condition as in Messages. In `handleNonStreamingResponse`, call `append` on `rewritten` after
  `putLimitHeaders` and before `Content-Length`, and send that body. Skip `collectResponseAttachments` when the status
  is 400 or above and the request is streaming, which is only the new path (D6).
- `server/src/main/java/com/epam/aidial/core/server/util/ProxyUtil.java:copyResponse`: copies the origin's
  `X-Request-ID`, `Content-Type` and `Content-Encoding` before `append` runs. `Content-Length` is in
  `baseHopByHopHeaders` and is never copied.
- `server/src/main/java/com/epam/aidial/core/server/AiDial.java:start`: the HTTP client options turn on
  `setDecompressionSupported(true)`, which D8 relies on. MCP uses the same client.
- Tests to follow: `ProxyTest` (mocked `Span` and `SpanContext`), `ProxyContextTest`, `TracingSettingsTest`,
  `ResourceBaseTest.additionalSettingsOverrides` (turns the settings on in an `*ApiTest`), `TestWebServer` (origin
  answers), `MessagesApiTest`, `ResponsesApiTest`, `ChatCompletionApiTest`, `DeploymentPostApiTest` and
  `AliasInProcessApiTest`, `RouterDecisionApiTest` (a router's decide answers) and `ToolSetApiTest` (an MCP
  server). `server.compressionSupported` is on in the test settings, so a test that checks `Content-Length` sends
  `Accept-Encoding: identity`. `ResourceBaseTest.send` adds `api-key` when a request has no credentials, so a test of
  an unauthenticated request builds its own request, as `MessagesApiTest.post` does.
- Docs: `docs/tracing.md` (the `tracing` section and its example), `README.md` (the settings table row next to
  `tracing.responseTraceHeaders`), `docs/error_codes.md`, `docs/anthropic-spend-limits.md` and
  `docs/codex-spend-limits.md`.
