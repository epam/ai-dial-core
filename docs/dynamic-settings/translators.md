# Dynamic Setting for Translators

A translator is a service that converts a request from one LLM API to another, so that a deployment can serve an API it does not speak itself. DIAL Core forwards the request to the translator; the translator converts it and calls DIAL Core back on an API the deployment does serve, then converts the answer back on the way out.

Translators are declared once at the root of the config and referenced by name from the `interfaces` entries that use them. Refer to [models.<model_name>.interfaces](models.md#modelsmodel_nameinterfaces) for how an interface picks one up.

## translators

A map of available translators.

* `<translator_name>`: A unique translator name, used to reference it from an `interfaces` entry.

### translators.<translator_name>

* `in`: **Required.** The interface type the translator accepts — the API a client calls DIAL Core on. It has to differ from `out`.
* `out`: **Required.** The interface type it converts to. The deployment referencing the translator has to serve `out` itself, and serve it pass-through: that is the API the translator calls DIAL Core back on.
* `baseUrl`: The root URL of the translator service. Each request is forwarded to `baseUrl` + **the exact ingress path it was received on**, exactly as a deployment's own base URL is. A trailing slash is normalized. Exactly one of `baseUrl` and `builtin` is required.
* `builtin`: A translation DIAL Core performs itself instead of forwarding to a translator service. See [Builtin translators](#builtin-translators). Exactly one of `baseUrl` and `builtin` is required.

Both name an interface type this version of DIAL Core knows. Unlike an `interfaces` key, which is simply inert when it names a type Core does not know, these two are matched — `in` against the interface the translator is referenced from, `out` against the one the deployment serves back — so a name Core cannot resolve is rejected as the config is read, and the previous config stays live.

**Example**

```json
"translators": {
    "anthropicMessagesToOpenaiResponses": {
        "in": "anthropicMessages",
        "out": "openaiResponses",
        "baseUrl": "http://dial-bedrock-translator/to-responses"
    },
    "anthropicMessagesToOpenaiChatCompletions": {
        "in": "anthropicMessages",
        "out": "openaiChatCompletions",
        "baseUrl": "http://dial-bedrock-translator/to-chat-completions"
    }
}
```

A model then serves `/anthropic/v1/messages` through one of them, while serving the OpenAI APIs itself:

```json
"models": {
    "openai-gpt-5.4-mini": {
        "type": "chat",
        "overrideName": "gpt-5.4-mini",
        "baseUrl": "http://dial-openai:5000",
        "interfaces": {
            "openaiChatCompletions": { "mode": "passthrough" },
            "anthropicMessages": {
                "mode": "translator",
                "translator": "anthropicMessagesToOpenaiChatCompletions"
            }
        }
    }
}
```

A request to `POST /anthropic/v1/messages` for this model is forwarded to `http://dial-bedrock-translator/to-chat-completions/anthropic/v1/messages`. The translator converts it to a chat completions request and calls DIAL Core back for `openai-gpt-5.4-mini`, which serves it pass-through from `http://dial-openai:5000`.

## Translators declared inline

An interface that needs a translator no other deployment uses can define one in place, without registering it. The inline form takes the same fields minus `in`, which is implied by the interface the definition sits under — `out` and one of `baseUrl` or `builtin` are required here too:

```json
"interfaces": {
    "anthropicMessages": {
        "mode": "translator",
        "translator": {
            "out": "openaiChatCompletions",
            "baseUrl": "http://some-custom-translator/to-chat-completions"
        }
    }
}
```

## Builtin translators

A translator with `builtin` instead of `baseUrl` is performed by DIAL Core inside the request it serves: Core converts the request, sends it to the deployment's own interface for `out`, and converts the answer back. There is no second request to Core and no translator service. The request is checked and charged against the caller's limits once, from the usage the client receives, and writes one request-log row: see [Limits and a translated request](#limits-and-a-translated-request).

| `builtin` | `in` | `out` |
|---|---|---|
| `anthropicMessagesToOpenaiResponses` | `anthropicMessages` | `openaiResponses` |

```json
"translators": {
    "anthropicMessagesToOpenaiResponsesBuiltin": {
        "in": "anthropicMessages",
        "out": "openaiResponses",
        "builtin": "anthropicMessagesToOpenaiResponses"
    }
}
```

* A `builtin` value DIAL Core does not know is rejected as the config is read. Unlike a translator *name*, the set is fixed in code, so it cannot be registered later.
* `in` and `out` must be the pair the builtin converts. Written inline, `in` is implied by the interface the definition sits under, as for `baseUrl`.
* Only a model can use a builtin translator: the translated request is served by the model's own `out` interface. An application referencing one answers `503` for that interface.
* Consent and per-request-key access are checked against the features of both the model's `anthropicMessages` interface and its `openaiResponses` interface: a request that either one refuses (`consentRequired: true` without consent, or `accessibleByPerRequestKey: false` with a per-request key) gets `403`.
* Falling back to a translator service is a config edit: replace the translator entry's `builtin` with the service's `baseUrl`.

  ```json
  "anthropicMessagesToOpenaiResponsesBuiltin": {
      "in": "anthropicMessages",
      "out": "openaiResponses",
      "baseUrl": "http://dial-bedrock-translator/to-responses"
  }
  ```

  What changes with it: each client call makes two passes through Core and writes two request-log rows again; the limits are checked and charged on the translator's callback, whose cost is also credited to the model's aggregated cost and listed in the outer row's `usage_per_model` (see [Limits and a translated request](#limits-and-a-translated-request)); the callback carries a per-request key, so the model's `openaiResponses` interface must not set `accessibleByPerRequestKey: false`, or the callback gets `403`; and `count_tokens` and interceptors are served again.
* `anthropicMessagesToOpenaiResponses` currently translates text conversations, tools and reasoning, streaming and non-streaming: the `system` prompt (as `instructions`), `system`-role turns (as `system` input messages, kept in place; one with `clear_at: "next_user_message"` is not sent once a later user turn exists), `mid_conv_system` blocks (as `system` input messages before the turn that holds them, after the turn's tool results in a user turn), replayed `compaction` blocks (as their summary text), and text blocks. A `system`-role turn keeps only its text; any other block in it is dropped. Fields with no Responses counterpart (`stop_sequences`, `top_k`, `output_config.format`, `thinking.budget_tokens`, …) are dropped.
* Tools: a custom tool (no `type`, or `custom`) becomes a Responses function tool, with its `input_schema` as `parameters` minus `$schema`, and `strict: false` unless the tool sets `strict: true`. Every other tool type (`bash_*`, `text_editor_*`, `computer_*`, `code_execution_*`, `web_fetch_*`, `mcp_toolset`, …) is forwarded exactly as sent, so a deployment that cannot run one answers for it, usually with its own `400`. `tool_choice` becomes `auto`, `required`, `none` or a named function, and `disable_parallel_tool_use` becomes `parallel_tool_calls`. Replayed `tool_use` blocks become `function_call` items and `tool_result` blocks `function_call_output` items, with text results only. On the stream, a function call becomes a `tool_use` block and sets `stop_reason: tool_use`.
* A function name the upstream may reject (one not matching `^[a-zA-Z_][a-zA-Z0-9_-]{2,63}$`, such as Claude Code's long `mcp__<server>__<tool>` names) goes upstream as `<head>_<8 hex of its SHA-256>`, and the client gets the original name back. The alias lives for one request and is the same from turn to turn.
* Reasoning: `output_config.effort` (or the effort of the last `system`-role turn still shown) is sent as `reasoning.effort` as it is, and `thinking` adds `reasoning.summary: "auto"` unless its `display` is `omitted`. No effort is inferred from the thinking type or `budget_tokens`, so a request that names none keeps the deployment's default. `thinking: {"type": "disabled"}` sends `reasoning: {"effort": "none"}` whatever effort is named. An effort other than `none`, `minimal`, `low`, `medium`, `high`, `xhigh` or `max` is refused with `400`. On the stream, reasoning arrives as `thinking` blocks (summary and reasoning text as `thinking_delta`), and its encrypted content as the block's `signature`, or as a `redacted_thinking` block. A replayed `thinking` or `redacted_thinking` block in an assistant turn goes back upstream as the `reasoning` item it came from, and to the upstream that produced it; one Core did not write (a Claude signature) is dropped. The signature uses the translator service's format, so a deployment can move between a builtin translator and a translator service in the middle of a conversation, either way.
* Upstream cache affinity is computed on the translated request: the cache key is built from the Responses body with the `openaiResponses` hashing order (`tools`, `instructions`, `input`) and the model's `openaiResponses` features (`cacheSupported`, `autoCachingSupported`). So the adapter's `X-DIAL-CACHE-BREAKPOINT-PATH` names Responses fields (`prefix.body.input[0]`, …), and the next turn goes to the upstream that answered; a path naming Messages fields (`prefix.body.messages[0]`) stores nothing.
* The translated request carries `store: false` and `include: ["reasoning.encrypted_content"]`, and the client's `stream` (`false` when absent), as the request a translator service sends does. A non-streaming answer is one Anthropic message, with the spend headers, `Content-Length` and `X-UPSTREAM-ATTEMPTS` of any Messages answer. A `200` Responses object whose `status` is `failed`, `queued`, `in_progress` or `cancelled` is answered as an error instead (`400`, `429`, `502` or `504`, by the failure the upstream names), and is charged the usage it reports, as is a stream that ends with `response.failed`.
* Errors come in the Anthropic shape, `{"type":"error","error":{"type":...,"message":...}}` with `Content-Type: application/json`, once DIAL Core knows a builtin translator serves the request: its own refusals and limit answers, a connect failure or timeout, no upstream left (`503` keeps its `Retry-After`), and an upstream error answer, which keeps the upstream's status and headers and carries its message. The status and every other header stay as they would be for any deployment; the error type follows the status (`400` and `422` `invalid_request_error`, `401` `authentication_error`, `403` `permission_error`, `404` `not_found_error`, `413` `request_too_large`, `429` `rate_limit_error`, `503` and `529` `overloaded_error`, otherwise `api_error`). Answers given before the deployment is known (`415`, an unreadable body, an unknown deployment) keep their shape, and pass-through Anthropic models keep theirs. After the stream has started, a failure the upstream reports, a stream that ends early and a lost upstream connection each end it with one `error` event; `response.incomplete` is a normal end with `stop_reason: max_tokens`.
* Refused with `400` rather than translated partially: a tool whose `type` starts with `web_search`; a malformed `tools` or `tool_choice` (not an array or not an object, an entry that is not an object, a `name` or `type` that is not a string); a content block of any other type in a user or assistant turn (`image`, `document`, a `thinking` block in a user turn, …), or inside a `tool_result` (anything but `text` and `tool_reference`); a `tool_use` block in a user turn or a `tool_result` block in an assistant turn; and a `messages` that is not an array or holds an unknown role. `count_tokens`, and a model with interceptors, are answered `503`. A deployment that needs web search, images or documents keeps the translator service for now.
* Its translation matches every in-scope fixture the Python translator recorded at commit `5e70209`.

## Rules

* An interface is served **either** by a base URL **or** by a translator, never by both. `mode` says which: `translator` requires a `translator` and rejects a `base_url` on the same entry, and `passthrough` (the default) is the other way round. A model breaking this is rejected on config load with a validation error, rather than having one of the two silently win.
* A translator declaring no `out`, or neither or both of `baseUrl` and `builtin`, is rejected as the config is read: it converts nothing, or converts it nowhere. A registry entry declaring no `in` is rejected on config load for the same reason — it is declared under no interface, so nothing else says what it accepts.
* A **name no `translators` entry defines** is different, and is **not** a config error: the entry may simply not be registered yet. It leaves that one interface unserved — the deployment answers `503` for it, and it never falls back to `endpoint`/`responsesEndpoint` — while everything else the deployment serves keeps working. The interface is still advertised in `/v1/deployments`: carrying a translator reference is what declares it, and whether the name resolves is a serving-time question, exactly as an application whose backend is missing still lists. Registering the translator serves it, with no edit to the deployment.
* A translator converts between two **different** interfaces. One whose `out` equals its `in` — or, for an inline definition, equals the interface it is declared under — is rejected: its own output arrives back on the interface it came from, so DIAL Core hands it straight back to the translator. Nothing bounds that at runtime, which is why it is a config error rather than a request-time failure.
* The deployment must serve `out` itself, pass-through. A translator converting to an API the deployment does not serve leaves the callback with nowhere to land, and one converting to another translated interface is refused because the callback would be handed to a translator again.
* Together with the rule above, that is what keeps a **cycle of translators** out of a config rather than out of a running request: a cycle of two or more interfaces needs a translator whose `out` lands on a second translated interface, and no such pair is accepted. A model where `anthropicMessages` converts to `openaiResponses` while `openaiResponses` converts back to `anthropicMessages` is rejected on load, at both ends. The one-interface cycle is the rule above it.
* **A request translated by a translator service is not charged to the caller's limits.** On `/anthropic/v1/messages` and `/openai/v1/responses` a spend cap already reached rejects it; another translated interface reads no limit on the client call. The translator's callback is the real request, and it carries the tokens, the cost and the request slot, so a client call is counted exactly once. A request translated by a builtin translator is the real request itself: it is checked and charged once. Refer to [Limits and a translated request](#limits-and-a-translated-request).
* Editing a `translators` entry takes effect for every deployment referencing it by name as soon as the edit is loaded — a name is resolved against the registry on each request, never frozen into a copy when the deployment is written or loaded.

## Limits and a translated request

A client call served through a translator service reaches DIAL Core twice: once from the client, on the translated interface, and once from the translator, on the interface named by `out`. Only the second call is a real request against the caller's quotas — the first is a routing hop to the translator service. So one client call is counted once and not twice, in tokens, in cost, and in requests alike:

| [Role limit](roles.md#rolesrole_namelimits) | The client's translated call | The translator's callback |
|---|---|---|
| `minute` / `day` / `week` / `month` (tokens) | not read, not added to | checked and added to |
| [`costLimit`](roles.md#rolesrole_namecostlimit) | on `/anthropic/v1/messages` and `/openai/v1/responses`, read for the spend headers and a reached cap ([Claude Code spend limits](../anthropic-spend-limits.md)); on any other interface not read; never added to | checked and added to |
| `requestHour` / `requestDay` | not read, not counted | checked and counted |

**A translated interface is not a way around a quota**, but it is enforced one hop later. A caller who has exhausted their token, request or per-minute cost limit is not rejected on the translated call itself — that call is let through to the translator — and is then rejected on the callback, which is an ordinary pass-through request checked like any other. So the quota still holds; the `429` reaches the client through the translator rather than straight from DIAL Core, and the translator pays a round-trip for a request that was going to be refused.

Usage is attributed to the initiator, not to the translator, as long as the translator calls back with the per-request key DIAL Core issued it — the same contract an interceptor follows. The callback is a child of the client's call, so its cost is also credited to the model's aggregated cost and listed in the `usage_per_model` of the client call's row: `GET /v1/deployments/{id}/usage`, which adds the model's own and aggregated cost, shows the request's cost twice.

**A builtin translator** serves the client call in one request, so that request is checked and charged like a pass-through one. Its token, request and cost limits are checked before anything goes upstream, and a reached limit gets DIAL's own `429`. Its usage is charged once, on that request, priced from the Anthropic usage the client receives through the model's `pricing` with the `anthropicMessages` usage fields. For an upstream answer whose `total_tokens` is its input plus output tokens, it adds the same tokens, cost and request as the callback of a translator service, cached and reasoning tokens included: with `prompt` `0.01`, `completion` `0.02` and `cacheRead` `0.001`, an answer of 100 input tokens (40 of them cached) and 10 output tokens adds 110 tokens and a cost of 0.84 (60 × 0.01 + 40 × 0.001 + 10 × 0.02) either way. It differs in four cases:

* A pricing decision tree that branches on `serviceTier` takes its no-value branch: the Anthropic usage carries no service tier.
* A `$.usage...` JSON path in a pricing condition reads the Anthropic usage, not the Responses usage.
* The tokens are counted from the Anthropic counters (input, cache read, cache write and output). The callback takes the upstream's `total_tokens` as it is, and adds no tokens when it is missing.
* A cost the upstream reports in its usage does not reach the builtin row: its price comes only from the model's `pricing`.

The request log differs too:

* A translator service: **both** hops are written to the request log. The client call's row carries the Messages request and the tokens it saw, but no price; the callback's row carries the translated request, the upstream's answer and the price. It is the limits that count once, not the log, so summing usage across log entries double counts the request.
* A builtin translator: one row, for the model, with the Messages request the client sent (with the interface's `defaults` applied), the Anthropic answer the client received and the price. It has no `usage_per_model` entry, and no aggregated cost is credited. No field names the translator: the deployment's `anthropicMessages` entry tells which path served the request.
