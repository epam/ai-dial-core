# Design

## Context

See proposal.md for the motivation, and the four spec deltas for the behaviour. Source citations are from the
working tree, which carries PR #2136 on top of `development`.

How the builtin path works today:

- Config. `Translator.java:Translator` (constructor) accepts exactly one of `baseUrl` and `builtin`, and the
  `BuiltinTranslator` enum (`anthropicMessagesToOpenaiResponses`, in `anthropicMessages`, out `openaiResponses`)
  makes Jackson reject an unknown value. `ConfigPostProcessor.java:builtinMismatch` rejects a registered or inline
  builtin declared with another `in`/`out` pair. `DeploymentEndpointUtil.java:resolveBuiltinTranslator` returns the
  builtin for a `Model` only. For an application it returns null, and the application's builtin interface then
  resolves to no url and gets a 503.
- Request. `MessagesBaseController.java:setupDeployment` resolves the builtin and checks that the model's `out`
  interface has a url. `verifyAccess` checks consent and per-request-key access on both interfaces. A model with
  interceptors gets a 503. `MessagesController.java:verifyLimit` runs `SpendCapCheck.verify` with
  `BaseDeploymentPostController.java:isSubjectToLimits`, which is true for a builtin, so limits are enforced.
  `MessagesController.java:prepareTranslatedUpstreamRoute` refuses a non-streaming request, then applies the
  Messages defaults (`ApplyDefaultDeploymentSettingsFn(ANTHROPIC_MESSAGES)`). It translates with
  `MessagesToResponses.java:translateRequest` and runs `ResponsesController.java:buildEnhancementFunctions` on the
  result. The client body stays in the context for pricing and the analytics log, and `resolveProxyRequestBody` sends
  the translated body. `MessagesBaseController.java:sendRequest` builds the URI with
  `DeploymentEndpointUtil.java:resolveRequestUri(…, OPENAI_RESPONSES_BASE_PATH)`.
  `BaseDeploymentPostController.java:sendProxyRequest` adds the `openaiResponses` features, and `X-UPSTREAM-*` for a
  model with `upstreams`.
  `MessagesBaseController.java:enrichProxyRequestHeaders` removes `anthropic-version` and `anthropic-beta`. It adds no
  `X-DIAL-DEPLOYMENT-ID` (`BaseDeploymentPostController.java:sendProxyRequest` adds it only for a translator-mode
  type, and `openaiResponses` is pass-through), but `ProxyUtil.java:copyHeaders` copies one the client sent or a
  `defaultHeaders` entry set, as on `/openai/v1/responses`.
- Response. `MessagesController.java:processResponse` wraps a 200 event stream in `ResponsesToMessagesReadStream`.
  For each event it runs `EncryptedContentWrapFn` and then `ResponsesToMessagesStream.java:handle`. It feeds
  `BufferingReadStream(MessagesSseListener)`, so `CollectMessagesTokenUsageFn` charges the Anthropic usage once.
  The message id comes from `ResponseIdUtil.createResponseId`.

The reference is the Python translator at commit `5e70209`: `to_responses.py:to_responses_request`,
`_convert_system`, `_system_parts`, `_convert_user_message`, `_convert_assistant_message`, `_content_parts`;
`common.py:visible_messages`, `compaction_text`, `resolve_service_tier`, `anthropic_usage`, `cache_write_tokens`;
`streaming.py:ResponsesToAnthropicStream.handle`, `_on_final`, `finalize`; `errors.py:anthropic_error_from_openai_error`.

The fixtures:

- There are 287 of them: 186 request, 41 response and 60 stream.
- 249 are in this series' scope: 154 request, 36 response and 59 stream.
- Every request fixture with a body output carries `include: ["reasoning.encrypted_content"]`. Python sends it on
  every generation request.
- Request fixtures pass the upstream model name as `input.model`, apart from the body. In 37 of the 47 text-path
  ones it differs from the body's `model`.
- No fixture's body carries `stream`.
- Five reasoning stream fixtures give the event type only as `event_type`, with no `type` inside `data`. One of
  them, `stream/f090be3551756c51.json`, has empty `features`: only its `response.reasoning_summary_text.delta`
  events say it needs reasoning.
- No in-scope fixture carries a `srvtoolu_` id.

## Goals / Non-Goals

**Goals:**

