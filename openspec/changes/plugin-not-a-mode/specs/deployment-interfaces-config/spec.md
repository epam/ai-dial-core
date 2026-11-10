# Spec Delta

## MODIFIED Requirements

### Requirement: Legacy endpoint fields
Core SHALL serve a type that has no `interfaces` entry, or a pass-through entry that resolves to no url, from the
pre-`interfaces` field (a `translator`-mode entry or one naming a `plugin` never falls back, see Interface mode): `endpoint`
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

### Requirement: Interface mode
Core SHALL read `interfaces.<type>.mode` as `passthrough` (the default when absent) or `translator`, and no other
value. An entry in `translator` mode SHALL be served by its translator only, and a pass-through entry that names a
`plugin` by that plugin only (deployment-origin), never by a `base_url`, the deployment-level `baseUrl`, `endpoint`
or `responsesEndpoint`.

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
- **WHEN** an application declares `baseUrl: http://app` and `interfaces.openaiChatCompletions: {"plugin": "p1"}`,
  `plugins.p1` is of type `test-origin`, and a client sends `POST /openai/deployments/<application>/chat/completions`
- **THEN** the `test-origin` plugin answers the request and nothing is sent to `http://app`

#### Scenario: Embedded interface never falls back
- **WHEN** a model declares `endpoint: http://legacy/chat` and
  `interfaces.openaiChatCompletions: {"plugin": "missing"}` and no `plugins.missing`
- **THEN** a chat completion for it is answered `502` and nothing is sent to `http://legacy/chat`

#### Scenario: Explicit pass-through mode with a plugin
- **WHEN** an application declares `interfaces.openaiChatCompletions: {"mode": "passthrough", "plugin": "p1"}`
- **THEN** the `test-origin` plugin answers a chat completion for it

#### Scenario: Embedded is not a mode
- **WHEN** an admin sends `PUT /v1/applications/platform/a` with `interfaces.openaiChatCompletions:
  {"mode": "embedded", "plugin": "p1"}`
- **THEN** Core answers `400` and stores nothing

### Requirement: Translator reference
Core SHALL read `interfaces.<type>.translator` as either a string naming a `translators` entry or an inline object
`{"out", "baseUrl"}` or `{"out", "plugin"}` whose `in` is the interface it sits under. A name SHALL be resolved
against the `translators` of the live config on each request, so an edit to that entry takes effect with no edit to
the deployment.

#### Scenario: Named translator resolved per request
- **WHEN** a model's `interfaces.anthropicMessages` is `{"mode": "translator", "translator": "a2c"}` and
  `translators.a2c.baseUrl` changes from `http://t1` to `http://t2` in a reloaded config
- **THEN** the next `POST /anthropic/v1/messages` for it is sent to `http://t2/anthropic/v1/messages`

#### Scenario: Name kept as a name
- **WHEN** a config with that model is read and written back
- **THEN** `interfaces.anthropicMessages.translator` is still the string `"a2c"`

### Requirement: Translator definition
Core SHALL read a translator (in `translators.<name>` or inline) with `in`, `out` and exactly one of `baseUrl`
(alias `base_url`) and `plugin`, the name of a `plugins` entry. Core SHALL reject as the config is read one with no
`out`, with neither or both of a non-empty `baseUrl` and a non-empty `plugin`, or an `in`/`out` naming an interface
type Core does not know, and SHALL reject a `translators` entry declaring no `in`. A `plugin` naming no `plugins`
entry SHALL NOT make the config invalid.

#### Scenario: Missing baseUrl rejected on read
- **WHEN** a config declares `translators.a2c: {"in": "anthropicMessages", "out": "openaiChatCompletions"}`
- **THEN** the config fails to read and is not applied

#### Scenario: Both baseUrl and plugin rejected on read
- **WHEN** a config declares `translators.a2c: {"in": "anthropicMessages", "out": "openaiChatCompletions",
  "baseUrl": "http://t", "plugin": "p1"}`
- **THEN** the config fails to read and is not applied

#### Scenario: Translator with a plugin read and written back
- **WHEN** an admin sends `PUT /v1/translators/platform/a2c` with `{"in": "anthropicMessages",
  "out": "openaiChatCompletions", "plugin": "later"}` and no `plugins.later` exists
- **THEN** Core answers `200`, and `GET /v1/translators/platform/a2c` returns `plugin: later` and no `baseUrl`

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

