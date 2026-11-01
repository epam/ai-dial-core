# Design

## Context

See proposal.md for the motivation and the spec delta for the behaviour. The builtin path these requirements
describe is the first version's code, as changes 1-4 leave it:

- **Request.** `MessagesBaseController.java:setupDeployment` resolves the builtin translator and refuses a deployment
  with interceptors. `MessagesController.java:prepareTranslatedUpstreamRoute` applies the `anthropicMessages`
  defaults to the Messages request, translates it, runs `ResponsesController.java:buildEnhancementFunctions` on the
  translated body (which includes `BuildUpstreamCacheFn(OPENAI_RESPONSES)`), mints one per-request key, keeps the
  Messages body as `ProxyContext.requestBody` and the translated body for `resolveProxyRequestBody`, and builds the
  `UpstreamRoute` over the model's `openaiResponses` endpoint.
- **Retries.** `MessagesBaseController.java:handleProxyResponse` retries a retriable status on the next upstream
  (`sendRequest`, `UpstreamRoute.java:next`) before `processResponse` runs, and marks a 200 succeeded through
  `UpstreamRoute.java:succeed`, which calls `updateUpstreamCache` with the `X-DIAL-CACHE-BREAKPOINT-PATH` answer
  header.
- **Response.** `MessagesController.java:processResponse` builds a new `ResponsesToMessagesStream` and
  `ResponsesToMessagesReadStream` for the answer it relays, then `BufferingReadStream` with
  `MessagesSseListener(CollectMessagesTokenUsageFn)`. `CollectMessagesTokenUsageFn.java:apply` builds the
  `TokenUsage` and the pricing data from the Anthropic `usage` of `message_start` and `message_delta`.
  `MessagesController.java:completeProxyResponse` writes the row with `AnalyticsLogContext.java:from(context, null)`.
- **Limits.** `BaseDeploymentPostController.java:isSubjectToLimits` is true for a builtin translator, so
  `MessagesController.java:verifyLimit` runs `SpendCapCheck.java:verify` with `subjectToLimits = true`
  (`RateLimiter.java:limitWithSpendCaps`), and `BaseDeploymentPostController.java:collectTokenUsage` charges through
  `increaseLimits` and `RateLimiter.java:increase` with `interfaceType()` = `ANTHROPIC_MESSAGES`.
  `ModelCostCalculator.java:calculate` resolves the pricing fields through `StandardFieldResolver.java:resolve`.
  `MessagesController.java:putLimitHeaders` adds the spend headers on a 200.
- **Client gone.** `MessagesController.java:processResponse` hands a failed write to
  `BaseDeploymentPostController.java:handleResponseError`, whose `Model` branch waits for the upstream stream to end,
  collects the usage and writes the row.

The HTTP translator path for the same request makes two passes. The outer Messages pass is a `translator`-mode pass:
`isSubjectToLimits` is false, so it only reads the spend caps (`SpendCapCheck.java:verify`, fail open) and charges
nothing, but `collectTokenUsage` still records its Anthropic usage in the token-stats span and its row (tokens, no
price). The translator's call back to Core on `/openai/v1/responses` is a pass-through Responses pass: it checks and
charges the limits, prices the terminal Responses object (`ExtractTerminalResponseFn.java:apply`) through the
`openaiResponses` fields, and writes a second row (Responses request and stream, with price). The callback carries the
outer pass's per-request key, so its span is a child of the outer `m` span (`ProxyContext.java:134`): its cost rolls
up into that span, which credits `<model>/aggregated-costs` in the initiator's bucket
(`TokenStatsTracker.java:updateStats`, `BaseDeploymentPostController.java:recordAggregatedCosts`), and the outer row
gets a `usage_per_model` entry for it.

## Goals / Non-Goals

**Goals:**

- Each requirement gets a test that fails if the shared code stops giving the builtin path that guarantee.
- No runtime change unless a test shows a gap.

**Non-Goals:**

- Running the HTTP translator path inside a test. The outer pass is already covered (D2).
- Making the builtin path's records identical to the HTTP path's: one row and one span is the point of the path.

## Decisions

