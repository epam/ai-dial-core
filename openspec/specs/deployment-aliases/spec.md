# deployment-aliases Specification

## Purpose
Aliases: admin-managed deployments of their own, called by their name, that forward calls to a model, application
or toolset and grant their callers the right to call it. Covers their definition and validation, who may use them,
the call chain they record, sign-in to a toolset target, listings and the health report.

## Requirements

### Requirement: Alias in the config
Core SHALL read aliases from the top-level `aliases` config section, keyed by the alias's name. An alias SHALL
have a `target` with a `path` of the form `models/<name>`, `applications/<name>` or `toolsets/<name>` for a config
deployment, or `applications/<bucket>/<path>` or `toolsets/<bucket>/<path>` for a resource. It MAY have `presentAs`
(`model`, `application` or `toolset`), `userRoles` and the display fields of a deployment.

#### Scenario: Alias loaded
- **WHEN** the config has an alias `assistant` with target path `models/gpt-4-internal`
- **THEN** the config loads and `assistant` can be called

#### Scenario: Bad target refused
- **WHEN** an alias has no `target.path`, or a path that is not one of these forms
- **THEN** the config file load fails, as for a duplicate deployment name

### Requirement: Alias names share the deployment namespace
An alias name SHALL NOT be the name of a model, application, toolset, interceptor or other alias.

#### Scenario: Name clash
- **WHEN** the config file has a model and an alias both named `assistant`
- **THEN** the config file load fails with `Deployment uniqueness is violated: duplicate is found assistant`

### Requirement: Alias type
The type an alias is listed and called as SHALL be its `presentAs`, or the type the first segment of its target path
names when `presentAs` is not set. Core SHALL refuse an alias whose target path starts with `aliases/`, and an alias
whose type does not fit a config target: `model` and `application` fit a model or an application target, `toolset`
fits a toolset target or an application target that has `mcp`.

#### Scenario: Type of the target by default
- **WHEN** an alias without `presentAs` targets `models/gpt-4-internal`
- **THEN** `/openai/models` lists it with `object` `model`

#### Scenario: Alias to an alias
- **WHEN** an alias's target path is `aliases/other`
- **THEN** the config file load fails

#### Scenario: Toolset shown as a model
- **WHEN** an alias with `presentAs: model` targets `toolsets/search`
- **THEN** the config file load fails

### Requirement: Missing alias target
Core SHALL load a config whose alias names a config target that does not exist, log a warning, and answer `503` to a
call of that alias. `GET /v1/admin/health/config` SHALL report an alias whose target, in the config or in storage,
does not exist as a skipped entry with id `aliases/<alias name>` and reason `alias_target_missing`, or with reason
`alias_target_incompatible` when the target no longer fits the alias's type. It SHALL read resource targets for it.

#### Scenario: Target removed
- **WHEN** the target model of `broken` is not in the config
- **THEN** the config still loads, a call of `broken` answers `503`, and `/openai/models` does not list it

#### Scenario: Broken alias in the health report
- **WHEN** an admin reads `/v1/admin/health/config` while `broken` points to a missing model
- **THEN** the status is `degraded` and `skipped` has `aliases/broken` with reason `alias_target_missing`

#### Scenario: Missing resource target in the health report
- **WHEN** an admin reads `/v1/admin/health/config` while an alias points to an application resource that does not exist
- **THEN** `skipped` has the alias with reason `alias_target_missing`

### Requirement: Calling an alias
Core SHALL let a caller who may use the alias call it, whatever the caller's rights on the target, SHALL answer `403`
to other callers, and SHALL forward a request of the endpoints in "Endpoints of an alias" to the target and relay its
answer. Calling through an alias SHALL NOT give the caller rights to read the target.

#### Scenario: Caller without rights on the target
- **WHEN** `gpt-4-internal` has `userRoles: ["power-user"]`, `assistant` targets it with `userRoles: ["default-user"]`, and a caller with only `default-user` sends a chat completion to `assistant`
- **THEN** the answer is `200` with the model's answer, and the same caller gets `403` from `gpt-4-internal` directly

#### Scenario: Resource target
- **WHEN** an alias targets an application in another user's bucket, and a caller with the alias's role calls it
- **THEN** the answer is `200`, and the caller gets `403` reading the application

#### Scenario: Target changed
- **WHEN** the config changes the target of `assistant` to another model
- **THEN** the next call of `assistant` reaches the new model