- Every in-scope text-path fixture passes: 48 request (one of them an error) and 21 stream.
- The known-gaps list is exact: no fixture passes while listed and none fails unlisted.
- No new Core pass, body copy or Redis/blob round trip.

**Non-Goals:**

- Changing the stream reader or the CPU cost per frame (`ResponsesToMessagesReadStream` parses each frame, and
  `MessagesSseListener` parses it again).
- A Java fixture recorder. Fixtures are recorded in Python and copied.

## Decisions

1. **The translation takes the upstream model from its caller.** `translateRequest(JsonNode request, String model)`,
   as `to_responses_request(req, model)` does. `MessagesController` passes `request.getModel()`, so the upstream body
   is unchanged: `EnhanceDeploymentRequestFn` still replaces it with `overrideName`. Rejected: the harness writes
   `input.model` into the request body before calling. That changes the input to make the output match, and it
   hides that Python never reads the body's `model`.

2. **`stream` leaves the translation, and Core sets it.** `prepareTranslatedUpstreamRoute` puts `stream: true` on
   the translated tree. Only streaming requests get that far, and Core is the transport, as the Python SDK call is.
   The upstream body is unchanged. Rejected: strip `stream` in the harness. Comparing whole trees only works if the
   harness does not edit the output.

3. **`include: ["reasoning.encrypted_content"]` is sent from this change on.** Every request fixture expects it. It
   is also what the deployment gets today through the HTTP translator, so moving a model from the translator service
   to the builtin keeps its upstream request the same. Rejected: leave it to `builtin-translator-reasoning`. Then
   every request fixture would be a known gap until that change, and this change could check no request body.
   Consequence: a reasoning model streams its encrypted reasoning. Core already parses every event and runs
   `EncryptedContentWrapFn` on it, and it drops the reasoning until the reasoning change translates it.
   `builtin-translator-reasoning` builds on this and does not add the field again.

4. **Text-path port.** `MessagesToResponses` gains:
   - a port of `_system_parts` for a `system`-role turn: string or text blocks, `mid_conv_system` nested, other
     blocks dropped with a warning (today this is a 400);
   - `mid_conv_system` blocks in a user turn, sent as `system` items before the user message, and in an assistant
     turn, sent before the turn's items;
   - `compaction` blocks as text, read by `compaction_text`: a non-empty string `content`, else nothing.

   Every other block in a user or assistant turn is still refused. `ResponsesToMessagesStream` and
   `ResponsesToMessages` change only where a text-path stream fixture fails; the reading so far shows no difference
   on the text path.

5. **Fixtures copied verbatim.** All 287 files and `manifest.json` go to
   `translator/src/test/resources/translation-fixtures/`, in the recorder's own layout. Re-recording means deleting
   that folder and copying the recorder's output in its place. The known-gaps list lives beside the folder, in
   `translator/src/test/resources/translation-fixtures-known-gaps.txt`, so a re-copy cannot delete it. Rejected:
   copying only the 249 in-scope files. The next series would need a second copy, and the manifest counts would no
   longer match.

6. **One parameterized test, `TranslationFixtureParityTest`, in the `translator` module.**
   - It checks the per-kind file counts against `manifest.json` `counts`, which catches a partial copy.
   - It skips a fixture whose `features` hold `image`, `document`, `search_result`, `web_search`,
     `web_search_call`, `url_citation`, `count_tokens`, `json_schema`, `input_image` or `input_file`, or a value
     starting with `web_search_`. It skips with `Assumptions.abort`, so the 38 show up as skipped.
   - request: it calls `translateRequest(input.request, input.model)` and builds `{"body": …, "tool_names":
     input.tool_names}`. The `tool_names` echo holds until `builtin-translator-tools` adds aliasing; every text-path
     fixture has `null` or `{}` on both sides. An `UntranslatableRequestException` becomes `{"error": {"status": 400,
     "type": "invalid_request_error", "message": …}}`: Core answers that exception with 400, and
     `invalid_request_error` is the Anthropic type of a 400 (`errors.py:status_to_anthropic_type`).
   - stream: it builds a new `ResponsesToMessagesStream(input.requested_model, <any id>)`. For a `handle` call it
     gives `data` the `type` of `event_type` when the data has none (Core dispatches on the data's `type`, as Python's
     SSE client does), then parses each frame into `{"event", "data"}`. A `finalize` call goes to `finish()`.
     `emit_error` has no Java counterpart yet, so it fails.
   - response: there is no non-streaming translator yet, so it fails.
   - Normalisation: `message.id` in a response output and in a stream `message_start` becomes one placeholder on both
     sides, because Core sends an id it generates. The manifest's `srvtoolu_` rule is not ported: no in-scope fixture
     carries one, and the series that brings web search adds it.
   - Comparison: Jackson tree equality after a serialise-and-parse round trip of the Java output, so a `long` and an
     `int` of the same value compare equal. A failure prints the fixture path, its Python `tests`, the expected output
     and the actual output.

   Rejected: one test method per kind. It triples the code and gives no extra signal. Also rejected: putting the
   harness in the server module. The functions under test are pure, and the server's integration tests cover the
   wiring.

