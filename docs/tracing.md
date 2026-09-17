# Static Setting for Tracing

DIAL Core always emits OpenTelemetry spans for incoming requests. The `tracing` section of
`aidial.settings.json` adds *opt-in* enrichment on top of that: [OTel GenAI semantic-convention](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
attributes, a conversation/session correlation id taken from a request header, and response
headers that hand the caller Core's own trace and span ids.

Everything here is off by default, and nothing here ever publishes prompts, completions, tool
payloads, API keys, arbitrary headers, or upstream provider names.

These are static settings, so they are read once at startup: changing them needs a restart.

> This section used to live in the dynamic config (`aidial.config.json`). A leftover `tracing` block
> there is ignored rather than rejected, so move it to `aidial.settings.json` or the enrichment
> silently stays off.

## tracing

* `genAiSpanAttributes`: Defaults to `false`. When `true`, Core adds `gen_ai.*` and `dial.*`
  attributes to the request span and to OTel log records. See [Attributes](#attributes).
* `responseTraceHeaders`: Defaults to `false`. When `true`, every response carries
  `X-DIAL-TRACE-ID` and `X-DIAL-SPAN-ID` of Core's own root span.
* `conversationIdHeaders`: Request headers, in priority order, that may carry a conversation or
  session id. The first non-blank one present is published as `gen_ai.conversation.id`. Matching is
  case-insensitive and values are trimmed; a value longer than 256 characters is ignored rather than
  truncated. Blank entries and the known credential headers (`authorization`,
  `proxy-authorization`, `cookie`, `set-cookie`, `api-key`, `api_key`, `x-api-key`, `x-auth-token`,
  `x-amz-security-token`) are rejected with a warning rather than published, so naming one cannot
  put a credential on the span. An omitted or empty array means no correlation. An operator-supplied
  array replaces the bundled default outright rather than adding to it.

To suppress individual attributes, drop them in the OpenTelemetry Collector (an `attributes` or
`transform` processor) rather than in Core.

Defaults for `conversationIdHeaders` cover the harnesses DIAL ships with — Claude Code
(`x-claude-code-session-id`), OpenAI Codex CLI (`thread-id`), OpenCode (`x-session-id`), and DIAL
Chat (`x-dial-client-channel-id`, with `X-CONVERSATION-ID` as the legacy fallback). Any other client
needs its own session header listed explicitly.

**Example**

```json
"tracing": {
  "genAiSpanAttributes": true,
  "responseTraceHeaders": true,
  "conversationIdHeaders": [
    "x-claude-code-session-id",
    "thread-id",
    "x-session-id",
    "x-dial-client-channel-id",
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
| `gen_ai.response.*`                     | `id` (always the id the client sees — for Responses that is DIAL's own, not the upstream's), `model`, `finish_reasons`, `status` (from the body, else derived from the status the client receives) |
| `gen_ai.usage.*`                        | `input_tokens`, `output_tokens`, `cache_read.input_tokens`, `cache_write.input_tokens`, `reasoning.output_tokens`                                          |
| `dial.usage.total_tokens`               | Core's own total, not the upstream's                                                                                                                      |
| `dial.upstream.attempts`                | The `X-UPSTREAM-ATTEMPTS` the client receives — how many upstream attempts the load balancer spent on the request                                          |
| `dial.upstream.cache.breakpoint_path`   | The prefix path the upstream reported caching via `X-DIAL-CACHE-BREAKPOINT-PATH`. Absent when the upstream reported none                                   |
| `dial.upstream.cache.stored`            | Whether Core matched a hash for that path and submitted the cache entry. Only present alongside `breakpoint_path`; the Redis write itself is async         |
| `gen_ai.conversation.id`                | Resolved from `conversationIdHeaders`; set regardless of the API surface                                                                                  |
| `dial.request.parent_span.id`           | Parent span id of a valid incoming W3C `traceparent`. The header itself is still not forwarded upstream                                                   |

String attribute values are capped at 256 characters and list attributes at 32 elements, since model
names, response ids and stop sequences are caller- or upstream-controlled and every attribute is
replayed onto each log record of the request.

Enrichment can never fail a request: it runs on the critical path, before the client response is
completed, so a failure is logged and the response proceeds without the attributes.

Which request attributes apply depends on the API surface: `stop_sequences`, `choice.count`,
`frequency_penalty`, `presence_penalty` and `seed` come from Chat Completions,
`previous_response.id` from Responses, `encoding_formats` from Embeddings.
