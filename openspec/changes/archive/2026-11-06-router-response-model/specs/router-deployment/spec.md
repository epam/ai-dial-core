## ADDED Requirements

### Requirement: Router name in the answer
For a request served through a decision, Core SHALL replace the `model` of the answer with the router's name on chat
and legacy completions, Responses (a stored one read back included) and Messages: the top-level `model` of a buffered
answer and of each streamed chunk or event, `response.model` and `message.model` in events, where present. Embeddings,
a buffered legacy completion and a buffered answer relayed through interceptors SHALL keep the upstream's `model`.

#### Scenario: Buffered chat answer names the router
- **WHEN** the router `r1` answers `{"candidates": ["gpt-4"]}` and `gpt-4` answers a buffered chat completion with
  `"model": "gpt-4-real"`
- **THEN** the client receives `"model": "r1"`, and the serving row's `deployment` is `gpt-4`

#### Scenario: Streamed chat answer names the router
- **WHEN** the router `r1` answers `{"candidates": ["gpt-4"]}` and `gpt-4` streams chunks with `"model": "gpt-4-real"`
- **THEN** every chunk the client receives has `"model": "r1"`

#### Scenario: Responses answer names the router
- **WHEN** a client sends `POST /openai/v1/responses` with `{"model": "r3", "input": "hi"}`, the router answers
  `{"candidates": ["gpt-4"]}` and `gpt-4` answers with `"model": "gpt-4-real"`
- **THEN** the client receives `"model": "r3"`

#### Scenario: Stored Responses answer read back names the router
- **WHEN** a client creates a response through `r3` with `"store": true`, `gpt-4` answers with `"model": "gpt-4-real"`,
  and the client then sends `GET /openai/v1/responses/{id}`
- **THEN** the answer to the `GET` has `"model": "r3"`

#### Scenario: Streamed Messages answer names the router
- **WHEN** a client streams `POST /anthropic/v1/messages` with `"model": "r1"`, the router answers
  `{"candidates": ["claude"]}` and `claude`'s `message_start` event carries `"message": {"model": "claude-real"}`
- **THEN** the client's `message_start` event carries `"message": {"model": "r1"}`

#### Scenario: Buffered Messages answer names the router
- **WHEN** the router `r1` answers `{"candidates": ["claude"]}` and `claude` answers a buffered message with
  `"model": "claude-real"`
- **THEN** the client receives the whole message with `"model": "r1"`

#### Scenario: Streamed answer through the candidate's interceptor names the router
- **WHEN** the router `r1` answers `{"candidates": ["m-int"]}`, `m-int` has `interceptors: ["i2"]`, and the client
  streams the answer, whose chunks carry `"model": "m-int-real"`
- **THEN** every chunk the client receives has `"model": "r1"`

#### Scenario: Embeddings keep the upstream model
- **WHEN** the router answers `{"candidates": ["ada"]}` for `POST /openai/deployments/r1/embeddings` and `ada` answers
  with `"model": "ada-real"`
- **THEN** the client receives `"model": "ada-real"`

#### Scenario: Undecided answer unchanged
- **WHEN** a client calls `gpt-4` directly and it answers with `"model": "gpt-4-real"`
- **THEN** the client receives `"model": "gpt-4-real"`
