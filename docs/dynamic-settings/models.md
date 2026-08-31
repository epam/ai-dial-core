# Dynamic Setting for Models

In dynamic settings you can include language models and their parameters you wish to enable in DIAL.

> Refer to [DIAL Admin](https://docs.dialx.ai/tutorials/admin/entities-models) to learn how to manage models in DIAL Admin UI.

## models

A list of deployed models and their [parameters](#modelsmodel_name).

* `<model_name>`: A unique model name.

**Example**

```json
"models": {
        "chat-gpt-35-turbo": {},
        "embedding-ada": {}
}
```

### models.<model_name>

An object containing parameters for each [model](#models).

* `type`: A string defining the model type (chat or embedding). DIAL Core uses this to choose the correct API endpoint and a payload schema.
* `iconUrl`: A string with the URL with the icon location to display for the model on UI.
* `description`: A string with a brief model description.
* `intro`: A string with a short introductory/onboarding text for the model, shown to end users separately from `description`.
* `displayName`: A string with the models's name. Display name is shown in all DIAL client UI dropdowns, tables, and logs so operators can quickly identify the model.
* `displayVersion`: A string with the model's version. Use it to distinguish between "latest," "beta," or date-stamped builds.
* `endpoint`: Model API for chat completions or embeddings.
* `overrideName`: If set, the model is called under this name: the outgoing request body's `model` field (and the `X-DIAL-OVERRIDE-NAME` header) are rewritten to this value before the request reaches the model adapter. Doesn't change routing — only the value the adapter receives.
* `embeddingDimensions`: The size of the embedding vector returned by an embedding model (e.g. `1536` for `text-embedding-ada-002`). Omit for chat/completion models.
* `tokenizerModel`: Identifies the specific model whose tokenization algorithm exactly matches that of the referenced model. This is typically the name of the earliest-released model in a series of models sharing an identical tokenization algorithm (e.g. gpt-3.5-turbo-0301, gpt-4-0314, or gpt-4-1106-vision-preview). This parameter is essential for DIAL clients that reimplement tokenization algorithms on their side, instead of utilizing the tokenizeEndpoint provided by the model.
* `userRoles`: A specific claim value provided by a specific IDP in JWT or an API key role. If not defined, the language model is available to all users. Refer to [IDP Configuration](https://docs.dialx.ai/tutorials/devops/auth-and-access-control/configure-idps/overview) to view examples.
* `descriptionKeywords`: A list of keywords describes the model, e.g. code-gen, text2image.
* `maxRetryAttempts`: The number of times DIAL Core will [retry](https://docs.dialx.ai/platform/core/load-balancer#fallbacks) a connection in case of upstream errors (e.g. on timeouts or 5xx responses).
* `inputAttachmentTypes`: A list of allowed MIME types for the input attachments.
* `maxInputAttachments`: Maximum number of input attachments (default is zero when inputAttachmentTypes is unset, otherwise, infinity).
* `author`: The model's developer.
* `createdAt`: The date of the model creation.
* `updatedAt`: The date of the last model update.
* `defaults`: Default parameters are applied if a request doesn't contain them in OpenAI `chat/completions` API call.
* `responsesEndpoint`: Endpoint of the model adapter that supports the OpenAI Responses API. Currently only OpenAI adapters support this. When set, DIAL Core proxies the following Responses API operations to this endpoint:
  * `POST /openai/v1/responses` — create a response (streaming and non-streaming, including background mode).
  * `GET /openai/v1/responses/{id}` — retrieve a response by its DIAL-assigned ID; supports streaming via SSE.
  * `DELETE /openai/v1/responses/{id}` — delete a response and remove its stored ID mapping.
  * `POST /openai/v1/responses/{id}/cancel` — cancel an in-progress background response.

  DIAL Core rewrites upstream response IDs to stable `resp_dial_*` identifiers and uses sticky routing to ensure follow-up requests are forwarded to the same upstream instance that handled the original request. `previous_response_id`, conversations, prompts, and files are not supported.
* `responsesDefaults`: Default parameters applied if a request doesn't contain them in an OpenAI Responses API call. Works the same way as `defaults` for the chat completions API.
* `defaultHeaders`: HTTP headers DIAL Core adds to a request that doesn't already carry them, for every interface the model serves. Refer to [models.<model_name>.defaultHeaders](#modelsmodel_namedefaultheaders).
* `baseUrl`: The root URL shared by every `interfaces` entry that declares no `base_url` of its own. Refer to [models.<model_name>.interfaces](#modelsmodel_nameinterfaces).
* `interfaces`: An alternative to the flat `endpoint`/`responsesEndpoint` fields for declaring routing targets, keyed by interface type. Both shapes are first-class — pick whichever you prefer per model. Refer to [models.<model_name>.interfaces](#modelsmodel_nameinterfaces).
* `interceptors`: A list of interceptors to be triggered for the given model. Refer to [Interceptors](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/6.interceptors.md) to learn more.
* `fieldsHashingOrder`: **Deprecated, no longer has any effect.** It used to let `POST /openai/deployments/{deployment_name}/chat/completions` customize the order in which request components are hashed for upstream-cache pinning. DIAL Core now always uses a built-in, non-configurable order per interface, fixed by that API's wire format — the same mechanism `POST /anthropic/v1/messages` and `POST /openai/v1/responses` use:
  - OpenAI Chat Completions: `prefix.body.tools`, `prefix.body.messages`.
  - Anthropic Messages: `prefix.body.tools`, `prefix.body.system`, `prefix.body.messages` (block-level, i.e. `messages[i].content[j]`).
  - OpenAI Responses: `prefix.body.tools`, `prefix.body.instructions`, `prefix.body.input`.

  The hash uniquely identifies prefixes of the request that are marked by [cache breakpoints](https://docs.dialx.ai/tutorials/developers/prompt-caching) and lets DIAL Core redirect independent requests sharing the same prefix to the same upstream endpoint — essential for LLM providers whose caching scope is limited to a single upstream endpoint. For Anthropic, candidates come from the client's native `cache_control` blocks and/or `autoCachingSupported`; for Responses, only from `autoCachingSupported` (OpenAI prompt caching has no client breakpoint concept). The `fieldsHashingOrder` field is still accepted in config for backward compatibility, but its value is ignored.
* `features`: An object with the model features that define optional capabilities of the model. Refer to [models.<model_name>.features](#modelsmodel_namefeatures).
* `limits`: An object with the model token limits. Refer to [models.<model_name>.limits](#modelsmodel_namelimits)
* `pricing`: An object with the model cost estimation parameters. Refer to [models.<model_name>.pricing](#modelsmodel_namepricing).
* `upstreams`: An object with the upstreams parameters. Used for load-balancing—request is sent to model endpoint containing X-UPSTREAM-ENDPOINT and X-UPSTREAM-KEY headers. Refer to [models.<model_name>.upstreams](#modelsmodel_nameupstreams).


**Example**

```json
"models": {
        "chat-gpt-35-turbo": {
            "type": "chat",
            "tokenizerModel": "tokenizer",
            "limits": {
                "maxTotalTokens": 1000,
                "maxPromptTokens": 200,
                "maxCompletionTokens": 800
            },
            "pricing": {
                "unit": "token",
                "prompt": "0.56",
                "completion": "0.67"
            },
            "overrideName": "/some[!exotic?]/model/name",
            "displayName": "GPT-3.5",
            "displayVersion": "Turbo",
            "endpoint": "http://localhost:7001/openai/deployments/gpt-35-turbo/chat/completions",
            "upstreams": [
                {
                    "endpoint": "http://localhost:7001",
                    "key": "modelKey1"
                },
                {
                    "endpoint": "http://localhost:7002",
                    "key": "modelKey2"
                },
                {
                    "endpoint": "http://localhost:7003",
                    "key": "modelKey3"
                }
            ],
            "userRoles": ["role1", "role2"],
            "forwardAuthToken": true,
            "features": {
                "rateEndpoint": "http://host/rate",
                "tokenizeEndpoint": "http://host/tokinize",
                "truncatePromptEndpoint": "http://host/truncate",
                "configurationEndpoint": "http://host/configure",
                "systemPromptSupported": false,
                "toolsSupported": false,
                "seedSupported":false,
                "urlAttachmentsSupported": false,
                "folderAttachmentsSupported": false,
                "accessibleByPerRequestKey": true,
                "contentPartsSupported": false
            },
            "maxInputAttachments": 10,
            "inputAttachmentTypes": ["type1", "type2"],
            "defaults": {
                "paramStr": "value",
                "paramBool": true,
                "paramInt": 123,
                "paramFloat": 0.25
            },
            "responsesEndpoint": "http://localhost:7001/openai/v1/responses",
            "responsesDefaults": {
                "store": false
            },
            "interceptors": ["interceptor1"]
        },
        "embedding-ada": {
            "type": "embedding",
            "endpoint": "http://localhost:7001/openai/deployments/ada/embeddings",
            "embeddingDimensions": 1536,
            "upstreams": [
                {
                    "endpoint": "http://localhost:7001",
                    "key": "modelKey4"
                }
            ],
            "userRoles": ["role3"]
        }
    },
```

#### models.<model_name>.interfaces

An optional, typed alternative to the flat `endpoint`/`responsesEndpoint` fields. Both shapes are first-class and fully supported — choose whichever you prefer per model. There is no migration between them in either direction, and declaring `interfaces` never rewrites or removes the legacy fields.

The two shapes route differently:

* `endpoint`/`responsesEndpoint` are forwarded **verbatim** — the configured URL is used as-is and the ingress path is not appended.
* An `interfaces` entry is served by a base URL, and DIAL Core forwards each request to that base URL + **the exact ingress path it was received on** (e.g. `POST /openai/v1/responses` is routed to `<base_url>/openai/v1/responses`). A trailing slash is normalized.

The base URL serving an interface is resolved in this order:

1. `interfaces.<interface_type>.base_url`, when the entry declares one.
2. The model-level `baseUrl`. Usually a single root serves every interface and only the path differs (`/openai/deployments/{name}/chat/completions`, `/openai/v1/responses`, `/anthropic/v1/messages`), so declaring it once at the model level and listing the supported interfaces with no `base_url` of their own is enough; an entry that does declare one overrides it for that interface only.
3. `endpoint`/`responsesEndpoint`, for interface types the `interfaces` map does not declare at all.

`interfaces` is the whitelist of what the model serves: a model-level `baseUrl` on its own declares no interface, and an interface listed with neither base URL is answered with `503`. If both `interfaces` and a legacy field are declared for the same interface type, `interfaces` takes precedence; the legacy field is left untouched in config and ignored for routing.

An interface mapped to `null` reads exactly as an absent one — `"openaiResponses": null` documents that the model does not serve the Responses API.

Supported interface types for models:

* `openaiChatCompletions`: the OpenAI deployments POST family (`chat/completions`, `completions`). Peer of `endpoint`.
* `openaiEmbeddings`: the OpenAI deployments `embeddings` endpoint.
* `openaiResponses`: the OpenAI Responses API. Peer of `responsesEndpoint`.
* `anthropicMessages`: the Anthropic Messages API (`/anthropic/v1/messages`, `/anthropic/v1/messages/count_tokens`).

The `interfaces` map is strict: chat completions is configured via `openaiChatCompletions` and embeddings via `openaiEmbeddings`, and one never stands in for the other — a model declaring only `openaiChatCompletions` answers `503` to `embeddings`, and a model declaring only `openaiEmbeddings` answers `503` to `chat/completions` and `completions`. The untyped legacy `endpoint` predates the split and keeps serving `embeddings` requests verbatim, so models configured before the split keep working unchanged.

Only the interface types a model declares are reported in the `interfaces` array of the `/v1/deployments` listing. A legacy `endpoint` is advertised as the interface matching what the model says it is: `openaiEmbeddings` when `type` is `embedding`, `openaiChatCompletions` otherwise — so an embedding model configured this way reports `openaiEmbeddings` and `"chat_completion": false`, even though that one endpoint still serves the whole deployments POST family.

Each value is an object with the following fields:

* `base_url`: The root URL that the matching ingress path is appended to. Optional — the model-level `baseUrl` serves an entry that omits it.
* `mode`: `passthrough` (default) or `translator`. It declares whether the request is forwarded in the shape it arrived in, or handed to a service that translates it into an API the model does speak. A `translator` interface does not charge its tokens to the model's [limits](#modelsmodel_namelimits): the translator calls DIAL Core back to have the completion served, and that inner request is what carries the usage to the limits, so charging both would count it twice.
* `defaultHeaders`: Headers applied to requests for this interface only, laid over the model-level `defaultHeaders`. Refer to [models.<model_name>.defaultHeaders](#modelsmodel_namedefaultheaders).

**Example**

```json
"models": {
    "gpt-4-via-interfaces": {
        "type": "chat",
        "interfaces": {
            "openaiChatCompletions": { "base_url": "http://localhost:7005" },
            "openaiResponses": { "base_url": "http://localhost:7005" }
        }
    },
    "openai-gpt-5.4-mini": {
        "type": "chat",
        "overrideName": "gpt-5.4-mini",
        "baseUrl": "http://dial-openai-adapter",
        "interfaces": {
            "openaiChatCompletions": { "mode": "passthrough" },
            "openaiResponses": null,
            "anthropicMessages": {
                "mode": "passthrough",
                "base_url": "http://dial-bedrock-adapter"
            }
        }
    },
    "text-embedding-3-small": {
        "type": "embedding",
        "interfaces": {
            "openaiEmbeddings": { "base_url": "http://localhost:7006" }
        }
    }
}
```

#### models.<model_name>.defaultHeaders

An object of HTTP header names and values DIAL Core adds to a request that does not already carry a header of that name. A header sent by the client always wins, and so does one DIAL Core sets itself (`Api-Key`, `X-UPSTREAM-*`, `X-DIAL-DEPLOYMENT-ID`, ...). Names are matched case-insensitively.

A default header behaves exactly as if the client had sent it: DIAL Core reads it as part of the incoming request — so `X-DIAL-CACHE-POLICY` set this way drives upstream cache pinning, not just what the adapter receives — and forwards it under the same rules as a client header. That cuts both ways: a name DIAL Core strips on the way to the model — a hop-by-hop header, `Api-Key`/`x-api-key`, `traceparent`/`tracestate`, or `Authorization` unless `forwardAuthToken` is set — is stripped when it comes from `defaultHeaders` too, even though DIAL Core itself still sees it on the incoming request.

The model-level `defaultHeaders` apply to every interface the model serves. `interfaces.<type>.defaultHeaders` is laid over them for that interface only: a name it repeats is overridden, a new name is added, and every other model-level header still applies.

They are applied once per request, when it enters the model: with `interceptors` configured that is the hop to the first interceptor, from where they travel down the chain. An interceptor's own `defaultHeaders` are applied on the hop that calls it and take precedence over the model's.

**Example**

```json
"models": {
    "openai-gpt-5.4-mini": {
        "type": "chat",
        "defaultHeaders": {
            "x-dial-cache-policy": "cache-priority",
            "x-dial-custom-header": "foo-bar"
        },
        "interfaces": {
            "openaiChatCompletions": { "base_url": "http://dial-openai-adapter" },
            "openaiResponses": { "base_url": "http://dial-openai-adapter" },
            "anthropicMessages": {
                "base_url": "http://dial-anthropic-adapter",
                "defaultHeaders": {
                    "x-dial-custom-header": "foo-bar-2",
                    "x-dial-custom-header-2": "some-value"
                }
            }
        }
    }
}
```

The effective headers are `x-dial-cache-policy: cache-priority` and `x-dial-custom-header: foo-bar` for `chat/completions`, `embeddings` and the Responses API, and `x-dial-cache-policy: cache-priority`, `x-dial-custom-header: foo-bar-2`, `x-dial-custom-header-2: some-value` for the Anthropic Messages API.

#### models.<model_name>.limits

Parameters defining the token limits that apply to the model. Use to ensure that the model does not exceed a specified token limit during interactions.

* `maxPromptTokens`: Maximum number of tokens in a completion request.
* `maxCompletionTokens`: Maximum number of tokens in a completion response.
* `maxTotalTokens`: Maximum number of tokens in completion request and response combined. Typically either `maxTotalTokens` is specified or `maxPromptTokens` and `maxCompletionTokens`.

**Example**

```json
"models": {
        "chat-gpt-35-turbo": {
            "limits": {
                "maxTotalTokens": 1000,
                "maxPromptTokens": 200,
                "maxCompletionTokens": 800,
            },
        }
}
```

#### models.<model_name>.pricing

Parameters defining the pricing for the model. Use to enables real-time cost estimation and quota enforcement.

* `unit`: the pricing units
    * `token`: Every token sent or received by the model is counted towards your cost metrics.
    * `char_without_whitespace`: Tells DIAL to count only non-whitespace characters (letters, numbers, punctuation) in each request as the billing unit.
    * `none`: disables all cost tracking for this model.
* `prompt`: Cost per unit for prompt tokens.
* `completion`: Cost per unit for completion tokens (chat responses).
* `cacheRead` / `cacheWrite` (optional, `unit: "token"` only): Cost per cache-read/cache-write
  token. Each is either a flat rate string (same convention as `prompt`/`completion`) or a
  decision-tree object that picks a rate based on the call's own usage data — the field is
  either a standard name (`cachedReadTokens`, `cachedWriteTokens`, `promptTokens`, `serviceTier`,
  `ttl`) or a `$`-prefixed JSON Path expression. Left unset, cache tokens are billed at the
  `prompt` rate, exactly as if caching weren't split out at all.

**Example**

```json
"models": {
        "chat-gpt-35-turbo": {
            "pricing": {
                "unit": "token",
                "prompt": "0.56",
                "completion": "0.67"
            },
        },
        "claude-sonnet-4-5": {
            "pricing": {
                "unit": "token",
                "prompt": "0.000003",
                "completion": "0.000015",
                "cacheRead": "0.0000003",
                "cacheWrite": {
                    "test": { "field": "ttl", "operator": "==", "value": "1h" },
                    "ifTrue": "0.000006",
                    "ifFalse": "0.00000375"
                }
            },
        }
}
```

#### models.<model_name>.features

In features you can specify optional capabilities of the model. You can use model's features to tailor DIAL Core’s Unified Protocol behavior—turning features on when your model supports them, or off when it doesn’t.

Some models adapters expose specialized HTTP endpoints for tokenization, rate estimation, prompt truncation, or live configuration. You can override the default Unified Protocol calls by specifying them in this section.

* `rateEndpoint`: URL to invoke the model’s cost‐estimation or billing API. Exposed by DIAL Core as `<deployment name>/rate`.
* `tokenizeEndpoint`: URL to invoke a standalone tokenization service. Exposed by DIAL Core as `<deployment name>/tokenize`. Use when you need precise token counts before truncation or batching. Models without built-in tokenization require this.
* `truncatePromptEndpoint`: URL to invoke a prompt‐truncation API. Exposed by DIAL Core as `<deployment name>/truncate_prompt`. Ensures prompts are safely cut to max context length. Useful when working with very long user inputs.
* `configurationEndpoint`: A URL to fetch JSON Schema describing settings of the DIAL model. DIAL Core exposes this endpoint to DIAL clients as `GET v1/deployments/<deployment name>/configuration`. DIAL client must provide a JSON value corresponding to the configuration JSON Schema in a chat completion request in the `custom_fields.configuration` field.
* `systemPromptSupported`: A boolean parameter to enable/disable a system‐level message (the "agent’s instructions") at the start of every chat. Disable for models that ignore or block system prompts. Default is `true`.
* `toolsSupported`: A boolean parameter to enable/disable `tools` (a.k.a. functions) feature for safe external API calls. Enable if you plan to use DIAL Add-ons or function calling. Default is `false`.
* `seedSupported`: A boolean parameter to enable/disable `seed` parameter for deterministic output. Use in testing or reproducible workflows. Default is `false`.
* `urlAttachmentsSupported`: A boolean parameter to enable/disable passing URLs as attachments (images, docs) to the model. Can be required for image-based or file-referencing prompts. Default is `false`.
* `folderAttachmentsSupported`: A boolean parameter to enable/disable attaching folders (batching multiple files). Default is `false`.
* `accessibleByPerRequestKey`: A boolean parameter to enable/disable access to the model with a [per-request API key](https://docs.dialx.ai/platform/core/per-request-keys). Default is `true`.
* `contentPartsSupported`: A boolean parameter that indicates whether the deployment supports requests with content parts. Default is `false`.
* `maxTokensSupported`: A boolean parameter that indicates whether the upstream accepts the legacy `max_tokens` parameter in chat completions requests. Default is `true`.
* `maxCompletionTokensSupported`: A boolean parameter that indicates whether the upstream accepts `max_completion_tokens` parameter in chat completions requests. Default is `false`.
* `customTemperatureSupported`: A boolean parameter that indicates whether arbitrary `temperature` values are accepted. If `false`, only the API default (usually `1`) should be used and the client is recommended not to send the `temperature` parameter. Default is `true`.
* `cacheSupported`: A boolean parameter that indicates whether the deployment supports [LLM caching](https://docs.dialx.ai/tutorials/developers/prompt-caching). Default is `false`.
* `autoCachingSupported`: A boolean parameter that indicates whether the deployment supports [automatic caching](https://docs.dialx.ai/tutorials/developers/prompt-caching), where it's possible. Default is `false`.
* `parallelToolCallsSupported`: A boolean parameter that indicates whether the deployment supports `parallel_tool_calls` parameter in a chat completion request. Default is `true`.
* `assistantAttachmentsInRequestSupported`: A boolean parameter that indicates whether the deployment supports DIAL attachments in the assistant messages. Default is `false`. When set to `true`, DIAL Chat must preserve attachments in the assistant messages, instead of removing them. The feature is especially useful for models that can generate attachments as well as take attachments in its input. A typical example of such a model is an image-editing model.
* `supportCommentInRateResponse`: A boolean parameters that indicates whether the application supports the field `comment` in rate response payload.
* `reasoningEfforts`: A list of supported `effort` values for chat completions requests (e.g., `low`, `medium`, `high`). An empty list means the deployment does not support the `effort` parameter. Default is `[]`.

**Example**

```json
"models": {
        "chat-gpt-35-turbo": {
            "features": {
                "rateEndpoint": "http://host/rate",
                "tokenizeEndpoint": "http://host/tokinize",
                "truncatePromptEndpoint": "http://host/truncate",
                "configurationEndpoint": "http://host/configure",
                "maxTokensSupported": true,
                "maxCompletionTokensSupported": false,
                "customTemperatureSupported": true,
                "systemPromptSupported": false,
                "toolsSupported": false,
                "seedSupported":false,
                "urlAttachmentsSupported": false,
                "folderAttachmentsSupported": false,
                "accessibleByPerRequestKey": true,
                "contentPartsSupported": false,
                "assistantAttachmentsInRequestSupported": false,
                "reasoningEfforts": []
            },
        }
}
```

#### models.<model_name>.upstreams.interfaces

A typed alternative to the flat `endpoint`/`responsesEndpoint` fields, declaring the upstream backend URL per LLM API rather than per field. Both shapes are first-class and fully supported — choose whichever you prefer per upstream. Use `interfaces` when a provider serves several APIs for the same model (for example, Fireworks exposes both the OpenAI and the Anthropic API), so that one model deployment can front all of them instead of one deployment per API.

The interface types are the same as on the [model level](#modelsmodel_nameinterfaces) — `openaiChatCompletions`, `openaiEmbeddings`, `openaiResponses`, `anthropicMessages` — but each value carries a complete `endpoint` rather than a `base_url`, because an upstream is the provider itself and no ingress path is routed into it.

Each value is an object with the following fields, every one of which overrides its upstream-level namesake for that interface alone:

* `endpoint`: The complete upstream backend URL for this interface. Optional when the upstream declares a `baseUrl`.
* `key`: API key, token, or credential for this interface. Falls back to the upstream's `key`.
* `extraData`: Additional metadata for this interface. Falls back to the upstream's `extraData`.
* `secretExtraData`: Secret additional metadata for this interface. Falls back to the upstream's `secretExtraData`.

Each field falls back independently, so an interface overriding only `key` still inherits `extraData`. `extraData` and `secretExtraData` are then merged into the single `X-UPSTREAM-EXTRA-DATA` header exactly as at the upstream level, with `secretExtraData` winning on shared keys. Declaring the same key in *both* halves of one level is rejected as ambiguous, but an interface's half overriding the upstream's is the point of the feature and is allowed.

An entry with no `endpoint` is completed from the upstream's `baseUrl` plus the path the interface's own API spec serves it at, ignoring the `/openai` and `/anthropic` ingress keywords:

| Interface type          | Path appended to `baseUrl` |
|-------------------------|----------------------------|
| `openaiChatCompletions` | `/v1/chat/completions`     |
| `openaiEmbeddings`      | `/v1/embeddings`           |
| `openaiResponses`       | `/v1/responses`            |
| `anthropicMessages`     | `/v1/messages`             |

This is the one difference from `interfaces.<interface_type>.base_url` on the model level, which is instead concatenated with the ingress path the request reached DIAL Core on (`/openai/v1/responses`, `/openai/deployments/{name}/chat/completions`, `/anthropic/v1/messages`, ...).

`baseUrl` applies only to the interface types the upstream declares: `interfaces` is a whitelist, not a default. An interface type absent from the map falls back to the legacy field serving it — `responsesEndpoint` for `openaiResponses`, `endpoint` for every other type — so upstreams configured before the split keep working unchanged.

Two rules are enforced on load, and a model violating either is rejected:

* Every declared interface must resolve to a URL — its own `endpoint`, or the upstream's `baseUrl`.
* The upstream must declare an `id`. Without `interfaces`, an upstream falls back to its `endpoint` as its identifier for `X-UPSTREAM-ID` routing and prompt-cache pinning; this shape has no `endpoint`, so the `id` has to be explicit.

**Example**

```json
"models": {
        "openai-gpt-5.4-mini": {
            "overrideName": "gpt-5.4-mini",
            "type": "chat",
            "upstreams": [
                {
                    "id": "fireworks",
                    "key": "modelKey1",
                    "extraData": {"region": "us-east-1"},
                    "baseUrl": "https://api.fireworks.ai/inference",
                    "interfaces": {
                        "openaiChatCompletions": {},
                        "openaiResponses": {},
                        "anthropicMessages": {
                            "endpoint": "https://api.fireworks.ai/inference/something-else/v1/messages",
                            "key": "anthropicKey1"
                        }
                    }
                }
            ]
        }
}
```

Here `openaiChatCompletions` resolves to `https://api.fireworks.ai/inference/v1/chat/completions`, `openaiResponses` to `https://api.fireworks.ai/inference/v1/responses`, and `anthropicMessages` to the explicit URL, which wins over `baseUrl`. All three send `{"region": "us-east-1"}` as extra data, inherited from the upstream; the first two authenticate with `modelKey1` and `anthropicMessages` with `anthropicKey1`.

The legacy fields coexist with the map, yielding only for the types it declares. Given an upstream that sets `endpoint`, `responsesEndpoint` and `interfaces: {"openaiChatCompletions": {}, "anthropicMessages": {}}`, chat completions and Anthropic Messages are served by the map (from `baseUrl`), while the Responses API — which the map does not declare — still uses `responsesEndpoint`.

#### models.<model_name>.upstreams

Upstreams configurations. Use to configure [load balancing](https://docs.dialx.ai/platform/core/load-balancer).

* `endpoint`: The upstream backend URL for the chat completions API. Passed to the model adapter in the `X-UPSTREAM-ENDPOINT` header. It also backs the embeddings and Anthropic Messages APIs — prefer `interfaces` to configure those explicitly.
* `responsesEndpoint`: The upstream backend URL for the Responses API. Passed to the model adapter in the `X-UPSTREAM-ENDPOINT` header when routing Responses API requests.
* `interfaces`: A typed alternative to `endpoint`/`responsesEndpoint`, declaring one upstream backend URL per LLM API. Refer to [models.<model_name>.upstreams.interfaces](#modelsmodel_nameupstreamsinterfaces).
* `baseUrl`: The provider root that completes every `interfaces` entry declaring no `endpoint` of its own. Refer to [models.<model_name>.upstreams.interfaces](#modelsmodel_nameupstreamsinterfaces).
* `id`: A stable identifier for this upstream. Clients can set the `X-UPSTREAM-ID` request header to this value to pin a request to a specific upstream (supported in chat completions and Responses API). When the Responses API is enabled (via the model-level `responsesEndpoint`), `id` is required — it is used to route Responses API follow-up requests (retrieve, cancel, delete) back to the same upstream that handled the initial request. `id` is also required on any upstream declaring `interfaces`, because that shape has no `endpoint` to fall back on as an identifier.
* `key`: API key, token, or credential passed to the upstream.
* `weight`: Weight for upstream endpoint; positive number represents an endpoint capacity, zero or negative disables this endpoint from routing. Higher = more traffic share. Default value: 1.
* `tier`: Specifies tier group for the endpoint. Only positive numbers allowed. All requests will be routed to the endpoints with the highest tier (the lowest tier value), other endpoints (with lower tier/higher tier value) may be used only if the highest tier endpoints are unavailable. Default value: 0 - highest tier. Refer to [load balancing](https://docs.dialx.ai/platform/core/load-balancer) to learn more.
* `extraData`: Additional metadata containing any information that is passed to the upstream's endpoint. It can be a JSON or String.

**Example**

```json
"models": {
        "gpt-5.4-2026-03-05": {
            "upstreams": [
                {
                    "endpoint": "http://localhost:7001/openai/deployments/gpt-5.4-2026-03-05/chat/completions",
                    "responsesEndpoint": "http://localhost:7001/openai/v1/responses",
                    "key": "modelKey1"
                },
                {
                    "endpoint": "http://localhost:7002/openai/deployments/gpt-5.4-2026-03-05/chat/completions",
                    "responsesEndpoint": "http://localhost:7002/openai/v1/responses",
                    "key": "modelKey2"
                },
                {
                    "endpoint": "http://localhost:7003/openai/deployments/gpt-5.4-2026-03-05/chat/completions",
                    "responsesEndpoint": "http://localhost:7003/openai/v1/responses",
                    "key": "modelKey3"
                }
            ],
        }
}
```

