# deployment-interfaces-config Specification

## Purpose
How a deployment (model, application, interceptor) in the dynamic config declares what serves each interface type:
the pre-`interfaces` fields `endpoint` / `responsesEndpoint`, the deployment-level `baseUrl`, and the
`interfaces.<type>` entries with their `mode`, translator, `defaults`, `defaultHeaders`, `features` and
`overridePaths`. It covers what the listing APIs advertise against what the request path serves, and the config
validation that rejects contradicting interface and translator declarations. The origin URI and headers Core sends
are the `deployment-origin` capability; limits and accounting of a translated request are the
`app-to-model-accounting` capability.

## Requirements

### Requirement: Interface keys
Core SHALL read `interfaces` as a map keyed by interface type: `openaiChatCompletions` (deployments-POST
`chat/completions` and `completions`, `/openai/v1/chat/completions`), `openaiEmbeddings` (`embeddings`),
`openaiResponses` (`/openai/v1/responses`, `/openai/responses`, item operations) and `anthropicMessages`
(`/anthropic/v1/messages` and its `count_tokens`). A `null` entry SHALL read as absent; an entry under an unknown
key SHALL be kept and never routed or listed.

#### Scenario: Null entry reads as absent
- **WHEN** a model declares `baseUrl: http://adapter` and `interfaces: {"openaiChatCompletions": {}, "openaiResponses": null}`
  and a client sends `POST /openai/v1/responses` with `{"model": "<model>"}`
- **THEN** Core answers `503` and sends nothing to `http://adapter`

#### Scenario: Unknown key kept
- **WHEN** a model declares `interfaces` with `openaiChatCompletions`, `openaiResponses` and `geminiInteractions`,
  each with a `base_url`
- **THEN** the config loads, the model keeps all three entries, and no listing advertises `geminiInteractions`

### Requirement: Pass-through base url
For an `interfaces` entry with `mode` absent or `passthrough`, Core SHALL serve the interface from the entry's
`base_url` (alias `baseUrl`), else from the deployment-level `baseUrl` (alias `base_url`), with one trailing slash
stripped. A deployment-level `baseUrl` SHALL serve only the interface types the `interfaces` map declares.

#### Scenario: Deployment-level base url completes an entry
- **WHEN** a model `m` declares `baseUrl: http://adapter/` and `interfaces: {"openaiChatCompletions": {}}` and a client
  sends `POST /openai/deployments/m/chat/completions`
- **THEN** the origin receives `POST http://adapter/openai/deployments/m/chat/completions`

#### Scenario: Entry base url overrides the deployment one
- **WHEN** the same model declares `interfaces.openaiChatCompletions.base_url: http://chat-adapter`
- **THEN** the origin receives the request at `http://chat-adapter/openai/deployments/m/chat/completions`

#### Scenario: Deployment-level base url alone serves nothing
- **WHEN** a model declares `baseUrl: http://adapter` and `interfaces: {"openaiChatCompletions": {}}` and a client sends
  `POST /anthropic/v1/messages` for it
- **THEN** Core answers `503`

### Requirement: Legacy endpoint fields
Core SHALL serve a type that has no `interfaces` entry, or a pass-through entry that resolves to no url, from the
pre-`interfaces` field (a `translator`-mode entry never falls back, see Interface mode): `endpoint`
for the whole deployments-POST family (`chat/completions`, `completions`, `embeddings`) and `responsesEndpoint` for the
Responses API, each used as the complete URL. No legacy field SHALL serve `anthropicMessages`.

#### Scenario: Endpoint serves embeddings next to a chat interface
- **WHEN** a model declares `endpoint: http://host/openai/deployments/ada/embeddings` and
  `interfaces.openaiChatCompletions.base_url: http://chat-adapter`, and a client sends
  `POST /openai/deployments/<model>/embeddings`
- **THEN** the origin receives `POST http://host/openai/deployments/ada/embeddings`

#### Scenario: Interface wins over the legacy field
- **WHEN** a model declares `endpoint: http://legacy/chat` and `interfaces.openaiChatCompletions.base_url: http://adapter`
- **THEN** a chat completion for it is sent under `http://adapter`, not to `http://legacy/chat`

#### Scenario: Endpoint does not serve Responses
- **WHEN** a model declares only `endpoint` and a client sends `POST /openai/v1/responses` for it
- **THEN** Core answers `503`

### Requirement: Typed interfaces do not stand in for each other
Core SHALL NOT serve embeddings from `interfaces.openaiChatCompletions` nor chat completions from
`interfaces.openaiEmbeddings`, except for an interceptor: Core sends every request to an interceptor, embeddings
included, under its `openaiChatCompletions` interface.

