# Dynamic Setting for Interceptors

You can add an additional logic into the processing of every request and response for models and apps, enabling PII obfuscation, guardrails, safety checks, and beyond. This is achieved through the integration of pluggable components known as Interceptors.

Refer to [Interceptors](https://docs.dialx.ai/platform/core/interceptors) to learn more.

|Parameter | Description  |
|----------|----------|
| interceptors | A list of deployed DIAL Interceptors and their parameters:<br />`<interceptor_name>`: Unique interceptor name. Refer to [Interceptors](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/6.interceptors.md) to learn more.|
| interceptors.<interceptor_name>| `endpoint`: DIAL Interceptor API for chat completions.<br />`iconUrl`: Icon path for the DIAL Interceptor on UI.<br />`description`: Brief DIAL interceptor description.<br />`displayName`: DIAL interceptor name on UI.<br/> `forwardAuthToken`: If flag is set to `true` forward Http header with authorization token to chat completion endpoint of the interceptor. Refer to [Interceptors](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/6.interceptors.md) to learn more.  <br />`author`: the interceptor's developer.  <br />`createdAt`: the date of the interceptor creation. <br />`updatedAt`: the date of the last interceptor update.|

**Configuration Example:**

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