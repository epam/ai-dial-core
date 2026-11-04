# deployment-aliases Specification

## Purpose
Aliases: admin-managed deployments of their own, called by their short name, that forward calls to a model,
application or toolset and grant their callers the right to call it. Covers their definition and validation, the
call chain they record, listings and the health report.

## Requirements

### Requirement: Alias in the config
Core SHALL read aliases from the top-level `aliases` config section, keyed by the alias's short name. An alias SHALL
have a `target` with a `path` of the form `models/<name>`, `applications/<name>` or `toolsets/<name>` for a config
deployment, or `applications/<bucket>/<path>` or `toolsets/<bucket>/<path>` for a resource. It MAY have `presentAs`
(`model`, `application` or `toolset`), `userRoles` and the display fields of a deployment.

#### Scenario: Alias loaded
- **WHEN** the config has an alias `assistant` with target path `models/gpt-4-internal`
- **THEN** the config loads and `assistant` can be called

#### Scenario: Bad target refused
- **WHEN** an alias has no `target.path`, or a path that is not one of these forms
- **THEN** the config file load fails, as for a duplicate deployment name

#### Scenario: Reserved target fields refused
- **WHEN** an alias's target has `id` or `version`
- **THEN** the config file load fails

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
Core SHALL load a config whose alias names a config target that does not exist, log a warning, answer `503` to a
call of that alias, and report it in `GET /v1/admin/health/config` as a skipped entry with id
`aliases/<alias name>` and reason `alias_target_missing`, or `alias_target_incompatible` for a config target that no
longer fits the alias's type. A call of an alias whose resource target does not exist SHALL answer `503`.

#### Scenario: Target removed
- **WHEN** the target model of `broken` is not in the config
- **THEN** the config still loads, a call of `broken` answers `503`, and `/openai/models` does not list it

#### Scenario: Broken alias in the health report
- **WHEN** an admin reads `/v1/admin/health/config` while `broken` points to a missing model
- **THEN** the status is `degraded` and `skipped` has `aliases/broken` with reason `alias_target_missing`

### Requirement: Calling an alias
Core SHALL let a caller whose roles match the alias's `userRoles` call the alias, whatever the caller's rights on the
target, SHALL answer `403` to other callers, and SHALL forward a request of the endpoints in "Endpoints of an
alias" to the target and relay its answer. Calling through an alias SHALL NOT give the caller rights to read the
target.

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

### Requirement: Endpoints of an alias
An alias SHALL be called by its short name. An alias of type `model` or `application` SHALL be callable through the
deployments-POST endpoints, `/openai/v1/chat/completions`, `/openai/v1/responses` and `/anthropic/v1/messages`, and
through `/v1/deployments/<alias>/mcp` when the target has `mcp`. An alias of type `toolset` SHALL be callable through
`/v1/toolset/<alias>/mcp`.

#### Scenario: Responses and Messages
- **WHEN** a caller sends `/openai/v1/responses` or `/anthropic/v1/messages` with `model: assistant`
- **THEN** the target receives the request with `model` set to the target name

#### Scenario: Toolset alias over an MCP application
- **WHEN** an alias with `presentAs: toolset` targets an application with `mcp`, and a caller posts to `/v1/toolset/<alias>/mcp`
- **THEN** the request reaches the application's MCP endpoint, and a chat completion to the alias answers as one to the toolset does

### Requirement: Alias calls are recorded as a call chain
Core SHALL handle an alias call as two passes: the alias pass, with `execution_path` `[alias]`, and the target pass,
with `execution_path` `[alias, target]`. Each pass SHALL have its own token-stats span and analytics row. Limits and
cost SHALL be charged only by the target pass, under the target's name, to the user who started the request, on
every interface including MCP.

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

### Requirement: Aliases in listings
Core SHALL list an alias the caller may call, by its short name, with the deployments of its type in
`/openai/models`, `/openai/deployments`, `/anthropic/v1/models`, `/openai/applications`, `/openai/toolsets`,
`/v1/deployments` and `/v1/deployment-names`, and SHALL answer for the alias name on their single-item endpoints. An
alias whose config target is missing SHALL be left out of all of them; `/v1/deployment-names` does not read a
resource target.

#### Scenario: Broken alias not named
- **WHEN** a caller lists `/v1/deployment-names`
- **THEN** the list has `assistant` and not `broken`, whose target is missing

#### Scenario: Toolset entry
- **WHEN** an alias has `presentAs: toolset`
- **THEN** `/openai/toolsets` lists it and `/openai/models` does not

### Requirement: Listings answered from memory
`/openai/models`, `/openai/deployments`, `/anthropic/v1/models` and the model part of `/v1/deployments` SHALL leave
out an alias whose target is a resource; the other listings SHALL show it.

#### Scenario: Alias to a resource application
- **WHEN** an alias with `presentAs: application` targets an application resource the caller cannot read
- **THEN** `/openai/applications` lists the alias

### Requirement: Content of an alias entry
A listing entry of an alias SHALL carry the alias's name and display fields and the target's features, pricing,
limits, defaults and interfaces, and SHALL NOT carry the target's path or endpoint. The display name and the
description SHALL be the target's when the alias does not set them; no other field SHALL fall back to the target's.

#### Scenario: Model entry
- **WHEN** a caller with `default-user` lists `/openai/models`
- **THEN** the list has `assistant` with `display_name` `Assistant` and the pricing of `gpt-4-internal`, and not `gpt-4-internal`

#### Scenario: Display fields of the target
- **WHEN** an alias sets neither `displayName` nor `description`, and its target model has both
- **THEN** `/openai/models` shows the alias with the model's display name and description

#### Scenario: Application shown as a model
- **WHEN** an alias with `presentAs: model` targets an application
- **THEN** `/openai/models/<alias>` answers `200` with `object` `model`

### Requirement: Aliases of the config file
`GET /v1/admin/config/file/aliases` SHALL list the names of the aliases in the config file and
`GET /v1/admin/config/file/aliases/<name>` SHALL answer the alias, for admins only.

#### Scenario: File aliases listed
- **WHEN** an admin reads `/v1/admin/config/file/aliases`
- **THEN** the items have `assistant`, and a caller who is not an admin gets `403`
