# Spec Delta

## MODIFIED Requirements

### Requirement: Decision mode
Core SHALL read a router's `mode`, `proxy` (the default when absent) or `decision`. In decision mode every interface the
router declares is decided at its decide URL: the interface's `base_url`, or the router's `baseUrl` when it declares
none, followed by `/decide`; an interface in `embedded` mode is decided by the plugin it names instead (Decision by an
embedded plugin). No interface entry of any deployment SHALL accept `mode: decision`.

#### Scenario: Decision mode on an interface entry rejected
- **WHEN** an admin sends `PUT /v1/applications/platform/a` with `interfaces.openaiChatCompletions: {"mode": "decision"}`
- **THEN** Core answers `400` and stores nothing

#### Scenario: Decide URL per interface
- **WHEN** `routers.r3` declares `baseUrl: http://router`, `mode: decision`,
  `interfaces.openaiChatCompletions: {"base_url": "http://router/body"}` and `interfaces.openaiResponses: {}`
- **THEN** a chat completion for `r3` is decided by `POST http://router/body/decide` and a Responses request for `r3`
  by `POST http://router/decide`, and neither request is forwarded to the router

#### Scenario: Embedded interface next to an HTTP one
- **WHEN** `routers.r4` declares `baseUrl: http://router`, `mode: decision`,
  `interfaces.openaiChatCompletions: {"mode": "embedded", "plugin": "p1"}` and `interfaces.openaiResponses: {}`
- **THEN** a chat completion for `r4` is decided by plugin `p1` and `http://router` receives no request for it, and a
  Responses request for `r4` is decided by `POST http://router/decide`

### Requirement: Router identity through user info
A router SHALL get the caller's identity from `GET /v1/user/info` called with the `API-KEY` Core sent it; Core sends
it no identity header of its own. A router deciding in process SHALL also get the caller's identity with the decide
request, as deployment-origin states for every request an origin plugin answers.

#### Scenario: Project key caller's identity through the key
- **WHEN** a client with project key `p1` sends `POST /openai/deployments/r1/chat/completions`, and the router calls
  `GET /v1/user/info` with the `API-KEY` it received
- **THEN** the user info answer has `project: "p1"`

#### Scenario: JWT caller's identity through the key
- **WHEN** a client authenticated by a JWT with user id `u1` and roles `["analysts", "b"]` calls router `r1`, and the
  router calls `GET /v1/user/info` with the `API-KEY` it received
- **THEN** the answer has `userId: "u1"` and roles `["analysts", "b"]`

#### Scenario: Embedded router gets the caller with the decide request
- **WHEN** a client with API key `proxyKey1` (project `EPM-RTC-GPT`, role `default`) sends a chat completion to a
  decision router whose interface is served by plugin `p1`
- **THEN** `p1`'s decide request carries project `EPM-RTC-GPT`, roles `["default"]` and no user id

## REMOVED Requirements

### Requirement: Decision settings
**Reason**: It rejected an embedded interface on a router in decision mode, which this change serves; its other rules
move unchanged to "Decision router settings".
**Migration**: None. A config that loaded before still loads; a decision router with an embedded interface now loads
too.

## ADDED Requirements

### Requirement: Decision router settings
Core SHALL read a router's optional `decision` object with `body` (default `false`), `timeoutMs` (default 5000, must be
positive) and `maxDecisions` (see Decision limit), and SHALL reject it on a router not in decision mode (`decision
requires mode 'decision'`). On a router in decision mode Core SHALL reject a translated interface (`A router in mode
'decision' serves no interface through a translator`) and SHALL accept an embedded one.

#### Scenario: Decision settings without decision mode rejected
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `decision: {"maxDecisions": 2}` and no `mode`
- **THEN** the config is rejected with a message containing `decision requires mode 'decision'`

