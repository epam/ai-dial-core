# Design

## Context

See proposal.md for the motivation. Citations are to the working tree, which is `development` plus PR #2136, and
assume builtin-translator-fixture-parity, builtin-translator-tools and builtin-translator-reasoning are applied.

What the builtin path does today:

- **Non-streaming.** `MessagesController.java:prepareTranslatedUpstreamRoute` throws a 400 for any request that is not
  streaming, before anything is translated. After builtin-translator-fixture-parity, the translation no longer
  writes `stream`; `prepareTranslatedUpstreamRoute` sets `stream: true` on the translated tree, since only streaming
  requests get that far.
- **Core errors.** They all reach the client through the `respond` overloads of
  `BaseDeploymentPostController.java:respond`, which call `ProxyContext.java:respond`. The sources on this path are:
  - `MessagesBaseController.java:handleRequestError`, for what `setupDeployment`, `verifyLimit` and
    `prepareTranslatedUpstreamRoute` throw: the refusals (`UntranslatableRequestException` as a 400), the
    interceptors 503, consent and per-request-key 403s, the affinity 400 and 409
    (`ResolveEncryptedContentAffinityFn.java:apply`), `Unknown upstream id` 400 (`UpstreamRouteProvider.java:get`);
  - `BaseDeploymentPostController.java:nextUpstream`, for `No route` 502 and `Service is not available` 503 with
    `Retry-After` (`TieredBalancer.java:createUpstreamUnavailableException`); a timeout before the response head is
    a retriable failure (`MessagesBaseController.java:handleProxyResponseError`) and ends here;
  - `BaseDeploymentPostController.java:handleProxyConnectionError`, for a connect failure and for a failed read of a
    non-streaming body (a timeout included): a 502 with a DIAL `ErrorData` JSON body;
  - `MessagesBaseController.java:sendRequest`, for `Upstream is missing required id` 503.

  `ProxyContext.respond(HttpException)` writes the exception's message as the body and adds its headers. So the
  answers above are plain text (`No route`, the refusals), or DIAL `ErrorData` JSON (the affinity 400 and 409, the
  connect failure), or, for the limit answers below, already Anthropic JSON. None of them is the Anthropic shape for
  the other errors.
- **Anthropic-format answers that already exist.** `MessagesController.java:verifyLimit` turns limit rejections into
  `AnthropicLimitResponses` answers: `spendCapReached` and `spendUnavailable` (429 `billing_error`), `limitReached`
  (429 `rate_limit_error`) and `limitCheckFailed` (500 `api_error`). Each is an `HttpException` whose message is the
  JSON body built by `AnthropicLimitResponses.errorBody` and whose headers carry `content-type: application/json`.
  They apply to pass-through and builtin models alike, and they reach the client through `handleRequestError`.
- **Upstream answers.** `MessagesBaseController.java:handleProxyResponse` retries 429, 502, 503, 504 and the
  configured codes (`BaseDeploymentPostController.java:isRetriableError`), so the client never sees an upstream 429.
  Any other non-200 reaches `MessagesController.java:processResponse`, which relays it as it is: chunked through
  `createResponseStream` for a streaming request, through `handleNonStreamingResponse` otherwise. Only a 200 event
  stream is translated (`ResponsesToMessagesReadStream`).
- **Stream failures.** `ResponsesToMessagesStream.java:handle` already ends the stream with an `error` frame for
  `response.failed` and for an `error` event, and `finish` does for a stream with no terminal event. The type comes
  from `ResponsesToMessages.java:errorType`, which knows `invalid_request_error`, `rate_limit_exceeded` and
  `vector_store_timeout` but not the request-fault codes of `errors.py`. An event that carries a top-level `error`
  object and no `type` (a DIAL error chunk) is dropped as unknown. An upstream connection failure after the head
  reaches `ResponsesToMessagesReadStream.exceptionHandler`, fails the pipe and ends in
  `BaseDeploymentPostController.java:handleResponseError`, which resets the client connection. The upstream failure
  also fails `BufferingReadStream.endStreamFuture()` (`handleException` calls `endStream.tryFail`), so
  `handleResponseError` resets the upstream request and skips its `compose` and `onSuccess` steps: no usage is
  collected, nothing is charged and no analytics row is written. Usage and the row are collected there only when the
  client write fails and the upstream ends normally.