7. **The known-gaps list only shrinks.** It is a text file with one `<kind>/<file> <change id>` per line and `#`
   comments. A failing fixture is skipped when it is listed. The test fails when a listed fixture passes, when an
   unlisted one fails, and when a line names a fixture that is missing or out of scope. A fixture's owner is the
   last change it needs. The rule reads `features`, a request's `output_config`, and the event type of each stream
   `handle` call (the data's `type`, else `event_type`):
   - a response kind fixture, or a stream fixture with an `emit_error` call: `builtin-translator-non-streaming-errors`;
   - otherwise one that needs reasoning: `builtin-translator-reasoning`. That is a feature among `reasoning`,
     `thinking`, `redacted_thinking`, `summary_text`, `reasoning_text`, `field:thinking`, `omit_thinking`,
     `adaptive`, `enabled` and `disabled`; an `output_config` holding `effort` (`field:output_config` alone is not
     reasoning); or an event type starting with `response.reasoning_` (in the fixtures:
     `response.reasoning_summary_part.added`, `response.reasoning_summary_part.done`,
     `response.reasoning_summary_text.delta` and `response.reasoning_text.delta`);
   - otherwise one that needs tools: `builtin-translator-tools`. That is a feature among `field:tools`,
     `field:tool_choice`, `function_call`, `tool_use` and `tool_result`, or the event type
     `response.function_call_arguments.delta` or `response.function_call_arguments.done`;
   - otherwise this change, which leaves no such line behind.

   This is `builtin-translator-tools`' own definition ("Fixtures" in its design.md), so the lines this change writes
   are the ones that change removes. Estimate by the rule: 41 for tools (35 request, 6 stream), 101 for reasoning (71
   request, 30 stream) and 38 for non-streaming and errors (36 response, 2 stream). Two of the reasoning request
   fixtures, `1acd561bc5894e4d` and `4c3e508c892771ad`, carry `thinking` but expect no reasoning in the output; they
   should pass with this change and stay off the list, so about 178 lines in all. The real list is what the test
   reports. Rejected: a JSON map. Nothing reads it but this test, and one line per fixture diffs better.

8. **Field-level scenarios are verified by the parity test, not by one integration test each.** The
   `config.yaml` rule asks for an integration test per HTTP-boundary scenario. The request mapping and the stream
   events are produced by the `translator` functions that the server calls unchanged. `BuiltinTranslatorApiTest`
   proves the wiring once: the translated body reaches the origin and the translated stream reaches the client. An
   integration test per mapping row would repeat the fixtures through a slower harness. The refusals, limits,
   access and config scenarios keep integration tests, and so do the blocks that went from `400` to `200`
   (`testBlocksOnceRefusedAreTranslated`): the Messages defaults run before the translation and the Responses chain
   after it, and either could break them.

### Deliberate differences

From today's behaviour (all on the builtin path, which has not shipped in a release):

- `mid_conv_system` and `compaction` blocks are translated instead of answered with a 400. A non-text block in a
  system turn is dropped instead of answered with a 400.
- The upstream body gains `include`.

From the reference, kept on purpose:

- A content block Python drops with a warning when it does not know it is refused by Core: it might be one the
  answer depends on.
- Images, documents and search results are refused (a later series), as are tool and thinking blocks (the next
  changes).
- The message id is a DIAL id, not the upstream's response id.

From the reference, deferred to a later change of the series:

- The stream's error type mapping knows `invalid_request_error`, `rate_limit_exceeded` and `vector_store_timeout`.
  It does not know Python's 18 request-fault codes (`invalid_prompt`, the image codes, …), which Core reports as
  `api_error`. No in-scope fixture carries one. `builtin-translator-non-streaming-errors` ports the full table.
- A mid-stream upstream failure resets the client connection with no `error` event, as on the other streaming
  routes. `builtin-translator-non-streaming-errors` turns it into an `error` event.

### Effect per request

- Core passes: unchanged, one.
- Body copies and parses: unchanged. The translation already parses the Messages tree and writes the Responses body;
  setting `stream` on the tree adds no copy.
- Redis and blob round trips: unchanged.
- Upstream answer: with `include`, a reasoning model adds its encrypted reasoning to `response.output_item.done` and
  `response.completed`. Core parses and wraps it, as it does every event, and drops it until the reasoning change.

None of this is measured. The upstream body is the one the HTTP translator already sends, so nothing changes against
the two-pass path.

## Risks / Trade-offs

- [The fixtures go stale when the Python translator changes] → `translators.md` names the manifest commit Core
  matches. Refreshing is a copy, and the count check catches a partial copy.
- [A stream fixture fails for a reason the reading missed] → task 1.5 lists it under this change's id, and group 2
  fixes it in the code, not in the list, and records it here as drift.
- [`include` on a model that rejects it] → Python sends it on every request and the HTTP translator already passes
  it to the same deployments. The integration test asserts it, so a decision to drop it is a visible change.

## Migration Plan

None. No config field changes. The builtin path has not shipped in a release.

## Open Questions

- Whether Core should drop unknown content block types with a warning, as Python does, once tools and reasoning are
  translated. This changes no spec here. The last change of the series decides.
- `builtin-translator-routing-accounting` may change `deployment-origin` or `app-to-model-accounting`. It should
  write its deltas against those specs as this change leaves them.
- The spec review below edited four requirement blocks that later changes copy as MODIFIED blocks: "Mid-conversation
  system text in a translated request" (`builtin-translator-tools`), "Generation settings in a translated request"
  (`builtin-translator-reasoning`), "Upstream request of a builtin translation" and "Truncated or failed stream ends
  in an error event" (`builtin-translator-non-streaming-errors`). Their copies must take the same edits.
  `builtin-translator-reasoning` also counts 29 reasoning stream fixtures; by decision 7 it is 30, with
  `f090be3551756c51`.

## Spec review

1. fidelity, high: the owner rule read only `features`, so `stream/f090be3551756c51.json` (empty `features`,
   reasoning events only) had no owner and could not pass. **Accepted.** Decision 7 now also reads each `handle`
   call's event type, and any `response.reasoning_*` type makes the owner `builtin-translator-reasoning`. Context
   names the fixture. Goals and task 2.2 say 21 stream fixtures. The estimate is 30 reasoning stream fixtures; the
   request side is finding 8.
2. fidelity, medium: the upstream request does not always go to the `openaiResponses` url plus
   `/openai/v1/responses`, and `X-UPSTREAM-*` is sent only for a model with `upstreams`. **Accepted.** "Upstream
   request of a builtin translation" now names the URI `POST /openai/v1/responses` resolves to (base url plus path,
   with `overridePaths` and a legacy `responsesEndpoint` applying) and sends `X-UPSTREAM-*` for a model with
   `upstreams`. deployment-interfaces-config "Interface mode" says the `out` interface is served as
   a pass-through request for it would be.
