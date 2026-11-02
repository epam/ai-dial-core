# Proposal

## Why

A model whose `anthropicMessages` interface is a builtin translator serves streaming text in one Core pass (#2136),
but it refuses every non-streaming request with a 400, and its errors are not Anthropic errors. Core's own errors on
this path are plain text, or DIAL `ErrorData` JSON (the affinity 400 and 409, a connect failure), or, for the limit
answers only, already Anthropic JSON; and an upstream error answer reaches the
client in the upstream's OpenAI shape. Anthropic clients read `{"type":"error","error":{"type":...,"message":...}}`
to decide whether to retry and what to show, and some of their calls are non-streaming. Until both work, a builtin
translator cannot replace a translator service, which answers non-streaming requests and gives Anthropic errors
today.

## What Changes

- **Non-streaming requests are served.** The translated request goes to the model's `openaiResponses` endpoint with
  `stream: false`. Core stamps the Responses object's encrypted items for upstream affinity, as the non-streaming
  Responses path stamps them, translates it to one Anthropic message as the Python translator's `from_responses.py`
  does (text, refusal, `tool_use`, thinking carriers, `stop_reason`, `usage`), and answers through the existing
  Messages non-streaming path: the usage is charged once, the spend headers are sent, and one analytics row is
  written. The 400 refusal of non-streaming requests is removed.
- **A failed or unfinished answer is an error.** A `200` Responses object whose `status` is `failed`, `queued`,
  `in_progress` or `cancelled` is answered as an Anthropic error with the status `errors.py` gives it (400, 429, 504
  or 502). It is charged the usage the object reports, as the Responses pass of a translator service charges it; a
  stream that ends with `response.failed` is charged the same way. A `200` object with any other status, a missing
  one included, is translated to a message, as `from_responses.py` does.
- **Core's own errors get the Anthropic body.** Once Core has resolved the deployment and found the builtin
  translator, every error it answers (the refusals, limit answers, the affinity 400 and 409, no route, connect
  failure, timeouts) carries the Anthropic body with `Content-Type: application/json`. Status and the other headers,
  `Retry-After` included, stay as they are today.
  The error type follows the status as `errors.py` maps it. The Anthropic-format limit answers Core already gives on
  `/anthropic/v1/messages` stay exactly as they are, and the new body is built by their helper.
- **Upstream error answers get the Anthropic body.** A non-retriable upstream error answer, to a streaming or a
  non-streaming request, keeps the upstream's status and headers, except `Content-Type`, which becomes
  `application/json`, and carries the upstream's error message in the Anthropic body.
- **Stream failures end in one `error` event.** After the stream has started, a `response.failed`, a Responses
  `error` event, a DIAL error chunk (`{"error":{...}}`), a stream that ends without a terminal event, and an upstream
  connection that fails each end the client's stream with one Anthropic `error` event. The type follows the
  upstream's error name as `errors.py` maps it. `response.incomplete` stays a normal end with its `stop_reason`, as
  the recorded fixtures show.
- **Fixture parity.** Every in-scope response fixture and the two stream fixtures with an `emit_error` call pass,
  so every in-scope fixture whose output is an error passes, and the known-gaps list of the fixture test is empty.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `anthropic-messages-translation`: created by builtin-translator-fixture-parity and not yet in `openspec/specs`;
  this delta is written against it as it stands after that change and builtin-translator-tools and
  builtin-translator-reasoning are archived. REMOVED `Non-streaming requests are refused`. MODIFIED `Upstream request
  of a builtin translation` (`stream` as the client sent it) and `Truncated or failed stream ends in an error event`
  (DIAL error chunks, a failed upstream connection, the full failure table) and `Limits of a builtin translation
  charged once` (non-streaming, failed and unfinished answers). ADDED the non-streaming answer, failed answers, the
  failure table, Core errors and upstream errors.

No other capability changes. `deployment-origin` leaves the Messages relay out of its scope, and the
`spend-limit-reporting` requirements are about the Codex headers of the Responses API.

## Out of scope

- Anthropic-shaped errors for pass-through Anthropic models. They keep today's answers; that is a separate topic.
- Answers Core gives before it has resolved the deployment (415, an unreadable body, an unknown deployment): Core does
  not know yet that a builtin translator serves the request.
- `count_tokens`, which keeps its 503 in today's shape, images, documents, web search (and the `server_tool_use`
  blocks and usage counts a non-streaming answer would carry for it), structured output, interceptors on the builtin
  path, the Chat Completions mapping, a four-body debug analytics row, and CPU tuning of the stream. These are a later
  series.
- A streaming request that the upstream answers `200` with a body that is not an event stream: relayed as today.
- Routing and accounting on the builtin path beyond what is above: builtin-translator-routing-accounting.

## Impact

- **Dependencies.** Depends on builtin-translator-fixture-parity, which creates the capability, the fixture test and
  its known-gaps list. Assumes builtin-translator-tools and builtin-translator-reasoning are applied first: the
  non-streaming message restores tool names and builds thinking carriers with their helpers.
  builtin-translator-routing-accounting comes after it. The five ship in one PR.
- **Motivation.** PR https://github.com/epam/ai-dial-core/pull/2136, the first version of the builtin translator,
  refused non-streaming requests and left Core errors and upstream errors outside the Anthropic shape. The reference
  is the Python translator's `responses-translations.md` (sections "Response: Responses API → Anthropic",
  "stop_reason", "usage", "Errors" and the stream rows for `response.failed` and `error`), with `from_responses.py`
  and `errors.py`. This change covers those sections except the web search rows (`url_citation`, `web_search_call`,
  `server_tool_use` usage) and the `DIAL_URL unset` row, which has no counterpart in Core.
- **Code.** `translator/`: `ResponsesToMessages` (the non-streaming translation, the in-`200` failure table),
  `ResponsesToMessagesStream` (DIAL error chunks, an error raised from outside, the usage of a `response.failed`).
  `server/`: `MessagesController` (the non-streaming branch, the upstream error branch, the error funnel, the charge of
  a failed stream), `MessagesBaseController.setupDeployment`, `ResponsesToMessagesReadStream`,
  `AnthropicLimitResponses` (`errorBody` made package-private; `typeForStatus` and `extractMessage` added).
- **Docs.** `docs/dynamic-settings/translators.md` (builtin translators), the `@ApiResponse` descriptions of
  `POST /anthropic/v1/messages` (the generated `docs/open_api_core.yaml` follows in CI).
- **Clients.** A builtin-translated model answers non-streaming requests, and its error bodies change to the Anthropic
  shape. Status codes do not change. The builtin path is new in this series, so no released client depends on the old
  bodies.