- **Billing of a failed answer by a translator service.** Its Responses pass charges any `200` that carries usage:
  `BaseDeploymentPostController.java:collectTokenUsage` skips only a non-200 status, and `TokenUsageParser.parse`
  finds `usage` whatever the Responses `status` is (`ResponsesController.java:handleNonStreamingResponse`, and
  `handleStreamingResponse` for the last `usage` in the raw frames, a `response.failed` one included). The fixture
  `response/269a38895f482a3e.json` (status `failed`) carries usage 12 in, 7 out. The builtin stream today charges a
  `response.failed` nothing: `CollectMessagesTokenUsageFn` reads only `message_start` (zeros) and `message_delta`,
  and the stream emits no `message_delta` before the `error` frame.
- **The non-streaming Responses path** stamps encrypted items in `ResponsesController.java:rewriteResponseId`:
  `EncryptedContentAffinityUtil.wrapOutputArray(output, upstreamId, deploymentName)` when
  `EncryptedContentAffinityUtil.hasConfiguredUpstreams(deployment)`.
- **The Messages non-streaming path** (`MessagesController.java:handleNonStreamingResponse`) copies the status and
  headers, strips upstream `anthropic-ratelimit-*` headers and adds the spend headers (`putLimitHeaders`), sets
  `Content-Length` and `X-UPSTREAM-ATTEMPTS`, charges the usage parsed by `MessagesTokenUsageParser.java:parse`
  (`BaseDeploymentPostController.java:collectTokenUsage`, which charges only a 200) and writes the analytics row
  (`completeProxyResponse`).

Corrections to the brief this change was written from:

- `response.incomplete` is not an error. The fixtures `stream/36539ea4f889555e.json` and
  `stream/f4f5960703e43647.json` end it with `message_delta` (`stop_reason: max_tokens`) and `message_stop`, as
  today's code does.
- An upstream 429 never reaches the client as a 429: Core retries it, and with no upstream left answers its own 503
  `Service is not available` with `Retry-After`. With the status unchanged, that is a 503 `overloaded_error`.
- Core's own errors on this path are plain text, DIAL `ErrorData` JSON (the affinity 400 and 409 and the connect
  failure) or already Anthropic JSON (the `AnthropicLimitResponses` 429 and 500), not one DIAL JSON shape.

## Goals / Non-Goals

**Goals:**
- One mapping from a status to an Anthropic error type and one body builder, used by every error the builtin path
  answers.
- The non-streaming answer reuses the Messages non-streaming path, so its headers, charging and analytics row cannot
  drift from a pass-through answer's.

**Non-Goals:**
- Any change for pass-through Anthropic models, `count_tokens` or the Responses API.
- Answers before the deployment is resolved (415, unreadable body, unknown deployment).

## Decisions

**D1. The non-streaming translation lives in the translator module.** `ResponsesToMessages` gets a
`translateResponse(response, requestedModel, messageId, toolNames, omitThinking)` that returns the message tree, a port
of `from_responses.py:from_responses` and `_convert_output`, reusing `stopReason` and `convertUsage` (already shared
with the stream), the tool-name restore of builtin-translator-tools and the carrier builder of
builtin-translator-reasoning (the Java form of `reasoning.py:encrypted_block`). As `from_responses.py` does, it
throws for a `status` of `failed`, `queued`, `in_progress` or `cancelled` and translates every other object, a
missing or `null` status included (fixture `response/b7e739e19bc3007a.json`). It throws a new
`UpstreamFailureException(status, type, message)`, the Java form of `errors.py:AnthropicHTTPError` for the response
side. The request side keeps `UntranslatableRequestException`, which always means 400 `invalid_request_error`.
Rejected: one exception for both sides, which would make every refusal site name a status it never varies.

