# Spec Delta

## MODIFIED Requirements

### Requirement: Caller identity in user info
`GET /v1/user/info` SHALL answer `roles`, the caller's roles. For an API-key caller it SHALL add `project` and
`userDisplayName`, both the key's project. For a JWT caller it SHALL add `userId`, the user id the identity provider
resolved, `userClaims`, every token claim with a string value turned into a one-element array, and `userDisplayName`
when the provider resolved one. A per-request key SHALL answer as the caller that started the chain.

#### Scenario: API-key caller
- **WHEN** a client with API key `proxyKey1` (project `EPM-RTC-GPT`, role `default`) sends `GET /v1/user/info`
- **THEN** the answer is `{"roles": ["default"], "project": "EPM-RTC-GPT", "costObject": "EPM-RTC-GPT",
  "costObjectSource": "key", "userDisplayName": "EPM-RTC-GPT"}`

#### Scenario: JWT caller
- **WHEN** a client whose token carries user id `user`, role `user`, claim `title: "Manager"`, display name
  `user user` and no project sends `GET /v1/user/info`
- **THEN** the answer is `{"roles": ["user"], "userId": "user", "userClaims": {"title": ["Manager"]},
  "costObject": "ORG", "costObjectSource": "default", "userDisplayName": "user user"}`

#### Scenario: JWT caller without a display name
- **WHEN** a client whose token carries only `azp: "svc"`, resolved as user id `svc` with no roles, sends
  `GET /v1/user/info`
- **THEN** the answer is `{"roles": [], "userId": "svc", "userClaims": {"azp": ["svc"]}, "costObject": "ORG",
  "costObjectSource": "default"}`

### Requirement: Project of a JWT caller in user info
For a JWT caller the answer SHALL also have `project`, the value the identity provider resolved from the token at its
`costObjectPath` (or `projectPath`, see below). When the provider has neither or the token has no string claim at that
path, the answer SHALL have no `project` field.

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

## ADDED Requirements

### Requirement: Deprecated projectPath setting
A provider without `costObjectPath` SHALL use `projectPath` in its place, and Core SHALL log a deprecation warning for
`projectPath` when it starts. When a provider has both, Core SHALL use `costObjectPath` and log a warning that
`projectPath` is ignored.

#### Scenario: Provider with costObjectPath
- **WHEN** an identity provider has `costObjectPath: "p0"` and a token carries `p0: "project1"`
- **THEN** the caller's project is `project1`

#### Scenario: Provider with only projectPath
- **WHEN** an identity provider has `projectPath: "azp"` and no `costObjectPath`, and a token carries `azp: "project1"`
- **THEN** the caller's project is `project1` and Core logged a deprecation warning for `projectPath`

#### Scenario: Provider with both paths
- **WHEN** an identity provider has `costObjectPath: "p0"` and `projectPath: "azp"`, and a token carries
  `p0: "projA"` and `azp: "projB"`
- **THEN** the caller's project is `projA` and Core logged a warning that `projectPath` is ignored

### Requirement: Cost object in user info
The answer SHALL have `costObject`, the cost object Core charges the caller's calls to, and `costObjectSource`, where it
came from: `claim` when the token has a project, `key` when the caller has an API key, and `default`, with
`spendingLimits.defaultCostObject` as `costObject`, otherwise. Both SHALL follow `project` and come before
`userDisplayName`. A per-request key SHALL answer with the cost object of the caller that started the chain.

#### Scenario: Token with a project
- **WHEN** a client whose token carries user id `user`, role `user`, claim `title: "Manager"`, display name
  `user user` and project `projA` sends `GET /v1/user/info`
- **THEN** the answer is `{"roles": ["user"], "userId": "user", "userClaims": {"title": ["Manager"]},
  "project": "projA", "costObject": "projA", "costObjectSource": "claim", "userDisplayName": "user user"}`

#### Scenario: Per-request key of a JWT caller with a project
- **WHEN** an application calls `GET /v1/user/info` with the per-request key of a call started by a client whose token
  carries project `projA`
- **THEN** the answer has `costObject: "projA"` and `costObjectSource: "claim"`