#### Scenario: Chat interface does not serve embeddings
- **WHEN** a model declares only `interfaces.openaiChatCompletions.base_url: http://chat-adapter` and no `endpoint`,
  and a client sends `POST /openai/deployments/<model>/embeddings`
- **THEN** Core answers `503`

### Requirement: Interface mode
Core SHALL read `interfaces.<type>.mode` as `passthrough` (the default when absent) or `translator`, and no other
value. An entry in `translator` mode SHALL be served by its translator only, never by a `base_url`, the
deployment-level `baseUrl`, `endpoint` or `responsesEndpoint`, and a pass-through entry whose base url is a plugin url
by that plugin only (deployment-origin), never by `endpoint` or `responsesEndpoint`.

#### Scenario: Translated interface goes to the translator
- **WHEN** a model declares `baseUrl: http://adapter` and `interfaces.anthropicMessages: {"mode": "translator",
  "translator": {"out": "openaiChatCompletions", "baseUrl": "http://translator/to-chat"}}` and
  `interfaces.openaiChatCompletions: {}`, and a client sends `POST /anthropic/v1/messages` for it
- **THEN** the request is sent to `POST http://translator/to-chat/anthropic/v1/messages`

#### Scenario: Translated interface never falls back
- **WHEN** a model declares `endpoint: http://legacy/chat` and
  `interfaces.openaiChatCompletions: {"mode": "translator", "translator": "missing"}` and no `translators.missing`
- **THEN** a chat completion for it is answered `503` and nothing is sent to `http://legacy/chat`

#### Scenario: Embedded interface goes to the plugin
- **WHEN** an application declares `baseUrl: http://app` and
  `interfaces.openaiChatCompletions: {"base_url": "plugin://p1"}`, `plugins.p1` is of type `test-origin`, and a
  client sends `POST /openai/deployments/<application>/chat/completions`
- **THEN** the `test-origin` plugin answers the request and nothing is sent to `http://app`

#### Scenario: Embedded interface never falls back
- **WHEN** a model declares `endpoint: http://legacy/chat` and
  `interfaces.openaiChatCompletions: {"base_url": "plugin://missing"}` and no `plugins.missing`
- **THEN** a chat completion for it is answered `502` and nothing is sent to `http://legacy/chat`

#### Scenario: Explicit pass-through mode with a plugin
- **WHEN** an application declares `interfaces.openaiChatCompletions: {"mode": "passthrough", "base_url": "plugin://p1"}`
- **THEN** the `test-origin` plugin answers a chat completion for it

#### Scenario: Embedded is not a mode
- **WHEN** an admin sends `PUT /v1/applications/platform/a` with `interfaces.openaiChatCompletions:
  {"mode": "embedded", "base_url": "plugin://p1"}`
- **THEN** Core answers `400` and stores nothing

### Requirement: Translator reference
Core SHALL read `interfaces.<type>.translator` as either a string naming a `translators` entry or an inline object
`{"out", "baseUrl"}`, its `baseUrl` an HTTP url or a plugin url, whose `in` is the interface it sits under. A name
SHALL be resolved against the `translators` of the live config on each request, so an edit to that entry takes
effect with no edit to the deployment.

#### Scenario: Named translator resolved per request
- **WHEN** a model's `interfaces.anthropicMessages` is `{"mode": "translator", "translator": "a2c"}` and
  `translators.a2c.baseUrl` changes from `http://t1` to `http://t2` in a reloaded config
- **THEN** the next `POST /anthropic/v1/messages` for it is sent to `http://t2/anthropic/v1/messages`

#### Scenario: Name kept as a name
- **WHEN** a config with that model is read and written back
- **THEN** `interfaces.anthropicMessages.translator` is still the string `"a2c"`

### Requirement: Translator definition
Core SHALL read a translator (in `translators.<name>` or inline) with `in`, `out` and `baseUrl` (alias `base_url`),
an HTTP url or a plugin url. Core SHALL reject as the config is read one with no `out`, with no non-empty `baseUrl`, with a field a translator does not have, or with an `in`/`out` naming an interface type
Core does not know, and SHALL reject a `translators` entry declaring no `in`. A plugin url naming no `plugins` entry
SHALL NOT make the config invalid.

#### Scenario: Missing baseUrl rejected on read
- **WHEN** a config declares `translators.a2c: {"in": "anthropicMessages", "out": "openaiChatCompletions"}`
- **THEN** the config fails to read and is not applied

