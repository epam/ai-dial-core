# Design

## Context

See proposal.md for the motivation and specs/spend-limit-reporting/spec.md for the behaviour.

Today `ResponsesController.java:verifyLimit` calls `BaseDeploymentPostController.java:checkLimits` and turns any
non-OK result into the DIAL (Azure-format) error through `RateLimitResult.java:throwIfError`. The answer path
(`ResponsesController.java:handleProxyResponse` for streaming, `handleNonStreamingResponse` otherwise) copies the
upstream headers with `ProxyUtil.handleChunkedResponse` / `ProxyUtil.copyResponse` and adds `X-UPSTREAM-ATTEMPTS`.

The `/anthropic` work (#2166) already built everything this change needs on the limit side:

- `RateLimiter.java:limitWithSpendCaps` returns the limit result together with the caller's `SpendCaps`, checks a
  reached cap first, and fails with `SpendReadException` when the spend record is unreadable.
- `RateLimiter.java:readSpendCaps` reads the caps without checking or charging, for translated deployments.
- `RateLimiter.java:hasCostLimit` decides whether an unreadable record fails closed.
- `SpendCaps.java:of` keeps the capped windows (DAY, WEEK, MONTH order) and `SpendCaps.java:select` picks the one to
  report (an exceeded window that resets last, else the fullest; ties to the longer period).
- `MessagesController.java:verifyLimit`, `readTranslatedSpendCaps`, `rejectIfSpendCapReached` and `putLimitHeaders`
  are the control flow to mirror.

How Codex reads all this (openai/codex, `codex-rs/codex-api`):

- `rate_limits.rs:parse_rate_limit_for_limit` reads `x-codex-{primary,secondary}-{used-percent,window-minutes,reset-at}`
  from any response. `used-percent` is parsed as a float, and each window is optional.
- `api_bridge.rs` maps a 429 whose body has `error.type == "usage_limit_reached"` to `UsageLimitReached`, with
  `resets_at` and `limit_window_minutes` taken from the body. That error is not retried, and with no ChatGPT plan type
  it prints "You've hit your usage limit. Try again at <time>." Codex retries any other 429 and then reports a
  retry-limit error.

## Goals / Non-Goals

**Goals:**

- The primary window Codex shows is the cap `/anthropic` reports, so a user who runs both clients sees the same
  limit.
- No extra Redis or blob round trip per request.

**Non-Goals:**

- A shared abstraction over the Anthropic and Codex header formats. The two formats share no names or value shapes.
  Only the cap selection is shared, and that already lives in `SpendCaps`.
- Changing `MessagesController` or `AnthropicLimitResponses`.

## Decisions

1. **A new `CodexLimitResponses` utility next to `ResponsesController`**, in `controller/`, shaped like
   `AnthropicLimitResponses`: `stripUpstreamCodexHeaders(MultiMap)`, `putSpendHeaders(MultiMap, SpendCaps)`,
   `spendCapReached(SpendCap, long nowMillis)` and `spendUnavailable()`. Alternative: generalise
   `AnthropicLimitResponses` behind a format parameter. Rejected because the header names, value scales (0–1 against
   0–100) and error bodies all differ, so a shared class would only switch on the format everywhere.

2. **Primary = `SpendCaps.reported()`, secondary = the fullest of the other capped windows** (ties to the longer
   period, as `SpendCaps.select` does). The selection lives as one static method on `SpendCaps`, for example
   `secondary()`, so it can be tested next to `select`. Alternative: primary and secondary as the two fullest windows.
   Rejected because when a day cap is exceeded and a month cap is fuller, the primary would disagree with the 429 and
   with `/anthropic`.

3. **`used-percent` is a whole percent, rounded down**, by the same arithmetic as
   `AnthropicLimitResponses.java:percent`. Codex accepts a float, but its notices only show whole percents. On a 200
   every reported window is below 100%, because a reached cap would have rejected the request.

4. **The reached-cap 429 body extends DIAL's error body.** It keeps `error.message` and `error.code: "429"`, and adds
   `type`, `resets_at` and `limit_window_minutes`, so DIAL clients that read `message` still work. It also keeps
   `display_message`, the user-facing text the limiter wrote before this change ("You've exceeded your daily cost
   limit", naming every exceeded window). A translated deployment has no limiter result, so its message is built for
   the reported cap alone. This was added after the PR review. The message
   follows `AnthropicLimitResponses.java:spendCapReached`: "DIAL day spend limit reached (resets 2026-10-28 00:00 UTC)".
   Codex does not show this message (it shows its own text with the reset time), but logs and other clients do. The
   body is a small Jackson object or `ObjectNode`, because `ErrorDetailsData` has no `resets_at` and should not get
   one for a single route.

5. **Unreadable spend record: 429 with a DIAL error body, no `retry-after`**, mirroring
   `AnthropicLimitResponses.java:spendUnavailable` and `MessagesController.java:verifyLimit`. Any other failure of the
   limit check keeps today's 500 from `ResponsesController.java:handleRequestError`. Alternative: `usage_limit_reached`.
   Rejected because Codex would tell the user they hit their limit, which is untrue. Consequence: Codex retries this
   429 a few times and then shows a retry-limit error (see Risks).

6. **Token, request and per-minute cost limits keep `RateLimitResult.throwIfError`**, unchanged. Their windows are
   rolling, so Codex retrying them is correct, and their body is what DIAL clients get today.

7. **Upstream `x-codex-*` headers are stripped on every status**, right after the upstream headers are copied and
   before Core adds its own. This is a case-insensitive prefix match, as in
   `AnthropicLimitResponses.java:stripUpstreamRateLimitHeaders`.

8. **Translated deployments** (`BaseDeploymentPostController.java:isSubjectToLimits` false) use
   `RateLimiter.readSpendCaps` and fail open, as `MessagesController.java:readTranslatedSpendCaps` does. The inner call
   to Core enforces the limits.

9. **No `isOriginalRequest()` gate**: an interceptor's or application's call to a model gets the headers and the 429
   like a client's own request, as on `/anthropic` (`MessagesController.java:verifyLimit` has no such gate). The limit
   check already uses the initiating user's cost record (`RateLimiter.java:getResourceDescription`). This is the user's
   decision. Alternative: send them only on the client's own request. Rejected because Codex users behind an
   interceptor, including a global one, would then get no warnings at all, and a reached cap would show as a retried
   generic 429. The cost is that the interceptor or application in the middle sees the user's spend percentage and
   reset time. Whether Codex sees the headers depends on the interceptor relaying them, and Core does not add any to
   its answer for the outer request (see Risks).

### Cost per request

- Redis: unchanged. `limitWithSpendCaps` builds the caps from the cost record that the limit check already reads
  (`RateLimiter.java:checkLimitWithSpendCaps`). On translated deployments `readSpendCaps` replaces today's no-op check
  with one read of the cost record, the same as `/anthropic`.
- Body: no extra copy or parse. Headers are set before the body is piped (streaming) or before the rewritten body is
  written (non-streaming).
- Core passes: none added.
- Measured by a test that counts the cost-record reads per Responses request before and after the change (task 3.3).

## Risks / Trade-offs

- [Codex changes its header or error contract] → The parsing points are cited above. The integration tests pin the
  wire format, so a later Codex change is a docs and test update, not a silent break.
- [Codex retries the unreadable-spend 429 and then shows a generic retry-limit error] → This happens only when the
  cost record alone cannot be read or parsed (`RateLimiter.java:checkLimitWithSpendCaps` wraps only that read in
  `SpendReadException`). In a full Redis outage the token and request checks that follow throw too, and the caller
  gets today's 500. The integration test therefore breaks only the cost record, for example by writing an unparsable
  value. The limit check rejects each retry before any upstream call.
- [A client other than Codex that treats any 429 `error.type` as fatal now stops on a reached spend cap] → That is
  correct behaviour: the cap does not reset until `resets_at`.
- [Behind an interceptor, Codex sees the headers only if the interceptor relays the inner answer's headers and 429
  body] → Documented in `docs/codex-spend-limits.md`. Relaying is up to the interceptor.
- [An interceptor or application sees the user's spend percentage and reset time] → Accepted, as on `/anthropic`.

## Migration Plan

None. The headers and the new 429 body are additive. A rollback removes them.
