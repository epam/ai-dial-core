# Codex spend limits on `/openai/v1/responses`

DIAL enforces day, week and month cost limits per user (`costLimit` on a role). When Codex CLI talks to DIAL
through the Responses API, DIAL reports those limits in the `x-codex-*` headers and the `usage_limit_reached` error
that Codex reads. Codex then shows its built-in limit notices:

- a warning after an answer, such as "Heads up, you have less than 25% of your daily limit left";
- an indicator above the input, such as "⚠ monthly limit: 10% left";
- once a cap is reached, "You've hit your usage limit. Try again at <time>.", and it does not retry.

Both `POST /openai/v1/responses` and `POST /openai/responses` do this. Other OpenAI routes are unchanged: Codex no
longer calls Chat Completions. The same caps are reported to Claude Code on `/anthropic/*`; see
[Claude Code spend limits](anthropic-spend-limits.md).

## Codex setup

Point a Codex model provider at DIAL's OpenAI base URL, with the Responses wire API and a DIAL API key:

```toml
model_provider = "dial"

[model_providers.dial]
name = "DIAL"
base_url = "https://<dial-core-host>/openai/v1"
env_key = "DIAL_API_KEY"
wire_api = "responses"
```

Codex sends the key as `Authorization: Bearer <key>`, which DIAL accepts. No other setting is needed: Codex reads the
headers from any provider.

Codex's `/status` screen and its status-line limit items stay empty ("Limits: data not available yet"). Since Codex
0.154.0 they read only ChatGPT's own usage endpoint, which Codex calls only for a ChatGPT sign-in
([openai/codex#45052](https://github.com/openai/codex/issues/45052)). The warning and the indicator do work. The
Codex desktop app receives the headers but shows nothing.

## Which caps are reported

A user can have several cost caps (day, week, month). Codex shows two windows, primary and secondary:

- **Primary** is the cap Claude Code is told about. While all caps are allowed, it is the fullest one (highest
  used/total). Once any cap is exceeded, it is the exceeded cap that resets last.
- **Secondary** is the fullest of the remaining caps. Ties go to the longer period. It is absent when the user has
  only one cap.

Unlimited windows (total is 0 or at least 2^53) are skipped. A user with no cap gets no headers. The per-minute cost
limit is never reported.

## Headers on a 200 answer

The upstream's own `x-codex-*` response headers are always removed, whatever the status. For a capped user, a 200
answer carries the following, describing the state before this request's cost is added. A streaming answer carries
them in the response headers, before the body.

| Header | Value |
|---|---|
| `x-codex-primary-used-percent` | used/total of the primary cap as a whole percent, rounded down (e.g. `82`) |
| `x-codex-primary-window-minutes` | `1440`, `10080` or `43200`: day, week or month |
| `x-codex-primary-reset-at` | when the primary cap resets, Unix seconds |
| `x-codex-secondary-used-percent`, `-window-minutes`, `-reset-at` | the same for the secondary cap |

Errors returned by the upstream carry no `x-codex-*` headers.

## 429 responses

### Spend cap reached

The request is rejected before it goes upstream. The body keeps DIAL's `code` and `message` and adds the fields Codex
reads:

```
HTTP/1.1 429
retry-after: <seconds until reset>
x-codex-primary-used-percent: 100
x-codex-primary-window-minutes: 1440|10080|43200
x-codex-primary-reset-at: <unix seconds>

{"error":{"type":"usage_limit_reached","code":"429","message":"DIAL daily spend limit reached (resets 2026-10-24 00:00 UTC)","resets_at":<unix seconds>,"limit_window_minutes":1440}}
```

A reached spend cap takes precedence over every other limit. A caller over both a token limit and a spend cap gets
this answer.

### Spend counter unreadable

If the spend record can't be read for a caller with a cost limit, and no token or request limit rejects the request,
the request fails closed. The answer is a 429 with no `retry-after` and no `x-codex-*` header, and the body
`{"error":{"code":"429","message":"DIAL could not read your spend limit; request rejected"}}`. Codex retries it a few
times and then reports a retry-limit error. A cost limit here means any cost window below 2^53, including the
per-minute one and a cap of 0.

This happens only when the cost record itself is unreadable. Any other failure of the limit check, including a full
Redis outage (the token and request records fail too), and an unreadable record of a caller without a cost limit,
give the usual 500.

### Other limits

Per-deployment token and request limits, the per-minute cost limit and a cost cap of `0` answer as on every other
route: 429 with `retry-after` and the DIAL error body (`{"error":{"code":"429","message":...}}`), with no `type` and no
`x-codex-*` header. Codex retries them.

## Translated deployments

A deployment whose `openaiResponses` interface uses `"mode": "translator"` gets the same headers. Its limits are
checked and charged by the call the translator makes back to Core, so the outer request only reads the spend caps.
It rejects with the spend-cap 429 once a cap is already reached. If the spend record can't be read, it sends no
headers instead of failing closed.

## Interceptors and applications

When Codex calls a model that has an interceptor in front, Core does not limit-check Codex's own request. It hands
that request to the interceptor and adds no `x-codex-*` header to the answer. The interceptor's call to the model
through `/openai/v1/responses` is checked against the Codex user's caps, and Core answers that call with the headers
and the 429 above. The same applies to an application calling a model. Codex sees them only if the interceptor or
application passes the inner answer's headers and error body back. The interceptor or application therefore sees the
user's spend percentage and reset time.
