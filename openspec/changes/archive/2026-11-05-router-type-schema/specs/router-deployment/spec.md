# Spec Delta

## ADDED Requirements

### Requirement: Router type schema and properties
A router SHALL accept `applicationTypeSchemaId` (an app-type schema `$id`) and `applicationProperties` (an object),
with the names and aliases an application accepts, SHALL leave both out of the stored JSON when unset, and SHALL
reject, on load and on a Configuration API write, a router that sets one of the two without the other, with a
message containing `A router declares applicationTypeSchemaId and applicationProperties together`.

#### Scenario: Typed router loads
- **WHEN** the config declares the schema `https://dial.test/router-type` and `routers.r3` with that
  `applicationTypeSchemaId`, `applicationProperties: {"tier": "medium", "preferredModels": ["gpt-4"]}`, `baseUrl`
  and `interfaces.openaiChatCompletions: {}`
- **THEN** the config loads and `r3` resolves as a deployment

#### Scenario: Properties without a schema rejected
- **WHEN** an admin sends `PUT /v1/routers/platform/r-bad` with `applicationProperties: {"tier": "medium"}` and no
  `applicationTypeSchemaId`
- **THEN** Core answers `422` with a warning containing
  `A router declares applicationTypeSchemaId and applicationProperties together`, and writes nothing

#### Scenario: Untyped router stored without the fields
- **WHEN** an admin sends `PUT /v1/routers/platform/r-plain` with `baseUrl` and `interfaces.openaiChatCompletions: {}`
- **THEN** the stored blob has neither an `applicationTypeSchemaId` nor an `applicationProperties` key

### Requirement: Router properties validated against the schema
Core SHALL validate a typed router's `applicationProperties` against its schema on the file load (against the
file's schemas, as for an application) and on every Configuration API write (against the merged config plus a
batch's earlier `Schema` entries), and SHALL reject the file, or answer `422` or fail the manifest entry and write
nothing, when the schema is unknown or the properties do not validate, whatever the soft-validation mode.

#### Scenario: Unknown schema
- **WHEN** an admin sends `PUT /v1/routers/platform/r-bad` with `applicationTypeSchemaId: "https://dial.test/none"`
  and `applicationProperties: {"tier": "medium"}`
- **THEN** Core answers `422` with a warning on `applicationTypeSchemaId` and `GET /v1/routers/platform/r-bad`
  answers `404`

#### Scenario: Properties that break the schema
- **WHEN** the schema `https://dial.test/router-type` requires the string `tier` and an admin sends
  `PUT /v1/routers/platform/r-bad` with that schema and `applicationProperties: {"tier": 5}`
- **THEN** Core answers `422` with a warning on `applicationProperties`

#### Scenario: File router that breaks its schema
- **WHEN** the config file declares the schema `https://dial.test/router-type` and a router with that schema and
  `applicationProperties: {"tier": 5}`
- **THEN** the file load fails

#### Scenario: Schema and router in one batch
- **WHEN** an admin applies, in this input order, a `Router` using the schema `https://dial.test/batch-type` and the
  `Schema` with that `$id`
- **THEN** Core answers `200` with both `APPLIED`

### Requirement: Router properties sent to the router
For a router with `applicationTypeSchemaId`, Core SHALL send `X-DIAL-APPLICATION-PROPERTIES`, the JSON of the
properties the schema declares taken from `applicationProperties`, on requests to the router's own interfaces and
feature endpoints and on decide requests, unless the schema sets `dial:appendApplicationPropertiesHeader: false`.
A translator in front of a router interface, a Responses item call and the deployment a decision chose SHALL NOT
receive it.

#### Scenario: Proxied request
- **WHEN** a client sends `POST /openai/deployments/r3/chat/completions` to the typed router `r3` whose properties
  are `{"tier": "medium", "preferredModels": ["gpt-4"], "undeclared": 1}` and whose schema leaves
  `additionalProperties` unset
- **THEN** the router receives `X-DIAL-APPLICATION-PROPERTIES` whose JSON equals
  `{"tier": "medium", "preferredModels": ["gpt-4"]}`

#### Scenario: Decide request
- **WHEN** the typed router's `openaiChatCompletions` interface is in decision mode and its verdict names `gpt-4`
- **THEN** the decide request carries the same `X-DIAL-APPLICATION-PROPERTIES`, and the request `gpt-4` receives
  carries none

#### Scenario: Feature endpoint
- **WHEN** a typed router declares `features.rateEndpoint` and a client sends `POST /v1/r3/rate`
- **THEN** the rate request the router receives carries `X-DIAL-APPLICATION-PROPERTIES`

#### Scenario: Header turned off
- **WHEN** the typed router's schema sets `dial:appendApplicationPropertiesHeader: false`
- **THEN** the router receives no `X-DIAL-APPLICATION-PROPERTIES`

#### Scenario: Router without a schema
- **WHEN** a client calls router `r1`, which declares no `applicationTypeSchemaId`
- **THEN** the router receives no `X-DIAL-APPLICATION-PROPERTIES`

### Requirement: Router properties that no longer match
Core SHALL build a typed router's properties header once per request, before it connects to the router, and SHALL
answer `502` without sending anything to the router when the schema is gone or the stored properties no longer
validate against it, in proxy and in decision mode.

#### Scenario: Schema changed under a stored router
- **WHEN** a typed router is stored valid, its schema is then replaced by one that requires a property the router
  lacks, and a client calls the router
- **THEN** Core answers `502` and the router receives no request