### Requirement: Advertised interface types
Core SHALL advertise an interface type for a deployment when its `interfaces` entry is in `translator` mode with a
`translator` reference, or is pass-through with a `plugin` reference or a resolving base url,
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
- **WHEN** an application declares only `interfaces.openaiChatCompletions: {"plugin": "p1"}`
- **THEN** its listing in `GET /v1/deployments` carries `interface_configs.openaiChatCompletions`, whether or not a
  `plugins.p1` entry exists

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
Core SHALL reject a model with a pass-through `interfaces.<type>` entry that names no `plugin` and declares no
`base_url` while the model declares no `baseUrl` (`Interface '<type>' declares no base_url and the model declares no
baseUrl`), whether or not a legacy field serves that type.

#### Scenario: Entry with no url anywhere
- **WHEN** a model declares `endpoint: http://legacy/chat` and `interfaces: {"openaiChatCompletions": {}}` and no `baseUrl`
- **THEN** the config is rejected with a message containing `Interface 'openaiChatCompletions' declares no base_url and the model declares no baseUrl`

### Requirement: Override path validation
Core SHALL reject a model, application or interceptor whose `overridePaths` sits under a `translator`-mode entry or
one naming a `plugin`, names a known key under an interface that does not own it, holds an empty template, or uses
`{id}` for an operation with no id (`postOpenaiResponses`, `postAnthropicMessages`, `postAnthropicMessagesCountTokens`).
An unknown key SHALL be ignored.

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
  {"plugin": "p1", "overridePaths": {"postAzureOpenaiChatCompletions": "/x"}}`
- **THEN** Core answers `422` with a body containing `An interface with a plugin is served by its plugin: overridePaths has no effect`

### Requirement: Rejection outcome
Core SHALL apply none of a file config holding a model, application, interceptor or translator these rules reject:
startup fails, and a
periodic reload keeps the previous config live. A Configuration API write of such a model, application or
interceptor SHALL be answered `422` and not stored. The base-url and translator rules SHALL apply to models only,
the plugin rules to models, applications and interceptors.

#### Scenario: Reload with an invalid model
- **WHEN** a running Core reloads a config file in which a model declares a translator without `mode: translator`
- **THEN** every deployment keeps being served as in the previous config

#### Scenario: Application not checked for a missing url
- **WHEN** an application declares `interfaces: {"openaiResponses": {}}`, no `baseUrl` and
  `responsesEndpoint: http://host/openai/v1/responses`
- **THEN** the config loads and `POST /openai/v1/responses` for it is sent to `http://host/openai/v1/responses`

#### Scenario: Application with a plugin but no embedded mode
- **WHEN** an admin sends `PUT /v1/applications/platform/a` declaring `interfaces.openaiChatCompletions:
  {"base_url": "http://app", "plugin": "p1"}`
- **THEN** Core answers `422` and `GET /v1/applications/platform/a` answers `404`

### Requirement: Plugin or base url
Core SHALL reject a model or application whose `interfaces.<type>` declares a `plugin` that is blank (`A plugin name
cannot be blank`), declares it next to a `base_url` (`An interface is served either by a plugin or by a base_url,
not by both`) or declares it in `translator` mode (`A translated interface is served by its translator, not by a
plugin`). A model with a pass-through entry that names a `plugin` SHALL need no `base_url` and no `baseUrl`. A
`plugin` naming no `plugins` entry SHALL NOT make the deployment invalid.

#### Scenario: Plugin on a translated interface
- **WHEN** a config file declares a model with `interfaces.anthropicMessages: {"mode": "translator",
  "translator": "a2c", "plugin": "p1"}`
- **THEN** the config is rejected with a message containing `A translated interface is served by its translator, not by a plugin`

#### Scenario: Model served by a plugin needs no url
- **WHEN** an admin sends `PUT /v1/models/platform/m` with `{"type": "chat", "interfaces":
  {"openaiChatCompletions": {"plugin": "p1"}}}`
- **THEN** Core answers `200` and stores the model

#### Scenario: Embedded mode without a plugin
- **WHEN** an admin sends `PUT /v1/models/platform/m` with `{"type": "chat", "interfaces":
  {"openaiChatCompletions": {"mode": "embedded"}}}`
- **THEN** Core answers `400`, and `GET /v1/models/platform/m` answers `404`

