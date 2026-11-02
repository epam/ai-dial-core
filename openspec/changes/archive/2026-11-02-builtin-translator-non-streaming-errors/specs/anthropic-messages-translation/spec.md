## REMOVED Requirements

### Requirement: Non-streaming requests are refused
**Reason**: Core now serves a non-streaming request through the builtin translator; see "Non-streaming answer
translated to a message".
**Migration**: None for clients: a non-streaming request that got a 400 before now gets the translated message.

## MODIFIED Requirements

### Requirement: Upstream request of a builtin translation
Core SHALL `POST` the translated request to the URI `POST /openai/v1/responses` resolves to for the model
(`overridePaths` and a legacy `responsesEndpoint` apply), with `model` = `overrideName` (else the name), the client's
`stream` (`false` when absent), `store: false` and `include: ["reasoning.encrypted_content"]`. It SHALL carry a
per-request `API-KEY` and, for a model with `upstreams`, the upstream's `X-UPSTREAM-ENDPOINT` and `X-UPSTREAM-KEY`.
Core SHALL drop `anthropic-version` and add no `X-DIAL-DEPLOYMENT-ID`.

#### Scenario: Upstream request
- **WHEN** `gpt-builtin` declares `overrideName: gpt-5-upstream` and one upstream with
  `responsesEndpoint: http://responses-upstream/v1/responses` and `key: upstreamKey`, and a client with
  `api-key: proxyKey1` and `anthropic-version: 2023-06-01` sends `{"model": "gpt-builtin", "max_tokens": 100,
  "stream": true, "system": "Be brief", "messages": [{"role": "user", "content": "Knock, knock"}]}`
- **THEN** the origin body has `model: gpt-5-upstream`, `stream: true`, `store: false`,
  `include: ["reasoning.encrypted_content"]`, `instructions: "Be brief"`, `max_output_tokens: 100` and
  `input[0].content[0].text: "Knock, knock"`; the origin request's `API-KEY` is not `proxyKey1`; it carries
  `X-UPSTREAM-ENDPOINT: http://responses-upstream/v1/responses` and `X-UPSTREAM-KEY: upstreamKey`, and no
  `anthropic-version` or `X-DIAL-DEPLOYMENT-ID`

#### Scenario: Non-streaming upstream request
- **WHEN** the same request is sent with `stream: false`, or without a `stream` field
- **THEN** the origin body has `stream: false`, and the rest of the origin request is as above

### Requirement: Truncated or failed stream ends in an error event
A translated stream SHALL send nothing after its `message_stop` or `error`. An upstream stream that ends with no
terminal event, or whose upstream connection fails, SHALL end in an `api_error`, and Core SHALL NOT reset the client
connection for it. A `response.failed`, an `error` event or an event carrying a top-level `error` object SHALL become
an `error` with the upstream's message and the type that "Upstream failure names mapped to Anthropic errors" gives.

#### Scenario: Stream without a terminal event
- **WHEN** the upstream stream ends after text deltas and before `response.completed`
- **THEN** the client's last event is `error` with `type: api_error` and the message
  `The upstream stream ended before the response completed`, and the client gets no `message_stop`

#### Scenario: Failed response
- **WHEN** the upstream sends `response.failed` with `error: {"code": "server_error", "message": "upstream exploded"}`
- **THEN** the client gets an `error` event with `type: api_error` and `message: upstream exploded`, and no event
  after it

#### Scenario: Failure caused by the request
- **WHEN** the upstream sends `response.failed` with an error whose `type` is `invalid_request_error`
- **THEN** the client's `error` event has `type: invalid_request_error`

#### Scenario: Flat error event
- **WHEN** the upstream sends `{"type": "error", "code": "rate_limit_exceeded", "message": "slow down"}`
- **THEN** the client gets an `error` event with `type: rate_limit_error` and `message: slow down`