#### Scenario: Both baseUrl and plugin rejected on read
- **WHEN** a config declares `translators.a2c: {"in": "anthropicMessages", "out": "openaiChatCompletions",
  "baseUrl": "http://t", "plugin": "p1"}`
- **THEN** the config fails to read and is not applied

#### Scenario: Translator with a plugin read and written back
- **WHEN** an admin sends `PUT /v1/translators/platform/a2c` with `{"in": "anthropicMessages",
  "out": "openaiChatCompletions", "baseUrl": "plugin://later"}` and no `plugins.later` exists
- **THEN** Core answers `200`, and `GET /v1/translators/platform/a2c` returns `baseUrl: plugin://later`

#### Scenario: Unknown interface type rejected on read
- **WHEN** a config declares `translators.g2r: {"in": "geminiInteractions", "out": "openaiResponses", "baseUrl": "http://t"}`
- **THEN** the config fails to read and is not applied

#### Scenario: Registry entry without in rejected
- **WHEN** a config declares `translators.a2c: {"out": "openaiChatCompletions", "baseUrl": "http://t"}`
- **THEN** the config is rejected with a message containing `Translator 'a2c': Translator declares no in`

### Requirement: Nothing serves the interface
Core SHALL answer `503` to a request whose deployment has nothing serving the requested interface: no `interfaces`
entry resolving to a url (a translator name with no `translators` entry resolves to none) or to a plugin (the
entry's own, or its translator's), and no legacy field serving the type. This SHALL hold for deployments-POST,
`/openai/v1/chat/completions`, `/openai/v1/responses` and its item operations, and `/anthropic/v1/messages`.

#### Scenario: Unregistered translator name
- **WHEN** a model declares `interfaces.anthropicMessages: {"mode": "translator", "translator": "not-registered-yet"}`
  and no such `translators` entry, and a client sends `POST /anthropic/v1/messages` for it
- **THEN** Core answers `503` and sends no request

#### Scenario: Model with only a legacy chat endpoint
- **WHEN** a model declares only `endpoint: http://localhost:4848/v1/chat/completions` and a client sends
  `POST /anthropic/v1/messages` for it
- **THEN** Core answers `503`

#### Scenario: Registering the translator serves it
- **WHEN** a `translators.not-registered-yet` entry is then added to the config
- **THEN** the same request is sent to that translator's `baseUrl` with no edit to the model

### Requirement: Override paths
Core SHALL replace the ingress path under a pass-through base url with `interfaces.<type>.overridePaths.<key>`
(alias `override_paths`), keyed `postAzureOpenaiChatCompletions`, `postAzureOpenaiEmbeddings`, `postOpenaiResponses`,
`getOpenaiResponsesById`, `deleteOpenaiResponsesById`, `postOpenaiResponsesCancel`, `postAnthropicMessages`,
`postAnthropicMessagesCountTokens`, substituting only `{overrideName}` and `{id}`.

#### Scenario: Override path with tokens
- **WHEN** a model `foo-bar` with `overrideName: tst-name` declares
  `interfaces.openaiChatCompletions: {"base_url": "http://switchyard", "overridePaths":
  {"postAzureOpenaiChatCompletions": "/openai/deployments/{overrideName}/v1/{id}/chat"}}` and a client sends
  `POST /openai/deployments/foo-bar/chat/completions?api-version=1`
- **THEN** the origin receives `POST http://switchyard/openai/deployments/tst-name/v1/foo-bar/chat?api-version=1`

#### Scenario: Override name token falls back to the name
- **WHEN** a model `foo-bar` without `overrideName` declares
  `overridePaths.postAzureOpenaiEmbeddings: /openai/deployments/{overrideName}/v1/embeddings` under
  `interfaces.openaiEmbeddings` with `base_url: http://switchyard`
- **THEN** an embeddings request is sent to `http://switchyard/openai/deployments/foo-bar/v1/embeddings`

#### Scenario: Legacy completions action not overridden
- **WHEN** the model declares only `overridePaths.postAzureOpenaiChatCompletions: /v1/chat/completions` and a client
  sends `POST /openai/deployments/foo-bar/completions`
- **THEN** the origin receives `POST http://switchyard/openai/deployments/foo-bar/completions`

#### Scenario: Chat override not applied to the unified chat endpoint
- **WHEN** the same model is called with `POST /openai/v1/chat/completions`
- **THEN** the origin receives `POST http://switchyard/openai/v1/chat/completions`

