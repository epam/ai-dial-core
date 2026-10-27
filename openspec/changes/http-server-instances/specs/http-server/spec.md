# Spec Delta

## Purpose

How Core accepts client connections: the port it listens on, the number of HTTP server instances that share that port,
and what stays the same whatever that number is. What each request then gets belongs to the other capabilities.

## ADDED Requirements

### Requirement: Server instances
Core SHALL start the number of HTTP server instances set by `server.instances` (env `aidial.server.instances`), all
listening on `server.port`, and SHALL start one when the setting is absent. Core SHALL refuse to start when the value
is below 1, with an error that names `server.instances`.

#### Scenario: Setting absent
- **WHEN** Core starts with settings that have no `server.instances`
- **THEN** Core answers requests on `server.port` as it did before the setting existed

#### Scenario: Value below 1
- **WHEN** Core starts with `server.instances: 0`
- **THEN** Core does not start, and the startup error names `server.instances`

### Requirement: Answers do not depend on the instance count
With any number of server instances, Core SHALL answer each request, and send each origin request, as it does with one
instance, whichever instance accepted the client's connection.

#### Scenario: Concurrent streamed requests over four instances
- **WHEN** Core runs with `server.instances: 4` and 32 clients, each on its own connection, send at the same time a
  streamed chat completion to a model whose origin answers with an SSE stream
- **THEN** every client gets `200` and the origin's events in order ending with `data: [DONE]`, and the origin receives
  32 requests

### Requirement: Analytics user hash under concurrent requests
When an identity provider hashes the user's key claim (`obfuscateUserEmail`, on by default), Core SHALL write as
`user.id` of each request's analytics row the hex SHA-256 of `loggingSalt` followed by that request's key claim, also
when tokens of different users are validated at the same time.

#### Scenario: Many users validated at once
- **WHEN** JWTs of 1,000 different users, with `loggingKey: email` and `loggingSalt: salt`, are validated
  concurrently
- **THEN** the user hash of each request is the hex SHA-256 of `salt` followed by that token's `email` claim