**D2. The failure table.** `ResponsesToMessages` gets a `failure(error)` that returns an `UpstreamFailureException`, a
port of `errors.py:anthropic_error_from_openai_error` (which also returns its `AnthropicHTTPError`) with the full
`_UPSTREAM_FAILURES` table, request-fault codes included. `translateResponse` throws it, and the stream reads its type
and message. `errorType` is removed: its one caller, the stream, calls `failure(error)`, so the stream and the
non-streaming answer cannot disagree. Rejected: a separate result record beside the exception, which would hold the
same three fields.

**D3. The non-streaming branch.** `prepareTranslatedUpstreamRoute` drops the refusal and sets `stream` on the
translated tree from `request.isStreaming()`, so a non-streaming request goes upstream with `stream: false`, as the
Python translator's SDK call sends it. In `MessagesController.processResponse`, a builtin request that is not
streaming reads the body whole as today, then:
- `200`: parse it, stamp it as `ResponsesController.rewriteResponseId` does (two lines, no new helper), translate it
  with a DIAL message id (`ResponseIdUtil.createResponseId`, as the stream does), and hand the serialized message to
  `handleNonStreamingResponse`. An `UpstreamFailureException` is answered with its own status, type and message,
  `errorBody(e.type(), e.message())`; `typeForStatus` and `extractMessage` of D5 do not apply to it, so
  `vector_store_timeout` stays 504 `timeout_error`. A body that is not a JSON object becomes a 502 `api_error` with
  the message `The upstream answer is not a Responses object`.
- any other status: the error answer of D5.

A builtin streaming request answered with a status other than 200 takes the same error branch instead of the chunked
relay. A streaming request answered 200 with a body that is not an event stream is relayed as today.

`handleNonStreamingResponse` takes the status to answer, since a `200` may become an error, and the body to charge:
the message for a translated `200`, `{"usage": <convertUsage of the object's usage>}` for a failed or unfinished
object (all zeros when it has none), the answered body otherwise. `putLimitHeaders` keys the spend headers on the
status to answer. After `copyResponse` it sets `Content-Type: application/json`, replacing the upstream's, since
every body it sends on the builtin path is Anthropic JSON; the other upstream headers (`Retry-After` included) stay as
today. It runs `collectTokenUsage` while the client status is still the upstream's, and sets the status to answer
after it, before the body is written. So the charge keys on the upstream's status, as the Responses pass of a
translator service does: a failed or unfinished `200` is charged its usage, an upstream answer other than `200`
nothing. Rejected: keeping a failed answer free, which would bill less than the translator service this path
replaces and break builtin-translator-routing-accounting's rule that a builtin request is billed as its Responses
pass. Rejected: running `ResponsesController`'s non-streaming path (response mapping, id rewrite,
`CollectResponsesApiOutputAttachmentsFn`). The request is sent with `store: false`, so the mapping only rewrites an id
the client never sees, and the streaming builtin path does not collect output attachments either.

**D4. Null-valued fields are emitted.** The Python translator validates its message through the Anthropic SDK's
`Message` model and dumps it with every field, nulls included, and the fixtures record that body. Core writes the same
fields, so a client sees the same body from Core as from the translator service. Rejected: comparing fixtures with
nulls treated as absent, which would let Core's body differ from the service it replaces.

