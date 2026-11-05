# Dynamic Setting for Routers

A router is a deployment that picks the model serving a request. Clients call it by name wherever they would call a model. By default DIAL Core forwards the request to the router service exactly as it does for an application: the router receives a per-request key and calls the chosen model back through DIAL Core. An interface in [decision mode](#decision-mode) instead asks the router which deployment should serve and serves that deployment itself.

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
* `interfaces`: The interfaces the router serves. **Required**: a router has no `endpoint` or `responsesEndpoint`. The entries take the same shape and are validated by the same rules as a model's, `mode: translator` and `overridePaths` included. Refer to [models.<model_name>.interfaces](models.md#modelsmodel_nameinterfaces). An entry may also use `mode: decision`, see [Decision mode](#decision-mode).
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

The header is only as trustworthy as the path it arrives on. A router that takes identity from `X-DIAL-USER-INFO` instead of calling `GET /v1/user/info` must accept requests from DIAL Core only: no route from outside the cluster, and a network policy that admits DIAL Core's pods where the platform offers one. Anyone else who can reach the router could send the header with roles of their choosing. A router reachable by clients should keep calling `GET /v1/user/info` with the caller's credentials.

## Decision mode

With `mode: decision` on an `interfaces` entry, DIAL Core does not forward the client's request to the router. It sends the router a small decide request, reads the answer (the verdict) and serves the request with the deployment the verdict names, in the same pass, as if the client had called that deployment. The router never calls DIAL Core back and never calls `GET /v1/user/info`. A router may declare decision mode for some interfaces and proxy others.

The fields of a decision entry:

* `mode`: `decision`. Allowed on a router only: DIAL Core rejects it on a model, application, interceptor or toolset (`Mode 'decision' is allowed on a router only`).
* `base_url`: **Required** on the entry: the URL of the decide request, used as written. The router-level `baseUrl` does not stand in for it (`Mode 'decision' requires base_url`), and DIAL Core never forwards a client request to it.
* `context.body`: `true` to send the client's request body with the decide request, byte for byte. Default `false`: the decide request has an empty body.
* `decisionTimeoutMs`: How long the decide request may take, connecting included. Default `5000`; must be positive.
* `translator` is not allowed on a decision entry (`Mode 'decision' excludes translator`).

**Example**: `r1` decides chat completions, embeddings and Messages requests, and proxies Responses requests to its service.

```json
{
  "routers": {
    "r1": {
      "defaultHeaders": {"X-DIAL-ROUTER-TIER": "medium"},
      "interfaces": {
        "openaiChatCompletions": {"mode": "decision", "base_url": "http://localhost:4848/decide"},
        "openaiEmbeddings": {"mode": "decision", "base_url": "http://localhost:4848/decide"},
        "anthropicMessages": {"mode": "decision", "base_url": "http://localhost:4848/decide"},
        "openaiResponses": {"base_url": "http://localhost:4848"}
      }
    }
  }
}
```

### The decide request

`POST <base_url>` with:

* The client's headers as DIAL Core forwards them to any deployment: `Authorization` only when the router has `forwardAuthToken: true`, no client `API-KEY`, no client `X-DIAL-APPLICATION-ID`, `X-DIAL-APPLICATION-PROPERTIES` or `X-DIAL-USER-INFO`.
* The router's `defaultHeaders` for the interface, under every name the client did not send. They go on the decide request only: the chosen deployment receives its own `defaultHeaders`, as on a direct call.
* `X-DIAL-USER-INFO` (see [Identity header](#identity-header)), `X-DIAL-ROUTER` (the router's name) and `X-DIAL-API` (the interface: `openaiChatCompletions`, `openaiEmbeddings`, `openaiResponses` or `anthropicMessages`).
* No `API-KEY`: DIAL Core mints no per-request key for the decide request.
* An empty body, or the client's body unchanged with `Content-Type: application/json` when `context.body` is `true`.

DIAL Core asks the router after the [global interceptors](interceptors.md), in the pass that reaches the router once the interceptor chain calls DIAL Core back.

### The verdict

A `200` answer with a JSON body naming the candidates in order of preference:

```json
{"candidates": ["gpt-4", "claude"]}
```

or one deployment, read as a one-element list:

```json
{"deployment": "gpt-4"}
```

DIAL Core ignores other fields. A candidate must not be a router.

### Serving the chosen deployment

DIAL Core serves the first usable candidate as if the client had called it: the caller's roles, consent and per-request-key access to it, its limits against the caller, its interceptors, one per-request key, its `defaultHeaders`, `defaults` and `overrideName`. On `/openai/v1/chat/completions`, `/openai/v1/responses` and `/anthropic/v1/messages` (`count_tokens` included) DIAL Core sets the body `model` to the candidate's name before applying its `overrideName`, and sends the candidate's name in `X-DIAL-DEPLOYMENT-ID` on Messages.

DIAL Core skips a candidate, without calling it, when the caller may not use it, its limits are exhausted, it does not serve the interface or it does not exist. It also skips a candidate whose call fails to connect, or answers a retriable status (`429`, `502`, `503`, `504` and the config's `retriableErrorCodes`) after its own upstreams are exhausted, while no byte of the answer has reached the client. Any other answer, a `400` included, is relayed to the client and the next candidate is not tried. Once the chosen deployment hands the request to its own interceptors, DIAL Core no longer falls back. When every candidate is skipped, the client gets the last failure.

### Failures

| What happens | The client gets |
|---|---|
| The decide request exceeds `decisionTimeoutMs` | `504` |
| The router is unreachable, or answers `5xx` | `502` |
| The `200` body is not a verdict | `502` |
| The router answers `4xx` | The router's status and body |
| The verdict names a router, or its last usable candidate does not exist | `502` |
| A deployment the router chose calls the router back | `502` |

In each case DIAL Core sends nothing to any deployment and writes no analytics row.

### Accounting

A decided request is one pass: one analytics row, for the deployment that served, and no row for the router. The router comes before that deployment in `execution_path` (`["r1", "gpt-4"]`; after a global interceptor `["i1", "r1", "gpt-4"]`) and is its `parent_deployment`. The per-request key the deployment receives carries the same `executionPath`, so a deployment it calls sees the same path, and `sourceDeployment` names the deployment itself. The row carries a `decision` object:

```json
"decision": {
  "router": "r1",
  "api": "openaiChatCompletions",
  "candidates": ["m1", "m2"],
  "deployment": "m2",
  "decide_ms": 12,
  "attempts": [{"deployment": "m1", "status": 502, "reason": "upstream"}]
}
```

`attempts` lists each candidate tried before the one that served: `status` is what DIAL Core would have answered for it, `reason` one of `forbidden`, `not_found`, `limit`, `unavailable` (skipped before the call), `connect` or `upstream` (skipped after the call). A background Responses job served through a decision carries the same `execution_path`, `parent_deployment` and `decision` on the row DIAL Core writes when the job ends.

Rolling back to a DIAL Core release without decision mode while a `mode: decision` entry exists makes the whole config file fail to load: switch those entries back to proxy mode first.
