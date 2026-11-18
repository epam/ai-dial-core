## MODIFIED Requirements

### Requirement: Alias in public storage
Core SHALL serve aliases stored directly under `aliases/public/<name>` and SHALL call them by that url, as public
applications are called: the url is the alias's deployment name in every endpoint, the execution path and listings.
The bare name `<name>` SHALL NOT reach a stored alias. A stored alias SHALL keep only `presentAs`, `target`,
`userRoles`, `defaultHeaders` (none on an alias of type `toolset`) and the display fields.

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

### Requirement: Managing a stored alias
Core SHALL create or replace a stored alias on `PUT /v1/aliases/public/<name>` (honouring `If-Match` and
`If-None-Match`), answer it on `GET`, delete it on `DELETE`, and list the stored aliases on
`GET /v1/metadata/aliases/public/`, for admins only: every method SHALL answer `403` to other callers. A user bucket
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
- **WHEN** an admin sends `PUT /v1/aliases/<user bucket>/pub-v1`, `PUT /v1/aliases/public/folder/pub-v1` or `PUT /v1/aliases/public/bad%20name`
- **THEN** the answers are `404`, `400` and `400`

### Requirement: Stored alias validated on write
A `PUT` of a stored alias SHALL answer `400` and write nothing when the body has a field other than those a stored
alias keeps, has a `name` other than the name in the path, has no `target.path` or a path of an unsupported form, targets an
alias, has an unknown `presentAs`, has non-empty `defaultHeaders` on an alias of type `toolset`, or has a type that
does not fit a target that exists (a config deployment or a resource). A target that does not exist SHALL be accepted.

#### Scenario: Invalid bodies
- **WHEN** an admin stores an alias with `userRoles` that is not a list of roles, with `defaultHeaders: ["X-Team"]`, with `endpoint`, with target `aliases/assistant`, with `presentAs: toolset` for a model target, with `presentAs: widget`, without `target`, or with `name` `other-v1`
- **THEN** every answer is `400` and `GET` of the alias answers `404`

#### Scenario: Resource target of the wrong kind
- **WHEN** an admin stores an alias with `presentAs: toolset` whose target is an application resource without `mcp`
- **THEN** the answer is `400`

#### Scenario: Default headers on a toolset alias
- **WHEN** an admin stores an alias with target `toolsets/search` and `defaultHeaders: {"X-Team": "a"}`, and then the
  same alias with `defaultHeaders: {}`
- **THEN** the first answers `400` and writes nothing, and the second answers `200` and `GET` of the alias has no
  `defaultHeaders`

### Requirement: Default headers of an alias
For an alias of type `model`, `application` or `router`, of the config file or stored through `/v1/aliases/`, the
alias pass SHALL set each header of the alias's `defaultHeaders` that the client did not send on the request it
forwards to the target, as for a deployment's default headers. The target pass SHALL treat them as headers the client
sent.

#### Scenario: Header reaches the decide request
- **WHEN** `strong` targets `routers/r1` and has `defaultHeaders: {"X-DIAL-ROUTER-TIER": "strong"}`, and a client
  calls `strong` without that header
- **THEN** the decide request `r1` receives has `X-DIAL-ROUTER-TIER: strong`

#### Scenario: Client value kept
- **WHEN** a client calls the same alias with `X-DIAL-ROUTER-TIER: hi-end`
- **THEN** the decide request has `X-DIAL-ROUTER-TIER: hi-end` and no other value of that header

#### Scenario: Header reaches a model target
- **WHEN** `assistant` targets `models/gpt-4-internal` and has `defaultHeaders: {"X-Team": "a"}`, and a client calls
  `assistant` without that header
- **THEN** the upstream of `gpt-4-internal` receives `X-Team: a`

#### Scenario: Direct call unaffected
- **WHEN** a client who may call `r1` calls it directly with `X-DIAL-ROUTER-TIER: hi-end`
- **THEN** the decide request has `X-DIAL-ROUTER-TIER: hi-end`

#### Scenario: Header of a router-type alias
- **WHEN** the alias `tier` without `presentAs` targets `routers/r1` and has
  `defaultHeaders: {"X-DIAL-ROUTER-TIER": "tier"}`, and a client calls `tier` without that header
- **THEN** the decide request `r1` receives has `X-DIAL-ROUTER-TIER: tier`

#### Scenario: Header of a public stored alias
- **WHEN** an admin stores `team-v1` under `/v1/aliases/public/` with target `models/gpt-4-internal` and
  `defaultHeaders: {"X-Team": "b"}`, and a client calls `aliases/public/team-v1` without that header
- **THEN** `GET /v1/aliases/public/team-v1` answers `defaultHeaders: {"X-Team": "b"}`, and the upstream of
  `gpt-4-internal` receives `X-Team: b`

#### Scenario: Header of a platform alias
- **WHEN** an admin stores `plt-team` under `/v1/aliases/platform/` with target `models/gpt-4-internal` and
  `defaultHeaders: {"X-Team": "b"}`, the config is reloaded, and a client calls `plt-team` without that header
- **THEN** `GET /v1/aliases/platform/plt-team` answers `defaultHeaders: {"X-Team": "b"}`, and the upstream of
  `gpt-4-internal` receives `X-Team: b`

### Requirement: Content of an alias entry
A listing entry of an alias SHALL carry the alias's name and display fields and the target's features, pricing,
limits, defaults, interfaces, provider and lifecycle dates (`provider`, `deprecation_date`, `end_of_service`), and
SHALL NOT carry the target's path or endpoint, nor any default headers. The display name, description, icon, intro
and keywords SHALL fall back to the target's when the alias does not set them; the display version, author, dates
and catalog fields SHALL NOT.

#### Scenario: Model entry
- **WHEN** a caller with `default-user` lists `/openai/models`
- **THEN** the list has `assistant` with `display_name` `Assistant` and the pricing of `gpt-4-internal`, and not `gpt-4-internal`

#### Scenario: Display fields of the target
- **WHEN** an alias sets none of `displayName`, `description`, `iconUrl` and `displayVersion`, and its target model has all of them
- **THEN** `/openai/models` shows the alias with the model's display name, description and icon, and without a display version

#### Scenario: Application shown as a model
- **WHEN** an alias with `presentAs: model` targets an application
- **THEN** `/openai/models/<alias>` answers `200` with `object` `model`

#### Scenario: Provider and lifecycle dates of the target
- **WHEN** an alias targets a model with `provider` `Azure`, `deprecationDate` `2027-06-30` and `endOfService`
  `2027-12-31`
- **THEN** `/openai/models` shows the alias with the same `provider`, `deprecation_date` and `end_of_service`

#### Scenario: Stored alias with default headers listed
- **WHEN** `team-v1` is stored under `/v1/aliases/public/` with `defaultHeaders: {"X-Team": "b"}`, and a caller who
  may use it reads `/openai/models/aliases/public/team-v1`
- **THEN** the answer is `200`, every `interface_configs` entry has empty `default_headers`, and the body has no
  `X-Team`
