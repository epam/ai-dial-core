# Spec Delta

## MODIFIED Requirements

### Requirement: Interface mode
Core SHALL read `interfaces.<type>.mode` as `passthrough` (the default when absent) or `translator`. An entry in
`translator` mode SHALL be served by its translator only, never by its own `base_url`, the deployment-level `baseUrl`,
`endpoint` or `responsesEndpoint`: a translator with a `baseUrl` at that url, and a builtin translator by Core itself,
through the model's own `out` interface (anthropic-messages-translation). That `out` interface is served as a
pass-through request for it would be: by its own `base_url`, the deployment-level `baseUrl`, or a legacy
`responsesEndpoint`. Only a Model SHALL be served by a builtin translator.

#### Scenario: Translated interface goes to the translator
- **WHEN** a model declares `baseUrl: http://adapter` and `interfaces.anthropicMessages: {"mode": "translator",
  "translator": {"out": "openaiChatCompletions", "baseUrl": "http://translator/to-chat"}}` and
  `interfaces.openaiChatCompletions: {}`, and a client sends `POST /anthropic/v1/messages` for it
- **THEN** the request is sent to `POST http://translator/to-chat/anthropic/v1/messages`

#### Scenario: Translated interface never falls back
- **WHEN** a model declares `endpoint: http://legacy/chat` and
  `interfaces.openaiChatCompletions: {"mode": "translator", "translator": "missing"}` and no `translators.missing`
- **THEN** a chat completion for it is answered `503` and nothing is sent to `http://legacy/chat`

#### Scenario: Builtin translator served through the model's own interface
- **WHEN** a model declares `interfaces.openaiResponses.base_url: http://localhost:4848` and
  `interfaces.anthropicMessages: {"mode": "translator", "translator": "builtinResponses"}`, where
  `translators.builtinResponses` is `{"in": "anthropicMessages", "out": "openaiResponses", "builtin":
  "anthropicMessagesToOpenaiResponses"}`, and a client sends a streaming `POST /anthropic/v1/messages` for it
- **THEN** the request is sent, translated, to `POST http://localhost:4848/openai/v1/responses`

### Requirement: Translator reference
Core SHALL read `interfaces.<type>.translator` as either a string naming a `translators` entry or an inline object
with `out` and either `baseUrl` or `builtin`, whose `in` is the interface it sits under. A name SHALL be resolved
against the `translators` of the live config on each request, so an edit to that entry takes effect with no edit to
the deployment.

#### Scenario: Named translator resolved per request
- **WHEN** a model's `interfaces.anthropicMessages` is `{"mode": "translator", "translator": "a2c"}` and
  `translators.a2c.baseUrl` changes from `http://t1` to `http://t2` in a reloaded config
- **THEN** the next `POST /anthropic/v1/messages` for it is sent to `http://t2/anthropic/v1/messages`

#### Scenario: Name kept as a name
- **WHEN** a config with that model is read and written back
- **THEN** `interfaces.anthropicMessages.translator` is still the string `"a2c"`

#### Scenario: Inline builtin translator kept inline
- **WHEN** a config with a model whose `interfaces.anthropicMessages.translator` is
  `{"out": "openaiResponses", "builtin": "anthropicMessagesToOpenaiResponses"}` is read and written back
- **THEN** the translator is still that inline object, with no `baseUrl`

### Requirement: Translator definition
Core SHALL read a translator (in `translators.<name>` or inline) with `in`, `out` and exactly one of `baseUrl` (alias
`base_url`) and `builtin`. As the config is read, Core SHALL reject one with no `out`, with neither or both of a
non-empty `baseUrl` and `builtin`, with a `builtin` Core does not know (only `anthropicMessagesToOpenaiResponses`
exists), or with an `in`/`out` naming an interface type Core does not know, and SHALL reject a `translators` entry
declaring no `in`.

