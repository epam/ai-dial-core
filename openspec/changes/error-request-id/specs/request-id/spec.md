# Spec Delta

## Purpose

The id Core gives a client on an error answer: the request id at the end of the error message and the
`X-Request-ID` header, so that a user can give an admin the id and the admin can find the request in Core's logs and
traces.

## ADDED Requirements

### Requirement: Request id in an error message
The request id applies to an error answer (status 400 or above) that "Error answers covered" lists, when
`tracing.errorRequestId` is true and the request has a valid trace id. Core SHALL then end its message with
` (DIAL request id: <trace id>)`, and an answer that is not chunked SHALL carry a `Content-Length` that matches the new
body. The trace id is the one Core logs the request under. In the scenarios, `<trace id>` is the answer's
`X-DIAL-TRACE-ID`, with `tracing.responseTraceHeaders` on.

#### Scenario: Core's own plain-text error
- **WHEN** a client sends `POST /anthropic/v1/messages` with `"model": "missing"`, and no deployment has that name
- **THEN** Core answers `404` with the body `Unknown deployment: missing (DIAL request id: <trace id>)` and a
  `Content-Length` of that body

#### Scenario: Core's own JSON error
- **WHEN** a client sends a chat completion to a model whose endpoint points at a port nothing listens on
- **THEN** Core answers `502` with
  `{"error":{"message":"Failed to connect to upstream server (DIAL request id: <trace id>)","display_message":"Failed to connect to upstream server","code":"BAD_GATEWAY"}}`

#### Scenario: Error before authentication
- **WHEN** a client sends `POST /openai/deployments/m/chat/completions` with neither `API-KEY` nor `Authorization`
- **THEN** Core answers `401` with the body
  `At least API-KEY or Authorization header must be provided (DIAL request id: <trace id>)`

### Requirement: Error answers covered
Core SHALL add the request id to its own error answers to a client, to a router's 4xx answer that it relays, to an
MCP error answer with a JSON body, and to the error answers that it relays from the origin of `/chat/completions`,
Anthropic Messages, Messages count tokens and the Responses create routes. The empty answers of `GET /health` and of
the well-known MCP resource metadata are not covered. Other origins' error answers SHALL be relayed as today.

#### Scenario: Router's 4xx answer
- **WHEN** a client sends a chat completion to a router in decision mode, and the router answers the decide request
  `400` with `{"error": {"message": "state either tier or target"}}`
- **THEN** the client gets `400` with `{"error":{"message":"state either tier or target (DIAL request id: <trace id>)"}}`

#### Scenario: MCP error answer
- **WHEN** the MCP server of a toolset answers a client's MCP `POST` with `400`, `Content-Type: application/json` and
  `{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Invalid request"}}`
- **THEN** the client gets `400` with that body, except that `error.message` is
  `Invalid request (DIAL request id: <trace id>)`

#### Scenario: Error answer to a non-streaming Responses request
- **WHEN** a client sends `{"model":"m"}` to `POST /openai/v1/responses` and the origin answers `400` with
  `{"error":{"message":"bad"}}`
- **THEN** the client gets `400` with `{"error":{"message":"bad (DIAL request id: <trace id>)"}}`

#### Scenario: Error answer of count tokens
- **WHEN** the origin of `POST /anthropic/v1/messages/count_tokens` answers `400` with
  `{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}`
- **THEN** the client gets `400` with
  `{"type":"error","error":{"type":"invalid_request_error","message":"bad (DIAL request id: <trace id>)"}}`

#### Scenario: Embeddings error relayed as today
- **WHEN** the origin of `POST /openai/deployments/m/embeddings` answers `400` with `{"error":{"message":"bad"}}`
- **THEN** the client gets that body byte for byte, chunked, and no `X-Request-ID` from Core

### Requirement: Error answer to a streaming request read whole
On a streaming Messages or Responses request, when the request id applies, Core SHALL read an error answer whose
media type is not `text/event-stream` whole, and SHALL send it with a `Content-Length`, not chunked.

#### Scenario: Error answer to a streaming Messages request
- **WHEN** a client sends `POST /anthropic/v1/messages` with `"stream": true` and the origin answers `400` with
  `Content-Type: application/json` and
  `{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 250000 tokens > 200000 maximum"}}`
- **THEN** the client gets `400` with
  `{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 250000 tokens > 200000 maximum (DIAL request id: <trace id>)"}}`,
  a `Content-Length` of that body and no `Transfer-Encoding: chunked`

#### Scenario: Error answer to a streaming Responses request
- **WHEN** a client sends `{"model":"m","stream":true}` to `POST /openai/v1/responses` and the origin answers `400`
  with `Content-Type: application/json` and `{"error":{"message":"bad"}}`
- **THEN** the client gets `400` with `{"error":{"message":"bad (DIAL request id: <trace id>)"}}`, a
  `Content-Length` of that body and no `Transfer-Encoding: chunked`

### Requirement: Message of a JSON or plain-text body
The message SHALL be `error.message` when the body is a JSON object whose `error.message` is a string, and the whole
body when the body is plain text. The media type decides, ignoring parameters such as `charset`: `application/json`,
or one ending in `+json`, is JSON, and `text/plain` is plain text. With no `Content-Type`, a body that starts with `{`
is JSON and any other body is plain text. Trailing whitespace of plain text SHALL be dropped before the suffix.

