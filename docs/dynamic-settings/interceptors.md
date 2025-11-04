# Dynamic Setting for Interceptors

You can add an additional logic into the processing of every request and response for models and apps, enabling PII obfuscation, guardrails, safety checks, and beyond. This is achieved through the integration of pluggable components known as Interceptors.

> * Refer to [DIAL Interceptors SDK](https://github.com/epam/ai-dial-interceptors-sdk/blob/development/README.md) for a comprehensive information about interceptors as well as configuration examples.
> * Refer to [DIAL Admin](https://docs.dialx.ai/tutorials/admin/entities-interceptors) to learn how to manage interceptors in DIAL Admin UI.

## Categories of Interceptors

### Global interceptors

Apply to any application. You can specify them in the DIAL Core dynamic settings for the `globalInterceptors` parameter.

Configuration example: 

```json
{
  "globalInterceptors": ["interceptor1", "interceptor2"]
}
```

### Application type interceptors

Apply to [schema-rich applications](https://docs.dialx.ai/platform/core/apps#schema-rich-applications). You can specify them in the application root JSON schema in `"dial:applicationTypeInterceptors"`.

Configuration example: 

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://dial.epam.com/application_type_schemas/schema#",
  "title": "Core meta-schema defining DIAL custom application schemas",
  "allOf": [
    {
      "$ref": "#/definitions/topLevelSchema"
    },
    {
      "$ref": "#/definitions/dialRootSchema"
    }
  ],
  "definitions": {
    "dialRootSchema": {
      "properties": {
        "dial:applicationTypeInterceptors": {
          "type": "array",
          "items": {
            "type": "string"
          },
          "description": "List of application type interceptors"
        }
      }
    }
  }
}
```
### Local interceptors

Apply to an instance of the application. Refer to [Applications](/applications.md) to learn more and see examples.

Configuration example: 

```json
{
"applications": {
        "app": {
            "endpoint": "http://localhost:7001/openai/deployments/10k/chat/completions",
            "displayName": "App",
            "iconUrl": "https://host/app.svg",
            "interceptors": ["interceptor3"]
        }
    }
}
```

### Execution Logic

When all categories of interceptors are configured, they are triggered in the following sequences:

* Chat completion request: `global interceptor -> application type interceptor -> local interceptor`
* Response for the chat completion request: `local interceptor -> application type interceptor -> global interceptor`

In other words, **global interceptors** have the most strict rules. They receive original input first and examine the response last.

## interceptors

An object containing deployed DIAL Interceptors and their [parameters](#interceptorsinterceptor_name). Interceptors must be defined in the `interceptors` object to be used in any of the [category](#categories-of-interceptors).

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
  }
}
```

### interceptors.<interceptor_name>

An object containing parameters for each [interceptor](#interceptors).

* `endpoint`: The URL of the interceptor service. This URL is used to handle requests and responses for the interceptor. **Required**.
* `iconUrl`: A string with the URL with the icon location to display for the interceptor on UI.
* `description`: A brief summary of what this interceptor does and any parameters it uses (e.g. BLACKLIST=bar or Logs request/response payloads).
* `displayName`: A string with the interceptor's name. Display name is shown in all DIAL client UI dropdowns, tables, and logs so operators can quickly identify the interceptor.
* `forwardAuthToken`: A boolean parameter that specifies whether to forward an Auth Token to your interceptor's endpoint. Use this when your interceptor service requires its own authentication. If flag is set to `true` forward Http header with authorization token to chat completion endpoint of the interceptor. Refer to [Interceptors](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/6.interceptors.md) to learn more.
* `author`: The interceptor's developer. 
* `createdAt`: The date of the interceptor creation. 
* `updatedAt`: The date of the last interceptor update.
* `features`: Features supported by the interceptors.
*  `configurationEndpoint`: The URL that exposes the configuration of the interceptor.
*  `defaults`: Default parameters are applied if a request doesn't contain them in OpenAI `chat/completions` API call.


## Configuration Example

> Refer to [DIAL Interceptors SDK](https://github.com/epam/ai-dial-interceptors-sdk/blob/development/README.md#dial-core-configuration) for more DIAL Core configuration examples.

```json
{
  "interceptors": {
    "interceptor1": {
      "endpoint": "http://localhost:4088/api/v1/interceptor/handle",
      "features": {
        "configurationEndpoint": "http://localhost:4088/configuration"
      },
      "defaults": {
        "custom_fields": {
          "interceptor_configuration": {
            "foo1": "bar1"
          }
        }
      }
    },
    "interceptor2": {
      "endpoint": "http://localhost:4089/api/v1/interceptor/handle",
      "features": {
        "configurationEndpoint": "http://localhost:4089/configuration"
      },
      "defaults": {
        "custom_fields": {
          "interceptor_configuration": {
            "foo2": "bar2"
          }
        }
      }
    },
    "interceptor3": {
      "endpoint": "http://localhost:4090/api/v1/interceptor/handle",
      "features": {
        "configurationEndpoint": "http://localhost:4090/configuration"
      },
      "defaults": {
        "custom_fields": {
          "interceptor_configuration": {
            "foo3": "bar3"
          }
        }
      }
    }
  },
  "applications": {
    "app": {
      "interceptors": ["interceptor1", "interceptor2", "interceptor3"]
    }
  }
}
```