#### Scenario: DIAL error chunk
- **WHEN** the upstream streams an event whose data is
  `{"error":{"type":"invalid_request_error","message":"prompt is too long"}}`
- **THEN** the client's stream ends with an `error` event of type `invalid_request_error` and message
  `prompt is too long`

#### Scenario: DIAL error chunk without a message
- **WHEN** the upstream streams an event whose data is `{"error":{"code":"server_error"}}`
- **THEN** the client's stream ends with an `error` event of type `api_error` and message
  `An error occurred during streaming`

#### Scenario: Upstream connection fails mid-stream
- **WHEN** the upstream connection fails after Core has sent `message_start` to the client
- **THEN** the client's stream ends with an `error` event of type `api_error` and message
  `The upstream connection failed before the response completed`, and the client connection is not reset

#### Scenario: Incomplete answer is not an error
- **WHEN** the upstream's stream ends with `response.incomplete` and `incomplete_details.reason: "max_output_tokens"`
- **THEN** the client's stream ends with `message_delta` carrying `stop_reason: "max_tokens"`, then `message_stop`,
  and holds no `error` event

### Requirement: Limits of a builtin translation charged once
Core SHALL check the initiator's limits for the model before it sends the translated request. It SHALL charge them
once, on this request, from the translated usage, whose input, cache-read and cache-write tokens add up to the
upstream's input total, for a streamed and a non-streaming answer alike. A `200` answer whose `status` is `failed`,
`queued`, `in_progress` or `cancelled`, and a stream that ends with `response.failed`, SHALL be charged the `usage`
it carries. An upstream answer other than `200` SHALL NOT be charged.

#### Scenario: Charged once
- **WHEN** the upstream reports `input_tokens: 10` and `output_tokens: 8` for a builtin-translated request
- **THEN** the day token usage of the model in `GET /v1/deployments/gpt-builtin/limits` grows by 18

#### Scenario: Limit reached
- **WHEN** the caller has used the model's token limit and sends another builtin-translated request
- **THEN** Core answers `429` and sends nothing upstream

#### Scenario: Non-streaming answer charged once
- **WHEN** a client sends a non-streaming request for `gpt-builtin` and the upstream answers `200` with
  `usage: {input_tokens: 10, output_tokens: 8}`
- **THEN** exactly one request reaches the upstream, and the caller's day token usage of `gpt-builtin` grows by 18

#### Scenario: Failed answer charged
- **WHEN** the upstream answers a non-streaming request `200` with `status: "failed"` and
  `usage: {input_tokens: 12, output_tokens: 7}`
- **THEN** the client gets the error answer, and the caller's day token usage of `gpt-builtin` grows by 19

#### Scenario: Failed stream charged
- **WHEN** the upstream's stream ends with `response.failed` whose `response.usage` is `input_tokens: 10` and
  `output_tokens: 8`
- **THEN** the client's stream ends with an `error` event, and the caller's day token usage of `gpt-builtin` grows
  by 18

## ADDED Requirements

### Requirement: Non-streaming answer translated to a message
For a `POST /anthropic/v1/messages` with `stream` absent or `false` on a model whose `anthropicMessages` interface is
a builtin translator, when the upstream answers `200` with a Responses object whose `status` is not `failed`,
`queued`, `in_progress` or `cancelled` (a missing or `null` status included), Core SHALL answer `200` with one
Anthropic message as a JSON body.

#### Scenario: Non-streaming text answer
- **WHEN** a client sends `POST /anthropic/v1/messages` for `gpt-builtin` with `stream: false`, `max_tokens: 100` and
  one user message, and the upstream answers `200` with a Responses object of status `completed` whose output is one
  `message` item with the `output_text` `Who’s there?`
- **THEN** the origin receives exactly one request, `POST /openai/v1/responses`, and the client gets `200`,
  `Content-Type: application/json` and a message with `type: "message"`, `role: "assistant"`, one `text` block
  `Who’s there?` and `stop_reason: "end_turn"`