**D5. One error funnel.** `MessagesController` overrides `respond(HttpStatus, String)`, `respond(HttpException)` and
`respond(HttpStatus, Object)` (the `ErrorData` of `handleProxyConnectionError`). With no builtin translator each calls
the base method, so pass-through models are untouched. With one, each builds
`AnthropicLimitResponses.errorBody(typeForStatus(status), extractMessage(text))` (`errorBody` made package-private),
keeps the status and the exception's headers, sets `content-type: application/json`, and calls the base
`respond(HttpException)`. An `HttpException` that already carries `content-type: application/json` (the
`AnthropicLimitResponses` answers) is passed on unchanged. `extractMessage` is a port of
`errors.py:_extract_error_message`: `error.message`, else a string `error`, else `message`, else the text, else
`Error code: <status>`. It serves both funnels, because some Core errors carry a DIAL error body as their message
(`EncryptedContentAffinityUtil.java:conflictingAffinityException` and `upstreamUnavailableException`, the `ErrorData`
of `handleProxyConnectionError`), while others are plain text (`No route`). The upstream branch of D3 builds its body
the same way and answers through `handleNonStreamingResponse`, so it keeps the upstream's headers (`Retry-After`
included) except `Content-Type`, which it sets to `application/json` after `copyResponse`, and writes the analytics
row as a relayed error does today. `typeForStatus` and `extractMessage` serve only Core errors and upstream answers
other than `200`; a failure reported inside a `200` carries its own type and message (D3). Both are
package-private methods of `AnthropicLimitResponses`, beside `errorBody`: that class is already the package's builder
of Anthropic error answers, and `AnthropicLimitResponsesTest` can cover every branch of the status table, which the
HTTP tests reach only in part. Rejected:
- mapping at each throw site: the sources include functions shared with the Responses API and the balancer, and one
  missed site would leak the old shape;
- an exception subclass to mark Anthropic answers: a new type for one check, where the content type already tells;
- wrapping in `ProxyContext.respond`, which every controller shares.

**D6. The builtin translator is resolved before the access checks.** `MessagesBaseController.setupDeployment` resolves
it right after `findDeployment`, so a 403 for the `anthropicMessages` consent is in the Anthropic shape too.
`DeploymentEndpointUtil.resolveBuiltinTranslator` is a config lookup that returns null for anything but a `Model`, so
nothing else changes. Rejected: leaving the order, which gives one model two 403 shapes depending on which
interface's consent failed.

**D7. Stream failures.** In `ResponsesToMessagesStream`:
- an event whose data has a top-level `error` object ends the stream with `failure(error).type()` and the object's
  `message`, else `An error occurred during streaming`. This is what the OpenAI SDK raises, and the Python translator
  maps, for a DIAL error chunk (`streaming.py:translate_stream`). The check runs before the `type` dispatch, as in
  the SDK;
- the private error builder becomes a public `emitError(type, message)`, the Java form of
  `streaming.py:emit_error`, which the fixture test calls for an `emit_error` call;
- a `response.failed` keeps `convertUsage(response.usage)` as `failedUsage()` (null for any other end). The client's
  frames do not change. `MessagesController` keeps the `ResponsesToMessagesStream` it builds, and `handleResponse`
  sets the token usage and the pricing usage node from `failedUsage()` before `collectTokenUsage`, as
  `CollectMessagesTokenUsageFn` does for a `message_delta`. So a failed stream is charged the usage it reports, as
  the translator service's Responses pass charges it (Context, "Billing of a failed answer").

`ResponsesToMessagesReadStream` handles an upstream exception by emitting `emitError("api_error", "The upstream
connection failed before the response completed")` and ending the stream, instead of passing the exception on. The
pipe then ends normally, and `MessagesController.handleResponse` collects the usage received so far (zeros, since
`message_delta` comes only with a terminal event) and writes one analytics row. Today an upstream failure resets
both connections and records nothing (Context, "Stream failures"). A failed write to the client still fails the pipe
and resets the connection. Rejected: resetting the client connection as today. The translator service ends the
stream with an `error` event (`streaming.py:translate_stream`), and an Anthropic client reports that event as the
failure.

