# Dynamic Setting for Applications

In dynamic settings you can include applications and their parameters you with to enable in DIAL.

|Parameter | Description  |
|----------|----------|
| applications       | A list of deployed DIAL Applications and their parameters:<br />`<application_name>`: Unique application name. |
| applications.<application_name>      | `endpoint`: DIAL Application API for chat completions. **Note**. It should be unset if `applicationTypeSchemaId` is set<br />`iconUrl`: Icon path for the DIAL Application on UI.<br />`description`: Brief DIAL Application description.<br />`displayName`: DIAL Application name on UI.<br />`inputAttachmentTypes`: A list of allowed MIME types for the input attachments.<br />`maxInputAttachments`: Maximum number of input attachments (default is zero when `inputAttachmentTypes` is unset, otherwise, infinity) <br/> `forwardAuthToken`: If flag is set to `true` forward Http header with authorization token to chat completion endpoint of the application. <br />`userRoles`: a specific claim value provided by a specific IDP. Refer to [IDP Configuration](https://github.com/epam/ai-dial/blob/main/docs/tutorials/2.devops/2.auth-and-access-control/3.configure-idps/0.overview.md) to view examples.<br />`descriptionKeywords`: a list of keywords describes the application, e.g. `code-gen`, `text2image`. <br />`maxRetryAttempts`: max retry attempts to route a single user request to the application's endpoint. <br />`author`: the application's developer.  <br />`createdAt`: the date of the application creation. <br />`updatedAt`: the date of the last application update. <br/> `dependencies`: a list of dependent deployments which the application may use.<br/> `viewerUrl`: an optional field with a URL of the application's custom UI.<br/> `editoUrl`: an optional field with a URL of the application's custom builder UI.          |
| applications.<application_name>.defaults     | Default parameters are applied if a request doesn't contain them in OpenAI `chat/completions` API call            |
| applications.<application_name>.interceptors | A list of interceptors to be triggered for the given application. Refer to [Interceptors](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/6.interceptors.md) to learn more.     |
| applications.<application_name>.features     | `rateEndpoint`: endpoint for rate requests *(exposed by DIAL Core as `<deployment name>/rate`)*.<br />`tokenizeEndpoint`: endpoint for requests to the model tokenizer *(exposed by DIAL Core as `<deployment name>/tokenize`)*.<br />`truncatePromptEndpoint`: endpoint for truncating prompt requests *(exposed by DIAL Core as `<deployment name>/truncate_prompt`)*.<br />`systemPromptSupported`: does the application support system prompt (default is `true`).<br />`toolsSupported`: does the application support tools (default is `false`).<br />`seedSupported`: does the application support `seed` request parameter (default is `false`).<br />`urlAttachmentsSupported`: does the application support attachments with URLs (default is `false`).<br />`folderAttachmentsSupported`: does the application support folder attachments (default is `false`)<br />`configurationEndpoint`: the endpoint to request application configuration parameters as JSON schema *(exposed by DIAL Core as `<deployment name>/configuration`)*.<br />`accessibleByPerRequestKey`: indicates whether the deployment is accessible using a per-request API key (default is `true`).<br />`contentPartsSupported`: indicates whether the deployment supports requests with content parts or not (default is `false`). <br /> `consentRequired`: indicates whether the application requires user consent before use.      |
| applications.<application_name>.applicationTypeSchemaId        | Application rich schema ID.  The ID must exist in the config property `applicationTypeSchemas`. |
| applications.<application_name>.applicationProperties          | Application rich schema properties. The properties must conform to the application rich schema referenced by `applicationTypeSchemaId`. |
| applications.<application_name>.routes       | A list of registered routes in the application. A route is used to proxy request through DIAL Core to upstream server.<br />DIAL Core provides capabilities: rate limiting, role based authorization, request balancing and access to DIAL Core resources such as LLMs, applications, file storage.|
| applications.<application_name>.routes.<route_name>.userRoles  | Route is accessible by user roles from this list.          |
| applications.<application_name>.routes.<route_name>.response   | Pre-configured route's response:<br />`status` - http status code<br />`body` - http response body.<br />If the `response` is set then DIAL Core returns the response immediately.  |
| applications.<application_name>.routes.<route_name>.rewritePath| A flag indicates that the path to the upstream server will be replaced with the path of the original request, if this flag is set to `true`               |
| applications.<application_name>.routes.<route_name>.paths      | A list of paths to be matched request's path. If any path is matched, the request will be processed by this route.<br />**Note**. A path can be a plain string or a regular expression.|
| applications.<application_name>.routes.<route_name>.methods    | A list of HTTP methods supported by this route             |
| applications.<application_name>.routes.<route_name>.upstreams  | A list of upstream servers. <br />`endpoint`: Route endpoint.<br />`key`: Your API key.<br />`weight`: Weight for upstream endpoint; positive number represents an endpoint capacity, zero or negative disables this enpoint from routing. Default value: 1.<br />`tier`: Specifies a tier group for the endpoint. Only positive numbers are allowed. All requests will be routed to the endpoints with the highest tier (the lowest tier value), other endpoints (with lower tier/higher tier value) may be used only if the highest tier endpoints are unavailable. Default value: 0 - highest tier. Refer to [Load Balancer](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/5.load-balancer.md) to learn more.<br/>`extraData`: Additional metadata containing any information that is passed to the upstream's endpoint. It can be a JSON or String.                 |    |
| applications.<application_name>.routes.<route_name>.maxRetryAttempts             | Maximum number of retry attempts in case if upstream server returns unsuccessful response code. In this case load balancer will try to find another upstream from the list of available upstreams.               |
| applications.<application_name>.routes.<route_name>.order      | The value determines the order within the application routes. The lower value means the higher priority. The value can't be negative integer. The default one is 2^31-1.               |
| applications.<application_name>.routes.<route_name>.permissions| The list of permissions (`READ`, `WRITE`) are required to for access to the route. The default value is an empty list.              |
| applications.<application_name>.routes.<route_name>.attachmentPaths              | The property specifies a list of attachment paths where DIAL Core should look for attachment links.               |
| applications.<application_name>.routes.<route_name>.attachmentPaths.requestBody  | The property contains a list of JSON Paths' strings. The DIAL Core will look for attachments in the request body. |
| applications.<application_name>.routes.<route_name>.attachmentPaths.responseBody | The property contains a list of JSON Paths' strings. The DIAL Core will look for attachments in the response body.|

**Configuration Example:**

```json
"applications": {
        "app": {
            "endpoint": "http://localhost:7001/openai/deployments/10k/chat/completions",
            "displayName": "Forecast",
            "iconUrl": "https://host/app.svg",
            "description": "Addon that provides forecast",
            "descriptionKeywords": ["code-gen"],
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
                    "permissions": ["WRITE"],
                    "attachmentPaths": {
                        "attachmentPaths": {
                            "requestBody": ["@.attachments[*].url"],
                            "responseBody": ["@.result.attachedFiles"]
                        }
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