#### Scenario: Not applied to the legacy endpoint
- **WHEN** chat completions is served by `endpoint: http://legacy/chat/completions` and only
  `interfaces.anthropicMessages` declares `overridePaths`
- **THEN** a chat completion is sent to `http://legacy/chat/completions`

### Requirement: Features per interface
Core SHALL resolve the features in force for an interface field by field: a non-null field of
`interfaces.<type>.features` overrides the deployment-level `features`, an explicit `false` or an empty list
included; an absent or null field inherits. The resolved set SHALL be the one sent in `X-DIAL-DEPLOYMENT-FEATURES`
and listed under `interface_configs.<type>.features`, but for `chat_completion`, `responses_api` and
`accessible_by_per_request_key` (see the scenarios).

#### Scenario: Interface overrides reasoning efforts
- **WHEN** a model declares `features.reasoning_efforts: ["low","medium"]` and
  `interfaces.anthropicMessages.features.reasoning_efforts: ["low","medium","high","xhigh","max"]`
- **THEN** `GET /v1/deployments` lists `interface_configs.anthropicMessages.features.reasoning_efforts` as
  `["low","medium","high","xhigh","max"]` and the model's own `features.reasoning_efforts` as `["low","medium"]`

#### Scenario: API flags differ between the header and interface_configs
- **WHEN** a model declares `openaiChatCompletions` and `anthropicMessages`, and a client sends
  `POST /anthropic/v1/messages` for it
- **THEN** the origin's `X-DIAL-DEPLOYMENT-FEATURES` has `chat_completion: true`, saying the deployment serves that
  API, while `interface_configs.anthropicMessages.features.chat_completion` is `false`

#### Scenario: accessible_by_per_request_key follows consentRequired
- **WHEN** a model declares `features.accessibleByPerRequestKey: false` and no `consentRequired`
- **THEN** its `X-DIAL-DEPLOYMENT-FEATURES` and `interface_configs.<type>.features` carry
  `accessible_by_per_request_key: true`, and with `consentRequired: true` they carry `false`

### Requirement: Default headers per interface
Core SHALL put in force for an interface the deployment-level `defaultHeaders` (alias `default_headers`) with
`interfaces.<type>.defaultHeaders` laid over them, names compared case-insensitively, and add each to the request
under every name the request does not already carry.

#### Scenario: Interface header overrides and extends
- **WHEN** a model declares `defaultHeaders: {"x-dial-cache-policy": "cache-priority", "x-dial-custom-header": "foo-bar"}`
  and `interfaces.anthropicMessages.defaultHeaders: {"x-dial-custom-header": "foo-bar-2", "x-dial-custom-header-2": "some-value"}`
- **THEN** `interface_configs.anthropicMessages.default_headers` lists `x-dial-cache-policy: cache-priority`,
  `x-dial-custom-header: foo-bar-2`, `x-dial-custom-header-2: some-value`, and
  `interface_configs.openaiChatCompletions.default_headers` lists `x-dial-custom-header: foo-bar`

#### Scenario: Client header wins
- **WHEN** a client sends `x-dial-custom-header: mine` to that model
- **THEN** the origin request carries `x-dial-custom-header: mine`

### Requirement: Defaults per interface
Core SHALL put in force for an interface `interfaces.<type>.defaults` when the entry declares any, replacing rather
than merging with the deployment-level set; otherwise `defaults` for `openaiChatCompletions` and `openaiEmbeddings`,
`responsesDefaults` for `openaiResponses`, and none for `anthropicMessages`. A key the request body carries SHALL
be kept.

#### Scenario: Interface defaults replace the model-level set
- **WHEN** a model declares `defaults: {"temperature": 1, "custom_fields": {"configuration": "foo.baz"}}` and
  `interfaces.openaiChatCompletions.defaults: {"temperature": 0.5}`, and a client sends
  `POST /openai/deployments/<model>/chat/completions` without `temperature`
- **THEN** the origin body carries `"temperature": 0.5` and no `custom_fields`

#### Scenario: Model-level defaults do not reach Responses
- **WHEN** the same model declares no `responsesDefaults` and `interfaces.openaiResponses: {}`
- **THEN** `interface_configs.openaiResponses.defaults` is `{}` and a Responses request gets no `temperature` added

### Requirement: Advertised interface types
Core SHALL advertise an interface type for a deployment when its `interfaces` entry is in `translator` mode with a
`translator` reference, or is pass-through with a resolving base url, a plugin url included,
or else when a legacy field serves it: `endpoint` declares `openaiEmbeddings` on a `type: embedding` model and
`openaiChatCompletions` on anything else; `responsesEndpoint` declares `openaiResponses`.

