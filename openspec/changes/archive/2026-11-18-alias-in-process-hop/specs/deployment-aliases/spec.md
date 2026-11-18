# Spec Delta

## ADDED Requirements

### Requirement: Target pass served in process
For a call of an alias of type `model`, `application` or `router` through a deployments-POST action,
`/openai/v1/chat/completions`, `/openai/v1/responses` or `/openai/responses` without `background: true`, or
`/anthropic/v1/messages`, Core SHALL serve the target pass in process, without an HTTP request to itself. Every other
request an alias forwards, Messages `count_tokens` included, SHALL reach the target over HTTP to Core's own address
(`backgroundJob.localBaseUrl`).

#### Scenario: Chat completion through an alias
- **WHEN** a caller sends a chat completion to `/openai/deployments/assistant/chat/completions`, where `assistant`
  targets `gpt-4-internal`
- **THEN** the answer is `200` with the model's answer, and Core sends no HTTP request to itself

#### Scenario: Responses and Messages through an alias
- **WHEN** a caller sends `/openai/v1/responses`, `/openai/responses` and `/anthropic/v1/messages` with
  `model: multi-alias`, whose target serves both interfaces
- **THEN** all three answer `200`, the target receives each request with `model` set to the target name, and Core
  sends no HTTP request to itself

#### Scenario: Call chain unchanged
- **WHEN** a caller sends a chat completion to `assistant`, which targets `gpt-4-internal`
- **THEN** the per-request key the model receives has `executionPath` `["assistant", "gpt-4-internal"]`, and Core
  writes one analytics row for `assistant` and one for `gpt-4-internal` with `parent_deployment: assistant`

#### Scenario: Router in decision mode behind an alias
- **WHEN** an alias with `presentAs: model` targets a router in decision mode, and the router names as its candidate
  an alias whose target is a model
- **THEN** the model receives the request, the caller gets `200` with its answer, and Core sends no HTTP request to
  itself

#### Scenario: Requests that stay on HTTP
- **WHEN** `backgroundJob.localBaseUrl` names a port nothing listens on, and a caller posts to
  `/v1/deployments/<alias>/tokenize` of an alias whose target has a tokenize endpoint, sends
  `/anthropic/v1/messages/count_tokens` with `model: multi-alias`, and sends `/openai/v1/responses` with
  `background: true` to an alias whose target serves Responses
- **THEN** all three answer `502`, and the target's endpoints receive nothing

### Requirement: Headers of an in-process alias call
When Core serves the target pass in process, the target's origin SHALL NOT receive the alias pass's
`X-DIAL-OVERRIDE-NAME`; it receives one only when the target declares an override name of its own. The caller's
answer SHALL carry `Access-Control-Allow-Origin`, and `X-DIAL-TRACE-ID` and `X-DIAL-SPAN-ID` when response trace
headers are on, once each.

#### Scenario: No override name from the alias pass
- **WHEN** a caller sends a chat completion to `assistant`, and `gpt-4-internal` declares no override name
- **THEN** the request the model's origin receives has no `X-DIAL-OVERRIDE-NAME`, and has the `X-DIAL-DEPLOYMENT-ID`
  `gpt-4-internal`

#### Scenario: Response headers once
- **WHEN** a caller sends a chat completion to `assistant`
- **THEN** the answer has exactly one `Access-Control-Allow-Origin` header

### Requirement: Attachments shared on through an alias
When Core serves the target pass in process and the target pass shares a file of its answer with the alias pass,
the alias pass SHALL share that file on with its own caller's per-request key, as a direct call of the target would.

#### Scenario: Application calls an alias
- **WHEN** application `app` calls `assistant` with its per-request key, and the answer of `gpt-4-internal` attaches a
  file that the model's pass may read
- **THEN** `app`'s per-request key may read that file after the call, as after a direct call of `gpt-4-internal`
