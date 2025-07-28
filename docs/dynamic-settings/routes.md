# Dynamic Setting for Routes

Routes in DIAL are used for communication through registered endpoints in the DIAL Core. They act as a bridging mechanism between the DIAL Core and external applications, facilitating seamless interactions. Once a route with a designated endpoint is set up in DIAL Core, it allows client applications, such as DIAL Chat, to interact with this endpoint. Essentially, DIAL Core functions as an intermediary, handling authentication and authorization between the client and the external application linked to the route.

| Parameter  | Description     |
| ------------------------------------ | ------------------------|
| routes | A list of registered routes in AI DIAL Core. A route is used to proxy request through AI DIAL Core to upstream server.<br />AI DIAL Core provides capabilities: rate limiting, role based authorization, request balancing and access to AI DIAL Core resources such as LLMs, applications, file storage.|
| routes.<route_name> | Route configuration. `name` - route's name.        |
| routes.<route_name>.userRoles | Route is accessible by user roles from this list.  |
| routes.<route_name>.response | Pre-configured route's response:<br />`status` - http status code<br />`body` - http response body.<br />If the `response` is set then AI DIAL Core returns the response immediately.          |
| routes.<route_name>.rewritePath      | A flag indicates that the path to the upstream server will be replaced with the path of the original request, if this flag is set to `true`                      |
| routes.<route_name>.paths | A list of paths to be matched request's path. If any path is matched, the request will be processed by this route.<br />**Note**. A path can be a plain string or a regular expression. |
| routes.<route_name>.methods| A list of HTTP methods supported by this route     |
| routes.<route_name>.upstreams| A list of upstream servers. <br />`endpoint`: Route endpoint.<br />`key`: Your API key.<br />`weight`: Weight for upstream endpoint; positive number represents an endpoint capacity, zero or negative disables this enpoint from routing. Default value: 1.<br />`tier`: Specifies a tier group for the endpoint. Only positive numbers are allowed. All requests will be routed to the endpoints with the highest tier (the lowest tier value), other endpoints (with lower tier/higher tier value) may be used only if the highest tier endpoints are unavailable. Default value: 0 - highest tier. Refer to [Load Balancer](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/5.load-balancer.md) to learn more.<br/>`extraData`: Additional metadata containing any information that is passed to the upstream's endpoint. It can be a JSON or String. |
| routes.<route_name>.maxRetryAttempts | Maximum number of retry attempts in case if upstream server returns unsuccessful response code. In this case load balancer will try to find another upstream from the list of available upstreams.|
| routes.<route_name>.order | The value determines the order within the global routes. The lower value means the higher priority. The value can't be negative integer. The default one is 2^31-1. |

**Configuration Example:**

```json
{
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
        ]
    },
    "rate": {
        "paths": ["/v1/rate"],
        "rewritePath": true,
        "methods": ["GET", "HEAD"],
        "response": {
            "status": 200,
            "body": "OK"
        }
    }
},
}