#### Scenario: Translated interface on a decision router rejected
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `mode: decision` and
  `interfaces.anthropicMessages: {"mode": "translator", "translator": {"out": "openaiChatCompletions", "baseUrl": "http://t"}}`
- **THEN** the config is rejected with a message containing `A router in mode 'decision' serves no interface through a
  translator`

#### Scenario: Embedded interface on a decision router accepted
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `mode: decision` and
  `interfaces.anthropicMessages: {"mode": "embedded", "plugin": "p1"}`
- **THEN** the config loads, and `GET /v1/routers/r1` answers `200`

### Requirement: Decision by an embedded plugin
For an interface a router in decision mode serves in `embedded` mode, Core SHALL send the decide request and the
mid-stream notice request to the plugin the interface names, in process, instead of to a URL: the method, headers and
body Core would send to an HTTP decide URL, with the path `/decide`. Core SHALL read the plugin's answer as an HTTP
router's answer: the same verdict rules, `decision.timeoutMs`, candidate walk, re-decide and records.

#### Scenario: Plugin decides
- **WHEN** `routers.r1` declares `mode: decision` and `interfaces.openaiChatCompletions: {"mode": "embedded", "plugin":
  "p1"}`, plugin `p1` answers `{"candidates": ["m1"]}`, and a client sends a non-streamed chat completion for `r1`
- **THEN** `p1` receives `POST /decide` with `X-DIAL-ROUTER: r1`, `X-DIAL-API: openaiChatCompletions`, an `API-KEY`
  that is not the client's key and an empty body; `m1` serves the request; the client's answer has `model: r1`; and
  the analytics log has one row for `m1` and one router row for `r1`

#### Scenario: Plugin re-decides
- **WHEN** `p1` answers `{"candidates": ["m1"]}`, `m1` answers `503` after its upstream retries, and `p1` answers the
  second decide request `{"candidates": ["m2"]}`
- **THEN** the second decide request `p1` receives carries `X-DIAL-ROUTER-ATTEMPTS` naming `m1`, and `m2` serves the
  request

#### Scenario: Notice to the plugin
- **WHEN** the verdict is `{"candidates": ["m1"]}`, the client streams the chat completion, and `m1` sends one SSE
  chunk and then drops the connection
- **THEN** `p1` receives a notice request: `POST /decide` with `X-DIAL-ROUTER-NOTICE: true`, no `API-KEY` and an empty
  body

#### Scenario: Client gone while the plugin decides
- **WHEN** the client closes its connection while `p1` decides, and `p1` then answers `{"candidates": ["m1"]}`
- **THEN** `p1`'s host does not report the request cancelled

### Requirement: Embedded decision failures
Core SHALL answer `502` for the first decide request when the plugin's answer fails, or when the plugin cannot be
called (no `plugins` entry by that name, or a type no loaded provider serves), as for an unreachable router. Core SHALL
answer `504` when the plugin has not answered within `decision.timeoutMs`, and only then SHALL it tell the plugin the
request is cancelled. A plugin's `4xx` answer SHALL be relayed as a router's.

#### Scenario: Plugin fails
- **WHEN** plugin `p1`'s answer to the decide request fails
- **THEN** the client receives `502`, no candidate receives a request, and no analytics row is written

#### Scenario: Plugin without an entry
- **WHEN** the embedded interface names plugin `p9` and the config has no `plugins.p9`
- **THEN** the client receives `502`

#### Scenario: Plugin too slow
- **WHEN** the router declares `decision: {"timeoutMs": 200}` and `p1` does not answer the decide request
- **THEN** the client receives `504` within the second, and `p1`'s host reports the request cancelled

#### Scenario: Plugin refuses the request
- **WHEN** `p1` answers the decide request `400` with `{"error": {"message": "state either tier or target"}}`
- **THEN** the client receives `400` with that body, no candidate receives a request, and `p1`'s host does not report
  the request cancelled

