# Static Setting for Tracing

DIAL Core always emits OpenTelemetry spans for incoming requests. The `tracing` section of
`aidial.settings.json` adds *opt-in* enrichment on top of that: [OTel GenAI semantic-convention](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
attributes, a conversation/session correlation id taken from a request header, and response
headers that hand the caller Core's own trace and span ids.

The bundled settings turn `genAiSpanAttributes` and `responseTraceHeaders` on. Nothing here ever
publishes prompts, completions, tool payloads, API keys, arbitrary headers, or upstream provider names.

These are static settings, so they are read once at startup: changing them needs a restart.

> This section used to live in the dynamic config (`aidial.config.json`). A leftover `tracing` block
> there is ignored rather than rejected, so move it to `aidial.settings.json` or the enrichment
> silently stays off.

## tracing

* `genAiSpanAttributes`: Defaults to `true`. When `true`, Core adds `gen_ai.*` and `dial.*`
  attributes to the request span and to OTel log records. See [Attributes](#attributes).
* `responseTraceHeaders`: Defaults to `true`. When `true`, every response carries the W3C
  `traceparent` of Core's own root span, plus `X-DIAL-TRACE-ID` and `X-DIAL-SPAN-ID` for existing
  consumers. All three are listed in `Access-Control-Expose-Headers`, so a browser client can read
  them. Nothing is emitted when Core has no valid span context — with no OpenTelemetry SDK attached
  the ids are all zeros, and a `traceparent` built from those is malformed.
* `conversationIdHeaders`: Request headers, in priority order, that may carry a conversation or
  session id. The first usable one present is published as `gen_ai.conversation.id`. Matching is
  case-insensitive and values are trimmed; a value longer than 256 characters is skipped rather than
  truncated — truncating one would correlate unrelated requests — and the next header on the list
  still gets its turn. Blank entries and the known credential headers (`authorization`,
  `proxy-authorization`, `cookie`, `set-cookie`, `api-key`, `api_key`, `x-api-key`, `x-auth-token`,
  `x-amz-security-token`) are rejected with a warning rather than published, so naming one cannot
  put a credential on the span. An omitted or empty array means no correlation. An operator-supplied
  array replaces the bundled default outright rather than adding to it.

To suppress individual attributes, drop them in the OpenTelemetry Collector (an `attributes` or
`transform` processor) rather than in Core.

Defaults for `conversationIdHeaders` cover the harnesses DIAL ships with — Claude Code
(`x-claude-code-session-id`), OpenAI Codex CLI (`thread-id`), OpenCode (`x-session-id`), and DIAL
Chat (`X-CONVERSATION-ID`). DIAL Chat's `x-dial-client-channel-id` is not on the list on purpose: it
identifies a browser tab, not a conversation. Any other client needs its own session header listed explicitly.

**Example**

```json
"tracing": {
  "genAiSpanAttributes": true,
  "responseTraceHeaders": true,
  "conversationIdHeaders": [
    "x-claude-code-session-id",
    "thread-id",
    "x-session-id",
    "X-CONVERSATION-ID"
  ]
}
```

## Attributes

Added only when `genAiSpanAttributes` is `true`, and only when the underlying value is actually
present — a missing value is omitted, never written as `null` or `""`.

| Attribute                               | Notes                                                                                                                                                    |
|-----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `gen_ai.operation.name`                 | `chat` (Chat Completions, Anthropic Messages), `embeddings`, `generate_content` (Responses create), `fetch_response` (Responses get)                       |
| `dial.api`                              | `openai_chat_completions`, `anthropic_messages`, `openai_responses`, `openai_embeddings`                                                                   |
| `gen_ai.provider.name`                  | Always `dial` — the upstream provider is never published                                                                                                  |
| `gen_ai.request.*`                      | `model`, `stream`, `max_tokens`, `temperature`, `top_p`, `stop_sequences`, `choice.count`, `frequency_penalty`, `presence_penalty`, `seed`, `reasoning.level`, `previous_response.id`, `encoding_formats` |
| `gen_ai.response.*`                     | `id` (only when DIAL owns the id the client sees — Responses create and fetch; omitted for Chat Completions, Anthropic Messages and Embeddings), `status` (`completed` or `failed`, derived from the status the client receives). `model` and `finish_reasons` are not published |
| `gen_ai.usage.*`                        | `input_tokens`, `output_tokens`, `cache_read.input_tokens`, `cache_write.input_tokens`, `reasoning.output_tokens`. Taken from the token usage Core already collected for limits and cost; a `fetch_response` publishes none |
| `dial.usage.total_tokens`               | Core's own total, not the upstream's                                                                                                                      |
| `dial.upstream.attempts`                | The `X-UPSTREAM-ATTEMPTS` the client receives — how many upstream attempts the load balancer spent on the request                                          |
| `dial.upstream.cache.breakpoint_path`   | The prefix path the upstream reported caching via `X-DIAL-CACHE-BREAKPOINT-PATH`. Absent when the upstream reported none                                   |
| `dial.upstream.cache.stored`            | Whether Core matched a hash for that path and submitted the cache entry. Only present alongside `breakpoint_path`; the Redis write itself is async         |
| `dial.latency.*`                        | `client_body_ms` (reading the client request body), `upstream_connect_ms`, `upstream_header_ms` (to the upstream's response headers — **not** to its first token: a DIAL application or interceptor flushes headers before it generates), `upstream_body_ms`. A phase that never happened is omitted rather than reported as zero |
| `gen_ai.conversation.id`                | Resolved from `conversationIdHeaders`; set regardless of the API surface                                                                                  |
| `dial.request.parent_span.id`           | Parent span id of a valid incoming W3C `traceparent`, of any version but `ff`. The header itself is still not forwarded upstream                          |

String attribute values are capped at 256 characters and list attributes at 32 elements, since model
names, response ids and stop sequences are caller- or upstream-controlled and every attribute is
replayed onto each log record of the request.

The response body is never parsed for tracing, so no response attribute costs an extra parse. Response
attributes come from Core's own state: the id DIAL assigned, the status the client receives, and the
token usage already collected for limits. A run that fails inside a successful stream (for example a
`response.failed` event) is therefore still reported as `completed`.

Enrichment can never fail a request: it runs on the critical path, before the client response is
completed, so a failure is logged and the response proceeds without the attributes.

Which request attributes apply depends on the API surface: `stop_sequences`, `choice.count`,
`frequency_penalty`, `presence_penalty` and `seed` come from Chat Completions,
`previous_response.id` from Responses, `encoding_formats` from Embeddings.
