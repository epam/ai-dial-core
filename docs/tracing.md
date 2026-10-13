# Static Setting for Tracing

DIAL Core always emits OpenTelemetry spans for incoming requests. The `tracing` section of
`aidial.settings.json` controls the enrichment on top of that: [OTel GenAI semantic-convention](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
attributes, a conversation/session correlation id taken from a request header, and response
headers that hand the caller Core's own trace and span ids.

Everything here is on by default, and nothing here ever publishes prompts, completions, tool
payloads, API keys, arbitrary headers, or upstream provider names.

These are static settings, so they are read once at startup: changing them needs a restart.

> This section used to live in the dynamic config (`aidial.config.json`). A leftover `tracing` block
> there is ignored rather than rejected, so move it to `aidial.settings.json` or your settings are
> silently replaced by the defaults.

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

## Storage and rate-limit spans

Core adds child spans for the storage, Redis, identity and key-management work a request does. They are emitted
only inside a traced request, so background jobs (resource sync, sweeps, bulk loads on their own
executor) start no traces of their own. They do not depend on `genAiSpanAttributes`.

| Span                                                     | Covers                                                                        | Attributes                                    |
|----------------------------------------------------------|-------------------------------------------------------------------------------|-----------------------------------------------|
| `auth.api_key.lookup`                                    | Reading a per-request API key from Redis                                      |                                               |
| `auth.api_key.assign`                                    | Storing the per-request API key handed to the upstream deployment             |                                               |
| `auth.jwks.fetch`                                        | Fetching an identity provider's signing key; once per key id while it is cached |                                             |
| `deployment.resolve`                                     | Finding the requested deployment and checking access and user consent         |                                               |
| `rate_limit.check`                                       | The token, request and cost limit checks before a request is forwarded        | `dial.deployment`, `dial.rate_limit.status`   |
| `oauth.request`                                          | A call to an authorization server: token exchange, metadata discovery, registration | `http.request.method`, `server.address`, `http.response.status_code` |
| `kms.encrypt`, `kms.decrypt`                             | A call to the configured AWS, Azure or GCP key management service             |                                               |
| `resource.get`, `resource.compute`, `resource.put`, `resource.delete` | A `ResourceService` operation, including Redis access and lock waits | `dial.resource.type`; `resource.get` also has `dial.cache.hit` (`false` when the value was read from blob storage) |
| `blob.<operation>`                                       | One blob storage call: `load`, `store`, `meta`, `exists`, `delete`, `copy`, `list`, and the multipart upload calls | `blob.load`: `dial.blob.found`; `blob.store`: `dial.blob.size` |

`blob.load` ends when the blob storage returns the blob, so it does not include reading the payload
stream. The per-trace usage record (cost and token counts, created before every upstream call, updated
with each deployment's usage and deleted when the trace ends) shows up as `resource.*` spans with
`dial.resource.type=DEPLOYMENT_COST_STATS`. Redis calls get no spans of their own: the time a `resource.*` span spends outside its
`blob.*` children is Redis access and lock waits.

The same blob storage calls are measured by the `dial_blob_operation` timer, tagged by `operation`
and `outcome` (`success` or `error`), with buckets from 5 ms to 5 s. Unlike the spans, the timer
also records calls made outside a request.

## Trace context after the response

The request's trace context stays on the request after the response is sent, so work the request
still does afterwards (cleanup such as `resource.delete`, an upstream retry after the client
disconnected, an application deployment that continues after `200`) is part of the same trace: its
spans are children of the server span and can start after it ends, and its log lines keep the
trace id. On a WebSocket route this work can run for as long as the socket is open, and on a
deployment for minutes; with tail sampling in the collector, spans that arrive after the sampling
decision has left its cache can be exported as a separate trace fragment. The request also keeps
the ended server span, with its attributes and events, referenced for as long as the request's
context lives, so an open WebSocket holds its server span in memory until the socket closes.

Core keeps the context by starting the server span on a scratch duplicate of the request's Vert.x
context and copying the span's OTel context onto the request's context itself, so the scope the
Vert.x tracer closes when the response ends resets only the scratch copy. Restoring the context
after the response has ended was rejected because a worker thread of the same request can read the
root context in between. The scratch copy's empty locals lose nothing: every server-span start in
Vert.x 4.5.30 already hands in a freshly duplicated context, so the incoming `traceparent` is the
only parent source either way.
