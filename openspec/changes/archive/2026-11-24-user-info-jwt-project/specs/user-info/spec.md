# Spec Delta

## Purpose

Describe what `GET /v1/user/info` returns about the caller: its roles and project, and for a caller with a JWT its
user id, token claims and display name.

## ADDED Requirements

### Requirement: Caller identity in user info
`GET /v1/user/info` SHALL answer `roles`, the caller's roles. For an API-key caller it SHALL add `project` and
`userDisplayName`, both the key's project. For a JWT caller it SHALL add `userId`, the user id the identity provider
resolved, `userClaims`, every token claim with a string value turned into a one-element array, and `userDisplayName`
when the provider resolved one. A per-request key SHALL answer as the caller that started the chain.

#### Scenario: API-key caller
- **WHEN** a client with API key `proxyKey1` (project `EPM-RTC-GPT`, role `default`) sends `GET /v1/user/info`
- **THEN** the answer is `{"roles": ["default"], "project": "EPM-RTC-GPT", "userDisplayName": "EPM-RTC-GPT"}`

#### Scenario: JWT caller
- **WHEN** a client whose token carries user id `user`, role `user`, claim `title: "Manager"`, display name
  `user user` and no project sends `GET /v1/user/info`
- **THEN** the answer is `{"roles": ["user"], "userId": "user", "userClaims": {"title": ["Manager"]},
  "userDisplayName": "user user"}`

#### Scenario: JWT caller without a display name
- **WHEN** a client whose token carries only `azp: "svc"`, resolved as user id `svc` with no roles, sends
  `GET /v1/user/info`
- **THEN** the answer is `{"roles": [], "userId": "svc", "userClaims": {"azp": ["svc"]}}`

### Requirement: Project of a JWT caller in user info
For a JWT caller the answer SHALL also have `project`, the project the identity provider's `projectPath` resolved from
the token. When the provider has no `projectPath` or the token has no string claim at that path, the answer SHALL have
no `project` field.

#### Scenario: Token with a project
- **WHEN** a client whose token carries user id `user`, role `user`, claim `title: "Manager"`, display name
  `user user` and project `projA` sends `GET /v1/user/info`
- **THEN** the answer has `project: "projA"` and the fields of the JWT caller scenario: `roles: ["user"]`,
  `userId: "user"`, `userClaims: {"title": ["Manager"]}`, `userDisplayName: "user user"`

#### Scenario: Token without a project
- **WHEN** the client's token carries no project
- **THEN** the answer has no `project` field

#### Scenario: Per-request key of a JWT caller with a project
- **WHEN** an application calls `GET /v1/user/info` with the per-request key of a call started by a client whose token
  carries user id `user` and project `projA`
- **THEN** the answer has `project: "projA"` and `userId: "user"`