**D8. Fixture scope.** builtin-translator-fixture-parity's `TranslationFixtureParityTest` fails every response
fixture and every `emit_error` call, and its owner rule gives both to this change in
`translator/src/test/resources/translation-fixtures-known-gaps.txt`: the 36 in-scope response fixtures and the two
stream fixtures with an `emit_error` call (`stream/b9750ab0ed887b26.json`, `stream/f6f52a87e0be887d.json`). This
change routes a response fixture to `translateResponse` (an `UpstreamFailureException` becomes `{"error": {status,
type, message}}`) and an `emit_error` call to `emitError`, and removes those 38 lines. The other in-scope fixtures
whose output is an error are already compared on all three fields and pass before this change: the request ones
(`request/e8dbc15917afa8f9.json`; the effort ones `47aa290459c3b5ee` and `83fa47a22e338f13` after
builtin-translator-reasoning) and the seven stream ones that end in `error` (`90a9f69669c97320`, `802dc8d0ed2ececc`,
`9d9f6e932bbaa0fa`, `fcca5a604050964a`, `275fb9875b59e5ad`, `e4031d0ec9b5c17f`, and `07c3d813f5c3ee5b`, a truncated
stream whose `finalize` call ends in the `api_error` event). The response fixtures include the six
with the `error` flag (`269a38895f482a3e`, `31fb0437fc740d9e`, `34a18d839b7b1442`, `687e214160fad229`,
`81da2169b8611ecb`, `ff322dd3b51c834d`). The owner rule gives builtin-translator-routing-accounting no fixture, so
after this change the list is empty.

**D9. Where the scenarios are tested.** Scenarios about the wiring (what reaches the upstream, statuses, headers,
charging, the funnels) get integration tests in `BuiltinTranslatorApiTest`, `MessagesApiTest` and
`MessagesSpendLimitApiTest`. Scenarios about the translation itself (blocks, thinking, stop reason, usage, failure
names) are tested in the translator module, `ResponsesToMessagesTest` and `ResponsesToMessagesStreamTest`, where the
fixtures replay too; one integration test per mode proves the translation is wired in. This change owns the reached
spend cap on the builtin path: the scenario "Reached spend cap unchanged",
`MessagesSpendLimitApiTest#testBuiltinTranslatorKeepsTheSpendCapAnswer` and the `claude-builtin` model in
`dial-config/anthropic-spend-limits.json`. builtin-translator-routing-accounting applies after it and points to these
without a condition (its D4 and task 4.1); its "At 82% of a day cap" covers a streamed answer, and "Spend headers" here
a non-streaming one.

**Deliberate differences from the Python translator.**
- Upstream error answers keep the upstream's headers, `Retry-After` included; the Python translator answers with a
  new JSON response and drops them.
