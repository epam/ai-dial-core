## MODIFIED Requirements

### Requirement: Alias in the config
Core SHALL read aliases from the top-level `aliases` config section, keyed by the alias's name. An alias SHALL
have a `target` with a `path` of the form `models/<name>`, `applications/<name>`, `toolsets/<name>` or
`routers/<name>` for a config deployment, or `applications/<bucket>/<path>` or `toolsets/<bucket>/<path>` for a
resource. It MAY have `presentAs` (`model`, `application`, `toolset` or `router`), `userRoles`, `defaultHeaders` and
the display fields of a deployment.

#### Scenario: Alias loaded
- **WHEN** the config has an alias `assistant` with target path `models/gpt-4-internal`
- **THEN** the config loads and `assistant` can be called

#### Scenario: Bad target refused
- **WHEN** an alias has no `target.path`, or a path that is not one of these forms
- **THEN** the config file load fails, as for a duplicate deployment name

#### Scenario: Router target loaded
- **WHEN** the config has a router `r1` and an alias `strong` with `presentAs: model` and target path `routers/r1`
- **THEN** the config loads and `strong` can be called

### Requirement: Alias type
The type an alias is listed and called as SHALL be its `presentAs`, or the type the first segment of its target path
names when `presentAs` is not set, `router` for `routers/`. Core SHALL refuse an alias whose target path starts with
`aliases/`, and an alias whose type does not fit a config target: `model` and `application` fit a model, an
application or a router target, `toolset` fits a toolset target or an application target that has `mcp`, `router`
fits a router target.

#### Scenario: Type of the target by default
- **WHEN** an alias without `presentAs` targets `models/gpt-4-internal`
- **THEN** `/openai/models` lists it with `object` `model`

#### Scenario: Router type by default
- **WHEN** an alias `tier` without `presentAs` targets `routers/r1`
- **THEN** the config loads, `/v1/routers` lists `tier` and `/openai/models` does not

#### Scenario: Alias to an alias
- **WHEN** an alias's target path is `aliases/other`
- **THEN** the config file load fails

#### Scenario: Toolset shown as a model
- **WHEN** an alias with `presentAs: model` targets `toolsets/search`
- **THEN** the config file load fails

#### Scenario: Router shown as a model
- **WHEN** an alias with `presentAs: model` targets `routers/r1`, which declares `openaiChatCompletions` and `anthropicMessages`
- **THEN** `/openai/models` and `/anthropic/v1/models` list the alias with `object` `model`, and neither lists `r1`

#### Scenario: Router shown as a toolset
- **WHEN** an alias with `presentAs: toolset` targets `routers/r1`
- **THEN** the config file load fails

### Requirement: Aliases in listings
Core SHALL list an alias the caller may call with the deployments of its type in `/openai/models`,
`/openai/deployments`, `/anthropic/v1/models`, `/openai/applications`, `/openai/toolsets`, `/v1/routers`,
`/v1/deployments` and `/v1/deployment-names`, a config alias by its name and a stored alias by its url, and SHALL
answer for that name on their single-item endpoints. An alias whose target is missing SHALL be left out of all of
them.

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

#### Scenario: Router entry
- **WHEN** the alias `tier` of type `router` targets `routers/r1`, which declares `openaiChatCompletions`, and a
  caller who may use `tier` lists the deployments
- **THEN** `/v1/routers` and `/v1/deployments` hold an entry with `id: "tier"` and `object: "router"`,
  `/v1/deployment-names?type=router` holds `{"id": "tier", "object": "router"}`, `/v1/routers/tier` and
  `/v1/deployments/tier` answer `200` with `object: "router"`, and `/openai/models`, `/openai/deployments` and
  `/anthropic/v1/models` do not list `tier`

#### Scenario: Router entry by interface
- **WHEN** the target of `tier` declares `openaiChatCompletions` and not `openaiEmbeddings`, and a caller sends
  `GET /v1/deployments?interface_type=chat`, then `?interface_type=embedding`, then `?interface_type=mcp`