3. fidelity, medium: a client or `defaultHeaders` `X-DIAL-DEPLOYMENT-ID` reaches the origin. **Accepted**, by
   rewording (this review edits no Java). The upstream-request requirement says Core adds none; Context records that
   a client or default value is copied through, as on `/openai/v1/responses` (the requirement text is at the
   validator's 500-character limit). The deployment-origin delta no longer states it (see
   finding 14).
4. fidelity, medium: a mid-stream upstream failure resets the client connection with no `error` event.
   **Accepted.** The requirement now covers an upstream stream that ends without a terminal event, and says a
   mid-stream failure resets the client connection with no further event. New scenario "Upstream connection fails
   mid-stream", tested by `ResponsesToMessagesReadStreamTest.testFlowControlAndFailuresReachTheUpstream` with a
   no-frame assertion (task 4.3). Listed as deferred to `builtin-translator-non-streaming-errors`, which changes it.
5. fidelity, low: a stream whose first event is `response.failed` or `error` sends a lone `error`. **Accepted.**
   "Message identity" now says `message_start` and `ping` come before the first content block or terminal answer, and
   an error before anything else is sent alone. New scenario "Error before anything else", tested by the existing
   `ResponsesToMessagesStreamTest.testFailedResponseIsAnErrorAndNothingFollowsIt`.
