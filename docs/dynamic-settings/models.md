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
* An `interfaces` entry declares a `base_url`, and DIAL Core forwards each request to `base_url` + **the exact ingress path it was received on** (e.g. `POST /openai/v1/responses` is routed to `<base_url>/openai/v1/responses`). A trailing slash on `base_url` is normalized.

If both `interfaces` and a legacy field are declared for the same interface type, `interfaces` takes precedence; the legacy field is left untouched in config and ignored for routing.

Supported interface types for models:

* `openaiChatCompletions`: the OpenAI deployments POST family (`chat/completions`, `completions`). Peer of `endpoint`.
* `openaiEmbeddings`: the OpenAI deployments `embeddings` endpoint.
* `openaiResponses`: the OpenAI Responses API. Peer of `responsesEndpoint`.
* `anthropicMessages`: the Anthropic Messages API (`/anthropic/v1/messages`, `/anthropic/v1/messages/count_tokens`).

The `interfaces` map is strict: chat completions is configured via `openaiChatCompletions` and embeddings via `openaiEmbeddings`, and one never stands in for the other — a model declaring only `openaiChatCompletions` answers `503` to `embeddings`, and a model declaring only `openaiEmbeddings` answers `503` to `chat/completions` and `completions`. The untyped legacy `endpoint` predates the split and keeps serving `embeddings` requests verbatim, so models configured before the split keep working unchanged.

Only the interface types a model declares are reported in the `interfaces` array of the `/v1/deployments` listing. A legacy `endpoint` is advertised as the interface matching what the model says it is: `openaiEmbeddings` when `type` is `embedding`, `openaiChatCompletions` otherwise — so an embedding model configured this way reports `openaiEmbeddings` and `"chat_completion": false`, even though that one endpoint still serves the whole deployments POST family.

Each value is an object with the following fields:

* `base_url`: The model adapter root that the matching ingress path is appended to.
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

A default header behaves exactly as if the client had sent it: DIAL Core reads it as part of the incoming request — so `X-DIAL-CACHE-POLICY` set this way drives upstream cache pinning, not just what the adapter receives — and forwards it under the same rules as a client header. Headers DIAL Core never forwards are not forwarded here either: hop-by-hop headers, `Api-Key`/`x-api-key`, and `Authorization` unless `forwardAuthToken` is set.

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

**Example**

```json
"models": {
        "chat-gpt-35-turbo": {
            "pricing": {
                "unit": "token",
                "prompt": "0.56",
                "completion": "0.67"
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

#### models.<model_name>.upstreams

Upstreams configurations. Use to configure [load balancing](https://docs.dialx.ai/platform/core/load-balancer).

* `endpoint`: The upstream backend URL for the chat completions API. Passed to the model adapter in the `X-UPSTREAM-ENDPOINT` header.
* `responsesEndpoint`: The upstream backend URL for the Responses API. Passed to the model adapter in the `X-UPSTREAM-ENDPOINT` header when routing Responses API requests.
* `id`: A stable identifier for this upstream. Clients can set the `X-UPSTREAM-ID` request header to this value to pin a request to a specific upstream (supported in chat completions and Responses API). When the Responses API is enabled (via the model-level `responsesEndpoint`), `id` is required — it is used to route Responses API follow-up requests (retrieve, cancel, delete) back to the same upstream that handled the initial request.
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

