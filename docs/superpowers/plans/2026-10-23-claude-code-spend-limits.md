# Claude Code Spend Limits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Report DIAL's day/week/month spend caps to Claude Code on `/anthropic/*`: `anthropic-ratelimit-unified-*` headers and Anthropic-shaped 429s on `POST /anthropic/v1/messages`, plus `GET /anthropic/api/oauth/usage`, all built from one cap-selection function.

**Architecture:** A `SpendCaps` value (limiter package) is built from the three `CostItemLimitStats` windows and selects the reported cap. `RateLimiter` attaches it to the `RateLimitResult` of the pre-request check (from the cost record it already reads) and exposes it for the usage endpoint (from `getUserStats`). One utility, `AnthropicLimitResponses`, renders both the headers/429s and the usage body. `MessagesController` and a new `AnthropicUsageController` call it.

**Tech Stack:** Java 21, Vert.x 4.5 Futures, Lombok, Jackson, JUnit 5 + Mockito, embedded Redis + `TestWebServer` integration tests.

**Spec:** `docs/superpowers/specs/2026-10-23-claude-code-spend-limits-design.md` (read it first; header names, values and message texts come from there).

## Global Constraints

- Read `docs/CODE_STYLE.md` before writing Java. Checkstyle: Google style, 180-char lines.
- Branch `feature/claude-code-spend-limits` (already created from `origin/development`). Do not push, do not open a PR.
- Never hand-edit `docs/open_api_core.yaml` (CI regenerates it); change annotations and `AnnotationEndpointCollector` only.
- Non-Anthropic routes: `RateLimitResult.throwIfError()` and its Azure-format body stay byte-for-byte unchanged.
- `MessagesCountTokensController` is not modified.
- Unlimited window: `total.signum() <= 0 || total >= 2^53` (`BigDecimal.valueOf(1L << 53)`).
- Exceeded: `used.compareTo(total) >= 0` (same as `CostRateLimit.check`).
- Rounding: fractions/percents floor (`RoundingMode.DOWN`); cents `HALF_UP`.
- Reset in messages: `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)`. `resets_at`: `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A role with a cap of `0` for a window: `SpendCaps` skips it, yet `CostRateLimit.check` rejects (0 >= 0). Expect a `rate_limit_error` 429 with `x-should-retry: false` naming the daily spend limit, not a crash or a billing 429 without a cap (Task 3 test `limitReached_calendarCostWindow`).
2. Upstream answers non-200 (e.g. 400 or 529): upstream `anthropic-ratelimit-*` still stripped, no spend headers added (Task 4 test `testUpstreamErrorIsStrippedWithoutSpendHeaders`).
3. Week and month reset at the same instant: selection is deterministic (longer period wins) (Task 1 test `select_tieOnResetPrefersLongerPeriod`).
4. Boundaries: ratio 0.7499 → `0.74`, no threshold, `allowed`; exactly 0.75 → `allowed_warning`, `0.75` (Task 3 tests).
5. Capped user with no cost record yet: headers present with utilization `0.00`, `allowed` (Task 2 test `testLimit_CostRecordMissing_CarriesZeroSpendCaps`).

---

### Task 1: `SpendCap` / `SpendCaps` — the one cap-selection function

**Files:**
- Create: `server/src/main/java/com/epam/aidial/core/server/limiter/SpendCap.java`
- Create: `server/src/main/java/com/epam/aidial/core/server/limiter/SpendCaps.java`
- Test: `server/src/test/java/com/epam/aidial/core/server/limiter/SpendCapsTest.java`

**Interfaces:**
- Produces:
  - `public record SpendCap(CalendarPeriod period, BigDecimal used, BigDecimal total, long resetsAtMillis)` with `public boolean isExceeded()` and `public BigDecimal ratio()` (`used.divide(total, MathContext.DECIMAL64)`).
  - `public record SpendCaps(List<SpendCap> windows, @Nullable SpendCap reported)`; `windows` in DAY, WEEK, MONTH order, capped windows only.
  - `public static final SpendCaps EMPTY` (`List.of()`, `null`).
  - `public static SpendCaps of(@Nullable CostItemLimitStats day, @Nullable CostItemLimitStats week, @Nullable CostItemLimitStats month)`: skips null and unlimited stats; `resetsAtMillis` = `OffsetDateTime.parse(stats.getResetsAt()).toInstant().toEpochMilli()`.
  - `static @Nullable SpendCap select(List<SpendCap> windows)` (package-private): if any is exceeded, the exceeded one with the latest `resetsAtMillis`; else the one with the highest `ratio()`. Ties go to the longer period.

- [ ] **Step 1: Write failing tests** in `SpendCapsTest`, building `CostItemLimitStats` (total, used, resetsAt as ISO offset string):
  - `select_allAllowed_picksFullest`: day 8.2/10, week 20/100, month 100/1000 → reported DAY.
  - `select_exceeded_picksLatestReset`: day 10/10 (resets tomorrow), week 100/100 (resets in 5 days), month 500/1000 → reported WEEK; `isExceeded()` true.
  - `select_exceededBeatsFuller`: day 9.9/10 allowed, month 1000/1000 exceeded → MONTH.
  - `select_tieOnResetPrefersLongerPeriod`: week and month both exceeded with equal `resetsAt` → MONTH.
  - `of_skipsUnlimitedWindows`: day total `Long.MAX_VALUE`, week total `0`, month total `2^53` → `windows` empty, `reported` null; month total `2^53 - 1` → kept.
  - `of_noCap_isEmpty`: all three null → equals `SpendCaps.EMPTY`.
  - `of_parsesResetInstant`: resetsAt `"2026-10-24T00:00:00+02:00"` → `resetsAtMillis == Instant.parse("2026-10-23T22:00:00Z").toEpochMilli()`.
- [ ] **Step 2: Run** `./gradlew :server:test --tests "com.epam.aidial.core.server.limiter.SpendCapsTest"`. Expected: compilation FAIL.
- [ ] **Step 3: Implement** both records per Interfaces.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat: add spend cap selection for Claude Code limit reporting`.

