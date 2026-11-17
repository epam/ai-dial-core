# Spec Delta

## ADDED Requirements

### Requirement: Target pass served in process
For a call of an alias of type `model`, `application` or `router` through a deployments-POST action,
`/openai/v1/chat/completions`, `/openai/v1/responses` without `background: true`, or `/anthropic/v1/messages`, Core
SHALL serve the target pass in process: no HTTP request SHALL reach Core's own address (`backgroundJob.localBaseUrl`).
Every other request an alias forwards SHALL reach the target over HTTP to that address.

#### Scenario: Chat completion without Core's own address
- **WHEN** `backgroundJob.localBaseUrl` names a port nothing listens on, and a caller sends a chat completion to
  `/openai/deployments/assistant/chat/completions`, where `assistant` targets `gpt-4-internal`
- **THEN** the answer is `200` with the model's answer

#### Scenario: Responses and Messages without Core's own address
- **WHEN** `backgroundJob.localBaseUrl` names a port nothing listens on, and a caller sends `/openai/v1/responses`
  and then `/anthropic/v1/messages` with `model: multi-alias`, whose target serves both interfaces
- **THEN** both answer `200`, and the target receives each request with `model` set to the target name

#### Scenario: Call chain unchanged
- **WHEN** a caller sends a chat completion to `assistant`, which targets `gpt-4-internal`
- **THEN** the per-request key the model receives has `executionPath` `["assistant", "gpt-4-internal"]`, and Core
  writes one analytics row for `assistant` and one for `gpt-4-internal`

#### Scenario: Router in decision mode behind an alias
- **WHEN** `backgroundJob.localBaseUrl` names a port nothing listens on, an alias with `presentAs: model` targets a
  router in decision mode, and the router names as its candidate an alias whose target is a model
- **THEN** the model receives the request and the caller gets `200` with its answer

#### Scenario: Requests that stay on HTTP
- **WHEN** `backgroundJob.localBaseUrl` names a port nothing listens on, and a caller posts to
  `/v1/deployments/<alias>/tokenize` of an alias whose target has a tokenize endpoint, and then sends
  `/openai/v1/responses` with `background: true` to an alias whose target serves Responses
- **THEN** both answer `502`, and the target's endpoints receive nothing

### Requirement: Request body in the alias pass's row
When Core serves the target pass in process and the alias pass received the request body from its caller, the alias
pass's analytics row SHALL hold that body as `request.body`, unchanged. The target pass's row SHALL hold the body the
target pass sent to its origin.

#### Scenario: Messages through an alias
- **WHEN** a caller with a key that is not `secured` sends `/anthropic/v1/messages` with `model: multi-alias`
- **THEN** the alias's row has `request.body` with `"model": "multi-alias"`, and the target's row has `request.body`
  with `model` set to the target name
