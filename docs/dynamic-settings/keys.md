# Dynamic Setting for APi Keys

In dynamic settings you can define API keys with their settings.

|Parameter | Description  |
|----------|----------|
| keys    | API Keys parameters:<br />`<core_key>`: Your API key. Refer to [API Keys](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/2.access-control-intro.md#api-keys) to learn more.    |
| keys.<core_key>    | `project`: Project name is assigned to this key. **Required** <br />`role`: a role to be assigned to the key. **Note:** a key is invalid if `role` and `roles` are missed. **Required**  <br />`roles`: a list of roles to be assigned to the key. **Note:** a key is invalid if `role` and `roles` are missed. <br/> `secured`: the flag indicates if the key is secured. If it's set to `true` user request and deployment response won't be saved to the prompt log storage.     |

**Configuration Example:**

```json
//Example extract from aidial.config.json
"keys": {
    "proxyKey1": { //API key
        "project": "Project1",
        "role": "basic" // the name of the role
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