### Requirement: Body of an embedded decide request
With `decision.body: true`, the plugin SHALL receive the client's request bytes unchanged as the decide request's body
(for an in-process call, the serialization "Decide request of an in-process call" states), and the parsed request as
its body tree. The tree SHALL be the plugin's own copy: a change the plugin makes to it SHALL
NOT reach the deployment Core then serves. Without `decision.body` the plugin SHALL receive an empty body and no body
tree.

#### Scenario: Body and tree
- **WHEN** the router declares `decision: {"body": true}` and the client sends
  `{"model":"r1","messages":[{"role":"user","content":"hi"}]}`
- **THEN** `p1` receives that body byte for byte and a body tree whose `messages[0].content` is `hi`

#### Scenario: Plugin changes its tree
- **WHEN** `p1` removes `messages` from its body tree and answers `{"candidates": ["m1"]}`
- **THEN** `m1` receives the client's `messages`

#### Scenario: No body without the setting
- **WHEN** the router declares no `decision.body`
- **THEN** `p1` receives an empty body and no body tree

### Requirement: Calls of an embedded router while it decides
A plugin answering a decide request MAY call Core through its host, to the create operations deployment-origin lists.
Core SHALL serve each call as a request with the decide request key, as it serves an HTTP router's calls with that
key. When the decide call ends Core SHALL cancel the calls still open and SHALL answer `401` to later ones. The notice
request carries no key, so calls made while answering it SHALL be answered `401`.

#### Scenario: Plugin asks a model while deciding
- **WHEN** `p1`, on receiving the decide request, calls `POST /openai/deployments/m2/chat/completions` through its host
  and then answers `{"candidates": ["m1"]}`
- **THEN** the call is served, its analytics row has `parent_deployment: "r1"`, and the client's non-streamed answer
  has `m2` in `statistics.usage_per_model`

#### Scenario: Call after the decision
- **WHEN** `p1` keeps its host and calls `POST /openai/deployments/m2/chat/completions` after its answer was read
- **THEN** Core answers that call `401`

#### Scenario: Call open when the decision ends
- **WHEN** `p1` starts a streamed call to `m2` through its host, answers `{"candidates": ["m1"]}` before that call
  ends, and `m1` serves the request
- **THEN** the call's answer fails before it completes, and the client receives `m1`'s answer

#### Scenario: Call while answering a notice
- **WHEN** `p1`, on receiving a notice request, calls `POST /openai/deployments/m2/chat/completions` through its host
- **THEN** Core answers that call `401`

### Requirement: Decide request of an in-process call
When the request a decision router decides is an origin plugin's in-process call, which carries a body tree and no
bytes, Core SHALL serialize that tree once, before the first candidate, and SHALL use the serialization wherever the
decision uses the client's bytes: as the body of every decide request when `decision.body` is `true`, the re-decide
included, and as the request each fallback candidate is served from.

#### Scenario: Plugin calls a decision router that wants the body
- **WHEN** application `app` is served by plugin `p2`, a client sends a chat completion to `app` with
  `X-Test-Call: /openai/v1/chat/completions` and `X-Test-Call-Model: r2`, and `r2` is a decision router over HTTP with
  `decision: {"body": true}` answering `{"candidates": ["m1"]}`
- **THEN** the router's decide request body is JSON equal to the body `p2` passed, and `m1` serves the call

#### Scenario: Fallback inside an in-process call
- **WHEN** `p2` calls `/openai/v1/chat/completions` with `model: rf`, a decision router over HTTP without
  `decision.body` answering `{"candidates": ["m1", "m2"]}`, and `m1` declares `defaults: {"temperature": 0.5}` and
  answers `503` after its upstream retries
- **THEN** `m2` receives the `messages` `p2` passed, with `model` set to `m2` and no `temperature`

#### Scenario: In-process call on deployments-POST
- **WHEN** `p2` calls `/openai/deployments/r2/chat/completions`
- **THEN** `m1` serves the call with the `messages` `p2` passed