6. fidelity, low: the 18 request-fault codes are not kept out on purpose. **Accepted.** Moved to a "deferred" list
   under Deliberate differences that names `builtin-translator-non-streaming-errors`.
7. fidelity, low: the `u1` half of "Long user id dropped, not truncated" was in no test. **Accepted.** The scenario
   now says a 64-character `user_id` is sent unchanged, which `request/714b6d8b8c44a049.json` checks.
8. simplicity, medium: `field:output_config` made 11 tools-only fixtures reasoning-owned, against change 2's split.
   **Accepted.** Decision 7 uses change 2's definition (`output_config` counts only with `effort`) and names the
   reasoning and function-call event types. The tools request set is now exactly change 2's 35. Recomputed: 41 tools
   (35 request, 6 stream), 101 reasoning (71 request, 30 stream), 38 non-streaming-errors. Two of the 71
   (`1acd561bc5894e4d`, `4c3e508c892771ad`) expect no reasoning output and should pass now, so about 178 lines. The
   rule also moves `request/de7e36c5c4024381.json` (`output_config.format` only) to the text path: 48 request.
9. simplicity, medium: the per-request-key check on the `openaiResponses` interface had no spec or test, and no
   later change owned it. **Accepted.** "Consent checked on both interfaces" is now "Access checked on both
   interfaces", with the scenario "Responses interface closed to per-request keys" and
   `BuiltinTranslatorApiTest.testPerRequestKeyClosedOnTheTranslatedInterfaceIsRefused` on a new test model
   `gpt-builtin-closed` (task 4.1). The hand-off risk is removed, and app-to-model-accounting points to it.
10. simplicity, medium: `docs/dynamic-settings/models.md` still says an inline translator is `{"out", "baseUrl"}`
    and that translator mode never touches limits. **Accepted.** New task 5.2 updates both bullets; the CLAUDE.md
    task is now 5.3. The proposal's Docs impact lists the file.
11. simplicity, low: groups 1 and 2 could not end green. **Accepted.** Task 1.5 fills the list from the first run,
    with this change's id on text-path fixtures; group 2 deletes lines as they pass, and task 2.2 ends with none left
    under this change's id. The signature change moved from group 2 to task 1.3, because the harness calls the
    two-argument `translateRequest` and would not compile in group 1 otherwise. Group 3 now checks the counts and the
    only-shrinks rule.
12. simplicity, low: the blocks that went from `400` to `200` had no integration test. **Accepted.** Task 2.1 adds
    `BuiltinTranslatorApiTest.testBlocksOnceRefusedAreTranslated`, mapped to the compaction and nested
    mid-conversation scenarios; decision 8 keeps the fixture rule for the other mapping rows.
13. simplicity, low: a `mid_conv_system` block in a user or assistant turn had no scenario. **Accepted.** New
    scenario "Mid-conversation block in a user turn" (checked against `to_responses.py:_convert_user_message`: the
    `system` item comes before the user message), tested by
    `MessagesToResponsesTest.testMidConversationBlockInAUserTurnPrecedesIt` (task 2.1).
14. simplicity, low: app-to-model-accounting and deployment-origin repeated builtin scenarios of
    anthropic-messages-translation. **Accepted.** Both deltas keep only the narrowing to translator services, with
    a pointer to anthropic-messages-translation; "Builtin-translated interface checked" and "No deployment id on a
    builtin translation" are gone with their SHALL sentences.
15. simplicity, low: task 4.2 duplicated existing tests. **Accepted.** `ConfigPostProcessorTest` builds the
    translator through the `@JsonCreator` constructor that a config read uses, and reads the unknown `builtin` as
    JSON. "Both baseUrl and builtin" and "Unknown builtin" map to
    `testTranslatorNeedsExactlyOneOfBaseUrlOrBuiltin` and `testUnknownBuiltinTranslatorIsRejectedAsItIsRead`; task
    4.2 keeps only `inlineBuiltinTranslatorStaysInline`.
