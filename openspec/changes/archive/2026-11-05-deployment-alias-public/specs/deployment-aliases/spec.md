## ADDED Requirements

### Requirement: Listings without storage
When storage cannot list or read the stored aliases, a listing SHALL answer without them rather than fail.

#### Scenario: Storage fails
- **WHEN** listing `aliases/public/` fails while a caller lists `/openai/models`
- **THEN** the answer is `200` with the config models and config aliases


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

## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Listings answered from memory
**Reason**: the model listings now list and read the stored aliases on the task executor, and build every alias view
there, so they also show aliases to resource targets ("Aliases in listings").
**Migration**: none; such aliases now appear in `/openai/models`, `/openai/deployments`, `/anthropic/v1/models` and the
model part of `/v1/deployments`.
