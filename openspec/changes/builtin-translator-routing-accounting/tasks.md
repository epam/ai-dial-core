# Tasks

Changes 1-4 of the series are applied before this one. Every test here is written first and run against the code as
those changes leave it; code changes only where a test fails, and each such fix is recorded in design.md before it
is made (design, Risks).

## 1. Row recorder for integration tests

- [ ] 1.1 Give `AiDial` a `logStore` field that `AiDial.java:start` uses instead of `new GfLogStore(...)` when it is
  set (design D8). Add `server/src/test/java/com/epam/aidial/core/server/log/RecordingLogStore.java`: it keeps every
  `AnalyticsLogContext` it is given, passes it on to a `GfLogStore` built from the same `analytics` settings, and can
  wait for the rows of a request URI. Install it in `ResourceBaseTest` before `dial.start()`. Verify that
  `BuiltinTranslatorApiTest`, `DeploymentPostApiTest` and `GfLogStoreTest` pass unchanged.
- [ ] 1.2 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.BuiltinTranslatorApiTest" --tests "com.epam.aidial.core.server.DeploymentPostApiTest"
  --tests "com.epam.aidial.core.server.log.GfLogStoreTest"`; all pass.

## 2. Analytics row

- [ ] 2.1 Add `server/src/test/resources/dial-config/builtin-translator-accounting.json`: model `gpt-builtin-priced`
  with `overrideName` `gpt-builtin-priced-upstream`, `openaiResponses` at `http://localhost:4848`, `anthropicMessages`
  through a `builtin: anthropicMessagesToOpenaiResponses` translator, pricing `unit` `token`, `prompt` `0.01`,
  `completion` `0.02`, `cacheRead` `0.001`, two upstreams with `id`, `key` and `responsesEndpoint`,
  `maxRetryAttempts: 2` and `autoCachingSupported: true` under `interfaces.openaiResponses.features` only (not at
  model level, so the cache test fails if the `anthropicMessages` features are read); a key whose role sets a `day`
  token limit and a `requestDay` limit for it. Create `BuiltinTranslatorAccountingApiTest`, each test annotated with
  `@DialConfigLocation` of this file.
- [ ] 2.2 Add `BuiltinTranslatorAccountingApiTest#testOneClientViewRowWithCost` for the scenario "Streamed answer":
  exactly one recorded row for `/anthropic/v1/messages`, with `deployment`, `request.uri`, a request body whose
  `model` is `gpt-builtin-priced` and whose `messages` are the client's, a response body with `message_start` and
  `message_stop` and no `response.` event, `total_tokens` 18 and `price` 0.26.
- [ ] 2.3 Add `BuiltinTranslatorAccountingApiTest#testUpstreamErrorWritesOneRow` for the scenario "Upstream error":
  the upstream answers 400; exactly one row, with status 400 and no token usage.
- [ ] 2.4 In `docs/dynamic-settings/translators.md`, replace the closing note of "Limits and a translated request"
  (both hops are logged) with the two cases: a translator service writes two rows, the outer one without a price; a
  builtin translator writes one row with the client's Messages request, the Anthropic stream the client received
  and the price. Add that on a translator service the callback's cost is also credited to `<model>/aggregated-costs`
  and listed in the outer row's `usage_per_model` (so `GET /v1/deployments/{id}/usage` shows it twice), and that
  the builtin path writes neither (design D2). Verify the text matches what 2.2 asserts.
- [ ] 2.5 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.BuiltinTranslatorAccountingApiTest"`; all pass.

## 3. Billing parity

- [ ] 3.1 Add `BuiltinTranslatorAccountingApiTest#testBilledAsItsResponsesPass` for the scenario "Plain usage" (design
  D2): the mock upstream answers both requests with the same Responses stream (usage 10 in, 8 out, `total_tokens`
  18); after each request, wait for the charge and read `GET /v1/deployments/gpt-builtin-priced/usage` (the model's
  own token, request and cost records); the token, cost and request increments of `POST /openai/v1/responses` and of
  `POST /anthropic/v1/messages` are equal and are 18, 0.26 and 1.
