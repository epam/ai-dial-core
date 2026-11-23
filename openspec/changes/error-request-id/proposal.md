# Proposal

## Why

When Core answers a coding client (Claude Code, Codex CLI) with an error, the user sees no id to give an admin, so the
admin cannot find the request in Core's logs and traces. Core puts its trace id in `X-DIAL-TRACE-ID` and
`traceparent`, but neither client shows those headers. Both clients do show the error message text, and Codex also
shows an `X-Request-ID` header.

What the clients show (a test with a fake upstream: Claude Code 2.1.329, Codex CLI 0.168.0):

| Status | Claude Code | Codex CLI (`codex exec`) |
|---|---|---|
| 400 | the message | the raw body |
| 403 | the message | the message and `X-Request-ID` |
| 404 | its own text | the message and `X-Request-ID` |
| 429 | the message | `X-Request-ID` only |
| 500 | the message | its own text |

Neither client ever shows `X-DIAL-TRACE-ID` or a JSON `request_id` field.

## What Changes

- On an error answer (status 400 or above) of a request with a valid trace id, Core ends the error message with
  ` (DIAL request id: <trace id>)`. The message is `error.message` when the body is a JSON object with a string
  `error.message`, or the whole body when it is plain text, with its trailing whitespace dropped. `Content-Length`
  matches the new body.
- Core adds the suffix at the end and never rewrites the words of the message, because clients recognize some errors
  by their wording (`prompt is too long`). It leaves these bodies unchanged: empty, another media type, encoded with a
  `Content-Encoding` Core did not decode, over 64 KiB, a JSON body with no string `error.message`, and one that
  already carries the suffix with the same id. Answers below 400 and event streams are never changed.
- On the same answers Core sets `X-Request-ID` to the trace id when the answer has none. An `X-Request-ID` the
  origin sent (Azure OpenAI sends one) is kept.
- The id is the trace id Core logs the request under: `trace_id` in the console log, the analytics row and the audit
  logs. For a client's own request it is the value of `X-DIAL-TRACE-ID`. The label says "request id", the phrase
  users know and the one Codex prints. Core now also logs, at INFO, the error answers it sends before a request
  context exists (authentication failures, the early checks), so that their id can be found too.
- Covered answers:
  - Core's own error answers, including the early rejections and authentication failures.
  - A router's 4xx answer that Core relays.
  - An MCP error answer with a JSON body.
  - The error answers Core relays from the origin of `/chat/completions`, Anthropic Messages, Messages count tokens
    and the Responses create routes.
- On a streaming Messages or Responses request, an error answer that is not an event stream is now read whole before
  Core answers, so that the message can be changed. Today it is relayed as it arrives.
- A new static setting `tracing.errorRequestId` turns all of this off. The bundled settings turn it on.
- Docs: `docs/tracing.md`, the `README.md` settings table, `docs/error_codes.md`, `docs/anthropic-spend-limits.md`
  and `docs/codex-spend-limits.md`.

Out of scope:

- Errors inside a `200` event stream (such as Responses `response.failed`), and failures before Core (a gateway's
  502/503/504, Core down). These never reach Core's error path.
- The error answers Core relays from the origin of `/completions`, `/embeddings`, the deployment features
  (`tokenize`, `truncate_prompt`, `rate`, `configuration`), config routes, Responses item operations and interceptor
  passes. They are left out by choice, because coding clients do not read them. They keep today's body and get no
  `X-Request-ID` from that pass. An interceptor that passes on the body of Core's inner error answer passes on its
  suffix too.
- The empty answers of the health check (`GET /health` 500) and of the MCP resource metadata (404/500). Their
  controllers write them directly, and coding clients do not call them.
- The refusals of an in-process call that Core answers to an origin plugin (`Unknown api key`, the call depth limit).
  They go to the plugin, not to a client.
- What the clients themselves hide: Claude Code's 404 and Codex's 500 replace the message.
- DIAL Chat: Core does not detect it. The suffix has a fixed format, so a client that wants to hide it can strip it.

## Capabilities

### New Capabilities

- `request-id`: the id Core gives a client so an admin can find the request. It covers the suffix in an error message,
  the `X-Request-ID` header of an error answer, which answers get them, and the setting. One of its requirements says
  how it applies to the exact error bodies that other capabilities quote.

### Modified Capabilities

None. Where another capability quotes an exact error body, or says an error body is relayed unchanged, the
request-id requirement "Other capabilities' error bodies" applies instead of a delta for each one. About 77
requirement lines quote such bodies.

## Impact

No GitHub issue yet. One is to be opened before this leaves draft, and it will carry the harness study summarised in
Why. This change covers the study's three points: the suffix, `X-Request-ID`, and the "request id" label. Of the
brief's reach, it leaves out the relayed origin errors of the routes listed in Out of scope, errors inside event
streams, and failures before Core.

Touches `Proxy` and `ProxyContext` (Core's own errors), `BaseChatCompletionController`, `MessagesController`,
`MessagesCountTokensController` and `ResponsesController` (relayed errors), `TracingSettings` and the bundled
`aidial.settings.json`, plus one new helper class in the server module. No new endpoint or dependency.

Found while reviewing: `deployment-origin` "Connect failure" quotes the 502 body as
`{"error":{"code":"502",…}}`. Core sends `{"error":{"message":…,"display_message":…,"code":"BAD_GATEWAY"}}`. That
spec fix is a separate plain PR.
