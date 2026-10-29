# Claude Code spend limits on `/anthropic/*`

DIAL enforces day, week and month cost limits per user (`costLimit` on a role). When Claude Code
talks to DIAL through `/anthropic/*`, DIAL reports those limits in the shapes Claude Code reads, so it can
show its built-in notices at 75% and 95% and the dollar amounts in `/usage`.

Two things are reported:

1. Limit headers on `POST /anthropic/v1/messages`, on success and when a limit is reached.
2. `GET /anthropic/api/oauth/usage`.

Both are built from one function that picks the cap to report, so the two answers always agree. Codex CLI gets the
same cap on the Responses API; see [Codex spend limits](codex-spend-limits.md).

## Gateway-mode setup

Point Claude Code at DIAL and authenticate with a DIAL API key:

```bash
export CLAUDE_CODE_USE_GATEWAY=1
export ANTHROPIC_BASE_URL=https://<dial-core-host>/anthropic
export ANTHROPIC_AUTH_TOKEN=<dial-api-key>
```

`/anthropic/api/oauth/usage` uses the same authentication as `/anthropic/v1/messages`.

`CLAUDE_CODE_USE_GATEWAY=1` is required: without it Claude Code ignores the limit headers and never calls the
usage endpoint, so it shows no notices and no amounts.

When Claude Code is started through the `dial` launcher, the launcher sets the base URL and the credential, and
`CLAUDE_CODE_USE_GATEWAY` comes from its configuration. Add this key to the machine configuration the launcher
reads (`C:\ProgramData\dial\config.json` on Windows, `/Library/Application Support/dial/config.json` on macOS):

```json
"claude-code.env.CLAUDE_CODE_USE_GATEWAY": "1"
```

Deploy it together with, or after, a Core version that includes this feature.

## Which cap is reported

A user can have several cost caps (day, week, month). Exactly one is reported:

- while all are allowed: the fullest one (highest used/total);
- once any cap is exceeded (used >= total): among the exceeded caps, the one that resets last.

Unlimited windows (total is 0 or at least 2^53) are skipped. A user with no cap gets no headers, and the
usage endpoint returns `{"limits":[]}`. The per-minute cost limit is never reported as a cap.

A model served in translated mode (`"mode": "translator"` on its `anthropicMessages` interface) gets the same
headers. Its limits are checked and charged by the call the translator makes back to Core, so the outer
request only reads the spend caps. It rejects with the spend-cap 429 below once a cap is already reached,
and if the spend record can't be read it sends no headers instead of failing closed. Token and request limits
for such a model are enforced only by the inner call. When the inner call rejects (a token, request or
per-minute cost limit, or a cap crossed after the outer read), the client gets whatever error the translator
returns, without `anthropic-ratelimit-unified-*` headers.

## Headers on `POST /anthropic/v1/messages`

The upstream's own `anthropic-ratelimit-*` response headers are always removed. For a capped user a 200 response
carries the following, describing the state before this request's cost is added. Streaming responses carry them
in the response headers, before the body.

| Header | Value |
|---|---|
| `anthropic-ratelimit-unified-status` | `allowed`, or `allowed_warning` once past 75% |
| `anthropic-ratelimit-unified-representative-claim` | always `overage` |
| `anthropic-ratelimit-unified-overage-status` | same as `-status` |
| `anthropic-ratelimit-unified-overage-utilization` | used/total, floored to two decimals, at most `0.99` while allowed (e.g. `0.82`) |
| `anthropic-ratelimit-unified-overage-surpassed-threshold` | `0.75` or `0.95` once reached; absent below 75% |
| `anthropic-ratelimit-unified-reset`, `anthropic-ratelimit-unified-overage-reset` | when the reported cap resets, Unix seconds |

Errors returned by the upstream carry no spend headers. `count_tokens` is unchanged.

## 429 responses

### Spend cap reached

Rejected before the request goes upstream:

