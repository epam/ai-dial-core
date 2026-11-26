## ADDED Requirements

### Requirement: Core's API through the host
An origin plugin SHALL be able to call through its host any route Core serves once it has authenticated a request.
Core SHALL serve a call outside the create operations as that request, with its method, path, query, headers and JSON
body, sent with the key the plugin's calls carry, SHALL answer a body longer than `resources.maxSize` `413`, and SHALL
hand the plugin the answer as for any call. A call without a method or a uri, a file upload or a WebSocket fails.

#### Scenario: Caller with an API key
- **WHEN** a client with API key `proxyKey1` (project `EPM-RTC-GPT`, role `default`) sends a chat completion to `app`,
  served by plugin `p1`, and `p1` calls `GET /v1/user/info` through its host
- **THEN** `p1` gets the answer `200`
  `{"roles":["default"],"project":"EPM-RTC-GPT","costObject":"EPM-RTC-GPT","costObjectSource":"key","userDisplayName":"EPM-RTC-GPT"}`,
  the one that client gets from `GET /v1/user/info` over HTTP

#### Scenario: Caller with a JWT
- **WHEN** a client authenticated by a JWT with user id `user`, role `user`, claim `title: "Manager"` and display name
  `user user` sends a chat completion to `app`, and `p1` calls `GET /v1/user/info` through its host
- **THEN** `p1` gets the answer `200`
  `{"roles":["user"],"userId":"user","userClaims":{"title":["Manager"]},"costObject":"ORG","costObjectSource":"default","userDisplayName":"user user"}`,
  the one that client gets from `GET /v1/user/info` over HTTP

#### Scenario: User info after the plugin's pass ended
- **WHEN** `p1`'s pass has ended and its analytics row is written, and `p1` then calls `GET /v1/user/info` through the
  host it kept
- **THEN** that call's answer is `401` with the message `Unknown api key`

#### Scenario: Write, read and delete a resource
- **WHEN** a client with API key `proxyKey1` sends three chat completions to `app`, and `p1` calls through its host, one
  per request, `PUT /v1/conversations/<bucket>/appdata/app/c1` with the body of the client's request, `GET` of that
  path, and `DELETE` of it, where `<bucket>` is the bucket of `proxyKey1`
- **THEN** the `PUT` is answered `200` and the client's `GET` of the path over HTTP answers that body, the call's `GET`
  answers `200` with that body, and after the `DELETE` the client's `GET` answers `404`

#### Scenario: Body over the size limit
- **WHEN** `p1` calls `PUT /v1/conversations/<bucket>/appdata/app/c1` through its host with a body longer than
  `resources.maxSize`
- **THEN** that call's answer is `413` with the message `Request body is too large`, and no conversation is written

#### Scenario: File upload or WebSocket
- **WHEN** `p1` calls `PUT /v1/files/<bucket>/f.txt` with `Content-Type: multipart/form-data`, or a route with
  `Upgrade: websocket`, through its host
- **THEN** the call fails, and no controller serves it

## MODIFIED Requirements

### Requirement: Calling a deployment from an origin plugin
An origin plugin SHALL be able to call a deployment while it answers a request. Core SHALL serve a `POST` the plugin
names to a deployments-POST action, `/openai/v1/chat/completions`, `/openai/v1/responses` or `/openai/responses` (not
in background), or `/anthropic/v1/messages`, with the body tree the plugin passes (a call without one fails),
authenticated by the key of the request the plugin answers, and SHALL hand the plugin the answer: status and headers
first, then the body as it arrives.

#### Scenario: Call reaches a model
- **WHEN** application `app` is served by plugin `p1` of type `test-origin`, a client sends a chat completion to
  `app` with `X-Test-Call: /openai/deployments/<model>/chat/completions`, and `<model>` is served by plugin `p2`
- **THEN** `p2` receives `POST /openai/deployments/<model>/chat/completions` with the body `p1` passed, and the client
  gets `p2`'s answer

#### Scenario: Call to a model behind an HTTP origin
- **WHEN** the call names a model served by an HTTP origin
- **THEN** that origin receives the chat completion with the body `p1` passed, an `API-KEY` holding a per-request key,
  and a `traceparent` of the client's trace

#### Scenario: Unknown target
- **WHEN** `p1` calls `/openai/deployments/missing/chat/completions`
- **THEN** `p1` gets the answer `404`, and the client gets that `404`

#### Scenario: Target the caller may not use
- **WHEN** `p1` calls a model whose `userRoles` the caller's roles do not include
- **THEN** `p1` gets the answer `403`, and the client gets that `403`

#### Scenario: Target with an interceptor
- **WHEN** `p1` calls a model that declares an interceptor served by an HTTP origin
- **THEN** the interceptor receives the body `p1` passed

#### Scenario: Responses and Messages calls
- **WHEN** `p1` calls `/openai/v1/responses`, and then `/anthropic/v1/messages`, with `"model": "<model>"` in the body
- **THEN** `p2` receives `POST /openai/v1/responses`, and then `POST /anthropic/v1/messages`

#### Scenario: Target fails mid-stream
- **WHEN** `p1` relays the streamed answer of a call to a model whose HTTP origin closes its connection mid-stream
- **THEN** the answer of `p1`'s call fails, and the client's connection is closed before the answer completes

#### Scenario: Background response
- **WHEN** `p1` calls `/openai/v1/responses` with `"background": true`
- **THEN** the call fails, no deployment is called and no row is written for it, and the client gets `502` with the
  message `No route`

#### Scenario: Call without a body
- **WHEN** `p1` calls `/openai/v1/chat/completions` through its host with no body
- **THEN** the call fails, and no deployment is called