- **THEN** the first answer holds `tier` and the other two do not

#### Scenario: Model-type alias over a router not a router entry
- **WHEN** the alias `strong` with `presentAs: model` targets `routers/r1`
- **THEN** `/v1/routers` does not list `strong`, `/v1/routers/strong` answers `404` and
  `/v1/deployment-names?type=router` does not name it

### Requirement: Endpoints of an alias
An alias SHALL be called by its name. An alias of type `model`, `application` or `router` SHALL be callable through
the deployments-POST endpoints, `/openai/v1/chat/completions`, `/openai/v1/responses` and `/anthropic/v1/messages`,
and through `/v1/deployments/<alias>/mcp` when the target has `mcp`. An alias of type `toolset` SHALL be callable
through `/v1/toolset/<alias>/mcp`.

#### Scenario: Responses and Messages
- **WHEN** a caller sends `/openai/v1/responses` or `/anthropic/v1/messages` with `model: assistant`
- **THEN** the target receives the request with `model` set to the target name

#### Scenario: Toolset alias over an MCP application
- **WHEN** an alias with `presentAs: toolset` targets an application with `mcp`, and a caller posts to `/v1/toolset/<alias>/mcp`
- **THEN** the request reaches the application's MCP endpoint, and a chat completion to the alias answers as one to the toolset does

### Requirement: Features, stored responses and consent through an alias
For an alias of type `model`, `application` or `router`, Core SHALL serve `tokenize`, `truncate_prompt`,
`configuration` and `rate` by the target in a target pass, and SHALL serve `GET`, `DELETE` and `cancel` of a response
stored through the alias by the alias. Consent to the alias SHALL cover its target: the review SHALL list both, and
the accepted consent SHALL let the caller call the alias without rights on the target.

#### Scenario: Stored response
- **WHEN** a caller stores a response through `multi-alias` and reads `/openai/v1/responses/<id>`
- **THEN** the answer is `200` with that id

#### Scenario: Tokenize and rate
- **WHEN** a caller posts to `/v1/deployments/<alias>/tokenize` and `/v1/<alias>/rate` of an alias whose target has these endpoints
- **THEN** both answer `200` and the target's endpoints receive the requests

#### Scenario: Consent
- **WHEN** the target of `consent-alias` requires consent, and the caller accepts the consent `GET /v1/consent/consent-alias` reviews
- **THEN** the review lists the alias and the target as requiring consent, a call before accepting answers `403`, and a call after answers `200`

### Requirement: Default headers of an alias
For an alias of type `model`, `application` or `router`, the alias pass SHALL set each header of the alias's
`defaultHeaders` that the client did not send on the request it forwards to the target, as for a deployment's default
headers. The target pass SHALL treat them as headers the client sent.

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

## ADDED Requirements

### Requirement: Alias of type router
An alias of type `router` SHALL target `routers/<name>`: Core SHALL refuse one with any other target path, whether or
not that target exists, a resource included, on the config load and on a stored alias's `PUT`.

#### Scenario: Router type over a model
- **WHEN** an alias with `presentAs: router` targets `models/gpt-4-internal`, or `models/missing`
- **THEN** the config file load fails

#### Scenario: Router type over a resource
- **WHEN** an admin stores an alias with `presentAs: router` whose target is `applications/public/app`
- **THEN** the answer is `400`

#### Scenario: Router type called
- **WHEN** the alias `tier` of type `router` targets `routers/r1` in decision mode, `r1` answers
  `{"candidates": ["gpt-4"]}` and a client sends a chat completion to `tier`
- **THEN** `gpt-4` serves it and the client's answer has `"model": "tier"`

## REMOVED Requirements

### Requirement: Router target needs a type
**Reason**: A `routers/` target path now names the type `router`, as the other config target paths name theirs.
**Migration**: None. A config that loads keeps loading; an alias without `presentAs` over a router, refused until
now, loads with the type `router`. Add `presentAs: model` to list it as a model instead.
