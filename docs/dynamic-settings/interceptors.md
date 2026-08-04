# Dynamic Setting for Interceptors

You can add an additional logic into the processing of every request and response for models and apps, enabling PII obfuscation, guardrails, safety checks, and beyond. This is achieved through the integration of pluggable components known as Interceptors.

> * Refer to [DIAL Interceptors SDK](https://github.com/epam/ai-dial-interceptors-sdk/blob/development/README.md) for a comprehensive information about interceptors as well as configuration examples.
> * Refer to [DIAL Admin](https://docs.dialx.ai/tutorials/admin/entities-interceptors) to learn how to manage interceptors in DIAL Admin UI.

## interceptors

An object containing deployed DIAL Interceptors and their [parameters](#interceptorsinterceptor_name). Interceptors must be defined in the `interceptors` object to be used in any of the [category](#categories-of-interceptors). Once you have declared your interceptors, you can use them as global, application type, or local interceptors.

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

* `endpoint`: The URL of the interceptor service. This URL is used to handle requests and responses for the interceptor. **Required** unless `interfaces` is used instead.
* `interfaces`: A typed alternative to the flat `endpoint` field for declaring the interceptor service target. For interceptors, only the `openaiChatCompletions` interface is supported; the Responses API and other interfaces are not. Refer to [interceptors.<interceptor_name>.interfaces](#interceptorsinterceptor_nameinterfaces).
* `overrideName`: If set, the interceptor is called under this name: the outgoing chat completion request body's `model` field (and the `X-DIAL-OVERRIDE-NAME` header) are rewritten to this value before the request reaches the interceptor's endpoint. Only applied when the interceptor is invoked as part of a chat-completions request; interceptors are also invoked when processing Responses API requests, but that flow ignores `overrideName`. Doesn't change routing — only the value the endpoint receives.
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

### interceptors.<interceptor_name>.interfaces

An optional, typed alternative to the flat `endpoint` field. Both shapes are first-class — choose whichever you prefer per interceptor; there is no migration between them.

Unlike `endpoint`, which is forwarded **verbatim**, an `interfaces` entry declares a `base_url` and DIAL Core forwards each request to `base_url` + **the ingress path**, with the `{deployment-id}` segment rewritten to the interceptor's own name (e.g. `/openai/deployments/<model>/chat/completions` is routed to `<base_url>/openai/deployments/<interceptor-name>/chat/completions`). A trailing slash on `base_url` is normalized. If both `interfaces` and `endpoint` are declared, `interfaces` takes precedence.

Interceptors support only one interface type:

* `openaiChatCompletions`: the OpenAI chat completions interface. Peer of `endpoint`.

> The Responses API (`openaiResponses`) and any other interface types are **not** supported for interceptors. If declared, they are dropped on config read with a warning.

Each value is an object with a single field:

* `base_url`: The interceptor service root that the matching ingress path is appended to.

**Example**

```json
"interceptors": {
    "interceptor-via-interfaces": {
        "interfaces": {
            "openaiChatCompletions": { "base_url": "http://localhost:4088" }
        }
    }
}
```

## Categories of Interceptors

### Global interceptors

Global interceptors apply to any deployment in DIAL and tend to have the most strict rules, because they receive original input first and examine the response last. You can specify them in the DIAL Core dynamic settings for the `globalInterceptors` parameter.

Configuration example: 

```json
{
  "globalInterceptors": ["interceptor-id", "interceptor-id2"]
}
```

### Application type interceptors

Application Type interceptors apply to [schema-rich applications](https://docs.dialx.ai/platform/core/apps#schema-rich-applications). 

To enable application type interceptors:

1. Add the interceptor definitions to the main meta-schema via `"dial:applicationTypeInterceptors"` parameter:

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
2. In the JSON schema for each application type, include a `"dial:applicationTypeInterceptors"` property containing a list of interceptors.
3. Provide this JSON schema as the value for the `"applicationTypeSchemas"` parameter in DIAL Core dynamic settings.

    ```json
    {
    "applicationTypeSchemas": [
        {
            "$schema": "https://dial.epam.com/application_type_schemas/schema#",
            "$id": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
            "dial:applicationTypeEditorUrl": "https://mydial.somewhere.com/custom_application_schemas/schema",
            "dial:applicationTypeViewerUrl": "https://mydial.somewhere.com/custom_application_schemas/viewer",
            "dial:applicationTypeDisplayName": "Specific Application Type",
            "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",
            "dial:applicationTypeInterceptors": [
                "interceptor1",
                "interceptor2"
            ]
        }
    ]
    }
    ```

### Local interceptors

Local interceptors are configured and applied to a specific instance of an application. Local interceptors can be set by [DIAL admin](https://docs.dialx.ai/tutorials/admin/entities-interceptors) when an application is published or modified or by the application author when creating application via [API](https://dialx.ai/dial_api#tag/Applications/operation/saveCustomApplication). 

> Refer to [Applications](/applications.md) to learn more and see examples.

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


## Configuration Example

> Refer to [DIAL Interceptors SDK](https://github.com/epam/ai-dial-interceptors-sdk/blob/development/README.md#dial-core-configuration) for more DIAL Core configuration examples.

In this example, we define available interceptors in the `interceptors` section and then use them as local interceptors for the `app` application.

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