#### Scenario: Missing status read as completed
- **WHEN** the upstream answers `200` with a Responses object whose `status` is `null` and whose output is one
  `message` item with the `output_text` `hi`
- **THEN** the client gets `200` and a message with one `text` block `hi` and `stop_reason: "end_turn"`

### Requirement: Non-streaming answer headers
The translated message SHALL carry `Content-Type: application/json`, `Content-Length`, `X-UPSTREAM-ATTEMPTS` and, for
a caller with a day, week or month cost limit, the `anthropic-ratelimit-unified-*` headers of a successful Messages
answer.

#### Scenario: Answer headers
- **WHEN** a client sends a non-streaming request for `gpt-builtin` and the upstream answers `200` on the first attempt
- **THEN** the answer carries `Content-Type: application/json`, `X-UPSTREAM-ATTEMPTS: 1` and a `Content-Length` equal
  to the length of its body

#### Scenario: Spend headers
- **WHEN** a caller at 82% of a day cost limit sends a non-streaming request for a model served by a builtin
  translator, and the upstream answers `200`
- **THEN** the answer carries `anthropic-ratelimit-unified-overage-utilization: 0.82`

### Requirement: Non-streaming message identity and fields
The message SHALL carry a DIAL message id, never the upstream's response id, the Responses `model` or, when the
upstream names none, the `model` the client sent, `stop_sequence: null`, and every field the Python translator's
message carries with a `null` value: `container`, `stop_details`, a `text` block's `citations`, a `tool_use` block's
`caller`, and `usage.cache_creation`, `usage.inference_geo`, `usage.server_tool_use` and `usage.service_tier`.

#### Scenario: DIAL id and upstream model
- **WHEN** the upstream's Responses object has the id `resp_075fcfcf` and the model `gpt-5-upstream`
- **THEN** the message `id` matches `dial_gpt-builtin_[0-9a-f]{32}`, the body does not contain `resp_075fcfcf`, and
  `model` is `gpt-5-upstream`

#### Scenario: Model the upstream does not name
- **WHEN** the upstream's Responses object has no `model`
- **THEN** the message `model` is `gpt-builtin`

#### Scenario: Null-valued fields
- **WHEN** the upstream answers one `output_text` part
- **THEN** the message has `container: null`, `stop_details: null` and `stop_sequence: null`, its `text` block has
  `citations: null`, and its `usage` has `cache_creation`, `inference_geo`, `server_tool_use` and `service_tier` set
  to `null`

### Requirement: Non-streaming message content
The message `content` SHALL hold one block per Responses output part, in output order, as the Python translator's
`from_responses.py` builds it: an `output_text` or `refusal` part gives a `text` block, a `function_call` item a
`tool_use` block, a `reasoning` item the thinking blocks below, and any other item nothing. A message with no block
SHALL get one empty `text` block.

#### Scenario: Text and a function call
- **WHEN** the output is a `message` item with the `output_text` `let me check`, then a `function_call` with
  `call_id: "call_abc"`, `name: "get_weather"` and `arguments: "{\"city\": \"Kyiv\"}"`
- **THEN** `content` is a `text` block `let me check`, then a `tool_use` block with `id: "call_abc"`,
  `name: "get_weather"`, `input: {"city": "Kyiv"}` and `caller: null`

#### Scenario: Aliased tool name restored
- **WHEN** Core sent one of the request's tools upstream under an alias, and the `function_call` names that alias
- **THEN** the `tool_use` block carries the tool name the client sent

#### Scenario: Arguments that are not a JSON object
- **WHEN** a `function_call` has `arguments` that do not parse as a JSON object
- **THEN** its `tool_use` block has `input: {}`

#### Scenario: Refusal part
- **WHEN** the output is a `message` item with a `refusal` part `I can't help with that`
- **THEN** `content` is one `text` block `I can't help with that`