#### Scenario: Interfaces array in the listing
- **WHEN** `GET /v1/deployments` lists a `type: embedding` model with only `endpoint` and a `type: chat` model with
  only `endpoint`
- **THEN** their `interfaces` arrays are `["embedding", "openaiEmbeddings"]` and `["chat", "openaiChatCompletions"]`

#### Scenario: Interface configs per advertised type
- **WHEN** a model declares `baseUrl` and `interfaces` `openaiChatCompletions`, `openaiResponses` and `anthropicMessages`
- **THEN** its listing in `GET /v1/deployments`, `/openai/models`, `/openai/deployments`, `/v1/deployments/{id}` and
  `/openai/deployments/{id}` carries `interface_configs` with exactly those three keys, each with `features`,
  `defaults` and `default_headers`; `interface_configs.openaiChatCompletions.features.chat_completion` is `true` and
  `responses_api` `false`

#### Scenario: Deployment-level API flags
- **WHEN** a model declares only `interfaces.anthropicMessages` with a `base_url`
- **THEN** its listed `features.chat_completion` is `false`

#### Scenario: No typed interface omits the field
- **WHEN** an application declares no `endpoint`, `responsesEndpoint` or `interfaces`
- **THEN** its listing has no `interface_configs` field

#### Scenario: Embedded interface advertised
- **WHEN** an application declares only `interfaces.openaiChatCompletions: {"base_url": "plugin://p1"}`
- **THEN** its listing in `GET /v1/deployments` carries `interface_configs.openaiChatCompletions`, whether or not a
  `plugins.p1` entry exists

### Requirement: Advertised is not served
Core SHALL advertise a `translator`-mode interface whose `translator` name has no `translators` entry, and SHALL
answer `503` to a request for it.

#### Scenario: Listed but unserved
- **WHEN** a model declares `interfaces.anthropicMessages: {"mode": "translator", "translator": "missing"}` and no
  `translators.missing`
- **THEN** `GET /v1/deployments` lists `anthropicMessages` in its `interfaces` and `interface_configs`, and
  `POST /anthropic/v1/messages` for it is answered `503`

### Requirement: Anthropic model listing
`GET /anthropic/v1/models` SHALL list only the models the caller can access that advertise `anthropicMessages`, and
`GET /anthropic/v1/models/{model_name}` SHALL answer `404` for a model that does not advertise it.

#### Scenario: Legacy-only model hidden
- **WHEN** a model declares only `endpoint` and a client sends `GET /anthropic/v1/models/<model>`
- **THEN** Core answers `404` and the model is absent from `GET /anthropic/v1/models`

### Requirement: Base url or translator, decided by mode
Core SHALL reject a model whose `interfaces.<type>` in `translator` mode also declares a `base_url`
(`An interface is served either by a translator or by a base_url, not by both`) or declares no `translator`
(`Mode 'translator' requires a translator`), or not in `translator` mode declares a `translator`
(`A translator requires mode 'translator'`).

#### Scenario: Translator without mode
- **WHEN** an admin sends `PUT /v1/models/platform/m` with `{"type": "chat", "baseUrl": "http://localhost:7001",
  "interfaces": {"anthropicMessages": {"translator": {"out": "openaiChatCompletions", "baseUrl": "http://translator"}}}}`
- **THEN** Core answers `422` with a body containing `requires mode 'translator'`, and `GET /v1/models/platform/m`
  answers `404`

#### Scenario: Both translator and base url
- **WHEN** a config file declares a model with `interfaces.anthropicMessages: {"mode": "translator",
  "translator": "a2c", "base_url": "http://adapter"}`
- **THEN** the config is rejected with a message containing `An interface is served either by a translator or by a base_url, not by both`

### Requirement: Pass-through entry needs a url
Core SHALL reject a model with a pass-through `interfaces.<type>` entry that declares no `base_url` while the model
declares no `baseUrl` (`Interface '<type>' declares no base_url and the model declares no baseUrl`), whether or not a
legacy field serves that type.

#### Scenario: Entry with no url anywhere
- **WHEN** a model declares `endpoint: http://legacy/chat` and `interfaces: {"openaiChatCompletions": {}}` and no `baseUrl`
- **THEN** the config is rejected with a message containing `Interface 'openaiChatCompletions' declares no base_url and the model declares no baseUrl`

### Requirement: Translator matches the interface
Core SHALL reject a model whose translator, once resolved, declares an `in` other than the interface it is
referenced from (`Translator converts from '<in>', not from '<type>'`), or whose `out` equals its `in` (the
interface it sits under, for an inline definition).

