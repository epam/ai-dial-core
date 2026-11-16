# Dynamic Setting for Aliases

An alias is a deployment of its own, with its own name, catalog entry and `userRoles`, that forwards every call to a
target deployment: a model, an application or a toolset. Aliases are managed by admins only, in the config file
or [in public storage](#aliases-in-public-storage). A caller who may use
the alias may call the target through it, even without any rights on the target; it does not let the caller read
the target's definition or credentials.

## aliases

A map of aliases and their [parameters](#aliasesalias_name):

* `<alias_name>` is the name callers call the alias by. It shares the namespace of `models`, `applications`,
  `toolsets` and `interceptors`: a config with an alias and another deployment of the same name does not load. Aliases have no version of their own yet; put the version in the name,
  for example `assistant-v1`. It is independent of the target's version.

### aliases.<alias_name>

* `target`: The deployment the alias forwards to. Required.
  * `path`: `models/<name>`, `applications/<name>` or `toolsets/<name>` for a deployment of the config, or
    `applications/<bucket>/<path>` or `toolsets/<bucket>/<path>` for an application or toolset resource, for example
    `applications/public/assistant`. The first segment is the target's type. It cannot name another alias.
* `presentAs`: The type the alias is listed and called as: `model`, `application` or `toolset`. Optional; by
  default, the type of the target. It must fit the target:

  | `presentAs` ↓ / target → | model | application                     | toolset |
  |--------------------------|-------|---------------------------------|---------|
  | `model`                  | yes   | yes                             | no      |
  | `application`            | yes   | yes                             | no      |
  | `toolset`                | no    | yes, if the application has `mcp` | yes   |

  Changing `presentAs` changes where the alias is listed and which endpoints serve it, not its name.
* `userRoles`: The roles that may use the alias. If not defined, the alias takes the target's: the `userRoles` of a
  config target, or, for a resource target, read access to the resource. An alias never opens its target to more
  callers than the admin listed.
* `displayName`, `description`, `iconUrl`, `intro`, `descriptionKeywords`: Shown in listings. When not set, the
  target's are shown, read on every listing, so they follow changes of the target.
* `displayVersion`, `author`, `createdAt`, `updatedAt`, `catalogSchemaId`, `catalogProperties`: Shown in listings.
  They describe the alias's own entry, so they do not fall back to the target's: the alias's version is its own, and
  the target's version is never shown as the alias's.

Other deployment fields (`endpoint`, `interfaces`, `features`, `pricing`, ...) are ignored on an alias: listings
show the target's features, pricing, limits, defaults and interfaces, so they never drift from it.

A config whose alias has no `target.path`, points to an alias, or has a `presentAs` that does not fit a config target
does not load. A config target that does not exist only logs a warning: the rest of the config loads, calls of the
alias answer `503` and listings leave it out. A resource target is read on every call, so a missing one is found
then, and the call answers `503`. `/v1/admin/health/config` reports both as `aliases/<alias_name>` with reason
`alias_target_missing`, or `alias_target_incompatible` when the target no longer fits the alias's type; it reads
the resource targets for the report.

**Example:**

```json
{
  "models": {
    "gpt-4-internal": {
      "endpoint": "http://adapter:7001/openai/deployments/gpt-4/chat/completions",
      "userRoles": ["power-user"]
    }
  },
  "aliases": {
    "assistant-v1": {
      "displayName": "Assistant",
      "description": "General-purpose assistant.",
      "userRoles": ["default-user"],
      "target": { "path": "models/gpt-4-internal" }
    },
    "assistant-model-v1": {
      "presentAs": "model",
      "target": { "path": "applications/public/assistant" }
    }
  },
  "roles": {
    "default-user": { "limits": { "gpt-4-internal": { "day": "2000" } } }
  }
}
```

A user with only `default-user` can call `assistant-v1`, and gets `403` when calling `gpt-4-internal` directly.
`assistant-model-v1` shows the public application `assistant` as a model, with the application's display name and
description, to the callers who can read that application.

Admins can read the aliases of the config file with `GET /v1/admin/config/file/aliases` and
`GET /v1/admin/config/file/aliases/<alias_name>`.

## Calling an alias

An alias of the config file is called by its name; an alias [in public storage](#aliases-in-public-storage) by its
url `aliases/public/<alias_name>`. Either is called through the endpoints of its type (`presentAs`, or the target's
type), with that name as `<alias>`:

* `model` and `application`: `/openai/deployments/<alias>/chat/completions` (and `/completions`, `/embeddings`),
  `/openai/v1/chat/completions`, `/openai/v1/responses` and `/anthropic/v1/messages` with `model: <alias>`, and
  `/v1/deployments/<alias>/mcp` when the target application has `mcp`.
* `toolset`: `/v1/toolset/<alias>/mcp`.

Core calls the target in a second pass, over its own HTTP address, as an application calls a model. The address is
the static setting `backgroundJob.localBaseUrl`, by default `http://localhost:<server port>`; set it when this port
is not reachable on `localhost`. The second pass:

* records the call chain: `execution_path` is `[alias, target]`, with one span and one analytics row per pass. The
  target's usage is counted once, by the target pass; the alias's row shows it as an application's row shows the
  usage of the models it called;
* checks and charges limits and cost under the **target's** name, to the user who made the request. Several aliases
  to one model share its quota, and a role that reaches a model only through an alias needs its limit defined under
  the model's name; a limit under the alias's name has no effect, and the config load logs a warning for it;
* runs the target's interceptors and the global interceptors once;
* is not refused by `accessibleByPerRequestKey: false` on the target.

The alias relays the target's answer, error statuses included, so an error of the target pass may name the target.
Answers keep the target's `model` value. The caller's `Authorization` header is not forwarded through an alias, so a
target with `forwardAuthToken` does not receive it.

## Signing in to a toolset through an alias

A toolset target with per-user credentials (`API_KEY` or `OAUTH`) is signed in to through the alias:
`POST /v1/ops/toolset/signin` and `POST /v1/ops/toolset/signout` accept the alias's name as `url`. The caller needs
to be one who may use the alias, not to have rights on the target; the credentials are stored under the target,
where the target pass of an alias call finds them. Organization-level credentials (`credentialsLevel: GLOBAL`) need
an admin. The alias's entry in `/openai/toolsets` carries the target's `auth_settings`, without secrets, with the
caller's sign-in statuses, so that a client knows to sign in.

An application shown as a toolset has no toolset sign-in; its external services sign in on the application.

## Features, stored responses and consent

`tokenize`, `truncate_prompt`, `configuration` and `rate` of an alias of type `model` or `application` are served by
the target in a second pass, as calls are. A rating reaches the target's rate endpoint, or, when it has none, is
recorded under the target as for a direct rating.

A Responses API response stored through an alias is read, cancelled and deleted through the alias
(`/openai/v1/responses/<id>`, `.../cancel`), by the callers who may use the alias.

When the target requires consent (`features.consentRequired`, or declared `resourceDependencies`), so does the alias. `GET /v1/consent/<alias>` lists the
alias and its target, and the consent accepted with `POST /v1/consent/<alias>` covers both passes of the call; the
caller needs no rights on the target.

Not served through an alias yet: `/v1/toolset/<alias>/tools`.

## Listings

`/openai/models`, `/openai/deployments`, `/anthropic/v1/models`, `/openai/applications`, `/openai/toolsets`,
`/v1/deployments` and `/v1/deployment-names` list an alias under its type, by its name (the name of a config alias,
the url of a stored one), for the callers who may use it (one of its `userRoles`, or, when it has none, the callers
who may use its target), and their single-item endpoints answer for that name, for example
`/openai/models/aliases/public/assistant-v1`. Aliases to config deployments and to resources are both listed. An
alias whose target is missing is left out. Every listing that shows aliases lists and reads the stored aliases, on
the task executor.

An entry has the alias's name and display fields (with the fallbacks above) and the target's features, pricing,
limits, defaults and interfaces. It never shows the target's path, endpoint or default headers, nor the target's
version as the alias's.

## Aliases in public storage

Admins can also create aliases at run time, stored as resources directly under `aliases/public/<alias_name>`. A
stored alias is called and listed by its url `aliases/public/<alias_name>`, the way a public application is
(`/openai/deployments/aliases/public/<alias_name>/chat/completions`, `"model": "aliases/public/<alias_name>"`,
`/v1/toolset/aliases/public/<alias_name>/mcp`). The url is its name everywhere: in the execution path, analytics and
listings. The bare name `<alias_name>` does not reach it; a config alias of that name is a different alias. A stored
alias is read on each call, so a change applies to the next call.

| Method and path | What it does |
|---|---|
| `PUT /v1/aliases/public/<alias_name>` | Create or replace the alias. Honours `If-Match` and `If-None-Match` (`412` on a mismatch). |
| `GET /v1/aliases/public/<alias_name>` | Read the definition, target included. |
| `DELETE /v1/aliases/public/<alias_name>` | Delete the alias; the target is not touched. |
| `GET /v1/metadata/aliases/public/` | List the stored aliases. |

Every method is for admins only, `GET` included: other callers get `403`. Another bucket answers `404`; a folder
(`aliases/public/a/b`) answers `400`, and so does a name with characters other than letters, digits, `.`, `_` and `-`,
so that the id listings show is the id callers send. `GET` of a blob that calls ignore (written around this API, for
example without `target`) answers it with `status: invalid` and its `validationWarnings`. If storage cannot list the
stored aliases, listings answer without them.

**Body.** Only `target`, `presentAs`, `userRoles` and the display fields `displayName`, `displayVersion`, `iconUrl`,
`description`, `intro` and `descriptionKeywords`, with the meaning above. Any other field answers `400` (`status` and a `name` equal to `<alias_name>` from a `GET` answer are ignored, so it can be sent back;
another `name` answers `400`).

**Checks on `PUT`**, before anything is written:

* the rules of the config load (`target.path` present and of a supported form, no alias as target, a `presentAs`
  that fits a config target): `400` with `validationWarnings`;
* the target is a config deployment or a resource in the `public` bucket, such as `applications/public/assistant`;
  a resource in any other bucket answers `400`;
* a resource target that exists must fit `presentAs` too: `400`. A target that does not exist is accepted; the alias
  is broken until it exists, calls of it answer `503`, listings leave it out, and `/v1/admin/health/config` reports
  `aliases/public/<alias_name>` with reason `alias_target_missing` (or `alias_target_incompatible`). An alias of the
  config file is reported under `aliases/<alias_name>`.

A stored alias needs no unique name among the deployments: its url cannot be the name of a config deployment.

**Example:**

```http
PUT /v1/aliases/public/search-tools-v1
Content-Type: application/json

{
  "displayName": "Search",
  "userRoles": ["default-user"],
  "target": { "path": "toolsets/public/internal-search" }
}
```

Callers with the role `default-user` then use `POST /v1/toolset/aliases/public/search-tools-v1/mcp`.

### Access

Who may use a stored alias is decided by its `userRoles`, exactly as for an alias of the config file: a caller with
one of them may call it and sees it in listings, others get `403`; an alias without `userRoles` takes the target's.
The public access rules of the public bucket (publication rules) do not apply to stored aliases: they cannot be set
on the root of the public bucket, where aliases are stored.

### What is refused

* Sharing an alias (`/v1/ops/resource/share/create`), a publication that adds or removes one, and copying or moving
  one (`/v1/ops/resource/copy`, `/v1/ops/resource/move`) answer `400`.
* Only `aliases/public/<alias_name>` names a stored alias: `aliases/<other bucket>/...`, a folder under
  `aliases/public/`, `public/<alias_name>` or `<type>/public/<alias_name>` do not reach it or its target.
* Aliases cannot be written through `/v1/admin/validate` or `/v1/admin/apply` (there is no `Alias` manifest kind) or
  in a user bucket. A manifest model named like an alias of the config file fails. The admin system export includes
  the stored aliases, as the rest of the public bucket.