```
HTTP/1.1 429
retry-after: <seconds until reset>
x-should-retry: false
anthropic-ratelimit-unified-status: rejected
anthropic-ratelimit-unified-reset: <unix seconds>
anthropic-ratelimit-unified-overage-reset: <unix seconds>
anthropic-ratelimit-unified-overage-utilization: 1
anthropic-ratelimit-unified-overage-surpassed-threshold: 1
anthropic-ratelimit-unified-overage-period: daily|weekly|monthly
anthropic-ratelimit-unified-overage-disabled-reason: org_spend_cap_reached

{"type":"error","error":{"type":"billing_error","message":"DIAL spend limit reached (daily; resets 2026-10-24 00:00 UTC)"}}
```

### Spend counter unreadable

If the spend record can't be read for a caller with a cost limit, and no token or request limit rejects the request
(one that does decides the answer as usual), the request fails closed: 429 with `x-should-retry: false` and
`anthropic-ratelimit-unified-overage-disabled-reason: fetch_error`, and no other `anthropic-ratelimit-*` header.
The body is a `billing_error` with the message `DIAL could not read your spend limit; request rejected`.
A cost limit here means any cost window below 2^53, including the per-minute one and a cap of 0, which is enforced but
never reported. A window at or above 2^53 is unlimited for both reporting and this check.

Any other failure of the limit check (reading the token or request record, for example), and an unreadable spend record of
a caller without a cost limit, gives 500 with `x-should-retry: true` and the body
`{"type":"error","error":{"type":"api_error","message":"DIAL could not check your limits; please retry"}}`. Clients retry it.

### Other limits

Per-deployment token and request limits and the per-minute cost limit also reject on this route. None of them
carry `anthropic-ratelimit-unified-*` headers. The body is
`{"type":"error","error":{"type":"rate_limit_error","message":...}}`.

| What fired | Headers | Message |
|---|---|---|
| minute cost, minute tokens, hourly requests (sliding windows) | `retry-after`, `x-should-retry: true` | `DIAL per-minute spend limit reached; retry in N s` / `DIAL per-minute token limit for <model> reached; retry in N s` / `DIAL hourly request limit for <model> reached; retry in N s` |
| day/week/month tokens, daily requests (calendar windows) | `retry-after`, `x-should-retry: false` | `DIAL daily token limit for <model> reached (resets 2026-10-24 00:00 UTC)` |

When several windows fire, the longest `retry-after` decides; a sliding window never says less than 1 second. A reached
spend cap takes precedence over every other limit: the spend record is read first, and a caller over both a token limit
and a spend cap gets the `billing_error`. Such a request is rejected before the token and request records are touched,
so on this route it does not count toward the request limits. Otherwise the limits are checked in the order token,
request, then the per-minute or zero cost limit, and the first one that rejects decides the answer. Every other route
keeps its Azure-format errors and its order (token, request, cost).

A cost cap configured as `0` is treated as unlimited for reporting (no headers, not in `limits`) while DIAL still enforces it,
so such a user gets a `rate_limit_error` 429 naming the spend window rather than `billing_error`.

## `GET /anthropic/api/oauth/usage`

Returns the caller's own caps with `cache-control: no-store`.

```json
{"extra_usage": {"is_enabled": true, "monthly_limit": 1000, "used_credits": 820, "utilization": 82, "currency": "USD"},
 "limits": [{"kind": "spend", "group": "daily", "percent": 82, "resets_at": "2026-10-24T00:00:00.000Z", "severity": "warning", "is_active": true}]}
```

- `extra_usage` describes the reported cap. `monthly_limit` and `used_credits` are cents, whatever the period.
  `utilization` is a whole percent, above 100 once over.
- `limits` has one row per capped window. `group` is `daily`, `weekly` or `monthly`; `severity` is `normal`,
  `warning` (75% and above) or `critical` (95% and above); `is_active` is true only on the reported cap.
- No cap: `{"limits":[]}`. A failure returns 500 with `x-should-retry: true` and the body
  `{"type":"error","error":{"type":"api_error","message":"Failed to get spend limits"}}`.

## Consistency rule

`resets_at` of the `is_active` row is the same instant, to the second, as `anthropic-ratelimit-unified-overage-reset`,
and `extra_usage.utilization` divided by 100 equals the header utilization. Percentages and fractions are floored,
so a displayed value never claims a threshold the ratio has not reached; cents round half up.
