## ADDED Requirements

### Requirement: Author of an application or toolset on create
An application or toolset created through the Resource API, the Configuration API, `/v1/admin/apply` or file migration
SHALL store `author` in its body as sent, `""` included. When the body has none, Core SHALL store the caller's name: the
user display name of a JWT, the project of an API key, the display name of an admin. This `author` is a business value,
apart from the audit author that `GET /v1/metadata/` reports as `author`.

#### Scenario: Created without author
- **WHEN** a user sends `PUT /v1/applications/<bucket>/app` without `author`, then `GET`s it
- **THEN** the answer has `author` `EPM-RTC-GPT`, the caller's display name

#### Scenario: Created with another author
- **WHEN** a user sends `PUT /v1/toolsets/<bucket>/copy` with `"author": "original-dev"`, then `GET`s it and lists
  `GET /v1/metadata/toolsets/<bucket>/`
- **THEN** the `GET` has `author` `original-dev` and the listing item has `author` `EPM-RTC-GPT`

#### Scenario: Created with an empty author
- **WHEN** a user sends `PUT /v1/applications/<bucket>/anon` with `"author": ""`, then `GET`s it
- **THEN** the answer has `author` `""`

#### Scenario: Configuration API write without author
- **WHEN** an admin sends `PUT /v1/toolsets/platform/t` without `author`, then `GET`s it
- **THEN** the answer has `author` equal to the admin's display name

#### Scenario: Apply
- **WHEN** an admin applies an `Application` manifest `applications/public/a1` without `spec.author` and a `ToolSet`
  manifest `toolsets/public/t1` with `"spec": {"author": "team-x"}`
- **THEN** `GET /v1/applications/public/a1` has `author` equal to the admin's display name and
  `GET /v1/toolsets/public/t1` has `author` `team-x`

### Requirement: Author of an application or toolset on update
An update of an application or toolset that sends `author` SHALL store it as sent, `""` included. An update that sends
none SHALL keep the stored one. The audit author SHALL not change with the body.

#### Scenario: Update without author keeps it
- **WHEN** a user saves a toolset with `"author": "team-x"`, then sends `PUT` of it again without `author`
- **THEN** a `GET` of it has `author` `team-x`

#### Scenario: Update with author replaces it
- **WHEN** a user saves an application with `"author": "team-x"`, then sends `PUT` of it again with `"author": "team-y"`
- **THEN** a `GET` of it has `author` `team-y`

### Requirement: Author of an application or toolset on read
A read of an application or toolset (a `GET` of the resource or the Configuration API read) SHALL return the stored
`author`, `""` included. When none is stored, as for an entity saved before this requirement, it SHALL return the audit
author instead; with neither, no `author`.

#### Scenario: Entity saved before the change
- **WHEN** a stored application has no `author` in its body and the audit author `legacy-user`, and a caller with `READ`
  sends `GET` of it
- **THEN** the answer has `author` `legacy-user`

#### Scenario: Entity with no author at all
- **WHEN** a stored toolset has no `author` in its body and no audit author, and a caller with `READ` sends `GET` of it
- **THEN** the answer has no `author`

### Requirement: Owner of an application or toolset in the OpenAI-style API
The `owner` of an application or toolset in `GET /openai/applications`, `GET /openai/applications/{id}`,
`GET /openai/toolsets`, `GET /openai/toolsets/{id}` and `GET /v1/deployments` SHALL be the `author` a read of it
returns, `""` included, and absent when a read returns none.

#### Scenario: Listings show the stored author
- **WHEN** a user saved a toolset in the own bucket with `"author": "original-dev"`, and sends `GET /openai/toolsets`
  and `GET /v1/deployments`
- **THEN** the entry of the toolset has `owner` `original-dev` in both

#### Scenario: Empty author in a listing
- **WHEN** a user saved an application in the own bucket with `"author": ""` and sends `GET /openai/applications`
- **THEN** the entry of the application has `owner` `""`

#### Scenario: Entity saved before the change in a listing
- **WHEN** a stored application has no `author` in its body and the audit author `legacy-user`, and a caller with `READ`
  sends `GET /openai/applications/{id}` for it
- **THEN** the answer has `owner` `legacy-user`