- An upstream error body that is JSON without a message gives its raw text as the message; the Python translator
  gives the OpenAI SDK's `Error code: <status> - <Python repr of the body>`. An empty body gives `Error code:
  <status>` in both.
- A connection failure mid-stream gives a fixed message; the Python translator gives the exception's text, which may
  name internal addresses.
- A `200` body that is not a JSON object is a 502; the Python translator's catch-all makes it a 500.
- The message id is a DIAL id, not the upstream's response id, as on the streamed path.

**Deliberate differences from today.** The builtin path's error bodies change as the specs say, with
`Content-Type: application/json`; no status changes.
- Non-retriable upstream errors to a streaming request are now buffered and answered with `Content-Length` instead of
  being relayed chunked.
- An upstream connection that fails mid-stream now ends the client's stream normally with an `error` event, and Core
  writes one analytics row and runs the charge and token-stats calls with the usage received so far (zeros). Today it
  resets both connections and records nothing.
- A stream that ends with `response.failed` is now charged the `usage` it reports; today it is charged zero tokens.

## Cost per request

- **Core passes.** A non-streaming request on a builtin model takes one Core pass, where the translator service takes
  two: one per-request key, one limit check and charge, one token-stats span and one analytics row instead of two of
  each, and no translator hop.
- **Body work on a non-streaming answer.** The upstream body is parsed once into a tree, stamped in place,
  translated and serialized once. The Messages path then parses the serialized message again for its usage
  (`MessagesTokenUsageParser.parse`), as it parses every pass-through answer. That second parse is the one extra
  compared with a pass-through answer; removing it is CPU tuning, which is out of scope.
- **Errors.** No new blob calls. One new set of Redis calls: an upstream connection that fails mid-stream now runs
  `collectTokenUsage` (the limit charge and the token-stats update, with zero usage) and writes one analytics row,
  where today it does neither. A failed or unfinished answer makes the same calls as a successful one. An upstream
  error answer to a streaming request is read whole before it is answered; error bodies are small, and the
  non-streaming path already reads every body whole.
- **Stream.** One more field lookup per event (the top-level `error`).
- **Measured by** `BuiltinTranslatorApiTest`: the non-streaming test asserts that exactly one request reaches the
  upstream and that the limits grow once, by the answer's usage. No CPU effect is claimed, so no perf run is needed.

## Risks / Trade-offs

- [A Core error with a 503 status, such as the interceptors refusal, becomes `overloaded_error`, and the affinity 409
  becomes `api_error`; Anthropic SDKs retry both statuses] → the statuses are unchanged, so a client that retries by
  status retried them before too; the message still names the cause.
- [The `content-type: application/json` test in D5 passes through any future `HttpException` that sets that header]
  → only `AnthropicLimitResponses` sets it on this controller today; a later Anthropic answer built the same way is
  meant to pass through.
- [The null-valued fields of D4 come from the Python SDK version that recorded the fixtures; a newer SDK may add or
  drop one] → `TranslationFixtureParityTest` compares whole trees, so a re-recording shows the difference at once.

## Migration Plan

None. No config or setting changes; the builtin path is new in this series. Rollback is a revert.

## Open Questions

None. The spend-cap overlap with builtin-translator-routing-accounting is settled in D9.

## Spec review

1. fidelity, high: a failed or unfinished `200` was free here but charged by a translator service's Responses pass.
   **Accepted, option 1 (charge).** Checked: `collectTokenUsage` skips only a non-200 status and
   `TokenUsageParser.parse` finds `usage` whatever the `status` is, streamed or not. Charging keeps
   builtin-translator-routing-accounting's billing requirement true as written, so that change needs no exemption.
   The spec moves billing into a MODIFIED copy of change 1's "Limits of a builtin translation charged once" with
   "Failed answer charged" and "Failed stream charged". "Neither answer is charged" and the "does not grow" clause of
   "Failed answer" are gone. D3 now charges `{"usage": ...}` while the client status is still the upstream's. D7 adds
   `failedUsage()` and its charge in `handleResponse`. The Context, the proposal and tasks 2.1, 2.2, 2.4, 4.1 and 4.2
   follow.
2. fidelity, medium: D7 got today's behaviour wrong for an upstream failure mid-stream. **Accepted.** Checked:
   `endStreamFuture()` fails, so `handleResponseError` skips `compose` and `onSuccess`. The Context and D7 now say that
   today nothing is recorded. "Deliberate differences from today" and "Cost per request" list the new row, charge and
   token-stats call.
3. fidelity, medium: the pass-through connect-failure body had `code: "502"`. **Accepted.** `HttpStatus` has no
   `toString`, so the code is `BAD_GATEWAY`. The scenario now gives the real `ErrorData` body in field order, and task
   3.1 names the code.
4. fidelity, medium: an `UpstreamFailureException` went through `typeForStatus`, so it would answer 504 `api_error`.
   **Accepted.** D3 answers it with its own status, type and message. D5 says `typeForStatus` and `extractMessage` serve
   only Core errors and upstream answers other than 200.
5. fidelity, medium: the upstream error branch kept the upstream's `Content-Type`. **Accepted.** Checked:
   `copyResponse` copies `Content-Type`, which is not hop-by-hop. Both error requirements, the non-streaming success
   requirement and the failure requirement now require `Content-Type: application/json`. The Core-error requirement
   now says "other headers" stay as today. "Plain-text error body" sends `text/plain` and asserts `application/json`.
   D3 and D5 set the header after `copyResponse`.
6. fidelity, low: three places said Core's errors are plain text except the connect failure. **Accepted.** The Context,
   "Corrections to the brief" and the proposal now name the three shapes: plain text, DIAL `ErrorData` (the affinity 400
   and 409, the connect failure) and Anthropic JSON (the limit answers).
7. fidelity, low: a missing or unknown `status` was not covered. **Accepted.** The success requirement now reads "not
   `failed`, `queued`, `in_progress` or `cancelled` (a missing or `null` status included)". D1 says the same. See also
   14.
8. fidelity, low: D8 listed six in-scope stream fixtures ending in `error`, but there are seven. **Accepted.** Checked
   `stream/07c3d813f5c3ee5b.json`: its basic features and its `finalize` call end in `api_error`. Added to D8.
9. simplicity, medium: the spend-cap overlap was left as an Open Question. **Accepted in part.** The Open Question is
   gone, and D9 says this change owns "Reached spend cap unchanged", `testBuiltinTranslatorKeepsTheSpendCapAnswer` and
   the model. Rejected: renaming the model to `gpt-builtin-spend`. The evidence is stale: builtin-translator-routing-accounting
   was already revised to use `claude-builtin` and to point to this change's test without a condition (its D4, task 4.1
   and review item 10). Its `gpt-builtin-spend` task is deleted. "Spend headers" here covers a non-streaming answer, and
   its "At 82%" covers a streamed one, so they do not overlap.
10. simplicity, medium: the JSON error body had no stated `Content-Type`. **Accepted.** This is the same fix as 5.
11. simplicity, medium: only a unit test covered the upstream disconnect mid-stream. **Accepted.** Task 2.4 adds
    `BuiltinTranslatorApiTest#testUpstreamDisconnectMidStreamEndsWithErrorEvent`, which uses
    `SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY`. `TestWebServer.map` takes a `MockResponse`.