#### Scenario: Blank plugin name
- **WHEN** an admin sends `PUT /v1/models/platform/m` with `{"type": "chat", "interfaces":
  {"openaiChatCompletions": {"plugin": " "}}}`
- **THEN** Core answers `422` with a body containing `A plugin name cannot be blank`, and
  `GET /v1/models/platform/m` answers `404`

#### Scenario: Both plugin and base url
- **WHEN** a config file declares an application with `interfaces.openaiChatCompletions: {"plugin": "p1", "base_url": "http://app"}`
- **THEN** the config is rejected with a message containing `An interface is served either by a plugin or by a base_url, not by both`

#### Scenario: Plugin not registered yet
- **WHEN** an admin sends `PUT /v1/applications/platform/a` declaring `interfaces.openaiChatCompletions:
  {"plugin": "later"}` and no `plugins.later` exists
- **THEN** Core answers `200` and stores the application

### Requirement: No embedded interceptors
Core SHALL reject an interceptor with an `interfaces.<type>` entry that names a `plugin`, or whose inline translator
names one (`A plugin is not supported for an interceptor`). A request through an interceptor whose named translator
resolves to one with a `plugin` SHALL be answered as one whose interceptor cannot be reached, and SHALL call no
plugin.

#### Scenario: Interceptor with an inline translator plugin
- **WHEN** an admin sends `PUT /v1/interceptors/platform/i` declaring `interfaces.openaiChatCompletions:
  {"mode": "translator", "translator": {"out": "openaiResponses", "plugin": "p1"}}`
- **THEN** Core answers `422` with a body containing `A plugin is not supported for an interceptor`

#### Scenario: Interceptor naming a translator with a plugin
- **WHEN** interceptor `i` declares `interfaces.openaiChatCompletions: {"mode": "translator", "translator": "t1"}`,
  `translators.t1` names `plugin: p1` of type `test-origin`, and a client sends a chat completion for a model with
  interceptor `i`
- **THEN** the client gets the answer of a request whose interceptor cannot be reached, and the plugin is not called

#### Scenario: Embedded interceptor
- **WHEN** an admin sends `PUT /v1/interceptors/platform/i` declaring `interfaces.openaiChatCompletions:
  {"plugin": "p1"}`
- **THEN** Core answers `422` with a body containing `A plugin is not supported for an interceptor`

### Requirement: Plugins are for platform deployments
Core SHALL serve an interface by a plugin, the entry's own or its translator's, only for a deployment of the
platform config (the config files and the `platform` bucket). A custom application written to any other bucket with
an entry that names a plugin, or whose inline translator names one, SHALL be answered `422` and not stored. A
request to a custom application whose interface resolves to a plugin anyway SHALL be answered `502` without calling
a plugin.

#### Scenario: Custom application naming a translator with a plugin
- **WHEN** a custom application in a user bucket declares `interfaces.anthropicMessages: {"mode": "translator",
  "translator": "a2c"}`, `translators.a2c` names `plugin: p1` of type `test-origin`, and its owner sends
  `POST /anthropic/v1/messages` for it
- **THEN** Core answers `502` and the plugin is not called

#### Scenario: Custom application refused on write
- **WHEN** a user sends `PUT /v1/applications/<user bucket>/my-app` declaring `interfaces.openaiChatCompletions:
  {"plugin": "p1"}`
- **THEN** Core answers `422` with a body containing `A plugin is reserved for platform deployments` and
  stores nothing

#### Scenario: Custom application with an inline translator plugin refused on write
- **WHEN** a user sends `PUT /v1/applications/<user bucket>/my-app` declaring `interfaces.anthropicMessages:
  {"mode": "translator", "translator": {"out": "openaiChatCompletions", "plugin": "p1"}}`
- **THEN** Core answers `422` with a body containing `A plugin is reserved for platform deployments` and
  stores nothing

#### Scenario: Stored custom application not served by a plugin
- **WHEN** a custom application blob in a user bucket declares `interfaces.openaiChatCompletions: {"plugin": "p1"}`,
  `plugins.p1` is of type `test-origin`, and its owner sends a chat completion for it
- **THEN** Core answers `502` and the plugin is not called

## RENAMED Requirements

- FROM: `### Requirement: Plugin decided by mode`
- TO: `### Requirement: Plugin or base url`
- FROM: `### Requirement: Embedded mode is for platform deployments`
- TO: `### Requirement: Plugins are for platform deployments`