#### Scenario: Empty output
- **WHEN** the output is empty, or holds only a `reasoning` item without text or encrypted content
- **THEN** `content` is one `text` block with `text: ""`

### Requirement: Non-streaming thinking blocks
A `reasoning` item with `encrypted_content` SHALL give one block holding the reasoning carrier the streamed answer
uses, with the item's `id` and `encrypted_content` as Core stamped them: a `thinking` block whose text is the item's
summary and content texts joined by a blank line, or a `redacted_thinking` block when it has no text. One without
`encrypted_content` SHALL give one `thinking` block with `signature: ""` per non-empty text.

#### Scenario: Encrypted reasoning with a summary
- **WHEN** the output starts with a `reasoning` item with `id`, `encrypted_content` and the summary texts `a` and `b`
- **THEN** `content` starts with a `thinking` block whose `thinking` is `a`, a blank line and `b`, and whose
  `signature` is the carrier holding that item's `id` and `encrypted_content`

#### Scenario: Encrypted reasoning without text
- **WHEN** a `reasoning` item has `encrypted_content` and no summary or content text
- **THEN** it gives a `redacted_thinking` block whose `data` is the carrier

#### Scenario: Reasoning without encrypted content
- **WHEN** a `reasoning` item has no `encrypted_content`, the summary text `s` and the content text `c`
- **THEN** it gives a `thinking` block `s` and a `thinking` block `c`, both with `signature: ""`

#### Scenario: Thinking display omitted
- **WHEN** the request asks for `thinking.display: "omitted"`, and the output holds an encrypted `reasoning` item with
  a summary and an unencrypted one with a summary
- **THEN** the encrypted item gives a `thinking` block with `thinking: ""` and the carrier, and the unencrypted item
  gives no block

#### Scenario: Stamped for upstream affinity
- **WHEN** `gpt-builtin`, which has a configured upstream, answers a `reasoning` item with `id: "rs_1"` and
  `encrypted_content`
- **THEN** the carrier holds the item's `id` and `encrypted_content` wrapped with that upstream's id (`dialenc_...` and
  `dialenc:...`), as the non-streaming `POST /openai/v1/responses` answer for the same output carries them

### Requirement: Non-streaming stop reason and usage
The message `stop_reason` SHALL be the first that applies: `max_tokens` for an `incomplete` answer whose
`incomplete_details.reason` is `max_output_tokens`, `refusal` for a `content_filter` reason, `tool_use` when the
output holds a `function_call`, `refusal` when it holds a refusal part, else `end_turn`. Its `usage` SHALL be the
Anthropic usage the streamed answer reports for the same Responses `usage`.

#### Scenario: Cut-off function call
- **WHEN** the answer is `incomplete` with `incomplete_details.reason: "max_output_tokens"` and its output ends in a
  `function_call`
- **THEN** `stop_reason` is `max_tokens`

#### Scenario: Content filter
- **WHEN** the answer has `incomplete_details.reason: "content_filter"`
- **THEN** `stop_reason` is `refusal`

#### Scenario: Usage with cache reads and reasoning
- **WHEN** the Responses `usage` is `input_tokens: 12`, `input_tokens_details.cached_tokens: 4`, `output_tokens: 20`
  and `output_tokens_details.reasoning_tokens: 15`
- **THEN** the message `usage` has `input_tokens: 8`, `cache_read_input_tokens: 4`, `cache_creation_input_tokens: 0`,
  `output_tokens: 20` and `output_tokens_details.thinking_tokens: 15`

