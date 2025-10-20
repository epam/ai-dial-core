# Dynamic Setting for API Keys

In dynamic settings you can define API keys with their settings that will be available in DIAL. 

> * Refer to [DIAL Documentation](https://docs.dialx.ai/platform/core/access-control-intro) to learn more about API keys and access control.
> * Refer to [DIAL Admin](https://docs.dialx.ai/tutorials/admin/access-management-keys) to learn how to manage API keys in DIAL Admin UI.

## keys

A list of defined API keys and their [parameters](#keysparameters).

* `<core_key>`: A unique API key name.

### keys.<core_key>

An object containing properties an each [API key](#keys).

* `project`: Project name assigned to this key. **Required** 
* `role`: A role assigned to a specific key. **Note**: At least one of `role`, `roles` must be provided. **Required**
* `roles`: A list of roles assigned to a specific key. **Note**: At least one of `role`, `roles` must be provided. **Required**
* `secured`: A boolean flag that indicates if the key is a secured key. If it's set to `true`, user request and deployment response won't be saved to the prompt log storage. Refer to [Secured Keys](https://docs.dialx.ai/platform/core/privacy#applications-audit-logs) for more details.


## Configuration Example

```json
//Example extract from aidial.config.json
"keys": {
    "proxyKey1": { //API key
        "project": "Project1",
        "role": "basic" // the name of the role
    }
},
"models": {
    "chat-gpt-35-turbo": {
        "userRoles": [
            "basic"
            ]
    }
},
"roles": {
    "basic": { // the name of the role
        "limits": {
            "chat-gpt-35-turbo": {
            "minute": "100000", //number of tokens per minute
            "day": "10000000", //number of tokens per day
            "week": "10000000", //number of tokens per week
            "month": "10000000", //number of tokens per month
            },
        "share": {
            "APPLICATION": {
                "invitation_ttl": "24",
                "max_accepted_users": "10"
                },
            "FILE": {
                "invitation_ttl": "24",
                "max_accepted_users": "10"
                }
            }
        }
    }
}
```