**D1. The row names no translator.** The builtin row has the shape of a pass-through Messages row:
`request.uri` `/anthropic/v1/messages`, `deployment`, the Messages request, the Anthropic stream and the price
(`GfLogStore.java:append`). Whether the builtin translator served it is a question about the config, answered by the
deployment's `anthropicMessages` entry at that time. The row alone cannot tell the two apart: `upstream_uri` is the
upstream's legacy `endpoint` (`AnalyticsLogContext.java:from`), absent for an upstream configured with
`responsesEndpoint` only, as the test config of task 2.1 is. No existing field fits: `route_name` and `route_type` describe a config route (`RouteType` is `global` or
`application`), and an analytics query that groups by them would count builtin requests as routed. Rejected: a new
`translator` field, because the config already answers the question, the field changes the row every analytics
consumer parses, and no consumer has asked for it. Add it when one does.

Deliberate differences of the row:

- From the HTTP path: one row instead of two. The outer row (tokens, no price) and the inner row (Responses bodies,
  price) become one row with the client's bodies and the price. Summing rows no longer counts the request twice. The
  row has no `usage_per_model` entry, and no `<model>/aggregated-costs` record grows, because the builtin pass has no
  child span.
- From a pass-through Messages row: `request.body.model` is the deployment id the client sent, not `overrideName`.
  `EnhanceDeploymentRequestFn` runs on the translated body only (`ResponsesController.java:buildEnhancementFunctions`),
  so `overrideName` reaches the upstream and not the row.
- `assembled_response` stays absent, as for every Messages row (`MessagesController.java:completeProxyResponse`
  passes null).

**D2. The billing reference is a direct Responses request.** The test sends the same upstream answer once to
`POST /anthropic/v1/messages` and once to `POST /openai/v1/responses` for the same model and compares what each adds
to the token, request and cost records of the model (`GET /v1/deployments/{id}/usage`, which reads the model's own
`costs` record; `/limits` reports the caller's account-wide spend). That Responses pass matches the token, cost and
request records the translator's call back adds on the HTTP path, and the outer pass adds nothing (spec "Translated
interface not limit-checked", `MessagesSpendLimitApiTest#testTranslatedModelIsNotChargedOrLimitedByTheOuterRequest`).
It does not match the rest of the callback's records: a root Responses request has no parent span, so it credits no
`<model>/aggregated-costs` and gives no row a `usage_per_model` entry. On the HTTP path both happen, and
`GET /v1/deployments/{id}/usage`, which adds the `costs` and `aggregated-costs` records
(`RateLimiter.java:LimitType.AGGREGATED_COSTS`), shows the request's cost twice; on the builtin path it shows it once. Rejected: a
mock translator that calls Core back with the per-request key it received. It tests the same sum with a second HTTP
client inside a MockWebServer handler.

**D3. Pricing reads the Anthropic usage.** Kept from the first version: the cost comes from the Anthropic usage the
client receives, with the `anthropicMessages` fields of `StandardFieldResolver.java:resolve`. It equals the Responses
pass's cost for flat rates and for decision trees on `cachedReadTokens`, `cachedWriteTokens` and `promptTokens`,
because `ResponsesToMessages.java:convertUsage` (ported from `common.py:anthropic_usage`) splits the upstream
`input_tokens` into three counters that add up to it, `cached_tokens` becomes `cache_read_input_tokens`, and
`reasoning_tokens` stay inside `output_tokens`. `ttl` resolves to nothing on both paths (the translator sends no
`cache_creation`, and Responses has no TTL field). These inputs differ, deliberately:

- `serviceTier`: the Responses pass reads the upstream's `service_tier`; the Anthropic usage has none
  (`responses-translations.md`, usage: "`service_tier`, `cache_creation` — Not sent"), so a tree branching on it takes
  its no-value branch.
- A `$.usage...` JSON path reads the Anthropic usage, not the Responses usage.
- The token count: the builtin path counts input + cache + output tokens from the Anthropic counters
  (`MessagesTokenUsageParser.java:build`) and ignores the upstream's `total_tokens`; the Responses pass takes
  `total_tokens` as it is (`TokenUsage.java`, `TokenUsageParser.java:parse`) and adds no tokens when it is missing
  (`RateLimiter.java:increase` skips a total of 0). The two counts agree only when the upstream reports a
  `total_tokens` equal to input + output, which the billing scenarios state.
- An upstream-reported cost: for a model without DIAL pricing, the Responses pass's row carries the `cost` and
  `aggCost` the upstream's `usage` reports (app-to-model-accounting "Usage in the analytics row"). The builtin row
  carries neither, because `ResponsesToMessages.java:convertUsage` emits only token counters; its price comes only from
  the model's DIAL `pricing`.

Rejected: pricing the builtin pass from the Responses terminal event with the `openaiResponses` fields. That needs a
second usage collector on the Responses events, and the price would come from other data than the tokens and the row.
Rejected: copying the upstream `service_tier` into the pricing data. It is the smallest fix, but no test config prices
by service tier today; add it when a model served through the builtin translator gets such a price.
`docs/dynamic-settings/translators.md` states the differences.

**D4. Limits are the pass's own.** The builtin request is the request the initiator is accountable for
(`BaseDeploymentPostController.java:isSubjectToLimits`), so it gets the pass-through Messages answers: a token or
request limit is refused by Core before the upstream with `rate_limit_error` (`AnthropicLimitResponses.java:limitReached`),
where the HTTP path forwards the request and relays whatever the translator makes of the callback's 429; and an
unreadable spend record of a caller with a cost limit fails closed (`AnthropicLimitResponses.java:spendUnavailable`),
where the HTTP path's outer pass fails open. Change 1 already narrows app-to-model-accounting "Translated interface
not limit-checked" to a translator service and adds "Limits of a builtin translation charged once" (the token-limit
429); builtin-translator-non-streaming-errors adds the reached spend cap ("Reached spend cap unchanged",
`MessagesSpendLimitApiTest#testBuiltinTranslatorKeepsTheSpendCapAnswer` on its `claude-builtin` model). This change
adds the spend headers of a 200 and the unreadable spend record. The unreadable-spend scenario is tested in `MessagesControllerLimitTest` with a mocked
`RateLimiter`, as the pass-through case is today: the HTTP API cannot make the spend record unreadable.

