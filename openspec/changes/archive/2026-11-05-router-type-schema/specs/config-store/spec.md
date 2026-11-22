# Spec Delta

## MODIFIED Requirements

### Requirement: Write rejected with 422
A config `PUT` SHALL answer `422` `{"validationWarnings": [{"field": ..., "message": ...}]}` and write nothing when a
model breaks a rebuild invariant (pricing, interfaces, translator refs, catalog properties) or, unless soft mode is
on, references an interceptor absent from the merged config; when a router breaks a write rule of router-deployment;
and when a translator or an `overridePaths` entry is invalid, whatever the mode.

#### Scenario: Unknown interceptor
- **WHEN** an admin sends `PUT /v1/models/platform/cr-unknown` with `interceptors: ["unknown-interceptor"]` and no
  such interceptor exists
- **THEN** Core answers `422` with one warning, `field: "interceptors[0]"`, whose `message` contains
  `unknown-interceptor`, and `GET` on the model answers `404`

#### Scenario: Two unknown interceptors
- **WHEN** the model references two missing interceptors
- **THEN** the warnings are `interceptors[0]` and `interceptors[1]`

#### Scenario: Router with interceptors rejected on write
- **WHEN** an admin sends `PUT /v1/routers/platform/r-bad` with `{"baseUrl": "http://localhost:4848", "interfaces":
  {"openaiChatCompletions": {}}, "interceptors": ["i1"]}`
- **THEN** Core answers `422` with one warning on `interceptors` whose message contains `A router declares no
  interceptors`, and `GET /v1/routers/platform/r-bad` answers `404`

#### Scenario: Router with a translator mismatch rejected on write
- **WHEN** an admin sends `PUT /v1/routers/platform/r-bad` with `{"baseUrl": "http://localhost:4848", "interfaces":
  {"openaiChatCompletions": {}, "anthropicMessages": {"translator": {"out": "openaiChatCompletions", "baseUrl":
  "http://t"}}}}`
- **THEN** Core answers `422` with a body containing `requires mode 'translator'`

#### Scenario: Typed router properties rejected on write
- **WHEN** an admin sends a typed router whose `applicationProperties` break its schema while
  `config.write.softValidation` is `true`
- **THEN** Core answers `422` and writes nothing