#### Scenario: Inline translator converting to its own interface
- **WHEN** a model declares `interfaces.anthropicMessages: {"mode": "translator", "translator": {"out": "anthropicMessages", "baseUrl": "http://t"}}`
- **THEN** the config is rejected with a message containing `A translator cannot convert 'anthropicMessages' to itself`

#### Scenario: Registry translator under the wrong interface
- **WHEN** `translators.a2c` has `in: anthropicMessages` and a model references `a2c` from `interfaces.openaiResponses`
- **THEN** the config is rejected with a message containing `Translator converts from 'anthropicMessages', not from 'openaiResponses'`

### Requirement: Translator output served pass-through
Core SHALL reject a model whose translator's `out` is an interface the model serves in `translator` mode, or one the
model does not serve at all (`The model does not serve '<out>', which the translator converts to`). An unresolved
translator name SHALL NOT make the model invalid.

#### Scenario: Output not served
- **WHEN** a model declares only `interfaces.anthropicMessages: {"mode": "translator", "translator":
  {"out": "openaiChatCompletions", "baseUrl": "http://t"}}`
- **THEN** the config is rejected with a message containing `The model does not serve 'openaiChatCompletions', which the translator converts to`

#### Scenario: Two translated interfaces pointing at each other
- **WHEN** a model translates `anthropicMessages` to `openaiResponses` and `openaiResponses` to `anthropicMessages`
- **THEN** the config is rejected

#### Scenario: Unregistered name accepted
- **WHEN** an admin sends `PUT /v1/models/platform/u` with `{"type": "chat", "baseUrl": "http://localhost:7001",
  "interfaces": {"anthropicMessages": {"mode": "translator", "translator": "not-registered-yet"}}}`
- **THEN** Core answers `200`

### Requirement: Override path validation
Core SHALL reject a model, application or interceptor whose `overridePaths` sits under a `translator`-mode entry or
one served by a plugin url (its own `base_url`, or the deployment-level `baseUrl` when it declares none), names a known
key under an interface that does not own it, holds an empty template, or uses `{id}` for an operation with no id
(`postOpenaiResponses`, `postAnthropicMessages`, `postAnthropicMessagesCountTokens`). An unknown key SHALL be ignored.

#### Scenario: Key under the wrong interface
- **WHEN** a model declares `interfaces.openaiChatCompletions.overridePaths.postAnthropicMessages: /v1/messages`
- **THEN** the config is rejected with a message containing `Override path key 'postAnthropicMessages' belongs to interface 'anthropicMessages'`

#### Scenario: Id on an operation without one
- **WHEN** an application declares `interfaces.openaiResponses.overridePaths.postOpenaiResponses: /v1/{id}/responses`
- **THEN** the config is rejected with a message containing `The operation carries no id: {id} cannot render`

#### Scenario: Unknown key ignored
- **WHEN** a model declares `interfaces.openaiChatCompletions: {"base_url": "http://adapter", "overridePaths":
  {"postSomethingNew": "/x"}}`
- **THEN** the config loads

#### Scenario: Override paths under an embedded entry
- **WHEN** an admin sends `PUT /v1/applications/platform/a` declaring `interfaces.openaiChatCompletions:
  {"base_url": "plugin://p1", "overridePaths": {"postAzureOpenaiChatCompletions": "/x"}}`
- **THEN** Core answers `422` with a body containing `An interface with a plugin is served by its plugin: overridePaths has no effect`

#### Scenario: Override paths under the deployment's plugin url
- **WHEN** an admin sends `PUT /v1/applications/platform/a` declaring `baseUrl: plugin://p1` and
  `interfaces.openaiChatCompletions: {"overridePaths": {"postAzureOpenaiChatCompletions": "/x"}}`
- **THEN** Core answers `422` with a body containing `An interface with a plugin is served by its plugin: overridePaths has no effect`

### Requirement: Rejection outcome
Core SHALL apply none of a file config holding a model, application, interceptor, router or translator these rules
reject: startup fails, and a
periodic reload keeps the previous config live. A Configuration API write of such a model, application, interceptor or
router SHALL be answered `422` and not stored. The base-url and translator rules SHALL apply to models only,
the plugin url rules to models, applications, interceptors and routers.

#### Scenario: Reload with an invalid model
- **WHEN** a running Core reloads a config file in which a model declares a translator without `mode: translator`
- **THEN** every deployment keeps being served as in the previous config

