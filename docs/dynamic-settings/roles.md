# Dynamic Setting for Roles

Roles in DIAL are used to enable a roles-based access to resources (applications, models, files, conversations, toolsets and prompts) and also for cost control. In dynamic settings you can define roles with their settings.

> * Refer to [DIAL Documentation](https://docs.dialx.ai/platform/core/access-control-intro) to learn more about roles and access control.
> * Refer to [DIAL Admin](https://docs.dialx.ai/tutorials/admin/access-management-roles) to learn how to manage roles in DIAL Admin UI.

## roles

A list of roles configure in DIAL Core with their [parameters](#rolesrole_name). 

* `<role_name>`: A unique role name.

**Example**:

```json
"roles": {
    "operator": {},
    "app-user": {},
}
```

### roles.<role_name> 

An object containing parameters for each [role](#roles).

* `limits`: Limits for the number of tokens a role can use with a specific resource. **IMPORTANT:** Unlimited if not defined. Refer to [limits](#rolesrole_namelimits) for more details.
* `share`: Use this parameter to define resource sharing limits based on user roles. Refer to [share](#rolesrole_nameshare) for more details.
* `costLimit`: Use this parameter to define time-based spending limits (minute/day/week/month) in dollars for a role across all models. Refer to [costLimits](#rolesrole_namecostlimits) for more details.

**Example:**

```json
"roles": {
    "operator": {
        "limits": {
            "chat-gpt-35-turbo": {
                "minute": "200000",
                "day": "10000000",
                "week": "10000000",
                "month": "10000000"
            }
        },
        "costLimit": {
            "day": 100.00
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

#### roles.<role_name>.limits 

Use to define token usage limits for resources. **IMPORTANT:** Unlimited if not defined.

Available values:

* `minute`: Total tokens per minute that can be sent to a specific resource, managed via floating window approach for well-distributed rate limiting. **Default**: If it's not set the default value is unlimited
* `day`: Total tokens per day that can be sent to a specific resource, managed via floating window approach for balanced rate limiting. **Default**: If it's not set the default value is unlimited
* `week`: Total tokens per week that can be sent to a specific resource, managed via floating window approach for balanced rate limiting. **Default**: If it's not set the default value is unlimited
* `month`: Total tokens per month that can be sent to a specific resource, managed via floating window approach for balanced rate limiting. **Default**: If it's not set the default value is unlimited

**Example**:

```json
"limits": {
    "chat-gpt-35-turbo": {
        "minute": "200000",
        "day": "10000000",
        "week": "10000000",
        "month": "10000000",
    }
}
```
#### roles.<role_name>.costLimit 

Use to define cost limits for a role applied across all models. Cost limits work additively with existing token/request limits. Use to implement cost-conscious resource allocation policies.

**IMPORTANT:** Unlimited if not defined.

**Example**:

```json
"limits": {
    "chat-gpt-35-turbo": {
        "minute": "200000",
        "day": "10000000",
        "week": "10000000",
        "month": "10000000"
    }
},
"costLimit": {
    "day": 100.00
}
```

#### roles.<role_name>.share

Use Share to define resource sharing limits based on user roles.

* `invitation_ttl`: TTL of the invitation link. Default: 72 (hrs)
* `max_accepted_users`: The maximum number of users who can accept an invitation link for a resource being shared. The limit is applied to the shared resource. Default: 10 for APPLICATION and UNLIMITED for other resource types.

**Example:**

```json

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
```