#### Scenario: Missing baseUrl rejected on read
- **WHEN** a config declares `translators.a2c: {"in": "anthropicMessages", "out": "openaiChatCompletions"}`
- **THEN** the config fails to read and is not applied

#### Scenario: Both baseUrl and builtin rejected on read
- **WHEN** a config declares `translators.a2r: {"in": "anthropicMessages", "out": "openaiResponses",
  "baseUrl": "http://t", "builtin": "anthropicMessagesToOpenaiResponses"}`
- **THEN** the config fails to read and is not applied

#### Scenario: Unknown builtin rejected on read
- **WHEN** a config declares `translators.a2r: {"in": "anthropicMessages", "out": "openaiResponses",
  "builtin": "noSuchTranslation"}`
- **THEN** the config fails to read and is not applied

#### Scenario: Unknown interface type rejected on read
- **WHEN** a config declares `translators.g2r: {"in": "geminiInteractions", "out": "openaiResponses", "baseUrl": "http://t"}`
- **THEN** the config fails to read and is not applied

#### Scenario: Registry entry without in rejected
- **WHEN** a config declares `translators.a2c: {"out": "openaiChatCompletions", "baseUrl": "http://t"}`
- **THEN** the config is rejected with a message containing `Translator 'a2c': Translator declares no in`

### Requirement: Translator matches the interface
Core SHALL reject a model whose translator, once resolved, declares an `in` other than the interface it is
referenced from (`Translator converts from '<in>', not from '<type>'`), or whose `out` equals its `in` (the
interface it sits under, for an inline definition). Core SHALL reject a builtin translator, registered or inline,
whose `in` and `out` are not the pair its `builtin` converts (`Builtin translator '<builtin>' converts '<in>' to
'<out>', not '<in>' to '<out>'`).

#### Scenario: Inline translator converting to its own interface
- **WHEN** a model declares `interfaces.anthropicMessages: {"mode": "translator", "translator": {"out": "anthropicMessages", "baseUrl": "http://t"}}`
- **THEN** the config is rejected with a message containing `A translator cannot convert 'anthropicMessages' to itself`

#### Scenario: Registry translator under the wrong interface
- **WHEN** `translators.a2c` has `in: anthropicMessages` and a model references `a2c` from `interfaces.openaiResponses`
- **THEN** the config is rejected with a message containing `Translator converts from 'anthropicMessages', not from 'openaiResponses'`

#### Scenario: Registered builtin with another output
- **WHEN** `translators.builtin` is `{"in": "anthropicMessages", "out": "openaiChatCompletions", "builtin":
  "anthropicMessagesToOpenaiResponses"}`
- **THEN** the config is rejected with a message containing `Builtin translator 'anthropicMessagesToOpenaiResponses'`

#### Scenario: Inline builtin under another interface
- **WHEN** a model declares `baseUrl: http://model`, `interfaces.openaiResponses: {}` and
  `interfaces.openaiChatCompletions: {"mode": "translator", "translator": {"out": "openaiResponses", "builtin":
  "anthropicMessagesToOpenaiResponses"}}`
- **THEN** the config is rejected with a message containing `not 'openaiChatCompletions' to 'openaiResponses'`

### Requirement: Nothing serves the interface
Core SHALL answer `503` to a request whose deployment has no url for the requested interface: neither an
`interfaces` entry resolving to a url (a translator name with no `translators` entry resolves to none, and so does a
builtin translator) nor a legacy field serving the type. This SHALL hold for deployments-POST,
`/openai/v1/chat/completions`, `/openai/v1/responses` and its item operations, and `/anthropic/v1/messages`, bar what
anthropic-messages-translation serves.

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

#### Scenario: Application with a builtin translator
- **WHEN** an application declares `interfaces.openaiResponses.base_url: http://localhost:4848` and
  `interfaces.anthropicMessages: {"mode": "translator", "translator": "builtinResponses"}` with a builtin
  `translators.builtinResponses`, and a client sends a streaming `POST /anthropic/v1/messages` for it
- **THEN** Core answers `503` and sends no request