### Task 2: Carry spend caps and the breached window out of `RateLimiter`

**Files:**
- Modify: `server/src/main/java/com/epam/aidial/core/server/limiter/RateLimitResult.java`
- Create: `server/src/main/java/com/epam/aidial/core/server/limiter/LimitBreach.java`
- Modify: `server/src/main/java/com/epam/aidial/core/server/limiter/TokenRateLimit.java` (`update(long, RateLimitSchedule, Limit)`), `RequestRateLimit.java` (`check`), `CostRateLimit.java` (`check`)
- Modify: `server/src/main/java/com/epam/aidial/core/server/limiter/RateLimiter.java` (`checkCostLimit` ~line 530, new `getSpendCaps`)
- Test: `server/src/test/java/com/epam/aidial/core/server/limiter/CostRateLimitTest.java`, `RateLimiterTest.java`, new `server/src/test/java/com/epam/aidial/core/server/limiter/TokenRateLimitTest.java` and `RequestRateLimitTest.java` (if absent)

**Interfaces:**
- Consumes: `SpendCaps.of(...)`, `SpendCaps.EMPTY` (Task 1).
- Produces:
  - `public record LimitBreach(Kind kind, Window window, long retryAfterSeconds)` with nested `public enum Kind { TOKENS, REQUESTS, COST }` and `public enum Window { MINUTE("per-minute", true), HOUR("hourly", true), DAY("daily", false), WEEK("weekly", false), MONTH("monthly", false) }` exposing `String label()` and `boolean isRolling()`.
  - `RateLimitResult(HttpStatus status, String errorMessage, String displayErrorMessage, long replyAfterSeconds, @Nullable LimitBreach breach, @Nullable SpendCaps spendCaps)`. Keep the existing 4-arg constructor (delegates with `null, null`) so every other call site compiles unchanged. Add `public RateLimitResult withSpendCaps(SpendCaps spendCaps)`. `SUCCESS` and `throwIfError()` unchanged.
  - Each 429 built by `TokenRateLimit.update`, `RequestRateLimit.check`, `CostRateLimit.check` carries a `LimitBreach`: among the fired windows, the one with the largest per-window retry-after; on a tie, the longer window. Its `retryAfterSeconds` equals the result's `replyAfterSeconds`. Messages and retry-after values stay unchanged.
  - `RateLimiter.checkCostLimit` always returns a result carrying `SpendCaps`: build `LimitStats` via the existing `create(DEFAULT_LIMIT, costLimit, timestamp, schedule)`, apply `rateLimit.update(timestamp, schedule, stats)` when the record exists, then `SpendCaps.of(stats.getDayCostStats(), stats.getWeekCostStats(), stats.getMonthCostStats())`. Result = (record missing ? `SUCCESS` : `rateLimit.check(...)`).`withSpendCaps(caps)`.
  - `public Future<SpendCaps> getSpendCaps(ProxyContext context)`: `SpendCaps.EMPTY` when `resourceService == null`; otherwise `getUserStats(context, List.of(), true).map(stats -> SpendCaps.of(stats.getDayCostStats(), stats.getWeekCostStats(), stats.getMonthCostStats()))`.

