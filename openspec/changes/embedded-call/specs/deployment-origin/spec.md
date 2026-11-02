## ADDED Requirements

### Requirement: Calling a deployment from an origin plugin
An origin plugin SHALL be able to call a deployment while it answers a request. Core SHALL serve a `POST` the plugin
names to a deployments-POST action, `/openai/v1/chat/completions`, `/openai/v1/responses` (not in background) or
`/anthropic/v1/messages`, with the body tree the plugin passes, as a request authenticated by the per-request key of
the plugin's pass, and SHALL hand the plugin the answer: status and headers first, then the body as it arrives.

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

#### Scenario: Call outside the create operations
- **WHEN** `p1` calls `/v1/ops/resource/share/list`, or `/openai/v1/responses` with `"background": true`
- **THEN** the call fails without reaching a controller, and the client gets `502` with the message `No route`

### Requirement: Calls end with the plugin's pass
When the plugin's pass ends, Core SHALL cancel the answers of its calls that are still open, and SHALL answer a call
made after that `401` with the message `Unknown api key`.

#### Scenario: Call after the plugin's pass ended
- **WHEN** `p1` answers the client and then makes a call
- **THEN** that call's answer is `401`

### Requirement: Caller in the plugin request
With each request Core SHALL hand an origin plugin the caller's identity: the user id of a caller with a JWT, the
project of a caller with an API key, and the caller's roles.

#### Scenario: Caller with an API key
- **WHEN** a client with API key `proxyKey1` (project `EPM-RTC-GPT`, role `default`) sends a chat completion to an
  application served by plugin `p1`
- **THEN** `p1` receives project `EPM-RTC-GPT`, roles `["default"]` and no user id

#### Scenario: Caller with a JWT
- **WHEN** a client authenticated by a JWT sends the same request
- **THEN** `p1` receives the token's user id and the roles it grants

### Requirement: Cancelling a call
When Core cancels the plugin's answer ("Client gone from an embedded origin"), or the plugin cancels the answer of a
call, Core SHALL end the child pass as one whose client went away ("Client gone during the relay").

#### Scenario: Client gone while a call streams
- **WHEN** `p1`, serving application `app`, relays the streamed answer of a call to application `app2`, served by
  plugin `p2`, and the client of `app`'s pass closes its connection
- **THEN** both `p1`'s answer and `p2`'s answer are cancelled

#### Scenario: Plugin cancels a call
- **WHEN** `p1` cancels the answer of its call to `app2` after the first event and answers the client itself
- **THEN** `p2`'s answer is cancelled, and the client gets `p1`'s answer
