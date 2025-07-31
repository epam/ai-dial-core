# Dynamic Setting for Interceptors

You can add an additional logic into the processing of every request and response for models and apps, enabling PII obfuscation, guardrails, safety checks, and beyond. This is achieved through the integration of pluggable components known as Interceptors.

> * Refer to [Interceptors](https://docs.dialx.ai/platform/core/interceptors) to learn more.
> * Refer to [DIAL Admin](https://docs.dialx.ai/tutorials/admin/entities-interceptors) to learn how to manage interceptors in DIAL Admin UI.

## interceptors

A list of deployed DIAL Interceptors and their [parameters](#interceptorsinterceptor_name):

* `<interceptor_name>`: A unique key for this interceptor (e.g. reject-external-links, audit-logger). Used when attaching to Models or Applications under their Interceptors tab. Keep it URL-safe and lowercase with hyphens. **Required**.

**Example**:

```json
{
  "interceptors": {
      "interceptor1": {
          "endpoint": "http://localhost:4088/api/v1/interceptor/handle"
      },
      "interceptor2": {
          "endpoint": "http://localhost:4089/api/v1/interceptor/handle"
      },
      "interceptor3": {
          "endpoint": "http://localhost:4090/api/v1/interceptor/handle"
      }
  },
}
```

### interceptors.<interceptor_name>

An object containing parameters for each [interceptor](#interceptors).

`endpoint`: The URL of the interceptor service. This URL is used to handle requests and responses for the interceptor. **Required**.
`iconUrl`: A string with the URL with the icon location to display for the interceptor on UI.
`description`: A brief summary of what this interceptor does and any parameters it uses (e.g. BLACKLIST=bar or Logs request/response payloads).
`displayName`: A string with the interceptor's name. Display name is shown in all DIAL client UI dropdowns, tables, and logs so operators can quickly identify the interceptor.
`forwardAuthToken`: A boolean parameter that specifies whether to forward an Auth Token to your interceptor's endpoint. Use this when your interceptor service requires its own authentication. If flag is set to `true` forward Http header with authorization token to chat completion endpoint of the interceptor. Refer to [Interceptors](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/6.interceptors.md) to learn more.
`author`: The interceptor's developer. 
`createdAt`: The date of the interceptor creation. 
`updatedAt`: The date of the last interceptor update.


## Configuration Example

```json
{
  "interceptors": {
      "interceptor1": {
          "endpoint": "http://localhost:4088/api/v1/interceptor/handle"
      },
      "interceptor2": {
          "endpoint": "http://localhost:4089/api/v1/interceptor/handle"
      },
      "interceptor3": {
          "endpoint": "http://localhost:4090/api/v1/interceptor/handle"
      }
  },
  "applications": {
      "app": {
          "interceptors": ["interceptor1", "interceptor2", "interceptor3"],
      }
  }
}
```