- [ ] 3.2 Add `BuiltinTranslatorAccountingApiTest#testCachedTokensBilledAsItsResponsesPass` for the scenario "Cached
  tokens": usage 100 in with `cached_tokens` 40, 10 out, `total_tokens` 110; both increments are 110 tokens and 0.84.
- [ ] 3.3 Add `BuiltinTranslatorAccountingApiTest#testReasoningTokensBilledAsItsResponsesPass` for the scenario
  "Reasoning tokens": usage 10 in, 30 out with `reasoning_tokens` 12, `total_tokens` 40; both increments are 40
  tokens and 0.70.
- [ ] 3.4 In `docs/dynamic-settings/translators.md`, change the rule "A translated request touches the caller's limits
  not at all" and the section "Limits and a translated request" so they cover a translator service only, and add
  that a builtin-translated request is checked and charged once, on its own request, priced from the Anthropic usage
  through the `anthropicMessages` pricing fields, with the differences of design D3: `serviceTier`, `$.usage` paths,
  the token count (the builtin path counts the Anthropic counters and ignores the upstream's `total_tokens`; the
  Responses pass trusts `total_tokens` and adds no tokens when it is missing), and an upstream-reported cost, which
  does not reach the builtin row (its price comes only from the model's DIAL `pricing`). Verify the numbers in the
  text match 3.1-3.3.
- [ ] 3.5 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.BuiltinTranslatorAccountingApiTest"`; all pass.

## 4. Limits before the upstream

- [ ] 4.1 Add `MessagesSpendLimitApiTest#testBuiltinTranslatedModelGetsSpendHeaders` for the scenario "At 82% of a day
  cap", on the `claude-builtin` model change 4 adds to `dial-config/anthropic-spend-limits.json` (streamed request,
  mock `POST /openai/v1/responses` answers 200). The reached cap is change 4's
  `testBuiltinTranslatorKeepsTheSpendCapAnswer`.
- [ ] 4.2 Add `MessagesControllerLimitTest#testVerifyLimit_builtinFailsClosedWhenSpendIsUnreadable` for the scenario
  "Spend record unreadable" (design D4): a deployment whose `anthropicMessages` uses the builtin translator, a
  `RateLimiter.limitWithSpendCaps` failing with `SpendReadException` and `hasCostLimit` true; the result is the 429
  with `fetch_error`.
- [ ] 4.3 In `docs/anthropic-spend-limits.md`, after the paragraph on translated mode, add that a model whose
  `anthropicMessages` uses a builtin translator is checked like a pass-through model: spend headers, the spend-cap
  429, the token and request 429 from DIAL, and a 429 when the spend record cannot be read. Verify against 4.1-4.2,
  change 4's `testBuiltinTranslatorKeepsTheSpendCapAnswer` and change 1's "Limits of a builtin translation charged
  once".
- [ ] 4.4 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.MessagesSpendLimitApiTest" --tests
  "com.epam.aidial.core.server.controller.anthropic.MessagesControllerLimitTest"`; all pass.

## 5. Upstream cache affinity

- [ ] 5.1 Add `BuiltinTranslatorAccountingApiTest#testSecondTurnPinnedToFirstUpstream` for the scenario "Second turn
  pinned": turn 1 (no `system`, one user message) is answered with `X-DIAL-CACHE-BREAKPOINT-PATH:
  prefix.body.input[0]` and `X-DIAL-CACHE-EXPIRE-AT` ten minutes ahead; after the asynchronous cache write, turn 2
  repeats the user message and adds an assistant and a user message; turn 2's upstream request has turn 1's
  `X-UPSTREAM-KEY` and `X-DIAL-CACHE-BREAKPOINT-PATH: prefix.body.input[0]`.
- [ ] 5.2 Add `BuiltinTranslatorAccountingApiTest#testMessagesPrefixIsNotCached` for the scenario "Messages prefix not
  cached": the same with `prefix.body.messages[0]`; turn 2's upstream request has `X-DIAL-CACHE-BREAKPOINT-PATH:
  prefix.body.input[2]` (Core's fallback to the last breakpoint on a miss, design D5), not `prefix.body.input[0]`.
- [ ] 5.3 In `docs/dynamic-settings/translators.md` "Builtin translators", add that upstream cache affinity uses the
  model's `openaiResponses` features and the translated body, so the adapter's breakpoint paths name Responses
  fields. Verify against 5.1-5.2.
- [ ] 5.4 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.BuiltinTranslatorAccountingApiTest"`; all pass.

