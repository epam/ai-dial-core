## MODIFIED Requirements

### Requirement: Approving a publication
On approve Core SHALL set `"status":"APPROVED"`, drop it from `publications/public/`, store its `rules` under its
`targetFolder`, copy review copies to their `targetUrl`s with `displayAuthor` as author, delete the review copies and the
`DELETE` targets, notify the author `Your request has been approved by admin`, and answer `200` with the publication.
For a published application or toolset `displayAuthor`, when the publication has one, SHALL also be its `author`, as
`GET` and `owner` of its listing entry report it; without `displayAuthor` the copy keeps the `author` the source reports.

#### Scenario: Published under rules
- **WHEN** an admin approves a publication of `conversations/public/folder/conversation` with rules
  `[{"function":"EQUAL","source":"roles","targets":["user"]}]`
- **THEN** the review copy answers `404`, a JWT with role `user` gets `200` on the target, and `proxyKey1` gets `403`

#### Scenario: Published application shows the display author
- **WHEN** a user saved an application with `"author": "original-dev"`, and an admin approves its publication with
  `"displayAuthor": "dream-team"`
- **THEN** `GET /v1/applications/public/<name>` has `author` `dream-team` and its entry in `GET /openai/applications`
  has `owner` `dream-team`

#### Scenario: Published toolset without a display author
- **WHEN** a user saved a toolset with `"author": "original-dev"`, and an admin approves its publication without
  `displayAuthor`
- **THEN** `GET /v1/toolsets/public/<name>` has `author` `original-dev`
