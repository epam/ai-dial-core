## MODIFIED Requirements

### Requirement: Alias in the config
Core SHALL read aliases from the top-level `aliases` config section, keyed by the alias's name. An alias SHALL
have a `target` with a `path` of the form `models/<name>`, `applications/<name>`, `toolsets/<name>` or
`routers/<name>` for a config deployment, or `applications/<bucket>/<path>` or `toolsets/<bucket>/<path>` for a
resource. It MAY have `presentAs` (`model`, `application` or `toolset`), `userRoles`, `defaultHeaders` and the
display fields of a deployment.

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
names when `presentAs` is not set. A `routers/` path names no type, so Core SHALL refuse such an alias without
`presentAs`. Core SHALL
refuse an alias whose target path starts with `aliases/`, and an alias whose type does not fit a config target:
`model` and `application` fit a model, an application or a router target, `toolset` fits a toolset target or an
application target that has `mcp`.

#### Scenario: Type of the target by default
- **WHEN** an alias without `presentAs` targets `models/gpt-4-internal`
- **THEN** `/openai/models` lists it with `object` `model`

#### Scenario: Alias to an alias
- **WHEN** an alias's target path is `aliases/other`
- **THEN** the config file load fails

#### Scenario: Toolset shown as a model
- **WHEN** an alias with `presentAs: model` targets `toolsets/search`
- **THEN** the config file load fails

#### Scenario: Router target without a type
- **WHEN** an alias without `presentAs` targets `routers/r1`
- **THEN** the config file load fails

#### Scenario: Router shown as a model
- **WHEN** an alias with `presentAs: model` targets `routers/r1`, which declares `openaiChatCompletions` and `anthropicMessages`
- **THEN** `/openai/models` and `/anthropic/v1/models` list the alias with `object` `model`, and neither lists `r1`

#### Scenario: Router shown as a toolset
- **WHEN** an alias with `presentAs: toolset` targets `routers/r1`
- **THEN** the config file load fails

### Requirement: Target of a stored alias
A stored alias of the public bucket SHALL target a config deployment other than a router, or a resource in the
`public` bucket. A `PUT` with a `routers/` target or a resource target in another bucket SHALL answer `400`, and a
stored blob with one SHALL be ignored by calls and listings and read back with `status: invalid`.

#### Scenario: Target in a user bucket
- **WHEN** an admin stores an alias whose target is `applications/<user bucket>/app` or `toolsets/<user bucket>/tools`
- **THEN** the answer is `400`

#### Scenario: Router target of a stored alias
- **WHEN** an admin stores an alias with `presentAs: model` whose target is `routers/r1` under `/v1/aliases/public/`
- **THEN** the answer is `400`; a blob with that target written around the API answers `404` to a call and
  `status: invalid` to `GET`

## ADDED Requirements

### Requirement: Alias to a router
A call of an alias whose target is a router SHALL be served as a call of that router by the same caller is, in the
target pass: the router decides in decision mode, or receives the request in the other modes. The alias SHALL grant
the caller the right to call the router only; a deployment the router chooses SHALL be checked against the caller's
own rights, as on a direct call of the router.

#### Scenario: Decision through an alias
- **WHEN** `strong` targets `routers/r1`, `r1` is in decision mode and answers `{"candidates": ["gpt-4"]}`, and a
  caller who may call `strong` and `gpt-4` but not `r1` sends a chat completion to `strong`
- **THEN** the answer is `200` with the answer of `gpt-4`, and the same caller gets `403` from `r1` directly

#### Scenario: Chosen deployment the caller may not call
- **WHEN** the same call is made by a caller who may call `strong` but not `gpt-4`, and `gpt-4` is the only candidate
- **THEN** the candidate is skipped with reason `forbidden`, as on a direct call of `r1`

#### Scenario: Messages through an alias
- **WHEN** a client sends `POST /anthropic/v1/messages` with `"model": "strong"` and `r1` answers
  `{"candidates": ["claude"]}`
- **THEN** `claude` receives the request and the client receives its answer

### Requirement: Alias over a router is not a candidate
An alias whose target is a router SHALL NOT be a routing candidate: Core SHALL answer it as a router named as a
candidate.

#### Scenario: Alias over a router as a candidate
- **WHEN** the router `r2` answers `{"candidates": ["strong"]}` and `strong` targets `routers/r1`
- **THEN** Core answers `502` and sends nothing to `strong` or `r1`

### Requirement: Default headers of an alias
For an alias of type `model` or `application`, the alias pass SHALL set each header of the alias's `defaultHeaders`
that the client did not send on the request it forwards to the target, as for a deployment's default headers. The
target pass SHALL treat them as headers the client sent.

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