#### Scenario: Application not checked for a missing url
- **WHEN** an application declares `interfaces: {"openaiResponses": {}}`, no `baseUrl` and
  `responsesEndpoint: http://host/openai/v1/responses`
- **THEN** the config loads and `POST /openai/v1/responses` for it is sent to `http://host/openai/v1/responses`

#### Scenario: Application with a plugin but no embedded mode
- **WHEN** an admin sends `PUT /v1/applications/platform/a` declaring `interfaces.openaiChatCompletions:
  {"base_url": "plugin://"}`
- **THEN** Core answers `422` and `GET /v1/applications/platform/a` answers `404`

### Requirement: No embedded interceptors
Core SHALL reject an interceptor with a plugin url in its `baseUrl`, in an `interfaces.<type>.base_url`, or in an
inline translator's `baseUrl` (`A plugin is not supported for an interceptor`). A request through an interceptor
whose named translator resolves to one with a plugin url SHALL be answered as one whose interceptor cannot be
reached, and SHALL call no plugin.

#### Scenario: Interceptor with an inline translator plugin
- **WHEN** an admin sends `PUT /v1/interceptors/platform/i` declaring `interfaces.openaiChatCompletions:
  {"mode": "translator", "translator": {"out": "openaiResponses", "baseUrl": "plugin://p1"}}`
- **THEN** Core answers `422` with a body containing `A plugin is not supported for an interceptor`

#### Scenario: Interceptor naming a translator with a plugin
- **WHEN** interceptor `i` declares `interfaces.openaiChatCompletions: {"mode": "translator", "translator": "t1"}`,
  `translators.t1` has `baseUrl: plugin://p1`, `plugins.p1` is of type `test-origin`, and a client sends a chat
  completion for a model with interceptor `i`
- **THEN** the client gets the answer of a request whose interceptor cannot be reached, and the plugin is not called

#### Scenario: Embedded interceptor
- **WHEN** an admin sends `PUT /v1/interceptors/platform/i` declaring `interfaces.openaiChatCompletions:
  {"base_url": "plugin://p1"}`
- **THEN** Core answers `422` with a body containing `A plugin is not supported for an interceptor`

#### Scenario: Interceptor with a plugin baseUrl
- **WHEN** an admin sends `PUT /v1/interceptors/platform/i` with `{"baseUrl": "plugin://p1", "interfaces":
  {"openaiChatCompletions": {}}}`
- **THEN** Core answers `422` with a body containing `A plugin is not supported for an interceptor`

### Requirement: Plugins are for platform deployments
Core SHALL serve an interface by a plugin, through its own plugin url or its translator's, only for a deployment of
the platform config (the config files and the `platform` bucket). A custom application written to any other bucket
with a plugin url in its `baseUrl`, an interface `base_url` or an inline translator's `baseUrl` SHALL be answered
`422` and not stored. A request to a custom application whose interface resolves to a plugin anyway SHALL
be answered `502` without calling a plugin.

#### Scenario: Custom application naming a translator with a plugin
- **WHEN** a custom application in a user bucket declares `interfaces.anthropicMessages: {"mode": "translator",
  "translator": "a2c"}`, `translators.a2c` has `baseUrl: plugin://p1`, `plugins.p1` is of type `test-origin`, and
  its owner sends `POST /anthropic/v1/messages` for it
- **THEN** Core answers `502` and the plugin is not called

#### Scenario: Custom application refused on write
- **WHEN** a user sends `PUT /v1/applications/<user bucket>/my-app` declaring `interfaces.openaiChatCompletions:
  {"base_url": "plugin://p1"}`
- **THEN** Core answers `422` with a body containing `A plugin is reserved for platform deployments` and
  stores nothing

#### Scenario: Custom application with an inline translator plugin refused on write
- **WHEN** a user sends `PUT /v1/applications/<user bucket>/my-app` declaring `interfaces.anthropicMessages:
  {"mode": "translator", "translator": {"out": "openaiChatCompletions", "baseUrl": "plugin://p1"}}`
- **THEN** Core answers `422` with a body containing `A plugin is reserved for platform deployments` and
  stores nothing

#### Scenario: Custom application with a plugin baseUrl refused on write
- **WHEN** a user sends `PUT /v1/applications/<user bucket>/my-app` declaring `baseUrl: plugin://p1` and
  `interfaces: {"openaiChatCompletions": {}}`
- **THEN** Core answers `422` with a body containing `A plugin is reserved for platform deployments` and
  stores nothing