- [ ] **Step 1: Write failing tests**
  - `CostRateLimitTest.check_minuteOnly_breachIsMinuteCost`: minute cap hit only → `breach().kind() == COST`, `window() == MINUTE`.
  - `CostRateLimitTest.check_dayAndMinute_breachIsDay`: both fire → `window() == DAY`, `breach().retryAfterSeconds() == result.replyAfterSeconds()`.
  - `TokenRateLimitTest.update_minuteOnly_breachIsMinuteTokens` and `update_dayFires_breachIsDayTokens`.
  - `RequestRateLimitTest.check_hourOnly_breachIsHourRequests` and `check_dayFires_breachIsDayRequests`.
  - `RateLimiterTest.testLimit_CostRecordAtEightyTwoPercent_CarriesSpendCaps`: role cost limit day `10`; seed a `costs` record with 8.2 → `result.status() == OK`, `spendCaps().reported().period() == DAY`, `ratio()` 0.82.
  - `RateLimiterTest.testLimit_CostRecordMissing_CarriesZeroSpendCaps`: cap configured, no record → `OK`, `reported().used()` zero.
  - `RateLimiterTest.testLimit_CostExceeded_CarriesExceededSpendCaps`: seed 10 of day 10 → 429, `reported().isExceeded()`.
  - `RateLimiterTest.testGetSpendCaps_MatchesCheckPath`: same seed; `getSpendCaps(context)` reported equals the one from `limit(...)` (same period, used, total, `resetsAtMillis`).
  - `RateLimiterTest.testGetSpendCaps_NoCap_IsEmpty`.
  Follow the existing `RateLimiterTest` setup (embedded Redis, `taskExecutor.submit` answer stubs, `costLimit("100")` helper).
- [ ] **Step 2: Run** `./gradlew :server:test --tests "com.epam.aidial.core.server.limiter.*"`. Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** the same command. Expected: PASS (all limiter tests, old and new).
- [ ] **Step 5: Commit** `feat: carry spend caps and breached window in rate limit results`.

### Task 3: `AnthropicLimitResponses` — headers, 429s and usage body

**Files:**
- Create: `server/src/main/java/com/epam/aidial/core/server/controller/anthropic/AnthropicLimitResponses.java` (`@UtilityClass`, package-private)
- Create: `server/src/main/java/com/epam/aidial/core/server/data/AnthropicUsageData.java` (Lombok `@Data`, `@JsonProperty` snake_case names; nested `ExtraUsage` and `UsageLimit`; `extra_usage` is `@JsonInclude(NON_NULL)`)
- Test: `server/src/test/java/com/epam/aidial/core/server/controller/anthropic/AnthropicLimitResponsesTest.java`