12. simplicity, low: "Non-streaming answer charged once" restated change 1's requirement. **Accepted.** That requirement
    is dropped. Its scenario is now "Non-streaming answer charged once" under the MODIFIED "Limits of a builtin
    translation charged once". The `Content-Length`, `X-UPSTREAM-ATTEMPTS` and spend-header clause, and the "Spend
    headers" scenario, moved to a new requirement, "Non-streaming answer headers", with the scenario "Answer headers".
    It is separate from "Non-streaming answer translated to a message" because strict validation limits a
    requirement's length.
13. simplicity, low: a new test duplicated change 3's gone-upstream replay. **Accepted.** `testReplayOfAGoneUpstreamIsAnAnthropicError`
    is dropped. Task 3.1 extends change 3's `testCarrierOfAGoneUpstreamIsRefused` (409 `api_error`) and
    `testCarriersFromTwoUpstreamsAreRefused` (400 `invalid_request_error`), and the spec gains the scenario "Carriers
    from two upstreams".
14. simplicity, low: a missing status fell under neither requirement. **Accepted.** This is the same fix as 7, plus the
    scenario "Missing status read as completed" (fixture `response/b7e739e19bc3007a.json`) and the test
    `testMissingStatusIsReadAsCompleted`.
15. simplicity, low: D6 had a test but no scenario. **Accepted.** Added "Consent missing on the Messages interface"
    (`gpt-builtin-messages-consent`, 403 `permission_error`). Task 3.1 names it.
16. simplicity, low: the OpenAPI task missed 504, the in-200 429 and the upstream 404 and 500. **Accepted.** Checked:
    the operation also lacks 409. The task moves to group 4 as 4.3 and adds `409` and `504`. It covers 400, 403, 404,
    409, 500, 502, 503 and 504 with the Anthropic body and extends the 429 description. Group 3 tasks are renumbered.
17. simplicity, low: D1 and D2 had two holders for the same three fields. **Accepted.** `failure(error)` returns the
    `UpstreamFailureException`. `errorType` is removed, with one main caller, and its test is folded into
    `testFailureTable`. Tasks 1.1 to 1.3 follow.
18. simplicity, low: the proposal's Impact line undercounted the changes to `AnthropicLimitResponses`. **Accepted.**
    It now reads "`errorBody` made package-private; `typeForStatus` and `extractMessage` added".

## Implementation notes

Compared with the code as builtin-translator-fixture-parity, builtin-translator-tools and builtin-translator-reasoning
left it (all three archived on 2026-11-01):