#### Scenario: Plain text from the origin
- **WHEN** the origin of a `/chat/completions` request answers `403` with `Content-Type: text/plain; charset=utf-8`
  and the body `Access denied\n`
- **THEN** the client gets `403` with the body `Access denied (DIAL request id: <trace id>)`

#### Scenario: Other JSON members kept
- **WHEN** the origin of a non-streaming `/chat/completions` request answers `400` with
  `{"error":{"message":"bad","code":"invalid_value"},"param":"temperature"}`
- **THEN** the client gets `400` with
  `{"error":{"message":"bad (DIAL request id: <trace id>)","code":"invalid_value"},"param":"temperature"}`

### Requirement: Bodies left unchanged
Core SHALL send these bodies unchanged: an empty body, a body of another media type (such as `text/html`), a body
that still has a `Content-Encoding` other than `identity` after Core's HTTP client has decoded gzip and deflate, a body
over 64 KiB, and a JSON body whose `error.message` is not a string. An answer below 400 or of media type
`text/event-stream` SHALL NOT be changed and SHALL get no `X-Request-ID` from Core.

#### Scenario: HTML error page
- **WHEN** the origin of a `/chat/completions` request answers `400` with `Content-Type: text/html`
- **THEN** the client gets that body byte for byte

#### Scenario: JSON without a string error message
- **WHEN** the origin of a `/chat/completions` request answers `422` with `{"detail":"bad input"}`
- **THEN** the client gets that body byte for byte

#### Scenario: Encoding Core did not decode
- **WHEN** the origin of a `/chat/completions` request answers `400` with `Content-Type: text/plain` and
  `Content-Encoding: x-custom`
- **THEN** the client gets that body byte for byte, with `Content-Encoding: x-custom`

#### Scenario: Success answer untouched
- **WHEN** the origin answers a non-streaming `POST /anthropic/v1/messages` with `200` and a JSON body
- **THEN** the client gets that body byte for byte and no `X-Request-ID` from Core

#### Scenario: Event-stream error answer
- **WHEN** a client sends a streaming `/chat/completions` request and the origin answers `400` with
  `Content-Type: text/event-stream`
- **THEN** the client gets the events unchanged and no `X-Request-ID` from Core

### Requirement: One request id per message
Core SHALL NOT add the suffix to a body that already contains ` (DIAL request id: <trace id>)` with the same trace id,
so an answer relayed through several Core passes of one request carries the id once.

#### Scenario: Error through an alias
- **WHEN** a client calls `POST /openai/deployments/a/chat/completions`, where `a` is an alias of the model `m`, and
  the origin of `m` answers `400` with `{"error":{"message":"bad"}}`
- **THEN** the client gets `400` with `{"error":{"message":"bad (DIAL request id: <trace id>)"}}`, with the suffix once

### Requirement: X-Request-ID on an error answer
When the request id applies to an error answer, Core SHALL set `X-Request-ID` to the trace id, whatever the body,
unless the answer already carries `X-Request-ID`. An `X-Request-ID` that the origin sent SHALL be kept.

#### Scenario: Core's own error
- **WHEN** a client sends `POST /anthropic/v1/messages` with `"model": "missing"`
- **THEN** the `404` answer carries `X-Request-ID` equal to `<trace id>`

#### Scenario: Origin's own id kept
- **WHEN** the origin of a `/chat/completions` request answers `400` with `X-Request-ID: up-1`
- **THEN** the client's answer carries `X-Request-ID: up-1` and the request id in its message

#### Scenario: Empty error body
- **WHEN** the origin of a `/chat/completions` request answers `403` with an empty body
- **THEN** the client gets `403` with an empty body and `X-Request-ID` equal to `<trace id>`

### Requirement: Other capabilities' error bodies
Where another capability quotes an exact error body or message, or says that Core relays an error body unchanged,
that text is what Core sends with `tracing.errorRequestId` false. With the setting true, Core SHALL add the request
id to those of the answers that "Error answers covered" lists, as this capability says.

#### Scenario: Body size limit
- **WHEN** a client sends a body over the size limit, which request-routing answers with `413` and the body
  `Request body is too large`
- **THEN** with `tracing.errorRequestId` true, Core answers `413` with
  `Request body is too large (DIAL request id: <trace id>)`

### Requirement: Request id setting
The bundled `aidial.settings.json` SHALL set `tracing.errorRequestId` to true. When the `tracing` section omits it,
it SHALL be false. When it is false, or the request has no valid trace id, Core SHALL answer errors as it did before
this setting existed: no suffix, no `X-Request-ID` from Core, and an error answer to a streaming Messages or Responses
request relayed as it arrives.

#### Scenario: Setting off
- **WHEN** `tracing.errorRequestId` is false and a client sends `POST /anthropic/v1/messages` with
  `"model": "missing"`
- **THEN** Core answers `404` with the body `Unknown deployment: missing` and no `X-Request-ID`

#### Scenario: No trace id
- **WHEN** `tracing.errorRequestId` is true and the trace id Core holds for the request is all zeros
- **THEN** Core answers the error with its body unchanged and no `X-Request-ID`