#### Scenario: Caller without the alias's role
- **WHEN** a caller whose roles are not in the alias's `userRoles` calls it
- **THEN** the answer is `403`

### Requirement: Who may use an alias
The callers who may use an alias SHALL be those with one of its `userRoles`. An alias without `userRoles` SHALL take
the target's: the `userRoles` of a config target, or read access to a resource target. Listings and single-item
endpoints SHALL apply the same rule.

#### Scenario: Alias without userRoles
- **WHEN** an alias without `userRoles` targets `gpt-4-internal`, which has `userRoles: ["power-user"]`
- **THEN** a caller with `power-user` calls the alias and sees it in `/openai/models`, and a caller with only `default-user` gets `403` and does not see it

### Requirement: Endpoints of an alias
An alias SHALL be called by its name. An alias of type `model` or `application` SHALL be callable through the
deployments-POST endpoints, `/openai/v1/chat/completions`, `/openai/v1/responses` and `/anthropic/v1/messages`, and
through `/v1/deployments/<alias>/mcp` when the target has `mcp`. An alias of type `toolset` SHALL be callable through
`/v1/toolset/<alias>/mcp`.

#### Scenario: Responses and Messages
- **WHEN** a caller sends `/openai/v1/responses` or `/anthropic/v1/messages` with `model: assistant`
- **THEN** the target receives the request with `model` set to the target name

#### Scenario: Toolset alias over an MCP application
- **WHEN** an alias with `presentAs: toolset` targets an application with `mcp`, and a caller posts to `/v1/toolset/<alias>/mcp`
- **THEN** the request reaches the application's MCP endpoint, and a chat completion to the alias answers as one to the toolset does

### Requirement: Features, stored responses and consent through an alias
For an alias of type `model` or `application`, Core SHALL serve `tokenize`, `truncate_prompt`, `configuration` and
`rate` by the target in a target pass, and SHALL serve `GET`, `DELETE` and `cancel` of a response stored through the
alias by the alias. Consent to the alias SHALL cover its target: the review SHALL list both, and the accepted consent
SHALL let the caller call the alias without rights on the target.

#### Scenario: Stored response
- **WHEN** a caller stores a response through `multi-alias` and reads `/openai/v1/responses/<id>`
- **THEN** the answer is `200` with that id

#### Scenario: Tokenize and rate
- **WHEN** a caller posts to `/v1/deployments/<alias>/tokenize` and `/v1/<alias>/rate` of an alias whose target has these endpoints
- **THEN** both answer `200` and the target's endpoints receive the requests

#### Scenario: Consent
- **WHEN** the target of `consent-alias` requires consent, and the caller accepts the consent `GET /v1/consent/consent-alias` reviews
- **THEN** the review lists the alias and the target as requiring consent, a call before accepting answers `403`, and a call after answers `200`

### Requirement: Alias calls are recorded as a call chain
Core SHALL handle an alias call as two passes: the alias pass, with `execution_path` `[alias]`, and the target pass,
with `execution_path` `[alias, target]`. Each pass SHALL have its own token-stats span and analytics row; the alias
pass SHALL NOT report the usage in the relayed answer as its own. Limits and cost SHALL be charged only by the target
pass, under the target's name, to the user who started the request, on every interface including MCP.

#### Scenario: Execution path
- **WHEN** a caller calls `assistant`, which targets `gpt-4-internal`
- **THEN** the per-request key the model receives has `executionPath` `["assistant", "gpt-4-internal"]`

#### Scenario: No limit under the alias's name
- **WHEN** the caller's role has a `requestDay` limit of 1 under the name of a toolset alias, and the caller makes two MCP calls through it
- **THEN** both answer `200`

#### Scenario: Limit under the target's name
- **WHEN** the caller's role, which is not in the target's `userRoles`, has a `day` token limit for `gpt-4-internal` and the limit is used up
- **THEN** a call of `assistant` answers `429`

### Requirement: The alias relays the target's answer
Core SHALL answer an alias call with the status and body of the target pass, SHALL NOT retry the target pass, and
SHALL NOT let the status of one call affect other calls of the alias.

#### Scenario: Error of one caller
- **WHEN** the target pass answers `429` to one caller of `assistant` because that caller's limit is used up
- **THEN** that caller gets `429`, and the next call of `assistant` by another caller answers `200`

