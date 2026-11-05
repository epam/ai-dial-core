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
* `applicationTypeSchemaId`, `applicationProperties`: An app-type schema and the properties it validates, as for a schema-rich application. Set both or neither. See [Type schema and properties](#type-schema-and-properties).

A router declares nothing of another deployment kind. DIAL Core rejects the config when a router has `upstreams`, `limits`, `pricing`, any other application field, `interceptors` (`A router declares no interceptors`), or `endpoint` / `responsesEndpoint` (`A router declares interfaces, not endpoint`). A [Configuration API](#configuration-api) write of such a router is rejected the same way.

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

* A router that sets one of the two fields without the other is rejected (`A router declares applicationTypeSchemaId and applicationProperties together`).
* The properties are validated against the schema when the config files load, against the files' schemas, as for applications. A [Configuration API](#configuration-api) write validates them against the stored and file schemas, plus the batch's earlier `Schema` entries on validate and apply. An unknown schema or properties that break it answer `422`, whatever `config.write.softValidation` says.
* DIAL Core sends `X-DIAL-APPLICATION-PROPERTIES`, the JSON of the properties the schema declares, to the router on its interfaces, on its feature endpoints (rate, tokenize, truncate prompt, configuration) and on decide requests. Properties the schema does not declare are left out. A schema with `dial:appendApplicationPropertiesHeader: false` turns the header off.
* The header does not go to a translator in front of a router interface, to Responses retrieve, cancel or delete calls, or to the deployment a decision chose. A client-sent header of that name is dropped.
* DIAL Core builds the header before it connects. When the schema is gone, or has changed so that the stored properties no longer match it, a request to the router answers `502` (`Router <name> properties do not match its schema`) and nothing reaches the router. A schema change takes effect on the next request on every pod.
* The schema contributes nothing else to a router: no endpoints, routes, MCP settings, files, prompts or skills. A router keeps `baseUrl` and `interfaces`.
* A router schema is still an app-type schema, so `GET /v1/application_type_schemas/schemas` lists it. An application of that type cannot be created, because it declares no completion or MCP endpoint.
* Properties are not secret. They are stored and exported in plain text, returned by `GET /v1/routers/platform/{router_name}`, sent to the router and written to the debug log when a router loads. Keep credentials out of them.

## Configuration API

Administrators can also manage routers at run time, in the `platform` bucket, like models, interceptors and translators. A router written there is a deployment as soon as the write returns.

* `PUT /v1/routers/platform/{router_name}` creates or replaces a router; the body is a [router entry](#routersrouter_name). It answers `200` with `{"name": "<router_name>"}` and an `ETag`, and honours `If-Match` and `If-None-Match: *`. A router that breaks a rule above answers `422` with `validationWarnings`; a field of another deployment kind answers `400`; a name already used by a model, application, toolset or interceptor answers `409`.
* `GET /v1/routers/platform/{router_name}` returns the stored router with `name` and `status`; `DELETE` removes it. Both see only routers written through the API: a router from the config files answers `404` here.
* `GET /v1/metadata/routers/platform/` lists the stored routers.
* `POST /v1/admin/validate` and `POST /v1/admin/apply` accept the manifest kind `Router`, named `routers/platform/{router_name}`. In a batch, routers are processed after translators and interceptors and before models, so a router may use a translator from the same batch:

```json
{
  "manifests": [
    {
      "kind": "Router",
      "name": "routers/platform/r-batch",
      "spec": {
        "baseUrl": "http://localhost:4848",
        "interfaces": {
          "openaiChatCompletions": {},
          "anthropicMessages": {"mode": "translator", "translator": "t-batch"}
        }
      }
    },
    {
      "kind": "Translator",
      "name": "translators/platform/t-batch",
      "spec": {"in": "anthropicMessages", "out": "openaiChatCompletions", "baseUrl": "http://localhost:4848/translate"}
    }
  ]
}
```

* `GET /v1/admin/config/file/routers[/{router_name}]` shows the routers of the config files, and `POST /v1/admin/config/file/migrate` with `{"types": ["routers"]}` copies them into the `platform` bucket.

A router written through the API replaces, as a whole, the router of the same name from the config files. Under `config.onInvalidEntity: skip` a stored router that breaks a rule is left out and reported by `GET /v1/admin/health/config`.

A router's `defaultHeaders` are stored and returned as written, as an interceptor's are: they must not carry a secret.

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

DIAL Core sends no identity header of its own. It drops a client-sent `X-DIAL-USER-INFO` from the chat completions, completions, embeddings, Responses, Messages, deployment feature and decide requests it sends to a model, application or router, so a router that used to trust that header cannot be given forged roles. An interceptor receives the client's headers as they are.

## Decision mode

With `mode: decision` on an `interfaces` entry, DIAL Core does not forward the client's request to the router. It sends the router a small decide request, reads the answer (the verdict) and serves the request with the deployment the verdict names, in the same pass, as if the client had called that deployment. While it decides, the router may call DIAL Core back with the decide request's key, for example to ask a classifier model or `GET /v1/user/info`. A router may declare decision mode for some interfaces and proxy others.

The fields of a decision entry:

* `mode`: `decision`. Allowed on a router only: DIAL Core rejects it on a model, application, interceptor or toolset (`Mode 'decision' is allowed on a router only`).
* `base_url`: **Required** on the entry: the URL of the decide request, used as written. The router-level `baseUrl` does not stand in for it (`Mode 'decision' requires base_url`), and DIAL Core never forwards a client request to it.
* `context.body`: `true` to send the client's request body with the decide request, byte for byte. Default `false`: the decide request has an empty body.
* `decisionTimeoutMs`: How long the decide request may take, connecting included. Default `5000`; must be positive.
* `maxDecisions`: How many decide requests one client request may need, the first included (see [Fallback and re-decide](#fallback-and-re-decide)). Default `3`; from 1 to 10.
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
* `X-DIAL-ROUTER` (the router's name) and `X-DIAL-API` (the interface: `openaiChatCompletions`, `openaiEmbeddings`, `openaiResponses` or `anthropicMessages`).
* `API-KEY`: a per-request key minted for the decide request. It carries the caller's identity, roles and claims and the per-request permissions granted to the router, with the router last in its execution path. It carries no shares of the request's attachments, so with `context.body: true` the router sees attachment URLs it cannot read. With it the router may call `GET /v1/user/info` and any deployment open to per-request keys; a deployment with `accessibleByPerRequestKey: false` answers `403`. Each such call is a request of its own, with its own analytics row (`parent_deployment` the router, no `decision`), and its usage shows in the served answer's `statistics.usage_per_model`. DIAL Core deletes the key when the decide request ends, so the router must finish calling DIAL Core before it answers; the key's TTL is `decisionTimeoutMs` plus a minute.
* An empty body, or the client's body unchanged with `Content-Type: application/json` when `context.body` is `true`.

DIAL Core asks the router after the [global interceptors](interceptors.md), in the pass that reaches the router once the interceptor chain calls DIAL Core back.

### The verdict

A `200` answer with a JSON body naming the candidates in order of preference, one or more:

```json
{"candidates": ["gpt-4", "claude"]}
```

DIAL Core ignores other fields. A body without a non-empty `candidates` array, `{"deployment": "gpt-4"}` included, is an invalid verdict (`502`). A candidate must not be a router.

### Serving the chosen deployment

DIAL Core serves the first usable candidate as if the client had called it: the caller's roles, consent and per-request-key access to it, its limits against the caller, its interceptors, one per-request key, its `defaultHeaders`, `defaults` and `overrideName`. On `/openai/v1/chat/completions`, `/openai/v1/responses` and `/anthropic/v1/messages` (`count_tokens` included) DIAL Core sets the body `model` to the candidate's name before applying its `overrideName`, and sends the candidate's name in `X-DIAL-DEPLOYMENT-ID` on Messages.

### Fallback and re-decide

DIAL Core skips a candidate, without calling it, when the caller may not use it, its limits are exhausted, it does not serve the interface or it does not exist. It also skips a candidate, as long as no byte of its answer has reached the client, when:

* its call fails to connect, or answers a retriable status (`429`, `502`, `503`, `504` and the config's `retriableErrorCodes`) after its own upstreams are exhausted;
* its `200` answer breaks before the client got any of it: a non-streamed body that does not arrive whole, or a stream that breaks before its first chunk. A background Responses create is the exception: its job may already run, so DIAL Core does not start another one elsewhere.

Any other answer, a `400` included, is relayed to the client. Once the chosen deployment hands the request to its own interceptors, DIAL Core no longer falls back.

When every candidate of the verdict was skipped, DIAL Core asks the router again, up to `maxDecisions` decide requests in all. The new decide request is built like the first, with a new key, and adds `X-DIAL-ROUTER-ATTEMPTS`: the JSON list of every attempt so far, in the shape of the analytics `attempts` below. The router may name any deployment again, one that failed included. When the router fails to answer, or no decision is left, the client gets the last candidate's failure.

When an answer breaks after bytes of it reached the client, DIAL Core cannot replace it: the client's connection is closed, as for any broken answer. DIAL Core then sends the router a notice: a request to the same `base_url` with `X-DIAL-ROUTER-NOTICE: true` and `X-DIAL-ROUTER-ATTEMPTS`, whose last attempt has `"received": true, "partial": true`, no `API-KEY` and an empty body. DIAL Core does not wait for it and ignores the answer. A client that hangs up gets neither a fallback nor a notice. DIAL Core drops a client-sent `X-DIAL-ROUTER-ATTEMPTS` or `X-DIAL-ROUTER-NOTICE`.

Three ways to use it:

* **Sticky conversation.** Answer one candidate per decide request and remember it for the conversation (`X-CONVERSATION-ID`). Every failure comes back to the router, so the last answer for a conversation is the deployment that served it, or the notice says it broke.
* **Cheap fallback.** Answer `["a", "b", "c"]`. DIAL Core falls back through the list without asking again.
* **Adaptive fallback.** Read `X-DIAL-ROUTER-ATTEMPTS` and change course, for example to another provider after a `429`.

### Failures

| What happens | The client gets |
|---|---|
| The decide request exceeds `decisionTimeoutMs` | `504` |
| The router is unreachable, or answers `5xx` | `502` |
| The `200` body is not a verdict | `502` |
| The router answers `4xx` | The router's status and body |
| The verdict names a router, or its last usable candidate does not exist | `502` |
| A deployment the router chose calls the router back | `502` |

These are the answers to the first decide request; in each case DIAL Core sends nothing to any candidate and writes no analytics row. A later decide request that fails ends the walk with the last candidate's failure instead.

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

`attempts` lists each try before the one that served: `status` is what DIAL Core would have answered for it, `reason` one of `forbidden`, `not_found`, `limit`, `unavailable` (skipped before the call), `connect`, `upstream`, `body` or `stream` (skipped after the call). `received: true` marks a try whose upstream had already answered. For a request that needed more than one decide request, `decisions` says how many, `candidates` lists every verdict's candidates in order, and `decide_ms` is their sum. A background Responses job served through a decision carries the same `execution_path`, `parent_deployment` and `decision` on the row DIAL Core writes when the job ends.

Rolling back to a DIAL Core release without decision mode while a `mode: decision` entry exists makes the whole config file fail to load: switch those entries back to proxy mode first.

A DIAL Core release without the router Configuration API ignores routers stored in the `platform` bucket: they stay in storage, are not deployments on that release, and serve again after the upgrade. While pods of both releases run, a stored router answers `404` on the older pods; routers from the config files behave the same on every pod.

A DIAL Core release without router type schemas rejects a router that sets `applicationTypeSchemaId` or `applicationProperties`: such a file router stops the config files from loading, and such a stored router is skipped. Remove the two fields from those routers before rolling back. Routers without them are stored exactly as before and are not affected.