### Requirement: Failed or unfinished non-streaming answer
When the upstream answers a non-streaming request `200` with a Responses object whose `status` is `failed`, Core SHALL
answer the Anthropic error its `error` object maps to, with that object's `message`, else `The upstream request
failed`. For `queued`, `in_progress` or `cancelled` Core SHALL answer `502` `api_error` with the message
`The upstream response did not complete (status '<status>')`. Both answers carry `Content-Type: application/json`.

#### Scenario: Failed answer
- **WHEN** the upstream answers `200` with `status: "failed"` and
  `error: {"code": "server_error", "message": "upstream exploded"}`
- **THEN** the client gets `502` with `Content-Type: application/json` and the body
  `{"type":"error","error":{"type":"api_error","message":"upstream exploded"}}`

#### Scenario: Failed answer without an error object
- **WHEN** the upstream answers `200` with `status: "failed"` and no `error`
- **THEN** the client gets `502` `api_error` with the message `The upstream request failed`

#### Scenario: Unfinished answer
- **WHEN** the upstream answers `200` with `status: "in_progress"`
- **THEN** the client gets `502` `api_error` with the message
  `The upstream response did not complete (status 'in_progress')`

#### Scenario: Body that is not a Responses object
- **WHEN** the upstream answers `200` with a body that is not a JSON object
- **THEN** the client gets `502` `api_error`, and the caller's token usage does not grow

### Requirement: Upstream failure names mapped to Anthropic errors
For an error object an upstream reports inside a `200` answer or a stream, Core SHALL read its `type`, then its
`code`, and use the first name it knows: `invalid_request_error` and the request-fault codes of the Python
translator's `errors.py` (such as `invalid_prompt` and the image and policy codes) give `400` `invalid_request_error`,
`rate_limit_exceeded` gives `429` `rate_limit_error`, `vector_store_timeout` gives `504` `timeout_error`. Any other
gives `502` `api_error`.

#### Scenario: Failure the request caused
- **WHEN** the upstream answers `200` with `status: "failed"` and `error: {"type": "invalid_request_error",
  "code": "invalid_request_error", "message": "This model does not support image inputs"}`
- **THEN** the client gets `400` `invalid_request_error` with that message

#### Scenario: Request-fault code
- **WHEN** the failed answer's `error` has `code: "invalid_prompt"` and no `type`
- **THEN** the client gets `400` `invalid_request_error`

#### Scenario: Rate limit inside a 200
- **WHEN** the failed answer's `error` has `code: "rate_limit_exceeded"`
- **THEN** the client gets `429` `rate_limit_error`

#### Scenario: Vector store timeout
- **WHEN** the failed answer's `error` has `code: "vector_store_timeout"`
- **THEN** the client gets `504` `timeout_error`

### Requirement: Anthropic error body for Core errors on the builtin path
Once Core has found that a builtin translator serves a `POST /anthropic/v1/messages`, every error Core answers SHALL
have the body `{"type":"error","error":{"type":<type>,"message":<message>}}` with `Content-Type: application/json`,
and today's status and other headers, `Retry-After` included. The message is Core's, or the `error.message` of a
DIAL error body. An answer already in the Anthropic shape, such as a reached spend cap, SHALL stay unchanged.

#### Scenario: Refused content block
- **WHEN** a client sends a streaming request for `gpt-builtin` whose user message holds an `image` block
- **THEN** the client gets `400` with `Content-Type: application/json`, `type: "error"`, `error.type:
  "invalid_request_error"` and an `error.message` that names `'image'`, and nothing reaches the upstream

#### Scenario: Connect failure
- **WHEN** nothing listens on the model's `openaiResponses` endpoint
- **THEN** the client gets `502` with `error.type: "api_error"` and `error.message:
  "Failed to connect to upstream server"`

#### Scenario: Replay of an upstream that is gone
- **WHEN** a request for `gpt-builtin` replays a thinking block whose carrier holds encrypted content stamped for an
  upstream id `gone` that the model no longer has
- **THEN** the client gets `409` with `error.type: "api_error"` and `error.message:
  "Upstream 'gone' referenced by encrypted content is no longer available."`, and nothing reaches the upstream

#### Scenario: Carriers from two upstreams
- **WHEN** a request for `gpt-builtin` replays thinking blocks whose carriers are stamped for two different upstreams
- **THEN** the client gets `400` with `error.type: "invalid_request_error"` and `error.message:
  "Conflicting encrypted content affinity across input items."`, and nothing reaches the upstream

#### Scenario: Every upstream busy
- **WHEN** the only upstream of `gpt-builtin` answers `429` with `Retry-After: 5` and `maxRetryAttempts` is 1
- **THEN** the client gets `503` with a `Retry-After` header of at most 5, `error.type: "overloaded_error"` and
  `error.message: "Service is not available"`

#### Scenario: Reached spend cap unchanged
- **WHEN** a caller who has reached a day cost limit sends a request for a model served by a builtin translator
- **THEN** the client gets `429` with `error.type: "billing_error"`, not `rate_limit_error`, and the same body and
  headers as for a pass-through Anthropic model, and nothing reaches the upstream

#### Scenario: Pass-through model keeps its error answers
- **WHEN** nothing listens on the endpoint of a model whose `anthropicMessages` interface is passed through
- **THEN** the client gets `502` with the body
  `{"error":{"message":"Failed to connect to upstream server","display_message":"Failed to connect to upstream server","code":"BAD_GATEWAY"}}`

### Requirement: Anthropic error type from the status
For an error answer on the builtin path that is not already in the Anthropic shape, Core SHALL take the error type
from the status as the Python translator's `errors.py` does: 400 and 422 `invalid_request_error`, 401
`authentication_error`, 403 `permission_error`, 404 `not_found_error`, 413 `request_too_large`, 429
`rate_limit_error`, 503 and 529 `overloaded_error`, and any other status `api_error`.

#### Scenario: Consent missing
- **WHEN** a user who has not consented to the `openaiResponses` interface of `gpt-builtin-consent` sends a request
  for it
- **THEN** the client gets `403` with `error.type: "permission_error"`

#### Scenario: Consent missing on the Messages interface
- **WHEN** a user who has not consented to the `anthropicMessages` interface of `gpt-builtin-messages-consent` sends a
  request for it
- **THEN** the client gets `403` with `error.type: "permission_error"`

#### Scenario: Deployment with interceptors
- **WHEN** a client sends a request for `gpt-builtin-intercepted`
- **THEN** the client gets `503` with `error.type: "overloaded_error"` and an `error.message` that names interceptors

### Requirement: Anthropic error body for upstream error answers
When the upstream answers the translated request, streaming or not, with a status Core does not retry other than
`200`, Core SHALL answer that status with the upstream's headers as it relays them today, except `Content-Type`, which
SHALL be `application/json`, and the Anthropic error body whose type follows the status and whose message is the
upstream's `error.message`, else its string `error`, else its `message`, else its body text, else
`Error code: <status>`. The answer is not charged.

#### Scenario: Upstream 500
- **WHEN** the upstream answers `500` with `{"error":{"message":"boom","type":"server_error"}}`
- **THEN** the client gets `500` with `{"type":"error","error":{"type":"api_error","message":"boom"}}`, and the
  caller's token usage of `gpt-builtin` does not grow

#### Scenario: Upstream 400 to a streaming request
- **WHEN** a client sends a streaming request and the upstream answers `400` with
  `{"error":{"message":"Invalid value","code":"invalid_value"}}`
- **THEN** the client gets `400` with `Content-Type: application/json`, not an event stream, and the body
  `{"type":"error","error":{"type":"invalid_request_error","message":"Invalid value"}}`

#### Scenario: Plain-text error body
- **WHEN** the upstream answers `404` with `Content-Type: text/plain` and the body `Not Found`
- **THEN** the client gets `404` with `Content-Type: application/json`, `error.type: "not_found_error"` and
  `error.message: "Not Found"`

#### Scenario: Pass-through model's upstream error relayed
- **WHEN** a model whose `anthropicMessages` interface is passed through answers `500` with a JSON body
- **THEN** the client gets `500` with that body unchanged
