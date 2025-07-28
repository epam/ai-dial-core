# Dynamic Setting for Models

In dynamic settings you can include language models and their parameters you with to enable in DIAL.

|Parameter | Description  |
|----------|----------|
| models  | A list of deployed models and their parameters:<br />`<model_name>`: Unique model name. |
| models.<model_name>| `type`: Model type—`chat` or `embedding`.<br />`iconUrl`: Icon path for the model on UI.<br />`description`: Brief model description.<br />`displayName`: Model name on UI.<br />`displayVersion`: Model version on UI.<br />`endpoint`: Model API for chat completions or embeddings.<br />`tokenizerModel`: Identifies the specific model whose tokenization algorithm exactly matches that of the referenced model. This is typically the name of the earliest-released model in a series of models sharing an identical tokenization algorithm (e.g. `gpt-3.5-turbo-0301`, `gpt-4-0314`, or `gpt-4-1106-vision-preview`). This parameter is essential for DIAL clients that reimplement tokenization algorithms on their side, instead of utilizing the `tokenizeEndpoint` provided by the model.<br />`features`: Model features.<br />`limits`: Model token limits.<br />`pricing`: Model pricing.<br />`upstreams`: Used for [load-balancing—request](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/5.load-balancer.md) is sent to model endpoint containing X-UPSTREAM-ENDPOINT and X-UPSTREAM-KEY headers.<br />`userRoles`: a specific claim value provided by a specific IDP. Refer to [IDP Configuration](https://github.com/epam/ai-dial/blob/main/docs/tutorials/2.devops/2.auth-and-access-control/3.configure-idps/0.overview.md) to view examples.<br />`descriptionKeywords`: a list of keywords describes the model, e.g. `code-gen`, `text2image`.  <br />`maxRetryAttempts`: max retry attempts to route a single user request to upstreams.<br/>`inputAttachmentTypes`: A list of allowed MIME types for the input attachments.<br />`maxInputAttachments`: Maximum number of input attachments (default is zero when `inputAttachmentTypes` is unset, otherwise, infinity). <br />`author`: the model's developer.  <br />`createdAt`: the date of the model creation. <br />`updatedAt`: the date of the last model update. |
| models.<model_name>.limits           | `maxPromptTokens`: maximum number of tokens in a completion request.<br />`maxCompletionTokens`: maximum number of tokens in a completion response.<br />`maxTotalTokens`: maximum number of tokens in completion request and response combined.<br />Typically either `maxTotalTokens` is specified or `maxPromptTokens` and `maxCompletionTokens`.           |
| models.<model_name>.pricing          | `unit`: the pricing units (currently `token` and `char_without_whitespace` are supported).<br />`prompt`: per-unit price for the completion request in USD.<br />`completion`: per-unit price for the completion response in USD.  |
| models.<model_name>.features         | `rateEndpoint`: endpoint for rate requests *(exposed by core as `<deployment name>/rate`)*.<br />`tokenizeEndpoint`: endpoint for requests to the model tokenizer *(exposed by DIAL Core as `<deployment name>/tokenize`)*.<br />`truncatePromptEndpoint`: endpoint for truncating prompt requests *(exposed by DIAL Core as `<deployment name>/truncate_prompt`)*.<br />`systemPromptSupported`: does the model support system prompt (default is `true`).<br />`toolsSupported`: does the model support tools (default is `false`).<br />`seedSupported`: does the model support `seed` request parameter (default is `false`).<br />`urlAttachmentsSupported`: does the model/application support attachments with URLs (default is `false`).<br />`folderAttachmentsSupported`: does the model/application support folder attachments (default is `false`)<br />`accessibleByPerRequestKey`: indicates whether the deployment is accessible using a per-request API key (default is `true`).<br />`contentPartsSupported`: indicates whether the deployment supports requests with content parts or not (default is `false`). <br />`cacheSupported`: indicates whether the deployment supports [LLM caching](https://docs.dialx.ai/tutorials/developers/prompt-caching) (default is `false`). <br />`autoCachingSupported`: indicates whether the deployment supports [automatic caching](https://docs.dialx.ai/tutorials/developers/prompt-caching), where it's possible (default is `false`). <br /> `parallelToolCallsSupported`: indicates whether the deployment supports `parallel_tool_calls` parameter in a chat completion request (default is `true`)    |
| models.<model_name>.upstreams        | `endpoint`: Model endpoint.<br />`key`: Your API key.<br />`weight`: Weight for upstream endpoint; positive number represents an endpoint capacity, zero or negative disables this enpoint from routing. Default value: 1.<br />`tier`: Specifies tier group for the endpoint. Only positive numbers allowed. All requests will be routed to the endpoints with the highest tier (the lowest tier value), other endpoints (with lower tier/higher tier value) may be used only if the highest tier endpoints are unavailable. Default value: 0 - highest tier. Refer to [Load Balancer](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/5.load-balancer.md) to learn more.<br/>`extraData`: Additional metadata containing any information that is passed to the upstream's endpoint. It can be a JSON or String.  |
| models.<model_name>.defaults         | Default parameters are applied if a request doesn't contain them in OpenAI `chat/completions` API call            |
| models.<model_name>.interceptors     | A list of interceptors to be triggered for the given model. Refer to [Interceptors](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/6.interceptors.md) to learn more.   |
| models.<model_name>.fieldsHashingOrder       | A list of chat completion request components that defines an order in which they are used to compute a hash of the request. The components of the request are identified by strings `prefix.body.tools` and `prefix.body.messages`. The default value of the parameter is ["prefix.body.tools", "prefix.body.messages"], meaning the hash is first computed for the tools definitions, then extended with the hash of the messages. It reflects the relative order of tools and messages components when they are converted to tokens and fed into a typical LLM. The hash is used uniquely identify prefixes of the request that are marked by [cache breakpoints](https://docs.dialx.ai/tutorials/developers/prompt-caching). It enables DIAL Core to redirect independent requests that are sharing the same prefix to the same upstream endpoint. This is essential to enable context caching feature of LLM since their caching scope is limited to a simple upstream endpoint.| 

**Configuration Example:**

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
            "interceptors": ["interceptor1"]
        },
        "embedding-ada": {
            "type": "embedding",
            "endpoint": "http://localhost:7001/openai/deployments/ada/embeddings",
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