- **Spec delta rebased.** The three MODIFIED requirements now start from their text in `openspec/specs` after those
  archives; only the clauses this change alters differ (`stream` as the client sent it, the upstream connection
  failure, the charge of a failed or unfinished answer). The REMOVED requirement still exists there.
- **Test names.** builtin-translator-reasoning named the gone-upstream test `testCarrierOfGoneUpstreamIsRefused`, not
  `testCarrierOfAGoneUpstreamIsRefused`; task 3.1 extends it under its real name.
  `testEveryUpstreamBusyKeepsRetryAfter` and `testConnectFailureIsAnAnthropicError` did not exist and are added.
- **The stream's error builder.** `ResponsesToMessagesStream` already had a private `emitError(JsonNode, frames)` (the
  `response.failed` / `error` handler) beside the private builder `error(type, message, frames)`. The handler is
  renamed `onFailure`, so the builder can become the public `emitError(type, message)` of D7. Like
  `streaming.py:emit_error`, it returns nothing once the stream has ended.
- **`UpstreamFailureException` accessors.** An exception cannot be a record, so D3's `e.type()` and `e.message()` are
  `getType()` and `getMessage()`, beside `getStatus()`.
- **`ResponsesToMessagesReadStream.exceptionHandler`.** The upstream's exception handler is now set in the constructor,
  as its chunk and end handlers are, and `exceptionHandler(...)` keeps no handler: an upstream failure ends the stream
  with an `error` frame and an end, so this stream never fails. The end handler runs at most once, should the upstream
  also report its end after a failure. The existing `testFlowControlAndFailuresReachTheUpstream` asserted the reset
  that D7 removes; it becomes `testFlowControlReachesTheUpstream`, and the failure moves to
  `testUpstreamFailureEndsWithAnErrorEvent`.
- **Pricing of a failed non-streaming answer.** The client's body is the error, which carries no `usage`, so D3 also
  sets the pricing usage node to the `{"usage": ...}` it charges, as `CollectMessagesTokenUsageFn` does on the stream;
  without it a price that reads `$.usage` would see nothing.
- **The span status of an answer that is not the upstream's.** `collectTokenUsage` also writes
  `gen_ai.response.status` from the response status, which D3 leaves at the upstream's `200` for the charge. When the
  status to answer differs from the upstream's (a failed or unfinished `200`, a `200` that is not a Responses object),
  `handleNonStreamingResponse` writes the response attributes again after it sets that status, so the span reads
  `failed` as a translator service's Messages pass would. `MessagesControllerTracingTest` covers it.
- **Arguments that are not a JSON object.** `from_responses.py:_parse_arguments` uses `json.loads`, which rejects
  text after the value; the port reads `arguments` with Jackson's `FAIL_ON_TRAILING_TOKENS` for the same result.
- **`stream` upstream.** D3 reads `request.isStreaming()`; the code sets `stream` from `context.isStreamingRequest()`,
  which `MessagesBaseController.handleRequestBody` sets from the same request just before. They differ only when a
  deployment's `anthropicMessages` defaults set `stream`, and then the upstream answer is read as the client asked:
  an event stream sent to a client that asked for one message would fail as "not a Responses object".
- **`failedUsage()`** is `getFailedUsage()`, a plain getter named as the code style asks.
- **`Content-Length` in the test.** The test client asks for `Accept-Encoding: identity` in
  `testNonStreamingIsTranslatedInOnePass`: with compression Vert.x sends the compressed length, and the client hands
  the test the inflated body.
- **A test name.** Checkstyle refuses `testUpstream400ToAStreamingRequestIsJson` (two capitals in a row); it is
  `testUpstream400ToStreamingRequestIsJson`, in tasks.md too.
- **The DIAL id in the test.** `ResourceBaseTest` stubs the id generator with a counter, so
  `testNonStreamingIsTranslatedInOnePass` checks `dial_gpt-builtin_` and hex digits; the 32 of the scenario come from
  `AiDial`'s default generator, which the stream already uses.
