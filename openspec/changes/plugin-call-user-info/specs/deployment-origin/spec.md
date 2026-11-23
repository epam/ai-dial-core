## ADDED Requirements

### Requirement: Caller's user info through the host
An origin plugin SHALL be able to read the user info of the request it answers through its host. Core SHALL serve a
`GET /v1/user/info` call without a body as `GET /v1/user/info` sent with the key the plugin's calls carry, and SHALL
hand the plugin the answer as it hands the answer of any call. Core SHALL refuse a call without a method, a `GET` of
any other path, a method other than `POST` and `GET`, and a `GET` with a body: the call fails, as a call outside the
create operations does.

#### Scenario: Caller with an API key
- **WHEN** a client with API key `proxyKey1` (project `EPM-RTC-GPT`, role `default`) sends a chat completion to `app`,
  served by plugin `p1`, and `p1` calls `GET /v1/user/info` through its host
- **THEN** `p1` gets the answer `200` `{"roles":["default"],"project":"EPM-RTC-GPT","userDisplayName":"EPM-RTC-GPT"}`,
  the one that client gets from `GET /v1/user/info` over HTTP

#### Scenario: Caller with a JWT
- **WHEN** a client authenticated by a JWT with user id `user`, role `user`, claim `title: "Manager"` and display name
  `user user` sends a chat completion to `app`, and `p1` calls `GET /v1/user/info` through its host
- **THEN** `p1` gets the answer `200`
  `{"roles":["user"],"userId":"user","userClaims":{"title":["Manager"]},"userDisplayName":"user user"}`, the one that
  client gets from `GET /v1/user/info` over HTTP

#### Scenario: User info after the plugin's pass ended
- **WHEN** `p1`'s pass has ended and its analytics row is written, and `p1` then calls `GET /v1/user/info` through the
  host it kept
- **THEN** that call's answer is `401` with the message `Unknown api key`

#### Scenario: Other reads, other methods and a GET with a body
- **WHEN** `p1` calls, through its host, `GET /v1/user/limits`, `DELETE /v1/user/info`, or `GET /v1/user/info` with a
  body
- **THEN** each call fails, and the client gets `502` with the message `No route`
