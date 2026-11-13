# Spec Delta

## MODIFIED Requirements

### Requirement: Calling a deployment from an origin plugin
An origin plugin SHALL be able to call a deployment while it answers a request. Core SHALL serve a `POST` the plugin
names to a deployments-POST action, `/openai/v1/chat/completions`, `/openai/v1/responses` or `/openai/responses` (not
in background), or `/anthropic/v1/messages`, with the body tree the plugin passes, as a request authenticated by the
key of the request the plugin answers, and SHALL hand the plugin the answer: status and headers first, then the body
as it arrives.

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

#### Scenario: Call outside the create operations
- **WHEN** `p1` calls `/v1/ops/resource/share/list`, or `/openai/v1/responses` with `"background": true`
- **THEN** the call fails, no deployment is called and no row is written for it, and the client gets `502` with the
  message `No route`

### Requirement: Calls end with the plugin's pass
When the plugin's pass ends, or, for a router's decide request, when the decide call ends (router-deployment),
Core SHALL cancel the answers of its calls that are still open, and SHALL answer a call
made after that `401` with the message `Unknown api key`.

#### Scenario: Call after the plugin's pass ended
- **WHEN** `p1`'s pass has ended and its analytics row is written, and `p1` then makes a call
- **THEN** that call's answer is `401`

### Requirement: Client gone before the plugin answers
When the client of a pass that an origin plugin serves goes away before the plugin answers, Core SHALL tell the plugin
that the request is cancelled. When the plugin then fails its answer, Core SHALL end the pass without trying another
upstream and without an analytics row; an answer it still gives SHALL be handled as an HTTP origin's answer after its
client went away. This SHALL NOT apply to a router's decide and notice requests, which router-deployment governs.

#### Scenario: Plugin told
- **WHEN** a client sends a chat completion to `app`, served by plugin `p1`, and closes its connection before `p1`
  answers
- **THEN** `p1` is told its request is cancelled

#### Scenario: No retry after the plugin fails
- **WHEN** the same happens for a model with two upstreams served by `p1`, and `p1` then fails its answer
- **THEN** `p1` is called once, and no analytics row is written
