## MODIFIED Requirements

### Requirement: Caller in the plugin request
With each request Core SHALL hand an origin plugin the caller's identity: the user id of a caller with a JWT, the
caller's project, and the caller's roles. The project SHALL be the key's project for a caller with an API key, and for
a caller with a JWT the project its identity provider's `projectPath` resolved from the token, none when it resolved
none.

#### Scenario: Caller with an API key
- **WHEN** a client with API key `proxyKey1` (project `EPM-RTC-GPT`, role `default`) sends a chat completion to an
  application served by plugin `p1`
- **THEN** `p1` receives project `EPM-RTC-GPT`, roles `["default"]` and no user id

#### Scenario: Caller with a JWT
- **WHEN** a client authenticated by a JWT without a project sends the same request
- **THEN** `p1` receives the token's user id and the roles it grants, and no project

#### Scenario: Caller with a JWT that carries a project
- **WHEN** a client whose token carries user id `user`, role `user` and project `projA` sends the same request
- **THEN** `p1` receives user id `user`, roles `["user"]` and project `projA`
