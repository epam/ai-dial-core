# Dynamic Setting for Tracing

DIAL Core always emits OpenTelemetry spans for incoming requests. The `tracing` section adds
*opt-in* enrichment on top of that: [OTel GenAI semantic-convention](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
attributes, a conversation/session correlation id taken from a request header, and response
headers that hand the caller Core's own trace and span ids.

Everything here is off by default, and nothing here ever publishes prompts, completions, tool
payloads, API keys, arbitrary headers, or upstream provider names.

## tracing

* `genAiSpanAttributes`: Defaults to `false`. When `true`, Core adds `gen_ai.*` and `dial.*`
  attributes to the request span and to OTel log records. See [Attributes](#attributes).
* `responseTraceHeaders`: Defaults to `false`. When `true`, every response carries
  `X-DIAL-TRACE-ID` and `X-DIAL-SPAN-ID` of Core's own root span.
* `conversationIdHeaders`: Request headers, in priority order, that may carry a conversation or
  session id. The first non-blank one present is published as `gen_ai.conversation.id`. Matching is
  case-insensitive. A header sent more than once, or a value longer than 256 characters, is ignored
  rather than truncated. Values are trimmed.
* `genAiAttributeBlacklist`: Defaults to `[]`. Java regexes, each matched **in full** against an
  attribute name, that suppress attributes this feature adds. Built-in Vert.x and OpenTelemetry HTTP
  attributes are never filtered. An invalid regex is rejected when the config is read, and the
  previous config stays live.

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
    ],
    "genAiAttributeBlacklist": [
        "gen_ai\.request\..*",
        "dial\.usage\.total_tokens"
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
| `gen_ai.response.*`                     | `id`, `model`, `finish_reasons`, `status`                                                                                                                  |
| `gen_ai.usage.*`                        | `input_tokens`, `output_tokens`, `cache_read.input_tokens`, `cache_write.input_tokens`, `reasoning.output_tokens`                                          |
| `dial.usage.total_tokens`               | Core's own total, not the upstream's                                                                                                                      |
| `gen_ai.conversation.id`                | Resolved from `conversationIdHeaders`; set regardless of the API surface                                                                                  |
| `dial.request.parent_span.id`           | Parent span id of a valid incoming W3C `traceparent`. The header itself is still not forwarded upstream                                                   |

Which request attributes apply depends on the API surface: `stop_sequences`, `choice.count`,
`frequency_penalty`, `presence_penalty` and `seed` come from Chat Completions,
`previous_response.id` from Responses, `encoding_formats` from Embeddings.