## 6. Retries and a client that leaves

- [ ] 6.1 Add `BuiltinTranslatorAccountingApiTest#testRetriableAnswerRetriedOnNextUpstream` for the scenario "First
  upstream unavailable": the mock answers 503 to the first request it gets and a stream with the text `Hi` (usage 10
  in, 8 out) to the second; the two requests have equal bodies and different `X-UPSTREAM-KEY`; the client gets 200,
  `X-UPSTREAM-ATTEMPTS: 2`, one `message_start`, one `message_stop`, the text `Hi`; the day token record grows by 18.
- [ ] 6.2 Add `BuiltinTranslatorAccountingApiTest#testClientGoneUpstreamReadToEnd` for the scenario "Client
  disconnects mid-stream" (design D7): the mock streams slowly (throttled body), the client closes after the first
  event; the day token record grows by 18 and one row is recorded for the request.
- [ ] 6.3 Add `ResponsesToMessagesReadStreamTest#testFlowControlReachesTheUpstream` (design D6): `pause`, `fetch(3)`
  and `resume` on the stream reach the mocked upstream. It holds the flow-control half of today's
  `testFlowControlAndFailuresReachTheUpstream`, whose failure half change 4's D7 replaces.
- [ ] 6.4 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.BuiltinTranslatorAccountingApiTest" --tests
  "com.epam.aidial.core.server.controller.anthropic.ResponsesToMessagesReadStreamTest"`; all pass.

## 7. End of the series

- [ ] 7.1 Open the known-gaps list of the fixture parity harness (created by `builtin-translator-fixture-parity`).
  It must be empty after changes 2-4. Delete it and the code that reads it, and verify the parity test still replays
  every in-scope fixture. If an entry is left, stop and report which change of the series owns it. In
  `docs/dynamic-settings/translators.md` "Builtin translators", replace the sentence that names
  `translation-fixtures-known-gaps.txt` with one saying the translation matches every in-scope fixture recorded at
  commit `5e70209`. Verify that `grep -r known-gaps docs/` finds nothing.
- [ ] 7.2 In `docs/dynamic-settings/translators.md` "Builtin translators", replace the line on moving between a builtin
  translator and a translator service with the fallback: change the translator entry's `builtin` to the service's
  `baseUrl` (example), and say what changes (design, Migration Plan): two passes and two rows, limits charged on the
  call back, the call back's cost also credited to `<model>/aggregated-costs` and listed in the outer row's
  `usage_per_model`, the model's `openaiResponses` interface must accept per-request keys (no
  `accessibleByPerRequestKey: false`) or the call back gets 403, and `count_tokens` and interceptors are served again.
  Verify the section's scope bullets still match what changes 1-4 shipped.
- [ ] 7.3 Run `./gradlew checkstyleMain checkstyleTest`, `./gradlew :translator:test` and `./gradlew :server:test
  --tests "com.epam.aidial.core.server.BuiltinTranslatorApiTest" --tests
  "com.epam.aidial.core.server.BuiltinTranslatorAccountingApiTest" --tests
  "com.epam.aidial.core.server.MessagesSpendLimitApiTest" --tests
  "com.epam.aidial.core.server.controller.anthropic.MessagesControllerLimitTest"` plus the parity test class; all
  pass. The full suite runs in CI.

## Workflow follow-up

- Write review.md, then archive after `builtin-translator-fixture-parity`, `builtin-translator-tools`,
  `builtin-translator-reasoning` and `builtin-translator-non-streaming-errors`.
