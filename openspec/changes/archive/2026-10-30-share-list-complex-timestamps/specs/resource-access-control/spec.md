## MODIFIED Requirements

### Requirement: List shared resources
On `POST /v1/ops/resource/share/list` with `{"resourceTypes":[...],"with":...}` Core SHALL answer `200` with
`{"resources":[<metadata with permissions>]}`: resources shared with the caller, or, when `with` is `others`, the
caller's resources it shared. With `"includeUserInfo":true` items SHALL carry `sharedBy` (with me) or
`sharedWith` `[{"user","permissions"}]` (by me). Each item SHALL carry `createdAt` and `updatedAt` of the stored
resource, as the resource metadata endpoints report them.

#### Scenario: Shared with me
- **WHEN** `proxyKey2` accepted a READ share of `conversations/<b1>/folder2/conversation2` and lists `{"resourceTypes":["CONVERSATION"],"with":"me"}`
- **THEN** `resources` holds one item with that `url`, `"nodeType":"ITEM"` and `"permissions":["READ"]`

#### Scenario: Items carry timestamps
- **WHEN** `proxyKey1` shared a conversation, and either `proxyKey2` lists `"with":"me"` or `proxyKey1` lists `"with":"others"`
- **THEN** the item has numeric `createdAt` and `updatedAt`, equal to those of `GET` of the conversation's metadata

#### Scenario: Complex items carry timestamps
- **WHEN** `proxyKey1` shared a project (or a skill), and either `proxyKey2` lists `"with":"me"` or `proxyKey1` lists `"with":"others"` for `PROJECT` (or `SKILL`)
- **THEN** the item has numeric `createdAt` and `updatedAt`, equal to those of `GET /v2/metadata/` of the project (or skill)