#### Scenario: Stored custom application not served by a plugin
- **WHEN** a custom application blob in a user bucket declares
  `interfaces.openaiChatCompletions: {"base_url": "plugin://p1"}`, `plugins.p1` is of type `test-origin`, and its
  owner sends a chat completion for it
- **THEN** Core answers `502` and the plugin is not called

### Requirement: Validate reports a plugin outside the platform
`/v1/admin/validate` SHALL report an `Application` manifest outside the `platform` bucket with a plugin url in its
`baseUrl`, an interface `base_url` or an inline translator's `baseUrl` `FAILED` with the error a write of it gets, so
validate and apply agree.

#### Scenario: Custom application refused by validate
- **WHEN** an admin sends `POST /v1/admin/validate` with a manifest of kind `Application` named
  `applications/public/my-app` declaring `interfaces.openaiChatCompletions: {"base_url": "plugin://p1"}`
- **THEN** Core answers `422`, and the manifest's result has `status: FAILED` and
  `error: A plugin is reserved for platform deployments`

### Requirement: Plugin url in a base url
Core SHALL read a base url starting with `plugin://` (lowercase) as a plugin url, `plugin://<name>` optionally
followed by `/<path>`, naming the `plugins` entry `<name>`: the text after `plugin://` up to the next `/` or the end.
A plugin url SHALL be accepted in `interfaces.<type>.base_url`, in the deployment-level `baseUrl`, where it serves
the entries that declare no `base_url`, and in a translator's `baseUrl`. A name no `plugins` entry has SHALL NOT
make the deployment invalid.

#### Scenario: Deployment-level plugin url serves the entries without a base url
- **WHEN** application `a` declares `baseUrl: plugin://p1` and `interfaces: {"openaiChatCompletions": {},
  "openaiEmbeddings": {"base_url": "http://app"}}`, `plugins.p1` is of type `test-origin`, and a client sends a chat
  completion and then an embeddings request for `a`
- **THEN** the `test-origin` plugin answers the chat completion, and the embeddings request is sent to
  `http://app/openai/deployments/a/embeddings`

#### Scenario: Plugin url not registered yet
- **WHEN** an admin sends `PUT /v1/applications/platform/a` declaring `interfaces.openaiChatCompletions:
  {"base_url": "plugin://later"}` and no `plugins.later` exists
- **THEN** Core answers `200` and stores the application

### Requirement: Plugin url where no plugin serves
Core SHALL reject a model, application or interceptor with a plugin url in `endpoint` or `responsesEndpoint`, which
no plugin serves, with a warning on that field (`A plugin url is only accepted in a base url`), on every write and on
a reload, as Rejection outcome states.

#### Scenario: Plugin url in endpoint
- **WHEN** an admin sends `PUT /v1/models/platform/m` with `{"type": "chat", "endpoint": "plugin://p1"}`
- **THEN** Core answers `422` with a warning whose `field` is `endpoint` and whose `message` contains
  `A plugin url is only accepted in a base url`, and `GET /v1/models/platform/m` answers `404`

#### Scenario: Plugin url in responsesEndpoint refused by validate and apply
- **WHEN** an admin sends `POST /v1/admin/validate` with one `Model` manifest `models/platform/m` whose spec has
  `type: chat`, an HTTP `endpoint` and `responsesEndpoint: plugin://p1`
- **THEN** Core answers `422` with that result `FAILED` and an `error` containing
  `A plugin url is only accepted in a base url`, and `POST /v1/admin/apply` with the same body and
  `"precheck": false` answers the same result and writes nothing

### Requirement: Plugin url with a blank name
Core SHALL reject a plugin url whose name is blank (`plugin://`, `plugin:///v1`) in an `interfaces.<type>.base_url`,
a deployment-level `baseUrl` or a translator's `baseUrl` (`A plugin name cannot be blank`), on every write and on a
reload, as Rejection outcome states.

#### Scenario: Plugin url with a blank name
- **WHEN** an admin sends `PUT /v1/models/platform/m` with `{"type": "chat", "baseUrl": "plugin:///v1",
  "interfaces": {"openaiChatCompletions": {}}}`
- **THEN** Core answers `422` with a body containing `A plugin name cannot be blank`, and
  `GET /v1/models/platform/m` answers `404`

#### Scenario: Translator plugin url with a blank name
- **WHEN** an admin sends `PUT /v1/translators/platform/a2c` with `{"in": "anthropicMessages",
  "out": "openaiChatCompletions", "baseUrl": "plugin://"}`
- **THEN** Core answers `422` with a body containing `A plugin name cannot be blank`, and
  `GET /v1/translators/platform/a2c` answers `404`