### Requirement: Granted access is for the target pass only
The right to call the target SHALL be granted only to the target pass of the alias call, to the interceptors in
front of the target, and to calls back for the target itself (such as a translator's). It SHALL NOT let any of them
call another deployment.

#### Scenario: Interceptor in front of the target
- **WHEN** the target of an alias has an interceptor, and a caller without a role on the target calls the alias
- **THEN** the interceptor's call back to Core is accepted and the answer is `200`

#### Scenario: Key handed on by the target
- **WHEN** the target of an alias is an application that calls `gpt-4-internal` with its own per-request key, and the caller has no role on `gpt-4-internal`
- **THEN** that call answers `403`

### Requirement: Sign-in through a toolset alias
`/v1/ops/toolset/signin` and `/v1/ops/toolset/signout` SHALL accept the name of a toolset alias whose target is a
toolset, for a caller who may use the alias, and SHALL store or remove the credentials under the target, where the
target pass finds them. Organization-level credentials SHALL need an admin. The listing entry of such an alias SHALL
carry the target's auth settings, without secrets, with the caller's auth statuses.

#### Scenario: User signs in through the alias
- **WHEN** a caller without rights on an API-key toolset signs in with the name of an alias to it, and calls the alias
- **THEN** the sign-in answers `200`, `/openai/toolsets` shows the alias as `SIGNED_IN`, and the toolset receives the caller's key

### Requirement: Aliases in listings
Core SHALL list an alias the caller may call with the deployments of its type in `/openai/models`,
`/openai/deployments`, `/anthropic/v1/models`, `/openai/applications`, `/openai/toolsets`, `/v1/deployments` and
`/v1/deployment-names`, a config alias by its name and a stored alias by its url, and SHALL answer for that name on
their single-item endpoints. An alias whose target is missing SHALL be left out of all of them.

#### Scenario: Broken alias not named
- **WHEN** a caller lists `/v1/deployment-names`
- **THEN** the list has `assistant` and not `broken`, whose target is missing

#### Scenario: Toolset entry
- **WHEN** an alias has `presentAs: toolset`
- **THEN** `/openai/toolsets` lists it and `/openai/models` does not

#### Scenario: Stored alias to a resource target
- **WHEN** an admin stores `res-model-v1` with `presentAs: model` and an application resource as target
- **THEN** `/openai/models`, `/openai/deployments`, `/v1/deployments` and `/v1/deployment-names` list `aliases/public/res-model-v1`, `/openai/models/aliases/public/res-model-v1` answers `200` with `object` `model`, and no listing shows the target's path or the bare name `res-model-v1`

#### Scenario: Anthropic listing
- **WHEN** stored aliases target a model with the Messages interface and a model without it
- **THEN** `/anthropic/v1/models` lists the first only, and `/anthropic/v1/models/<url of the second>` answers `404`

### Requirement: Listings without storage
When storage cannot list or read the stored aliases, a listing SHALL answer without them rather than fail.

#### Scenario: Storage fails
- **WHEN** listing `aliases/public/` fails while a caller lists `/openai/models`
- **THEN** the answer is `200` with the config models and config aliases

### Requirement: Content of an alias entry
A listing entry of an alias SHALL carry the alias's name and display fields and the target's features, pricing,
limits, defaults and interfaces, and SHALL NOT carry the target's path or endpoint. The display name, description,
icon, intro and keywords SHALL be the target's when the alias does not set them; the display version, author, dates
and catalog fields SHALL NOT fall back to the target's.

#### Scenario: Model entry
- **WHEN** a caller with `default-user` lists `/openai/models`
- **THEN** the list has `assistant` with `display_name` `Assistant` and the pricing of `gpt-4-internal`, and not `gpt-4-internal`

#### Scenario: Display fields of the target
- **WHEN** an alias sets none of `displayName`, `description`, `iconUrl` and `displayVersion`, and its target model has all of them
- **THEN** `/openai/models` shows the alias with the model's display name, description and icon, and without a display version

#### Scenario: Application shown as a model
- **WHEN** an alias with `presentAs: model` targets an application
- **THEN** `/openai/models/<alias>` answers `200` with `object` `model`

### Requirement: Aliases of the config file
`GET /v1/admin/config/file/aliases` SHALL list the names of the aliases in the config file and
`GET /v1/admin/config/file/aliases/<name>` SHALL answer the alias, for admins only.

#### Scenario: File aliases listed
- **WHEN** an admin reads `/v1/admin/config/file/aliases`
- **THEN** the items have `assistant`, and a caller who is not an admin gets `403`

### Requirement: Alias in public storage
Core SHALL serve aliases stored directly under `aliases/public/<name>` and SHALL call them by that url, as public
applications are called: the url is the alias's deployment name in every endpoint, the execution path and listings.
The bare name `<name>` SHALL NOT reach a stored alias. A stored alias SHALL keep only `presentAs`, `target`,
`userRoles` and the display fields.

#### Scenario: Called by its url
- **WHEN** an admin stores `pub-v1` with target `models/gpt-4-internal`, and a caller with no role on `gpt-4-internal` sends a chat completion to `aliases/public/pub-v1`
- **THEN** the answer is `200` with the model's answer, the same caller gets `403` from `gpt-4-internal` directly, and the per-request key the model receives has `executionPath` `["aliases/public/pub-v1", "gpt-4-internal"]`

#### Scenario: Short name
- **WHEN** `pub-v1` is stored and a caller sends a chat completion to `pub-v1`
- **THEN** the answer is `404`; and when a stored alias `assistant` and a config alias `assistant` both exist, a call of `assistant` reaches the config alias's target

#### Scenario: Responses and Messages
- **WHEN** `multi-v1` is stored with target `models/multi-model` and a caller sends `/openai/v1/responses` or `/anthropic/v1/messages` with `model: aliases/public/multi-v1`
- **THEN** the target receives the request with `model` set to `multi-model`

#### Scenario: Toolset by url
- **WHEN** `tools-v1` is stored with `presentAs: toolset` and an MCP application as target, and a caller posts to `/v1/toolset/aliases/public/tools-v1/mcp`
- **THEN** the request reaches the application's MCP endpoint, and `/openai/toolsets` lists `aliases/public/tools-v1`

#### Scenario: Not directly in the public bucket
- **WHEN** a caller calls `aliases/<user bucket>/pub-v1` or `aliases/public/folder/pub-v1`
- **THEN** the answer is `404` and no target receives a request

### Requirement: Who may call a stored alias
Core SHALL let a caller use a stored alias when one of the caller's roles is in the alias's `userRoles`, or, when the
alias has none, when the caller may use its target, exactly as for an alias of the config file, and SHALL answer
`403` to other callers. The same SHALL decide whether listings show the alias and whether its single-item endpoints
answer.

#### Scenario: Caller with a role of the alias
- **WHEN** `pub-v1` is stored with `userRoles: ["default-user"]` and target `models/gpt-4-internal`
- **THEN** a caller with `default-user` calls `aliases/public/pub-v1` with `200`, and a caller with only `power-user` gets `403`

#### Scenario: Listings follow userRoles
- **WHEN** `pub-v1` is stored with `userRoles: ["default-user"]`
- **THEN** `/openai/models` lists `aliases/public/pub-v1` for a caller with `default-user` and not for one with only `power-user`; for the latter `/openai/models/aliases/public/pub-v1`, `/openai/deployments/aliases/public/pub-v1` and `/v1/deployments/aliases/public/pub-v1` answer `403` and `/v1/deployment-names` does not name it

#### Scenario: No userRoles
- **WHEN** `open-v1` is stored without `userRoles`, with target `models/gpt-4-internal`, which has `userRoles: ["power-user"]`
- **THEN** a caller with `power-user` calls `aliases/public/open-v1` with `200` and sees it in `/openai/models`, and a caller with only `default-user` gets `403` and does not see it

### Requirement: Managing a stored alias
Core SHALL create or replace a stored alias on `PUT /v1/aliases/public/<name>` (honouring `If-Match` and
`If-None-Match`), answer it on `GET`, delete it on `DELETE`, and list the stored aliases on
`GET /v1/metadata/aliases/public/`, for admins only: every method SHALL answer `403` to other callers. Another bucket
SHALL answer `404`; a nested path or a name of characters other than letters, digits, `.`, `_` and `-` SHALL answer
`400`. A change SHALL apply to the next call.

#### Scenario: Create, update and delete
- **WHEN** an admin creates `pub-v1` with `If-None-Match: *`, repeats it, updates it with `If-Match` and its ETag, lists `/v1/metadata/aliases/public/`, calls it and deletes it
- **THEN** the answers are `200`, `412`, `200`, a listing with `aliases/public/pub-v1`, a `200` call, and `204`; then `GET` answers `404`, a call of `aliases/public/pub-v1` answers `404` and `/openai/models` does not list it

#### Scenario: Not an admin
- **WHEN** a caller without the admin role sends `PUT`, `GET` or `DELETE` to `/v1/aliases/public/pub-v1`, or `GET /v1/metadata/aliases/public/`
- **THEN** every answer is `403`

#### Scenario: Name the url carries unchanged
- **WHEN** an admin stores `x%5B1%5D`, `x%25` or `pub@v1`, and then `Pub.v1_2-x`
- **THEN** the first three answer `400`; `/openai/models` lists `aliases/public/Pub.v1_2-x`, and that id is callable in the path and as body `model`

#### Scenario: Not directly in the public bucket on write
- **WHEN** an admin sends `PUT /v1/aliases/<user bucket>/pub-v1`, `PUT /v1/aliases/platform/pub-v1`, `PUT /v1/aliases/public/folder/pub-v1` or `PUT /v1/aliases/public/bad%20name`
- **THEN** the answers are `404`, `404`, `400` and `400`

### Requirement: Stored alias read back
`GET /v1/aliases/public/<name>` SHALL answer the stored fields with `status: valid`, or, for a blob that calls ignore,
the body as stored with `status: invalid` and its `validationWarnings`.

#### Scenario: Blob that calls ignore
- **WHEN** the blob `aliases/public/hand-v1` has no `target`
- **THEN** `GET /v1/aliases/public/hand-v1` answers `200` with `status: invalid` and a warning on `target.path`, a call of it answers `404`, and listings leave it out

### Requirement: Stored alias validated on write
A `PUT` of a stored alias SHALL answer `400` and write nothing when the body has a field other than those a stored
alias keeps, has a `name` other than the name in the path, has no `target.path` or a path of an unsupported form, targets an
alias, has an unknown `presentAs`, or has a type that does not fit a target
that exists (a config deployment or a resource). A target that does not exist SHALL be accepted.

#### Scenario: Invalid bodies
- **WHEN** an admin stores an alias with `userRoles` that is not a list of roles, with `endpoint`, with target `aliases/assistant`, with `presentAs: toolset` for a model target, with `presentAs: widget`, without `target`, or with `name` `other-v1`
- **THEN** every answer is `400` and `GET` of the alias answers `404`

#### Scenario: Resource target of the wrong kind
- **WHEN** an admin stores an alias with `presentAs: toolset` whose target is an application resource without `mcp`
- **THEN** the answer is `400`

### Requirement: Target of a stored alias
A stored alias SHALL target a config deployment or a resource in the `public` bucket. A `PUT` with a resource target in
another bucket SHALL answer `400`, and a stored blob with one SHALL be ignored by calls and listings.

#### Scenario: Target in a user bucket
- **WHEN** an admin stores an alias whose target is `applications/<user bucket>/app` or `toolsets/<user bucket>/tools`
- **THEN** the answer is `400`

### Requirement: Broken stored alias reported
Core SHALL accept a stored alias whose target does not exist, answer `503` to a call of it, leave it out of listings,
and report it in `GET /v1/admin/health/config` as skipped entry `aliases/public/<name>` with reason
`alias_target_missing` (or `alias_target_incompatible`) when its target is a config deployment.

#### Scenario: Missing target
- **WHEN** an admin stores `broken-v1` with target `models/missing`
- **THEN** the `PUT` answers `200`, a call of `aliases/public/broken-v1` answers `503`, `/openai/models` does not list it, and the health report names `aliases/public/broken-v1`, while the broken config alias `broken` keeps the id `aliases/broken`

### Requirement: Stored aliases are not shared, published, copied or moved
Core SHALL answer `400` to sharing an alias (`/v1/ops/resource/share/create`), to a publication that adds or deletes an
alias, and to copying or moving an alias (`/v1/ops/resource/copy`, `/v1/ops/resource/move`), and SHALL write nothing.

#### Scenario: Refused operations
- **WHEN** an admin shares `aliases/public/pub-v1`, publishes `aliases/<bucket>/pub-v2` to `aliases/public/pub-v2`, unpublishes `aliases/public/pub-v1`, or copies or moves `aliases/public/pub-v1` to `aliases/public/pub-v2`
- **THEN** every answer is `400`, `pub-v2` does not exist and `pub-v1` is unchanged

### Requirement: Manifests do not take config alias names
A manifest of `/v1/admin/validate` or `/v1/admin/apply` naming a model like an alias of the config file SHALL fail.

#### Scenario: Manifest named like a config alias
- **WHEN** the config file has alias `assistant` and an admin sends a `Model` manifest `models/platform/assistant` to `/v1/admin/validate` and to `/v1/admin/apply` with `precheck: false`
- **THEN** the entry is `FAILED` on both and no model `assistant` is written
