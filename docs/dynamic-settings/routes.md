# Dynamic Setting for Routes

Routes in DIAL are used for communication through registered endpoints in the DIAL Core. They act as a bridging mechanism between the DIAL Core and external applications, facilitating seamless interactions. Once a route with a designated endpoint is set up in DIAL Core, it allows client applications, such as DIAL Chat, to interact with this endpoint. Essentially, DIAL Core functions as an intermediary, handling authentication and authorization between the client and the external application linked to the route.

## routes

A list of registered routes in DIAL Core. A route is used to proxy request through DIAL Core to upstream server.<br />DIAL Core provides capabilities: rate limiting, role based authorization, request balancing and access to DIAL Core resources such as LLMs, applications, file storage.

* `<route_name>`: A unique route name.

### routes.<route_name>

An object containing parameters for each [route](#routes).

* `userRoles`: A list of specific claim values provided by IDP in JWT or an API key role. If not defined, the route is available to all users. Refer to [IDP configuration](https://docs.dialx.ai/tutorials/devops/auth-and-access-control/configure-idps/overview) for details.
* `response`: Pre-configured route's response. If the `response` is set then DIAL Core returns the response immediately. Either `response` or `upstreams` must be provided. Available parameters:
    - `status` - http status code
    - `body` - http response body.
* `rewritePath`: A boolean flag that indicates that the path to the upstream server will be replaced with the path of the original request, if this flag is set to `true`.
* `paths`: A list of paths to be matched request's path. If any path is matched, the request will be processed by this route. **Note**. A path can be a plain string or a regular expression. 
* `methods`: A list of HTTP methods supported by this route. Supported methods: GET, POST, PUT, DELETE, HEAD and PATCH. 
* `maxRetryAttempts`: Maximum number of [retry](https://docs.dialx.ai/platform/core/load-balancer#fallbacks) attempts in case if upstream server returns unsuccessful response code. In this case load balancer will try to find another upstream from the list of available upstreams.
* `order`: The value of this parameter determines the order within the global routes. The lower value means the higher priority. The value can't be negative integer. The default one is 2^31-1.
* `upstreams`: A list of upstream servers. Either `response` or `upstreams` must be provided. Refer to [routes.<route_name>.upstreams](#routesroute_nameupstreams) for more details.

#### routes.<route_name>.upstreams

A list of upstream servers with their parameters. Use to configure [load balancing](https://docs.dialx.ai/platform/core/load-balancer).

* `endpoint`: One or more backend URLs (e.g., http://, https://, ws://, wss://) to which requests are sent. Supports HTTP and WebSocket protocols. When multiple endpoints are provided, round-robin load balancing and automatic fallback can be enabled among the hosts.
* `key`: API key, token, or credential passed to the upstream. 
* `weight`: Weight for upstream endpoint; positive number represents an endpoint capacity, zero or negative disables this endpoint from routing. Higher = more traffic share. Default value: 1.
* `tier`: Specifies tier group for the endpoint. Only positive numbers allowed. All requests will be routed to the endpoints with the highest tier (the lowest tier value), other endpoints (with lower tier/higher tier value) may be used only if the highest tier endpoints are unavailable. Default value: 0 - highest tier. Refer to [load balancing](https://docs.dialx.ai/platform/core/load-balancer) to learn more.
* `extraData`: Additional metadata containing any information that is passed to the upstream's endpoint. It can be a JSON or String.

## Configuration Example

```json
{
  "routes": {
    "vector_store_query": {
      "paths": ["/v1/vector_store(/[^/]+)*$"],
      "rewritePath": true,
      "": ["GET", "HEAD"],
      "userRoles": ["role1"],
      "upstreams": [
        {
          "endpoint": "http://localhost:9876"
        },
        {
          "endpoint": "http://localhost:9877"
        }
      ]
    },
    "rate": {
      "paths": ["/v1/rate"],
      "rewritePath": true,
      "": ["GET", "HEAD"],
      "response": {
        "status": 200,
        "body": "OK"
      }
    },
    "websocket-realtime-openai": {
      "paths": ["/openai/realtime"],
      "rewritePath": true,
      "upstreams": [
        {
          "endpoint": "wss://${AZURE_FOUNDRY_PROJECT_NAME}.cognitiveservices.azure.com/openai/realtime",
          "key": "${AZURE_FOUNDRY_API_KEY}"
        }
      ]
    }
  }
}
```

## Calling Route

DIAL Core will expose a route from the application configuration using the following path pattern:

`http_method ^/+v1/deployments/(?<id>.+)/route(?<route_path>/.+?)$`

where:

- **http_method**: One of the allowed HTTP methods, as specified in the route’s configuration property `methods`.
- **id**: The deployment ID.
- **route_path**: The path segment to be matched against at least one value in the route’s configuration property `paths`.

  **Note:** The `route_path` must not include the keyword `route` anywhere in its value. This restriction prevents conflicts when resolving the deployment `id` and the route’s path from the request path.

