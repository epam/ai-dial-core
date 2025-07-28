# Dynamic Setting for Roles

Roles in DIAL are used to enable a roles-based access to resources (applications, models, files, conversations and prompts) and also for cost control. In dynamic settings you can define roles with their settings.

|Parameter | Description  |
|----------|----------|
| roles   | API key or user roles. Each role may have limits to be associated with applications, models, assistants or addons. Refer to [API Keys](https://github.com/epam/ai-dial/blob/main/docs/platform/3.core/1.auth-intro.md#api-keys) to learn more.|
| roles.<role_name>  | `limits`: Limits for models, applications, or assistants. **Note:** it is necessary to define this for a role.    |
| roles.<role_name>.limits             | Use to define token usage limits for models.<br />`minute`: Total tokens per minute limit sent to the model, managed via floating window approach for well-distributed rate limiting. If it's not set the default value is unlimited<br />`day`: Total tokens per day limit sent to the model, managed via floating window approach for balanced rate limiting.<br />`week`: Total tokens per week limit sent to the model, managed via floating window approach for balanced rate limiting.<br />`month`: Total tokens per month limit sent to the model, managed via floating window approach for balanced rate limiting.<br />**Note**: you can skip these parameters to apply their default value - unlimited.                 |
| roles.<role_name>.share              | Use to define resource sharing limits based on user roles<br />`invitation_ttl`: TTL of the invitation link. Default: 72 (hrs)<br />`max_accepted_users`: The maximum number of users who can accept an invitation link for a resource being shared. The limit is applied to the shared resource.<br />Default: 10 for APPLICATION and UNLIMITED for other resource types.               |

**Configuration Example:**

```json
"roles": {
    "operator": {
        "limits": {
            "chat-gpt-35-turbo": {
                "minute": "200000",
                "day": "10000000",
                "week": "10000000",
                "month": "10000000",
            }
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
```