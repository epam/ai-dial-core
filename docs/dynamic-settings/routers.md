# Dynamic Setting for Routers

A router is a deployment that picks the model serving a request. Clients call it by name wherever they would call a model. By default DIAL Core forwards the request to the router service exactly as it does for an application: the router receives a per-request key and calls the chosen model back through DIAL Core. A router in [decision mode](#decision-mode) is instead asked which deployment should serve, and DIAL Core serves that deployment itself.

## routers

An object containing the routers and their [parameters](#routersrouter_name).

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

* `baseUrl`: The router service root, used by every `interfaces` entry that declares no `base_url` of its own.
* `interfaces`: The interfaces the router serves. **Required**: a router has no `endpoint` or `responsesEndpoint`. The entries take the same shape and are validated by the same rules as a model's, `mode: translator` and `overridePaths` included. Refer to [models.<model_name>.interfaces](models.md#modelsmodel_nameinterfaces).
* `mode`: `proxy` (the default) or `decision`. See [Decision mode](#decision-mode).
* `decision`: The settings of decision mode. See [Decision mode](#decision-mode).
* `userRoles`: The roles allowed to call and list the router. If not defined, the router is available to all users; an empty list allows no one.
* `displayName`, `displayVersion`, `iconUrl`, `description`, `intro`, `reference`, `descriptionKeywords`, `author`, `createdAt`, `updatedAt`: Display fields, as for a model.
* `features`, `defaults`, `defaultHeaders`, `overrideName`, `forwardAuthToken`, `maxRetryAttempts`: As for a model.
* `applicationTypeSchemaId`, `applicationProperties`: An app-type schema and the properties it validates, as for a schema-rich application. Set both or neither. See [Type schema and properties](#type-schema-and-properties).

A router declares nothing of another deployment kind. DIAL Core rejects a router that has `upstreams`, `limits`, `pricing`, any other application field, `interceptors` (`A router declares no interceptors`), or `endpoint` / `responsesEndpoint` (`A router declares interfaces, not endpoint`).

## Type schema and properties

One router service can sit behind several router entries, each with its own typed settings. The entry names an app-type schema in `applicationTypeSchemaId` and holds the values in `applicationProperties`, as a schema-rich application does:

```json
{
  "applicationTypeSchemas": [
    {
      "$schema": "https://dial.epam.com/application_type_schemas/schema#",
      "$id": "https://dial.test/router-type",
      "dial:applicationTypeDisplayName": "Router type",
      "properties": {
        "tier": {"type": "string", "dial:meta": {"dial:propertyKind": "server", "dial:propertyOrder": 1}}
      },
      "required": ["tier"]
    }
  ],
  "routers": {
    "r3": {
      "baseUrl": "http://localhost:4848",
      "applicationTypeSchemaId": "https://dial.test/router-type",
      "applicationProperties": {"tier": "medium"},
      "interfaces": {"openaiChatCompletions": {}}
    }
  }
}
```

* A router that sets one of the two fields without the other is rejected (`A router declares applicationTypeSchemaId and applicationProperties together`). The properties are validated against the schema as an application's are.
* DIAL Core sends `X-DIAL-APPLICATION-PROPERTIES`, the JSON of the properties the schema declares, to the router on its interfaces, on its feature endpoints (rate, tokenize, truncate prompt, configuration) and on decide requests. Properties the schema does not declare are left out. A schema with `dial:appendApplicationPropertiesHeader: false` turns the header off.
* The header goes to the router only:
  * A translator in front of a router interface does not get it: the translator calls DIAL Core back on the router's interface, and the router gets the header on that request.
  * Responses retrieve, cancel and delete calls do not carry it, as they do not for an application.
  * The deployment a decision chose does not get it: the properties are the router's, not that deployment's.
* DIAL Core drops a client-sent `X-DIAL-APPLICATION-PROPERTIES`, for every deployment: the header carries what the administrator configured, so a client must not be able to set it.
* DIAL Core builds the header before it connects. When the schema is gone, or has changed so that the stored properties no longer match it, a request to the router answers `502` (`Router <name> properties do not match its schema`) and nothing reaches the router. A schema change takes effect on the next request on every pod.
* The schema contributes nothing else to a router: no endpoints, routes, MCP settings, files, prompts or skills. A router keeps `baseUrl` and `interfaces`.
* A router schema is still an app-type schema, so `GET /v1/application_type_schemas/schemas` lists it. An application of that type cannot be created, because it declares no completion or MCP endpoint.
* Properties are not secret: they are stored in plain text and sent to the router. Keep credentials out of them.

## Configuration API

Administrators can also manage routers at run time, in the `platform` bucket, as they manage models: `/v1/routers/platform/{router_name}`, the manifest kind `Router` (named `routers/platform/{router_name}`) in `POST /v1/admin/validate` and `POST /v1/admin/apply`, and the type `routers` of the config file endpoints. In a batch, routers are processed after translators and interceptors and before models, so a router may use a translator from the same batch.

## Limits

A router has no limits of its own. DIAL Core neither checks nor charges limits for a router on any API: `limits.<router_name>` on a role is ignored. The models the router calls back with its per-request key are checked and charged against the caller, so put limits on those models. `GET /v1/deployments/<router_name>/limits` answers the figures the role declares for the router, which never move.

## Listing

Routers appear, as entries with `object: "router"`, in the generic deployment listings:

* `GET /v1/deployments`, for `interface_type` `all`, `chat` (routers that declare `openaiChatCompletions`) and `embedding` (routers that declare `openaiEmbeddings`); not for `mcp` or `custom_ui`.
* `GET /v1/deployment-names`, with `type=router` or `all`.
* `GET /v1/deployments/{router_name}`: `403` when the caller's roles do not allow the router.

They have their own listing too:

* `GET /v1/routers`: `{"data": [...]}` with one entry per router the caller's roles allow.
* `GET /v1/routers/{router_name}`: that entry; `404` for an unknown name, `403` when the caller's roles do not allow it.

Routers do not appear in the model listings: `GET /openai/models`, `/openai/deployments`, `/openai/applications` and `/anthropic/v1/models`.

An entry carries `id`, `object: "router"`, `display_name`, `display_version`, `icon_url`, `description`, `intro`, `reference`, `features`, `defaults`, `description_keywords`, `interfaces` (`chat` and `embedding` as above, then the declared interface types), `created_at` and `updated_at`. It never carries the router's url.

## Identity

DIAL Core sends a router the same `API-KEY` it sends an application: a per-request key, on every request it forwards and on every decide request. A router that needs the caller's identity calls `GET /v1/user/info` with that key; the answer is the caller's, as if the caller had asked (`roles`, and `project` for a project API key or `userId` and `userClaims` for a JWT).

## Decision mode

With `mode: decision`, DIAL Core does not forward the client's request to the router. It sends the router a small decide request, reads the answer (the verdict) and serves the request with the deployment the verdict names, in the same pass, as if the client had called that deployment. While it decides, the router may call DIAL Core back with the decide request's key, for example to ask a classifier model or `GET /v1/user/info`.

The mode is the router's: every interface the router declares is decided. A router service that should also serve an interface by proxy needs a second router entry in proxy mode.

* `mode`: `decision`.
* `decision.body`: `true` to send the client's request body with the decide request, byte for byte. Default `false`: the decide request has an empty body.
* `decision.timeoutMs`: How long the decide request may take, connecting included. Default `5000`; must be positive.
* `decision.maxDecisions`: How many decide requests one client request may need, the first included (see [Fallback and re-decide](#fallback-and-re-decide)). Default `3`; from 1 to 10.

The decide request of an interface goes to its `base_url`, or to the router's `baseUrl` when it declares none, followed by `/decide`. DIAL Core rejects a `decision` object on a router in proxy mode (`decision requires mode 'decision'`) and a translated interface on a router in decision mode (`A router in mode 'decision' serves no interface through a translator`).

**Example**: `r1` decides chat completions and Messages requests at `http://localhost:4848/decide`, and embeddings at `http://localhost:4949/decide`.

```json
{
  "routers": {
    "r1": {
      "baseUrl": "http://localhost:4848",
      "mode": "decision",
      "decision": {"body": true, "timeoutMs": 2000},
      "defaultHeaders": {"X-DIAL-ROUTER-TIER": "medium"},
      "interfaces": {
        "openaiChatCompletions": {},
        "openaiEmbeddings": {"base_url": "http://localhost:4949"},
        "anthropicMessages": {}
      }
    }
  }
}
```

### The decide request

`POST <decide URL>` with:

* The client's headers as DIAL Core forwards them to any deployment: `Authorization` only when the router has `forwardAuthToken: true`, no client `API-KEY`, no client `X-DIAL-APPLICATION-ID` or `X-DIAL-APPLICATION-PROPERTIES`.
* The router's `defaultHeaders` for the interface, under every name the client did not send. They go on the decide request only: the chosen deployment receives its own `defaultHeaders`, as on a direct call.
* `X-DIAL-ROUTER` (the router's name) and `X-DIAL-API` (the interface: `openaiChatCompletions`, `openaiEmbeddings`, `openaiResponses` or `anthropicMessages`).
* `API-KEY`: a per-request key minted for the decide request. It carries the caller's identity, roles and claims and the per-request permissions granted to the router, with the router last in its execution path. With `decision.body: true` it also gives read access to the files and folders the request attaches, as the chosen deployment's key does; when the caller may not read one of them, DIAL Core answers `403` without asking the router. With the key the router may call `GET /v1/user/info` and any deployment open to per-request keys; a deployment with `accessibleByPerRequestKey: false` answers `403`. Each such call is a request of its own, with its own analytics row (`parent_deployment` the router, no `decision`), and its usage shows in the served answer's `statistics.usage_per_model`. DIAL Core deletes the key when the decide request ends, so the router must finish calling DIAL Core before it answers; the key's TTL is `decision.timeoutMs` plus a minute.
* An empty body, or the client's body unchanged with `Content-Type: application/json` when `decision.body` is `true`.

DIAL Core asks the router after the [global interceptors](interceptors.md), in the pass that reaches the router once the interceptor chain calls DIAL Core back.

### The verdict

A `200` answer with a JSON body naming the candidates in order of preference, one or more:

```json
{"candidates": ["gpt-4", "claude"]}
```

DIAL Core ignores other fields. A body without a non-empty `candidates` array, `{"deployment": "gpt-4"}` included, is an invalid verdict (`502`). A candidate must not be a router.

### Serving the chosen deployment

DIAL Core serves the first usable candidate as if the client had called it: the caller's roles, consent and per-request-key access to it, its limits against the caller, its interceptors, one per-request key, its `defaultHeaders`, `defaults` and `overrideName`. On `/openai/v1/chat/completions`, `/openai/v1/responses` and `/anthropic/v1/messages` (`count_tokens` included) DIAL Core sets the body `model` to the candidate's name before applying its `overrideName`, and sends the candidate's name in `X-DIAL-DEPLOYMENT-ID` on Messages.

The client sees the router's name in the answer's `model`, as with an alias: DIAL Core replaces the `model` of chat completions, Responses and Messages answers, streamed and buffered, with the router's name, and does the same when a stored response is read back. Analytics still names the deployment that served, in `deployment`. Embeddings, a buffered legacy completion and a buffered answer relayed through the chosen deployment's interceptors keep the upstream's `model`, because DIAL Core passes those bodies through unparsed.

### Fallback and re-decide

DIAL Core skips a candidate, without calling it, when the caller may not use it, its limits are exhausted, it does not serve the interface or it does not exist. It also skips a candidate, as long as no byte of its answer has reached the client, when:

* its call fails to connect, or answers a retriable status (`429`, `502`, `503`, `504` and the config's `retriableErrorCodes`) after its own upstreams are exhausted;
* its `200` answer breaks before the client got any of it: a non-streamed body that does not arrive whole, or a stream that breaks before its first chunk. A background Responses create is the exception: its job may already run, so DIAL Core does not start another one elsewhere.

Any other answer, a `400` included, is relayed to the client. Once the chosen deployment hands the request to its own interceptors, DIAL Core no longer falls back.

When every candidate of the verdict was skipped, DIAL Core asks the router again, up to `decision.maxDecisions` decide requests in all. The new decide request is built like the first, with a new key, and adds `X-DIAL-ROUTER-ATTEMPTS`: the JSON list of every attempt so far, in the shape of the analytics `attempts` below. The router may name any deployment again, one that failed included. When the router fails to answer, or no decision is left, the client gets the last candidate's failure.

When an answer breaks after bytes of it reached the client, DIAL Core cannot replace it: the client's connection is closed, as for any broken answer. DIAL Core then sends the router a notice: a request to the same decide URL with `X-DIAL-ROUTER-NOTICE: true` and `X-DIAL-ROUTER-ATTEMPTS`, whose last attempt has `"received": true, "partial": true`, no `API-KEY` and an empty body. DIAL Core does not wait for it and ignores the answer. A client that hangs up gets neither a fallback nor a notice. DIAL Core drops a client-sent `X-DIAL-ROUTER-ATTEMPTS` or `X-DIAL-ROUTER-NOTICE`.

Three ways to use it:

* **Sticky conversation.** Answer one candidate per decide request and remember it for the conversation (`X-CONVERSATION-ID`). Every failure comes back to the router, so the last answer for a conversation is the deployment that served it, or the notice says it broke.
* **Cheap fallback.** Answer `["a", "b", "c"]`. DIAL Core falls back through the list without asking again.
* **Adaptive fallback.** Read `X-DIAL-ROUTER-ATTEMPTS` and change course, for example to another provider after a `429`.

### Failures

| What happens | The client gets |
|---|---|
| The decide request exceeds `decision.timeoutMs` | `504` |
| The router is unreachable, or answers `5xx` | `502` |
| The `200` body is not a verdict | `502` |
| The router answers `4xx` | The router's status and body |
| The verdict names a router, or its last usable candidate does not exist | `502` |
| A deployment the router chose calls the router back | `502` |

These are the answers to the first decide request; in each case DIAL Core sends nothing to any candidate and writes no analytics row. A later decide request that fails ends the walk with the last candidate's failure instead.

### Accounting

A decided request is one pass, and it writes two analytics rows: the row of the deployment that served, and a router row above it.

* **Serving row.** The router comes before the deployment in `execution_path` (`["r1", "gpt-4"]`; after a global interceptor `["i1", "r1", "gpt-4"]`) and is its `parent_deployment`. Its `core_parent_span_id` is the router row's `core_span_id`, unless an interceptor chain served it. The per-request key the deployment receives carries the same `executionPath`, so a deployment it calls sees the same path, and `sourceDeployment` names the deployment itself.
* **Router row.** It has `deployment` the router, the `execution_path` and `parent_deployment` of a direct call to the router, and the client's response status. It has no bodies and no upstream. Its span exists only in the analytics rows. `price` and `usage_per_model` cover everything below the request, calls the router made while deciding included. Its token counts are zero, so summing tokens over all rows counts each model call once. DIAL Core writes it even when no candidate served, and it is the only row that carries the `decision` object:

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

`attempts` lists each try before the one that served: `status` is what DIAL Core would have answered for it, `reason` one of `forbidden`, `not_found`, `limit`, `unavailable` (skipped before the call), `connect`, `upstream`, `body` or `stream` (skipped after the call). `received: true` marks a try whose upstream had already answered. For a request that needed more than one decide request, `decisions` says how many, `candidates` lists every verdict's candidates in order, and `decide_ms` is their sum. For a background Responses job served through a decision, DIAL Core writes both rows when the job ends.