**Interfaces:**
- Consumes: `SpendCap`, `SpendCaps` (Task 1); `LimitBreach` (Task 2).
- Produces:
  - `void stripUpstreamRateLimitHeaders(MultiMap headers)`: removes every name starting with `anthropic-ratelimit-`, case-insensitively.
  - `void putSpendHeaders(MultiMap headers, SpendCap cap)`: the allowed-state header table from the spec. Utilization = `ratio().setScale(2, DOWN)` capped at `0.99`, as `toPlainString()`. Threshold `0.95` if ratio >= 0.95, `0.75` if >= 0.75, else the header is absent. Status `allowed_warning` when a threshold is present, else `allowed`. Both reset headers = `resetsAtMillis / 1000`.
  - `HttpException spendCapReached(SpendCap cap, long nowMillis)`: 429; headers exactly as in the spec's 429 block plus `content-type: application/json`. `retry-after` = ceiling of `(resetsAtMillis - nowMillis) / 1000`. `overage-period` = `daily|weekly|monthly`. Body `{"type":"error","error":{"type":"billing_error","message":"DIAL spend limit reached (<period>; resets <yyyy-MM-dd HH:mm> UTC)"}}`.
  - `HttpException spendUnavailable()`: 429; headers `x-should-retry: false`, `anthropic-ratelimit-unified-overage-disabled-reason: fetch_error`, `content-type: application/json`. Body `billing_error` with message `DIAL could not read your spend limit; request rejected`.
  - `HttpException limitReached(LimitBreach breach, String deploymentName, long nowMillis)`: 429; `retry-after` = `breach.retryAfterSeconds()`; `x-should-retry` = `breach.window().isRolling()`; `content-type: application/json`; no `anthropic-ratelimit-*`. Body `rate_limit_error`; message by kind:
    - COST: `DIAL <label> spend limit reached`
    - TOKENS: `DIAL <label> token limit for <deploymentName> reached`
    - REQUESTS: `DIAL <label> request limit for <deploymentName> reached`
    - then `; retry in <N> s` if rolling, else ` (resets <now + retryAfter, formatted> UTC)`.
  - `AnthropicUsageData buildUsage(SpendCaps caps)`: `limits` has one row per `caps.windows()`:
    - `kind` `spend`, `group` daily/weekly/monthly;
    - `percent` = floor(ratio × 100); `severity` `critical` >= 95, `warning` >= 75, else `normal`;
    - `resets_at` per Global Constraints; `is_active` only for `caps.reported()`.
    `extra_usage` (only when reported is non-null): `is_enabled` true, `monthly_limit` = total cents, `used_credits` = used cents (long, ×100 HALF_UP), `utilization` = the reported row's percent, `currency` `USD`.
  - Build JSON bodies with `ProxyUtil.MAPPER` (ObjectNode), not string concatenation.

- [ ] **Step 1: Write failing tests** (all call the production methods; use a `MultiMap.caseInsensitiveMultiMap()` and parse bodies with `ProxyUtil.MAPPER`):
  - `putSpendHeaders_belowThreshold`: ratio 0.50 → status `allowed`, claim `overage`, overage-status `allowed`, utilization `0.50`, threshold absent, both resets = epoch seconds.
  - `putSpendHeaders_justBelowThreshold`: 7.499/10 → `0.74`, no threshold, `allowed`.
  - `putSpendHeaders_atSeventyFive`: 7.5/10 → `0.75`, threshold `0.75`, `allowed_warning`.
  - `putSpendHeaders_eightyTwo`: → `0.82`, threshold `0.75`.
  - `putSpendHeaders_ninetySix`: → `0.96`, threshold `0.95`.
  - `putSpendHeaders_capsAtNinetyNine`: 9.997/10 → `0.99`.
  - `stripUpstreamRateLimitHeaders_removesOnlyRateLimitHeaders`: removes `Anthropic-RateLimit-Tokens-Remaining` and `anthropic-ratelimit-unified-status`, keeps `content-type` and `request-id`.
  - `spendCapReached_exactShape`: day cap 10/10, resets 2026-10-24T00:00Z, now 2026-10-23T20:00:00.500Z → status 429; `retry-after` `14400`; each spec header with its exact value; `anthropic-ratelimit-unified-representative-claim` and `-overage-status` absent; body equals `{"type":"error","error":{"type":"billing_error","message":"DIAL spend limit reached (daily; resets 2026-10-24 00:00 UTC)"}}`.
  - `spendUnavailable_exactShape`: exactly the three headers listed, no other `anthropic-ratelimit-*`, body type `billing_error`.
  - `limitReached_rollingTokens`: TOKENS/MINUTE/4 → `x-should-retry: true`, `retry-after: 4`, message `DIAL per-minute token limit for claude-sonnet reached; retry in 4 s`, no `anthropic-ratelimit-*`.
  - `limitReached_rollingCost`: COST/MINUTE/4 → `DIAL per-minute spend limit reached; retry in 4 s`.
  - `limitReached_calendarRequests`: REQUESTS/DAY, now 2026-10-23T20:00Z, retry 14400 → `x-should-retry: false`, `DIAL daily request limit for claude-sonnet reached (resets 2026-10-24 00:00 UTC)`.
  - `limitReached_calendarCostWindow` (Review Focus 1): COST/DAY → `DIAL daily spend limit reached (resets …)`, `x-should-retry: false`.
  - `buildUsage_centsPercentsAndActiveRow`: day 8.2/10 and month 271.40/500 (day reported) →
    - `extra_usage`: `monthly_limit` 1000, `used_credits` 820, `utilization` 82, `currency` USD;
    - rows: daily 82 `warning` active; monthly 54 `normal` inactive;
    - `resets_at` like `2026-10-24T00:00:00.000Z`.
  - `buildUsage_severities`: 96% → `critical`; 74.9% → `normal`, percent 74.
  - `buildUsage_overLimitUtilizationAbove100`: 10.5/10 → utilization 105.
  - `buildUsage_noCap`: `SpendCaps.EMPTY` serializes exactly to `{"limits":[]}`.
  - `headersAndUsageAgree`: for one `SpendCaps`, `Instant.parse(activeRow.resets_at).getEpochSecond()` equals the `anthropic-ratelimit-unified-overage-reset` header, and the active row's `group` is the reported period.