**D5. Cache affinity comes from the Responses chain.** `BuildUpstreamCacheFn.java:apply` runs with `OPENAI_RESPONSES`
on the translated body, so the prefix names (`prefix.body.input[0]`, …) are the ones a Responses adapter answers in
`X-DIAL-CACHE-BREAKPOINT-PATH`, and the cache features are the model's `openaiResponses` features. Hashing the
Messages body would produce `prefix.body.messages[…]` names that a Responses adapter never returns, so no entry would
ever be stored; the second scenario checks that such a name stores nothing. On a miss Core still sends a breakpoint
header, the last breakpoint of the body (`UpstreamCacheService.java:getCacheEntry`), so the test tells a hit from a
miss by the name: a hit names the stored prefix `prefix.body.input[0]`, a miss the last input item of turn 2,
`prefix.body.input[2]`. The Redis key is per model
(`UpstreamCacheService`), so a conversation that moves between the HTTP translator and the builtin translator keeps
its entry when the two translations of the earlier turns agree, which fixture parity gives. Not tested here: it needs
the HTTP translator in the test.

**D6. Retries and flow control need no code.** The state machine is built in `processResponse`, which runs once, for
the attempt Core relays; a retried attempt never reaches it. `EncryptedContentWrapFn` reads the upstream at answer
time, so it stamps the upstream that answered. Backpressure (`ResponsesToMessagesReadStream.java:pause`, `resume`,
`fetch`) gets its own unit test, `ResponsesToMessagesReadStreamTest#testFlowControlReachesTheUpstream` (task 6.3):
builtin-translator-non-streaming-errors' D7 turns an upstream exception into an error event, so the failure half of
today's `testFlowControlAndFailuresReachTheUpstream` no longer holds after it. It gets no spec
requirement: it is a property of the Java stream, and an HTTP test would have to detect a blocked socket write in
MockWebServer.

