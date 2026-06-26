# Dynamic Setting for Applications

In dynamic settings, you can include applications and their parameters you wish to enable in DIAL.

> Refer to [DIAL Admin](https://docs.dialx.ai/tutorials/admin/entities-applications) to learn how to manage apps in DIAL Admin UI.

## applications

A list of deployed applications and their [parameters](#applicationsapplication_name).

* `<application_name>`: A unique application name.

**Example**

```json
"applications": {
        "app1": {},
        "app2": {}
}
```

### applications.<application_name> 

An object containing parameters for each [application](#applications).

> **Effective Parameter Rule**: When `applicationTypeSchemaId` and `applicationProperties` are specified, parameters defined in the corresponding schema will take precedence and will override the corresponding parameters specified in the `application` object.

* `applications.<application_name>.applicationTypeSchemaId`: The identifier of a JSON schema that application is based upon. The shema ID must exist in the DIAL Core config property `applicationTypeSchemas`. Refer to [DIAL Documentation](https://docs.dialx.ai/platform/core/apps#application-types) to learn more about schema-rich apps.
* `applications.<application_name>.applicationProperties`: Properties of a schema-rich application. Specified properties must conform to the JSON schema referenced by `applicationTypeSchemaId`. Refer to [DIAL Documentation](https://docs.dialx.ai/platform/core/apps#application-types) to learn more about schema-rich apps.
* `interfaces`: An object declaring the LLM API interfaces the application supports, keyed by interface type, each with a `base_url` pointing at the adapter root. At request time DIAL Core forwards to `{base_url}` + the exact ingress path it received. Supported interface types: `openaiChatCompletions` (chat completions) and `openaiResponses` (the OpenAI Responses API) — see the [full list of supported LLM API interfaces](../../README.md#llm-api-interfaces-interfaces). This is the recommended replacement for the deprecated `endpoint`/`responsesEndpoint` fields — see the example below.
* `endpoint`: **Deprecated** — use `interfaces.openaiChatCompletions.base_url` instead. The application's API endpoint for chat completion requests; migrated automatically at config load (the authority `scheme://host[:port]` becomes the `base_url`). See the `config.migrateLegacyEndpoints` setting in the [README](../../README.md#dynamic-settings) for on-disk config write-back.
* `iconUrl`: A string with URL of the icon to display for the app in the UI.
* `description`: A string with a brief description of the application.
* `displayName`: A string with the app's name. Display name is shown in all DIAL client UI dropdowns, tables, and logs for identification purposes.
* `displayVersion`: A string with the app's version.
* `inputAttachmentTypes`: A list of allowed [MIME types](https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types/Common_types) for the input attachments.
* `maxInputAttachments`: Maximum number of input attachments. If `inputAttachmentTypes` is not set, this value is zero. Otherwise, if not specified, the default is unlimited.
* `forwardAuthToken`: A boolean parameter to determine whether the Auth Token should be forwarded from the caller's session to the upstream API call. This enables multi-tenant scenarios or pass-through authentication for downstream services. If this flag is set to `true`, the Http header with authorization token is forwarded to the chat completion endpoint of the application.
* `userRoles`: A specific `claim` value provided by a specific IDP in JWT or an [API key role](./keys.md). If not defined, the application is available to all users. Refer to [IDP Configuration](https://docs.dialx.ai/tutorials/devops/auth-and-access-control/configure-idps/overview) to view examples.
* `descriptionKeywords`: A list of keywords describing the application, e.g. `code-gen`, `text2image`.
* `maxRetryAttempts`: The number of times DIAL Core will [retry](https://docs.dialx.ai/platform/core/load-balancer#fallbacks) a connection in case of upstream errors.
* `author`: The application's developer.
* `createdAt`: The date of the application creation.
* `updatedAt`: The date of the last application update. 
* `dependencies`: A list of dependent deployments (applications, AI models) which the application may use. Refer to [Managing Authorization in Complex Application Ecosystems](https://docs.dialx.ai/tutorials/developers/apps-development/auth-matrix) to learn more about dependencies.
* `viewerUrl`: A string with URL of the application's [custom viewer UI](https://github.com/epam/ai-dial-chat/tree/development/docs). A custom UI, if enabled, will override the standard DIAL Chat UI.
* `editorUrl`: A string with URL of the application's custom builder UI. Application builder allows DIAL Chat end-users to create instances of apps using a [UI wizards](https://docs.dialx.ai/tutorials/user-guide#application-builder).
* `defaults`: Default parameters are applied if a request doesn't contain them in OpenAI `chat/completions` API call.         
* `interceptors`: A list of local interceptors to be triggered for the given application. Refer to [Interceptors](./interceptors.md) to learn more.
* `mcp`: MCP configuration. Refer to [MCP](#applicationsapplication_namemcp) to learn more.
* `features`: A list of features supported by the application. Refer to [Features](#applicationsapplication_namefeatures) for more details.
* `routes`: A list of registered routes in the application. Refer to [applications.<application_name>.routes](#applicationsapplication_nameroutes) for more details.

**Example**:

```json
    "applications": {
        "app": {
            "displayName": "Forecast",
            "iconUrl": "https://host/app.svg",
            "description": "Application that provides forecast",
            "descriptionKeywords": ["code-gen"],
            "endpoint": "http://localhost:7001/openai/deployments/10k/chat/completions",
            "interfaces": {
              "openaiChatCompletions": { "base_url": "http://localhost:7001" }
            },
            "userRoles": [
                "Forecast"
            ],
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
            "interceptors": ["interceptor1", "interceptor2", "interceptor3"],
            "mcp": {
                "endpoint": "http://host/mcp",
                "transport": "http",
                "allowedTools": ["tool1", "tolol2"],
                "configDelivery": "meta",
                "forwardPerRequestKey": true
            },
            "routes": {
                "vector_store_query": {
                    "paths": ["/v1/vector_store(/[^/]+)*$"],
                    "rewritePath": true,
                    "methods": ["GET", "HEAD"],
                    "userRoles": ["role1"],
                    "upstreams": [
                        {
                            "endpoint": "http://localhost:9876"
                        },
                        {
                            "endpoint": "http://localhost:9877"
                        }
                    ],
                    "order": 1,
                    "permissions": [
                        "WRITE"
                    ],
                    "attachmentPaths": {
                        "requestBody": [
                            "@.attachments[*].url"
                        ],
                        "responseBody": [
                            "@.result.attachedFiles"
                        ]
                    }
                },
                "rate": {
                    "paths": ["/v1/rate"],
                    "rewritePath": true,
                    "methods": ["GET", "HEAD"],
                    "response": {
                        "status": 200,
                        "body": "OK"
                    },
                    "order": 2
                }
            }
        }
    },
```

#### applications.<application_name>.features

> **Effective Parameter Rule**: When `applicationTypeSchemaId` and `applicationProperties` are specified, parameters defined in the corresponding schema will take precedence and will override the corresponding parameters specified in the `application` object.

Use `features` to specify additional capabilities of the application. Refer to [DIAL Admin](https://docs.dialx.ai/tutorials/admin/entities-applications#features) to learn more about features and the difference between model and app features. 

The following features are supported:

* `rateEndpoint`: A URL of a custom rate-estimation API to compute cost or quota usage based on your custom logic (e.g. grouping by tenant, complex billing rules). Exposed by DIAL Core as `<deployment name>/rate`.
* `tokenizeEndpoint`: A URL to call a custom tokenization service. Can be used if you require precise, app-wide token counting (for mixed-model or multi-step prompts) that the model adapter can’t provide. Exposed by DIAL Core as `<deployment name>/tokenize`.
* `truncatePromptEndpoint`: A URL to call your own prompt-truncation API. Handy if you implement advanced context-window management (e.g. dynamic summarization) before the actual app call. Exposed by DIAL Core as `<deployment name>/truncate_prompt`.
* `configurationEndpoint`: A URL to fetch JSON Schema describing settings of the application. DIAL Core exposes this endpoint to DIAL clients as `GET v1/deployments/<deployment name>/configuration`. DIAL client must provide a JSON value corresponding to the configuration JSON Schema in a chat completion request in the `custom_fields.configuration` field.
* `systemPromptSupported`: A boolean parameter that enables/disables an initial "system" message injection. Useful for orchestrating multi-step agents where you need to enforce a global policy at the application level. Default is `true`.
* `toolsSupported`: A boolean parameter that enables/disables tools/functions payloads in API calls. Switch on if your application makes external function calls (e.g. calendar lookup, database fetch). Default is `false`.
* `seedSupported`: A boolean parameter that enables/disables the `seed` parameter for reproducible results. Great for testing or deterministic pipelines. Disable to ensure randomized creativity. Default is `false`.
* `urlAttachmentsSupported`: A boolean parameter that enables/disables URL references (images, docs) as attachments in API requests. Must be enabled if your workflow downloads or processes remote assets via URLs. Default is `false`.
* `folderAttachmentsSupported`: A boolean parameter that enables/disables attachments of folders (batching multiple files). Default is `false`.
* `accessibleByPerRequestKey`: A boolean parameter that indicates whether the deployment is accessible using a per-request API key. Default is `true`.
* `contentPartsSupported`: A boolean parameter that indicates whether the deployment supports requests with content parts or not.Default is `false`.
* `consentRequired`: A boolean parameter that indicates whether the application requires user consent before use.
* `supportCommentInRateResponse`: A boolean parameters that indicates whether the application supports the field `comment` in rate response payload.
* `reasoningEfforts`: A list of supported `effort` values for chat completions requests (e.g., `low`, `medium`, `high`). An empty list means the deployment does not support the `effort` parameter. Default is `[]`.

**Example**:

```json
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
```

#### applications.<application_name>.mcp

Use `mcp` to specify configuration parameters for Model Context Protocol (MCP) interface for the application.

Supported configuration parameters: 

* `endpoint`: The application's MCP endpoint DIAL Core will use to communicate with application.
* `transport`: Transport used by MCP server for transmitting MCP messages between client and server. `http` by default.
* `allowedTools`: A list of available tools in the MCP server.
* `configDelivery`: Determines how application properties are sent to the MCP server. Choose `Header` to deliver application properties in Http header. Choose `Meta` to include application properties in `_meta` field within the MCP message payload.
* `forwardPerRequestKey`: Set this flag to `true` if you want a per request API key to be forwarded to the MCP Server endpoint allowing it to access files in the DIAL storage.

**Example**:

```json
"mcp": 
{
    "endpoint": "http://host/mcp",
    "transport": "http",
    "allowedTools": ["tool1", "tool2"],
    "configDelivery": "meta",
    "forwardPerRequestKey": true
}
```

#### applications.<application_name>.routes

> **Effective Parameter Rule**: When `applicationTypeSchemaId` and `applicationProperties` are specified, parameters defined in the corresponding schema will take precedence and will override the corresponding parameters specified in the `application` object.

A list of registered routes in the application. Refer to [Routes](./routes.md) for more information.

* `applications.<application_name>.routes.<route_name>.userRoles`: Route is accessible by user roles from this list. If not defined, `userRoles` are inherited from the parent application. If defined, they override the `userRoles` of the parent application.
* `applications.<application_name>.routes.<route_name>.response`: A pre-configured route's response. If the `response` is set, DIAL Core returns the response immediately. Available values:  
    - `status`: HTTP status code  
    - `body`: HTTP response body  
* `applications.<application_name>.routes.<route_name>.rewritePath`: A flag indicating that the path to the upstream server will be replaced with the path of the original request if this flag is set to `true`.
* `applications.<application_name>.routes.<route_name>.paths`: A list of paths to match the request's path. If any path is matched, the request will be processed by this route. **Note:** A path can be a plain string or a regular expression.
* `applications.<application_name>.routes.<route_name>.methods`:  A list of HTTP methods supported by this route.
* `applications.<application_name>.routes.<route_name>.upstreams`: A list of upstream servers with parameters:
    * `endpoint`: A route's endpoint.
    * `key`: Your API key.
    * `weight`: Weight for upstream endpoint; positive number represents endpoint capacity, zero or negative disables this endpoint from routing. Default: 1.
    * `tier`: Specifies a tier group for the endpoint. Only positive numbers are allowed. All requests will be routed to endpoints with the highest tier (lowest tier value); other endpoints may be used only if the highest tier endpoints are unavailable. Default: 0 (highest tier). Refer to [load balancing](https://docs.dialx.ai/platform/core/load-balancer) to learn more.
    * `extraData`: Additional metadata containing any information that is passed to the upstream's endpoint. Can be JSON or String.
* `applications.<application_name>.routes.<route_name>.maxRetryAttempts`: Use this parameter to set the **maximum** number of retry attempts if the upstream server returns an unsuccessful response code. The load balancer will try to find another upstream from the list of available upstreams.
* `applications.<application_name>.routes.<route_name>.order`: This parameter determines the order within the application routes. Lower value means higher priority. Cannot be a negative integer. Default: 2^31-1.
* `applications.<application_name>.routes.<route_name>.permissions`: A list of permissions (`READ`, `WRITE`) required for access to the route. Default is an empty list.
* `applications.<application_name>.routes.<route_name>.attachmentPaths`: Use this parameter to specify a list of attachment paths where DIAL Core should look for attachment links.
* `applications.<application_name>.routes.<route_name>.attachmentPaths.requestBody`: This property contains a list of JSON Path strings. DIAL Core will look for attachments in the request body.
* `applications.<application_name>.routes.<route_name>.attachmentPaths.responseBody`: This property contains a list of JSON Path strings. DIAL Core will look for attachments in the response body.

**Example**:

```json
"routes": {
    "vector_store_query": {
        "paths": ["/v1/vector_store(/[^/]+)*$"],
        "rewritePath": true,
        "methods": ["GET", "HEAD"],
        "userRoles": ["role1"],
        "upstreams": [
            {
                "endpoint": "http://localhost:9876"
            },
            {
                "endpoint": "http://localhost:9877"
            }
        ],
        "order": 1,
        "permissions": [
            "WRITE"
        ],
        "attachmentPaths": {
            "requestBody": [
                "@.attachments[*].url"
            ],
            "responseBody": [
                "@.result.attachedFiles"
            ]
        }
    },
    "rate": {
        "paths": ["/v1/rate"],
        "rewritePath": true,
        "methods": ["GET", "HEAD"],
        "response": {
            "status": 200,
            "body": "OK"
        },
        "order": 2
    }
}
```