- [ ] **Step 2: Run** `./gradlew :server:test --tests "com.epam.aidial.core.server.controller.anthropic.AnthropicLimitResponsesTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat: render DIAL spend caps as Anthropic limit headers and usage body`.

### Task 4: Wire limits into `POST /anthropic/v1/messages`

**Files:**
- Modify: `server/src/main/java/com/epam/aidial/core/server/controller/anthropic/MessagesController.java` (`verifyLimit`, `processResponse`, `handleNonStreamingResponse`, `@ApiOperation` responses)
- Create: `server/src/test/resources/dial-config/anthropic-spend-limits.json`
- Create: `server/src/test/java/com/epam/aidial/core/server/MessagesSpendLimitApiTest.java` (extends `ResourceBaseTest`; every test is annotated `@DialConfigLocation("dial-config/anthropic-spend-limits.json")`)
- Modify: `server/src/test/java/com/epam/aidial/core/server/MessagesApiTest.java` (`testRateLimitReturns429`)

**Interfaces:**
- Consumes: `RateLimitResult.spendCaps()/breach()` (Task 2); `AnthropicLimitResponses.*` (Task 3).
- Produces: none for later tasks besides the test config file and the seeding helper (reused in Task 5).

Behaviour:
- `verifyLimit()`:
  - `checkLimits(...)` failure → log a warning, then fail with `spendUnavailable()`. Apply `recover` to `checkLimits` only, before `map`.
  - Otherwise, in order:
    1. status 429 and `spendCaps` reported is exceeded → `spendCapReached(reported, now)`;
    2. status 429 with a `breach` → `limitReached(breach, context.getDeployment().getName(), now)`;
    3. else `throwIfError()` (keeps 403 etc.).
  - On success, keep `spendCaps` in a field.
- After `ProxyUtil.copyResponse` (non-streaming) and `ProxyUtil.handleChunkedResponse` (streaming), before any body write: `stripUpstreamRateLimitHeaders(response.headers())`. Then, if `proxyResponse.statusCode() == 200` and the stored caps have a reported cap, `putSpendHeaders(...)`.
- Document the new 200 headers (`@ApiHeader`) and the 429 description on the existing `@ApiOperation`.

Test config `anthropic-spend-limits.json`:
- Models (`"type": "chat"`, `anthropicMessages.base_url` `http://localhost:4848`): `claude-spend`, and `claude-burst` (token limit `{"minute": "10"}` only).
- A chat model `gpt-spend` (`"endpoint": "http://localhost:4848/openai/deployments/gpt-spend/chat/completions"`).
- Keys: `proxyKey1` → project `spend`, role `capped`; `proxyKey2` → project `free`, role `uncapped`.
- Roles:
  - `capped`: limits `{}` for the three models, `costLimit` `{"day": "10", "week": "100", "month": "1000"}`;
  - `uncapped`: limits `{}` for `claude-spend`.

