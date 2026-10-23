# Claude Code spend limits on `/anthropic/*` — design

Status: approved in chat on 2026-10-23. Implementation plan:
`docs/superpowers/plans/2026-10-23-claude-code-spend-limits.md`.

## Intent

When Claude Code talks to DIAL through `/anthropic/*` in gateway mode
(`CLAUDE_CODE_USE_GATEWAY=1` + `ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN`), it reads per-user
spend-limit headers from every successful `/v1/messages` response and shows its built-in notice past
75% and 95%, and it calls `GET {base}/api/oauth/usage` to show dollar amounts in `/usage`. DIAL already
enforces day/week/month cost limits in `RateLimiter`; this work only reports them in the shapes Claude
Code expects:

1. Limit headers on `POST /anthropic/v1/messages` (success and over-limit).
2. A new endpoint `GET /anthropic/api/oauth/usage`.

Both are built from ONE function that picks the cap to report, so the two answers always agree.

## Which cap to report

A user can have several cost caps (day, week, month). Report exactly one:
- while all are allowed: the fullest one (highest used/total);
- once any cap is exceeded (used >= total, the same test `CostRateLimit.check` applies): among the
  exceeded caps, the one that resets last.

Skip unlimited windows (`total` is 0 or >= 2^53; DIAL's unlimited sentinel is `Long.MAX_VALUE`). A user
with no cap gets no headers, and the usage endpoint returns `{"limits": []}`. The per-minute cost cap is
never a reported cap (sliding window, no single reset instant).

## Part 1. Headers on `POST /anthropic/v1/messages`

Always strip the upstream's own `anthropic-ratelimit-*` response headers first. Then, for a capped user,
add:

| Header | Value |
|---|---|
| `anthropic-ratelimit-unified-status` | `allowed`, or `allowed_warning` once past a threshold |
| `anthropic-ratelimit-unified-representative-claim` | `overage` (never `5h`/`7d`) |
| `anthropic-ratelimit-unified-overage-status` | same value as `-status` |
| `anthropic-ratelimit-unified-overage-utilization` | used/total as a fraction with two decimals, kept below `1` while allowed (e.g. `0.82`; cap at `0.99`) |
| `anthropic-ratelimit-unified-overage-surpassed-threshold` | `0.75` or `0.95` once utilization passes it; omit below 75% |
| `anthropic-ratelimit-unified-reset`, `anthropic-ratelimit-unified-overage-reset` | when the reported cap resets, Unix seconds |

Values are the state at request time (before this request's cost is added). Streaming responses carry
them in the response headers, before the body.

When the reported cap is reached, reject before proxying upstream:

```
HTTP/1.1 429
retry-after: <seconds until reset, integer>
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

Do NOT send `representative-claim` or `overage-status` on the 429. If the counter can't be read (the
limit check fails), fail closed: 429 with `x-should-retry: false`,
`anthropic-ratelimit-unified-overage-disabled-reason: fetch_error` and a message, nothing else.

### Other limit rejections on the Anthropic route (agreed extension)

Per-deployment token/request limits and the per-minute cost cap can also reject. None of these get
`anthropic-ratelimit-unified-*` headers. Body: `{"type":"error","error":{"type":"rate_limit_error","message":...}}`.

| What fired | Waits | Headers | Message |
|---|---|---|---|
| minute cost, minute tokens, hourly requests (sliding windows) | seconds/minutes | `retry-after`, `x-should-retry: true` | `DIAL per-minute spend limit reached; retry in 4 s` / `DIAL per-minute token limit for <model> reached; retry in N s` / `DIAL hourly request limit for <model> reached; retry in N s` |
| day/week/month tokens, daily requests (calendar windows) | hours/days | `retry-after`, `x-should-retry: false` | `DIAL daily token limit for <model> reached (resets 2026-10-24 00:00 UTC)` |

When several windows fire, the one with the longest `retry-after` decides (that's what `retry-after`
already reports). Check order stays token → request → cost; the first failing check decides.

Every non-Anthropic route keeps its existing behaviour and Azure-format errors. `count_tokens` is
unchanged: no limit check, no headers added, upstream headers passed through as today.

## Part 2. `GET /anthropic/api/oauth/usage`

Same authentication as `/anthropic/v1/messages`. Returns the caller's own caps only. Response header
`cache-control: no-store`. Body:

```json
{"extra_usage": {"is_enabled": true, "monthly_limit": 50000, "used_credits": 27140, "utilization": 54, "currency": "USD"},
 "limits": [{"kind": "spend", "group": "monthly", "percent": 54, "resets_at": "2026-11-01T00:00:00.000Z", "severity": "normal", "is_active": true}]}
```

- `extra_usage` describes the reported cap. `monthly_limit` and `used_credits` are cents as numbers
  (DIAL stores dollars, so ×100), whatever the period. `utilization` is a whole percent (above 100 once over).
- `limits`: one row per capped window, `group` = `daily`/`weekly`/`monthly`, `percent` whole percent,
  `resets_at` ISO 8601 UTC with milliseconds, `severity` = `normal` / `warning` (>= 75) / `critical`
  (>= 95), `is_active: true` only on the reported cap's row.
- No cap: `{"limits": []}`.

## Consistency

`resets_at` of the `is_active` row must be the same instant (to the second) and the same cap as
`anthropic-ratelimit-unified-overage-reset`. One function returns the reported cap (period, used, total,
reset instant); headers and usage body are both built from it. A test asserts they agree.

## Calls made by Claude (check these)

- Headers are added only when the upstream answered 200; `anthropic-ratelimit-*` from the upstream is
  stripped on every forwarded response.
- Percent/fraction rounding is floor (0.749 → `0.74`, no threshold), so the number shown never claims
  a threshold the ratio hasn't reached. Cents round half-up.
- Spend-429 `retry-after` = ceiling of seconds until the reset instant.
- The fetch-error 429 body uses `billing_error` with message
  `DIAL could not read your spend limit; request rejected`.
- When Redis isn't configured (limits disabled), no headers and `{"limits": []}`.
- Deployments exempt from limits (translated mode) get no headers.

## Out of scope

Device flow / OAuth endpoints, `/managed/settings`, telemetry endpoints, `/v1/models`, Codex `x-codex-*`
headers, any change to the `dial` utility or proxy.
