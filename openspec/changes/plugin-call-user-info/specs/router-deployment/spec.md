## MODIFIED Requirements

### Requirement: Calls of an embedded router while it decides
A plugin answering a decide request MAY call Core through its host, to the create operations deployment-origin lists,
and MAY read the caller's user info through its host as deployment-origin "Caller's user info through the host" states.
Core SHALL serve each call as a request with the decide request key, as it serves an HTTP router's calls with that
key. When the decide call ends Core SHALL cancel the calls still open and SHALL answer `401` to later ones. The notice
request carries no key, so calls made while answering it SHALL be answered `401`.

#### Scenario: Plugin asks a model while deciding
- **WHEN** `p1`, on receiving the decide request, calls `POST /openai/deployments/m2/chat/completions` through its host
  and then answers `{"candidates": ["m1"]}`
- **THEN** the call is served, its analytics row has `parent_deployment: "r1"`, and the client's non-streamed answer
  has `m2` in `statistics.usage_per_model`

#### Scenario: Plugin reads the caller while deciding
- **WHEN** a client with API key `proxyKey1` (project `EPM-RTC-GPT`, role `default`) calls `r1`, and `p1`, on receiving
  the decide request, calls `GET /v1/user/info` through its host and then answers `{"candidates": ["m1"]}`
- **THEN** the call's answer is `200` with `project: "EPM-RTC-GPT"` and `roles: ["default"]`, and the client receives
  `m1`'s answer

#### Scenario: Call after the decision
- **WHEN** `p1` keeps its host and calls `POST /openai/deployments/m2/chat/completions` after its answer was read
- **THEN** Core answers that call `401`

#### Scenario: Call open when the decision ends
- **WHEN** `p1` starts a streamed call to `m2` through its host, answers `{"candidates": ["m1"]}` before that call
  ends, and `m1` serves the request
- **THEN** the call's answer fails before it completes, and the client receives `m1`'s answer

#### Scenario: Call while answering a notice
- **WHEN** `p1`, on receiving a notice request, calls `POST /openai/deployments/m2/chat/completions`, or
  `GET /v1/user/info`, through its host
- **THEN** Core answers each call `401`