Seeding helper in the test:
- `seedSpend(String project, String dollars)` writes a `CostRateLimit` with `add(System.currentTimeMillis(), new RateLimitSchedule(), new BigDecimal(dollars))` through `dial.getProxy().getResourceService().computeResource(...)`.
- The descriptor comes from `ResourceDescriptorFactory.fromEntityPath(ResourceTypes.LIMIT, "Keys/" + project + "/", "Keys/" + project + "/", "costs")`.
- Expected reset: `CalendarWindowCalculator.nextPeriodStart(CalendarPeriod.DAY, System.currentTimeMillis(), new RateLimitSchedule()) / 1000`.

- [ ] **Step 1: Write failing tests** in `MessagesSpendLimitApiTest`:
  - `testNonStreamingAtEightyTwoPercent`: seed 8.2. The upstream returns 200 with `anthropic-ratelimit-unified-status: allowed` and `anthropic-ratelimit-tokens-remaining: 5`. The response carries:
    - `allowed_warning`, claim `overage`, overage-status `allowed_warning`;
    - utilization `0.82`, threshold `0.75`, both resets = expected;
    - no `anthropic-ratelimit-tokens-remaining`.
  - `testStreamingAtEightyTwoPercent`: same headers on an SSE response (reuse the `anthropic-messages-sse.txt` fixture).
  - `testBelowThresholdHasNoSurpassedThreshold`: seed 5 → `allowed`, `0.50`, no threshold header.
  - `testUpstreamErrorIsStrippedWithoutSpendHeaders` (Review Focus 2): the upstream returns 400 with `anthropic-ratelimit-unified-status: allowed` → 400 forwarded, no `anthropic-ratelimit-*` at all.
  - `testSpendCapReachedRejectsBeforeUpstream`: seed 10. Expect:
    - 429 with the spend headers and body shape from Task 3;
    - `representative-claim` and `overage-status` absent;
    - the upstream handler was never invoked (`AtomicInteger`).
  - `testUncappedUserGetsNoHeaders`: `proxyKey2` → 200, no `anthropic-ratelimit-unified-*`.
  - `testRollingTokenLimitAsksClientToRetry`: `claude-burst`, the first call charges 20 tokens. After a 1 s sleep, the second call returns:
    - 429 with `x-should-retry: true` and `retry-after` between 1 and 60;
    - body type `rate_limit_error`, message starting `DIAL per-minute token limit for claude-burst reached; retry in`.
  - `testCountTokensUnchanged`: seed 10; count_tokens returns 200. The upstream's `anthropic-ratelimit-tokens-remaining: 5` passes through, and no `anthropic-ratelimit-unified-*` header is added.
  - `testChatCompletionsKeepsAzureFormat`: seed 10; `POST /openai/deployments/gpt-spend/chat/completions` returns 429. The body JSON has `error.code == "429"` and no `type` field, and no `anthropic-ratelimit-*` or `x-should-retry` header.
  - In `MessagesApiTest.testRateLimitReturns429`, additionally assert:
    - `x-should-retry: false`;
    - body `error.type == "rate_limit_error"`;
    - message contains `daily token limit for claude-limited`.
