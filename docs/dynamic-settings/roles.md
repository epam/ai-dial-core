# Dynamic Setting for Roles

Roles in DIAL are used to enable a roles-based access to resources (applications, models, files, conversations, toolsets and prompts) and also for cost control. In dynamic settings you can define roles with their settings.

> * Refer to [DIAL Documentation](https://docs.dialx.ai/platform/core/access-control-intro) to learn more about access control.
> * Refer to [DIAL Admin](https://docs.dialx.ai/tutorials/admin/access-management-roles) to learn how to manage roles in DIAL Admin UI.

## roles

A list of roles configured in DIAL Core with their [parameters](#rolesrole_name). 

* `<role_name>`: A unique role name. Can be a specific claim value from JWT or a role defined for a specific API key.

**Example**:

```json
"roles": {
    "operator": {},
    "app-user": {},
}
```

### roles.<role_name> 

An object containing parameters for each [role](#roles).

* `limits`: Limits for the number of tokens a role can use with a specific resource. Refer to [limits](#rolesrole_namelimits) for more details.
* `share`: Use this parameter to define resource sharing limits based on user roles. Refer to [share](#rolesrole_nameshare) for more details.
* `costLimit`: Use this parameter to define time-based spending limits (minute/day/week/month) in dollars for a role across all models. Refer to [costLimits](#rolesrole_namecostlimits) for more details.

**Example:**

```json
"roles": {
    "operator": {
        "limits": {
            "chat-gpt-35-turbo": {
                "requestHour": "20",
                "requestDay": "300",
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

## rateLimitSchedule

A top-level (deployment-wide, not per-role) setting that anchors the fixed calendar windows used by the `day`/`week`/`month`/`requestDay` limits below - see [Fixed calendar windows](#rolesrole_namelimits). It is a single setting for the whole deployment: there is no per-role or per-user customization, and no self-service API for end users to change it.

Available values:

* `timezone`: An IANA timezone id (e.g. `"Europe/Warsaw"`, `"America/New_York"`, or `"UTC"`) the schedule is anchored to. Must be a real IANA region id, not a fixed UTC offset, since a fixed offset cannot express daylight-saving transitions. Default: `"UTC"`.
* `weekStartDay`: The day the `week` window starts on, using a 3-letter abbreviation (`Mon`, `Tue`, `Wed`, `Thu`, `Fri`, `Sat`, `Sun`). Default: `"Mon"`.
* `resetTime`: The 24h local time (`HH:mm`) at which `day`/`week`/`month`/`requestDay` periods reset. Default: `"00:00"`.

Omitting `rateLimitSchedule` entirely is equivalent to `{"timezone": "UTC", "weekStartDay": "Mon", "resetTime": "00:00"}`, which reproduces the previous implicit UTC-midnight day/month behavior.

**Example**:

```json
{
    "rateLimitSchedule": {
        "timezone": "Europe/Warsaw",
        "weekStartDay": "Mon",
        "resetTime": "09:00"
    },
    "roles": {}
}
```

#### roles.<role_name>.limits 

Use to define token usage limits for resources. 

**Default logic:** In case limits are not defined for a specific role, limits defined for the `default` role apply. If limits are not defined for the `default` role, the value is **unlimited**.

Available values:

* `requestHour`: Total requests per hour that can be sent to a specific resource, managed via floating window approach for well-distributed rate limiting.
* `requestDay`: Total requests per day that can be sent to a specific resource, managed via a fixed calendar window - see below.
* `minute`: Total tokens per minute that can be sent to a specific resource, managed via floating window approach for well-distributed rate limiting.
* `day`: Total tokens per day that can be sent to a specific resource, managed via a fixed calendar window - see below.
* `week`: Total tokens per week that can be sent to a specific resource, managed via a fixed calendar window - see below.
* `month`: Total tokens per month that can be sent to a specific resource, managed via a fixed calendar window - see below.

**Fixed calendar windows:** unlike `minute`/`requestHour`, which age usage out gradually as time advances, `day`/`week`/`month`/`requestDay` reset all at once at a deterministic boundary aligned to the calendar (e.g. "this month" is the 1st through the last day of the calendar month), so usage cannot exceed the limit until the next boundary regardless of when within the period it accrued. The boundary is anchored to the deployment-wide `rateLimitSchedule` top-level config setting (timezone, week start day, and daily reset time); omitting it defaults to UTC, Monday, 00:00, which reproduces the previous implicit UTC-midnight day/month behavior. The bulk limits/usage endpoints (`GET /v1/deployments/{deployment_id}/limits`, `GET /v1/user/limits`, `GET /v1/user/usage`) report this boundary as `resetsAt` on each fixed-window entry; `minute`/`requestHour` entries omit it, since a floating window has no single reset instant.

**Requests served through a translator:** a model interface configured with `"mode": "translator"` does not add its tokens to these limits — the translator calls DIAL Core back to have the completion served, and that second call is what carries the usage, so it is counted once rather than twice. The limits are still checked before a translated request is forwarded, so an exhausted quota blocks it like any other request. `requestHour` and `requestDay` work the same way: the translated call is checked against them but spends no slot, so one client request never consumes two. Refer to [translators](translators.md#limits-and-a-translated-request).

**Example**:

```json
"limits": {
    "chat-gpt-35-turbo": {
        "requestHour": "20",
        "requestDay": "300",
        "minute": "200000",
        "day": "10000000",
        "week": "10000000",
        "month": "10000000",
    }
}
```
#### roles.<role_name>.costLimit 

Use to define cost limits for a role applied across all models. Cost limits work additively with existing token/request limits. Use to implement cost-conscious resource allocation policies.

**Default logic:** In case limits are not defined for a specific role, limits defined for the `default` role apply. If limits are not defined for the `default` role, the value is **unlimited**.

Available values:

* `minute`: Total cost limits per minute in USD applied for a specific role across all models.
* `day`: Total cost limits per day in USD applied for a specific role across all models.
* `week`: Total cost limits per week in USD applied for a specific role across all models.
* `month`: Total cost limits per month in USD applied for a specific role across all models.

**Example**:

*Provided values are for example purposes only.*

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
    "minute": 0.069,
    "day": 100.00,
    "week": 500.00,
    "month": 2000.00,
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