**D7. A client that leaves does not stop the upstream.** The builtin path keeps the `Model` rule of
`BaseDeploymentPostController.java:handleResponseError`: read the upstream stream to its end, charge the usage, write
the row (deployment-origin "Client gone during the relay"). Rejected: resetting the upstream request when the client
leaves. It would charge only what arrived before the reset, less than the HTTP path charges for the same request (its
outer pass also reads the translator's stream to the end, and the callback reads the upstream to the end), and it
would make this the only model pass that does so.

**D8. A row recorder for integration tests.** `AiDial.java:start` creates its `GfLogStore` inline, so a test cannot
see the rows. `AiDial` gets a `logStore` field (the class already has Lombok `@Setter`), used when set, as
`accessTokenValidator` is. `ResourceBaseTest` installs a recorder that keeps each `AnalyticsLogContext` and passes it
on to a `GfLogStore` built from the same settings, so every other test logs as before. Rejected: a gflog appender that
captures the `aidial.log` output (a custom appender factory and a test logging config for the same data); a unit test
with a mocked `LogStore` (it would not run the chain the row is built from). Serialisation of the row stays covered
by `GfLogStoreTest`.

## Effect per request

This change adds no work per request. The `logStore` field is read once at startup.

For the record, the builtin path (first version) compared with the HTTP translator path for one Anthropic request:
one Core pass instead of two; one per-request key and one token-stats span instead of two; one analytics row instead
of two, so the bodies are escaped into the log once. Limits: the HTTP path reads the caller's spend record on the
outer pass (`SpendCapCheck.java:verify` with `subjectToLimits = false`), checks and charges on the callback, and writes
the callback's cost to `<model>/aggregated-costs` (D2); the builtin path does one check and one charge. It saves one
spend read and one aggregated-cost write per request. The integration tests assert the counts that are visible: one upstream request per attempt and
one row per request. No timing is claimed, so there is no measurement task.

## Risks / Trade-offs

- [A test shows a gap, for example a second row or a charge on a retried attempt] → fix it in the shared code where
  all callers route through, record it in this design before coding, and keep the fix inside the builtin branch if it
  would change a pass-through path.
- [Change 4 reshapes errors on the builtin path] → it owns the reached-cap 429 ("Reached spend cap unchanged"); the
  spend-cap scenarios here assert the headers of a 200 and the `fetch_error` 429, which `AnthropicLimitResponses`
  already produces for every Messages model.
- [A service-tier price on a builtin-translated model] → D3; documented, fix named.
- [Random upstream choice makes affinity tests flaky] → the second-turn scenarios assert the
  `X-DIAL-CACHE-BREAKPOINT-PATH` request header besides the upstream key. It names the stored prefix `input[0]` only
  when Core found the entry; on a miss it names the last breakpoint, `input[2]` (D5). The retry test answers 503 to
  whichever upstream is asked first.

## Migration Plan

None. Rolling back a model to a translator service is a config edit (`baseUrl` instead of `builtin` in its
translator entry). What changes with it: two passes and two rows again; limits charged on the callback; the callback's
cost also credited to `<model>/aggregated-costs` and listed in the outer row's `usage_per_model` (D2); the callback
carries a per-request key, so the model's `openaiResponses` interface must not set `accessibleByPerRequestKey: false`,
or the callback gets 403 (app-to-model-accounting "Deployment closed to per-request keys"); and `count_tokens` and
interceptors are served again.

## Spec review

Each finding was checked against the code before it was applied.

1. fidelity, high: "Messages prefix not cached" said turn 2 carries no breakpoint header. **Accepted.** On a miss,
   `UpstreamCacheService.getCacheEntry` returns the last breakpoint, so turn 2 carries `prefix.body.input[2]`. The
   scenario now says so. D5 and Risks say a hit is told from a miss by the name (`input[0]` against `input[2]`), not
   by whether the header is there. Task 5.2 matches.
2. fidelity, medium: billing parity holds only when the upstream reports `total_tokens`. **Accepted.** `TokenUsage`
   binds `totalTokens` only from `total_tokens`, and `RateLimiter.increase` skips a total of 0. The three billing
   scenarios and tasks 3.1-3.3 now give `total_tokens` (18, 110, 40). The requirement is limited to answers whose
   `total_tokens` is input + output, and says the builtin count comes from the Anthropic counters. D3, the proposal and
   task 3.4 list the token count as a difference.
3. fidelity, medium: D2 called the direct Responses request "exactly" the callback. **Accepted.** The callback's span
   is a child of the outer `m` span (`ProxyContext.java:134`), so it credits `<model>/aggregated-costs` and adds a
   `usage_per_model` entry. `/usage` adds `costs` and `aggregated-costs`, so it shows the cost twice. Context, D1, D2,
   proposal "Records", docs tasks 2.4/7.2, Migration Plan and "Effect per request" (one aggregated-cost write saved)
   now say this.
4. fidelity, medium: D1 relied on `upstream_uri` showing the Responses upstream. **Accepted.** The row takes
   `Upstream::getEndpoint`, which is null for a `responsesEndpoint`-only upstream. The claim is gone. D1 now says the
   row alone cannot tell the paths apart, and it rejects a `translator` field because the config already answers the
   question and the field would cost every consumer.
5. fidelity, low: D6/6.3 named a test that change 4 breaks. **Accepted.** Change 4's D7 stops passing the exception
   on, so `assertSame(reset, failure.get())` fails. Task 6.3 now adds `testFlowControlReachesTheUpstream` with the
   pause/fetch/resume assertions, D6 names that test, and "no change expected" is gone.
6. fidelity, low: an upstream-reported cost does not reach the builtin row. **Accepted.** `convertUsage` emits only
   token counters, and `MessagesTokenUsageParser.build` sets no cost. D3, the proposal and task 3.4 add this
   difference.
7. fidelity, low: the comparison left out the spend read on the HTTP path's outer pass. **Accepted.** `SpendCapCheck.verify`
   with `subjectToLimits = false` calls `readSpendCaps`. "Effect per request" now lists the spend read, the callback's
   check and charge, and the aggregated-cost write, against the builtin path's one check and one charge.
8. fidelity, low: the fallback has more effects than the doc listed. **Accepted.** `verifyAccess` refuses a
   per-request key when `accessibleByPerRequestKey: false`, and `count_tokens` and interceptors are refused only on
   the builtin path. The Migration Plan and task 7.2 list these.
9. fidelity, low: "two spec deltas". **Accepted.** It now says "the spec delta".
10. simplicity, medium: the spend-cap requirement duplicated change 4, and tasks 4.1/4.2 were conditional.
    **Accepted.** Change 4 adds "Reached spend cap unchanged" and `testBuiltinTranslatorKeepsTheSpendCapAnswer` on
    `claude-builtin`. "Day cap reached" is dropped, and the requirement points to change 4 for a reached cap. Old 4.1
    (`gpt-builtin-spend`) is deleted. New 4.1 is one definite test (82% headers on `claude-builtin`), and the later
    tasks are renumbered.
11. simplicity, medium: nothing removed the docs sentence about the known-gaps file. **Accepted.** Change 1's task 5.1
    adds it. Task 7.1 now replaces it with a sentence about the fixtures recorded at `5e70209` and checks with `grep -r
    known-gaps docs/`. The proposal's end-of-series bullet mentions it.
12. simplicity, low: the cache test could not catch the wrong features or the wrong hashing order. **Accepted in
    part.** `autoCachingSupported` moves to `interfaces.openaiResponses.features` only (task 2.1 and the scenario).
    The `(tools, instructions, input)` list is dropped from the requirement. Giving turn 1 a `system` prompt was not
    done, because both turns would carry it and the test would still pass with the wrong order. The order stays
    covered by the interface's fixed list and `UpstreamCacheServiceTest`.
13. simplicity, low: `/limits` reports the caller's account-wide spend, not `m`'s cost. **Accepted.** `getLimitStats`
    reads the global cost record. D2 and task 3.1 now read `GET /v1/deployments/{id}/usage`, which reads `m`'s own
    `costs` record. On a root request no aggregated cost is added, so the two paths compare cleanly.
14. simplicity, low: "none for the Responses request it sends upstream" cannot fail. **Accepted.** The translated
    request goes to the adapter, and every row Core writes carries the client's URI. The clause and task 2.2's
    assertion are removed. "Exactly one row" covers a second row.