- [ ] **Step 2: Run** `./gradlew :server:test --tests "com.epam.aidial.core.server.MessagesSpendLimitApiTest" --tests "com.epam.aidial.core.server.MessagesApiTest"`. Expected: new assertions FAIL.
- [ ] **Step 3: Implement** the controller changes and add the config file.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat: report DIAL spend caps on /anthropic/v1/messages`.

### Task 5: `GET /anthropic/api/oauth/usage`

**Files:**
- Create: `server/src/main/java/com/epam/aidial/core/server/controller/anthropic/AnthropicUsageController.java`
- Modify: `server/src/main/java/com/epam/aidial/core/server/data/RouteTemplate.java` (new `ANTHROPIC_OAUTH_USAGE("^/+anthropic/api/oauth/usage$", "/anthropic/api/oauth/usage")` next to the other Anthropic routes)
- Modify: `server/src/main/java/com/epam/aidial/core/server/controller/ControllerSelector.java` (`get(RouteTemplate.ANTHROPIC_OAUTH_USAGE, ...)` beside `LLM_ANTHROPIC_MODELS`)
- Modify: `openapi-generator/src/main/java/com/epam/aidial/core/openapi/AnnotationEndpointCollector.java` (add `AnthropicUsageController.class`)
- Create: `docs/anthropic-spend-limits.md`; Modify: `docs/error_codes.md` (429 section: one paragraph + link)
- Test: `server/src/test/java/com/epam/aidial/core/server/MessagesSpendLimitApiTest.java`

**Interfaces:**
- Consumes: `RateLimiter.getSpendCaps(ProxyContext)` (Task 2); `AnthropicLimitResponses.buildUsage(SpendCaps)` (Task 3); test config + `seedSpend` (Task 4).
- Produces: `public AnthropicUsageController(Proxy proxy, ProxyContext context)`; `public Future<?> handle()`.
  - Success: put `cache-control: no-store`, then `context.respond(HttpStatus.OK, buildUsage(caps))`.
  - Failure: log error, respond 500 `Failed to get spend limits`.
  - Annotate with `@ApiOperation` (GET, tags `Anthropic`, operationId `getAnthropicUsage`, 200 body `AnthropicUsageData.class`, 401, 500), like `LimitController.getUserUsage`.

- [ ] **Step 1: Write failing tests** in `MessagesSpendLimitApiTest`:
  - `testUsageEndpointAtEightyTwoPercent`: seed 8.2; GET with `Authorization: Bearer proxyKey1` (gateway-mode auth). Expect:
    - 200 with `cache-control: no-store`;
    - `extra_usage` = {is_enabled true, monthly_limit 1000, used_credits 820, utilization 82, currency USD};
    - three rows: daily 82 warning active, weekly 8 normal inactive, monthly 0 normal inactive.
  - `testUsageEndpointAgreesWithMessagesHeaders`: seed 8.2; one `/anthropic/v1/messages` call and one usage call. Expect:
    - the `is_active` row's `resets_at` epoch seconds == `anthropic-ratelimit-unified-overage-reset`;
    - the row's `group` is `daily`, matching the reported cap;
    - `extra_usage.utilization` / 100 == the header utilization (0.82).
  - `testUsageEndpointNoCap`: `proxyKey2` → body exactly `{"limits":[]}`.
  - `testUsageEndpointRequiresAuth`: no credentials → 401.
- [ ] **Step 2: Run** `./gradlew :server:test --tests "com.epam.aidial.core.server.MessagesSpendLimitApiTest"`. Expected: new tests FAIL (404).
- [ ] **Step 3: Implement** the controller, route, selector entry and collector entry. Write `docs/anthropic-spend-limits.md`: gateway-mode setup, header table, both 429 shapes, other-limit 429s, the usage body, and the consistency rule.
- [ ] **Step 4: Run** the same command plus `./gradlew :server:test --tests "com.epam.aidial.core.server.data.RouteTemplateTest" --tests "com.epam.aidial.core.server.controller.ControllerSelectorTest"`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: add /anthropic/api/oauth/usage for Claude Code`.

### Task 6: Full verification

- [ ] **Step 1:** `./gradlew checkstyleMain checkstyleTest`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 2:** `./gradlew :server:test :openapi-generator:test`. Expected: BUILD SUCCESSFUL. Investigate any failure; don't skip it.
- [ ] **Step 3:** `./gradlew :openapi-generator:mergeOpenApiSpec`. Expected: success, and the generated `openapi-generator/build/generated/openapi-merged.yaml` contains `/anthropic/api/oauth/usage`. Do not copy it over `docs/open_api_core.yaml`.
- [ ] **Step 4 (optional manual check):** if `GPR_*` credentials and an upstream are available, run Core locally and Claude Code in gateway mode as described in the spec. Otherwise report that it was skipped.
- [ ] **Step 5:** `git status` clean except the pre-existing untracked `aidial.config.json` / `local.settings.json`. Commit any fixes.
