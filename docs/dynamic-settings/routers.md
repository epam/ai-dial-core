# Dynamic Setting for Routers

A router is a deployment that picks the model serving a request. Clients call it by name wherever they would call a model, and DIAL Core forwards the request to the router service exactly as it does for an application: the router receives a per-request key and calls the chosen model back through DIAL Core.

## routers

An object containing the routers and their [parameters](#routersrouter_name). Router names share one namespace with models, applications, toolsets and interceptors: a name declared twice across those maps is a duplicate deployment id, and a name containing `/` is dropped.

**Example**:

```json
{
  "routers": {
    "r1": {
      "baseUrl": "http://localhost:4848",
      "userRoles": ["analysts"],
      "displayName": "Cost-aware router",
      "description": "Picks the cheapest model that can answer",
      "interfaces": {
        "openaiChatCompletions": {},
        "openaiEmbeddings": {},
        "anthropicMessages": {}
      }
    }
  }
}
```

### routers.<router_name>

* `baseUrl`: The router service root shared by every `interfaces` entry that declares no `base_url` of its own.
* `interfaces`: The interfaces the router serves. **Required**: a router has no `endpoint` or `responsesEndpoint`. The entries take the same shape and are validated by the same rules as a model's, `mode: translator` and `overridePaths` included. Refer to [models.<model_name>.interfaces](models.md#modelsmodel_nameinterfaces).
* `userRoles`: The roles allowed to call and list the router. Empty means everyone.
* `displayName`, `displayVersion`, `iconUrl`, `description`, `intro`, `reference`, `descriptionKeywords`, `author`, `createdAt`, `updatedAt`: Display fields, as for a model.
* `features`, `defaults`, `defaultHeaders`, `overrideName`, `forwardAuthToken`, `maxRetryAttempts`: As for a model.

A router declares nothing of another deployment kind. DIAL Core rejects the config when a router has `upstreams`, `limits`, `pricing`, any application field, `interceptors` (`A router declares no interceptors`), or `endpoint` / `responsesEndpoint` (`A router declares interfaces, not endpoint`).

## Limits

A router has no limits of its own. DIAL Core neither checks nor charges limits for a router on any API: `limits.<router_name>` on a role is ignored. The models the router calls back with its per-request key are checked and charged against the caller, so put limits on those models. `GET /v1/deployments/<router_name>/limits` answers the figures the role declares for the router, which never move.

## Listing

Routers do not appear in `GET /openai/models`, `/openai/deployments`, `/openai/applications`, `/v1/deployments`, `/v1/deployment-names` or `/anthropic/v1/models`, and `GET /v1/deployments/<router_name>` answers `404`. They have their own listing:

* `GET /v1/routers`: `{"data": [...]}` with one entry per router the caller's roles allow.
* `GET /v1/routers/{router_name}`: that entry; `404` for an unknown name, `403` when the caller's roles do not allow it.

An entry carries `id`, `object: "router"`, `display_name`, `display_version`, `icon_url`, `description`, `intro`, `reference`, `features`, `defaults`, `description_keywords`, `created_at` and `updated_at`. It never carries the router's url.

## Identity header

DIAL Core adds `X-DIAL-USER-INFO` to every request it forwards to a router, so the router need not call `GET /v1/user/info`. The value is a JSON object with the same values `GET /v1/user/info` answers the caller, without `userClaims` and `userDisplayName`:

* `roles`: the caller's roles, always.
* `project`: the project, for a caller with a project API key.
* `userId`: the user id, for a caller with a JWT.

```
X-DIAL-USER-INFO: {"roles":["analysts","b"],"userId":"u1"}
```

Non-ASCII characters are sent as JSON `\u` escapes. DIAL Core drops a client-sent `X-DIAL-USER-INFO` from the chat completions, completions, embeddings, Responses, Messages and deployment feature requests it forwards to a model, application or router, so a client cannot forge it on any request that reaches a router; an interceptor receives the client's headers as they are, and the router call after the interceptor chain carries DIAL Core's own value. The header grows with the caller's roles: a caller with several hundred roles can exceed the receiving server's header size limit.
