# Spec Delta

## ADDED Requirements

### Requirement: Router editor URL
A router SHALL accept `editorUrl`, a string with the URL of its admin UI, under the names an application accepts
(`editorUrl`, `editor_url`), SHALL store it as `editorUrl`, SHALL leave it out of the stored JSON when unset, and
SHALL return it from `GET /v1/routers/platform/{name}` and `GET /v1/admin/config/file/routers/{name}`. No entry of
`GET /v1/routers`, `GET /v1/routers/{id}` or the generic deployment listings SHALL carry it.

#### Scenario: Blob router editor URL read back
- **WHEN** an admin sends `PUT /v1/routers/platform/r-ui` with `{"baseUrl": "http://router", "editorUrl":
  "https://router-ui.example", "interfaces": {"openaiChatCompletions": {}}}`
- **THEN** Core answers `200`, and `GET /v1/routers/platform/r-ui` answers `200` with
  `editorUrl: "https://router-ui.example"`

#### Scenario: Snake-case name accepted
- **WHEN** an admin sends `PUT /v1/routers/platform/r-ui` with `editor_url: "https://router-ui.example"`, `baseUrl`
  and `interfaces.openaiChatCompletions: {}`
- **THEN** Core answers `200`, and the stored blob has `editorUrl: "https://router-ui.example"` and no `editor_url`
  key

#### Scenario: Router without an editor URL stored without the key
- **WHEN** an admin sends `PUT /v1/routers/platform/r-plain` with `baseUrl` and `interfaces.openaiChatCompletions: {}`
- **THEN** the stored blob has no `editorUrl` key

#### Scenario: File router editor URL read back
- **WHEN** the config file declares `routers.r2` with `editorUrl: "https://router-ui.example"` and an admin sends
  `GET /v1/admin/config/file/routers/r2`
- **THEN** the config loads and the answer has `editorUrl: "https://router-ui.example"`

#### Scenario: Editor URL not listed
- **WHEN** the config declares `routers.r2` with `editorUrl` and a caller the router admits sends `GET /v1/routers`,
  `GET /v1/routers/r2`, `GET /v1/deployments` and `GET /v1/deployments/r2`
- **THEN** each answer is `200` and its `r2` entry has neither an `editor_url` nor an `editorUrl` field
